package eu.exeris.sku.gateway;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

/** An Ed25519 key pair generated per test class, and the tokens, JWKS document and PEM that go with it. */
final class EdDsaTokens {

    static final String ISSUER = "https://auth.example.com";
    static final String AUDIENCE = "gateway";
    static final String KID = "k1";

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final KeyPair pair;

    EdDsaTokens() {
        try {
            this.pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    PublicKey publicKey() {
        return pair.getPublic();
    }

    /** The key as an X.509 SubjectPublicKeyInfo PEM. */
    String pem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    /** A JWKS document publishing the key under {@code kid}. */
    String jwks(String kid) {
        byte[] encoded = pair.getPublic().getEncoded();
        byte[] raw = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        return "{\"keys\":[{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"alg\":\"EdDSA\",\"use\":\"sig\",\"kid\":\"" + kid
                + "\",\"x\":\"" + URL.encodeToString(raw) + "\"}]}";
    }

    String token() {
        return token(KID, ISSUER, AUDIENCE, System.currentTimeMillis() / 1000 + 3600);
    }

    String token(String kid, String issuer, String audience, long exp) {
        long now = System.currentTimeMillis() / 1000;
        return sign(pair, "{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}",
                "{\"sub\":\"u1\",\"iss\":\"" + issuer + "\",\"aud\":\"" + audience + "\",\"nbf\":" + (now - 10)
                        + ",\"iat\":" + (now - 10) + ",\"exp\":" + exp + "}");
    }

    /** A token signed by a key nobody published. */
    static String forged() {
        EdDsaTokens stranger = new EdDsaTokens();
        return stranger.token();
    }

    private static String sign(KeyPair pair, String header, String claims) {
        String input = URL.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + URL.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(pair.getPrivate());
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + URL.encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
