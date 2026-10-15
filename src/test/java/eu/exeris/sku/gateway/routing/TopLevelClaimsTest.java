package eu.exeris.sku.gateway.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopLevelClaimsTest {

    private static Map<String, String> read(String json) {
        return TopLevelClaims.read(json.getBytes(StandardCharsets.UTF_8), "sub", "iss", "tenant_id", "tid");
    }

    @Test
    @DisplayName("reads the top-level string claims")
    void readsTopLevel() {
        assertEquals(Map.of("sub", "u1", "iss", "https://i", "tenant_id", "t1"),
                read("{\"sub\":\"u1\", \"iss\" : \"https://i\",\"tenant_id\":\"t1\",\"exp\":1.5e3}"));
    }

    @Test
    @DisplayName("a nested same-named key ahead of the top-level claim is not taken")
    void nestedKeyAheadIsIgnored() {
        assertEquals(Map.of("sub", "real"),
                read("{\"ctx\":{\"sub\":\"evil\",\"a\":[{\"sub\":\"evil2\"}]},\"list\":[\"sub\",{\"sub\":\"x\"}],\"sub\":\"real\"}"));
    }

    @Test
    @DisplayName("a nested key alone yields nothing")
    void nestedKeyOnly() {
        assertEquals(Map.of(), read("{\"ctx\":{\"sub\":\"evil\"}}"));
    }

    @Test
    @DisplayName("escaped quotes and a member text inside a string do not confuse the scan")
    void escapedQuotes() {
        assertEquals(Map.of("sub", "a\"b\\cé\n", "iss", "ok"),
                read("{\"x\":\"\\\",\\\"sub\\\":\\\"evil\",\"sub\":\"a\\\"b\\\\c\\u00e9\\n\",\"iss\":\"ok\"}"));
    }

    @Test
    @DisplayName("an escaped member name is compared decoded")
    void escapedName() {
        assertEquals(Map.of("sub", "v"), read("{\"s\\u0075b\":\"v\"}"));
    }

    @Test
    @DisplayName("a non-string claim value is left out")
    void nonStringValues() {
        assertEquals(Map.of("iss", "i"),
                read("{\"sub\":42,\"tid\":null,\"tenant_id\":{\"a\":1},\"iss\":\"i\"}"));
        assertEquals(Map.of(), read("{\"sub\":[\"a\"],\"tid\":true}"));
    }

    @Test
    @DisplayName("a missing claim is absent")
    void missing() {
        assertEquals(Map.of(), read("{}"));
        assertEquals(Map.of(), read("{\"exp\":1}"));
    }

    @Test
    @DisplayName("a duplicate top-level wanted claim, or malformed JSON, yields nothing")
    void ambiguousOrMalformed() {
        assertEquals(Map.of(), read("{\"sub\":\"a\",\"sub\":\"b\"}"));
        assertEquals(Map.of(), read("{\"sub\":\"a\""));
        assertEquals(Map.of(), read("{\"sub\":\"a\"} x"));
        assertEquals(Map.of(), read("[\"sub\"]"));
        assertEquals(Map.of(), read("{\"sub\":\"a\",}"));
        assertEquals(Map.of(), read(""));
    }

    @Test
    @DisplayName("nesting beyond the limit yields nothing")
    void tooDeep() {
        String deep = "[".repeat(100) + "]".repeat(100);
        assertEquals(Map.of(), read("{\"a\":" + deep + ",\"sub\":\"x\"}"));
    }
}
