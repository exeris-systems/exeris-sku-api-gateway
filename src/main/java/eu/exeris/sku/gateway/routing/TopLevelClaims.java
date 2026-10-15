package eu.exeris.sku.gateway.routing;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads selected string members from the top level of a JSON object.
 *
 * <p>A strict, single-pass scanner over the payload bytes. It tracks nesting depth and string escapes, so a
 * same-named key inside a nested object or array is never mistaken for the top-level member. Only the wanted
 * values are materialised; everything else is skipped without allocation.
 *
 * <p>The result is empty when the payload is not one well-formed JSON object, when it nests deeper than
 * {@value #MAX_DEPTH}, or when a wanted name occurs twice at the top level (which of the two a consumer saw would
 * be ambiguous). A wanted member whose value is not a string is left out.
 */
final class TopLevelClaims {

    private static final int MAX_DEPTH = 32;

    private final byte[] in;
    private int pos;

    private TopLevelClaims(byte[] in) {
        this.in = in;
    }

    /**
     * @param json   the UTF-8 payload
     * @param wanted the member names to read; at most a handful, compared by value
     * @return the string values found by name, never {@code null}
     */
    static Map<String, String> read(byte[] json, String... wanted) {
        try {
            Map<String, String> out = new HashMap<>(wanted.length * 2);
            TopLevelClaims s = new TopLevelClaims(json);
            s.object(wanted, out);
            s.skipWhitespace();
            if (s.pos != json.length) {
                return Map.of();
            }
            return out;
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private void object(String[] wanted, Map<String, String> out) {
        skipWhitespace();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return;
        }
        boolean[] seen = new boolean[wanted.length];
        while (true) {
            skipWhitespace();
            int idx = matchName(wanted);
            skipWhitespace();
            expect(':');
            skipWhitespace();
            if (idx >= 0) {
                if (seen[idx]) {
                    throw bad();
                }
                seen[idx] = true;
                if (peek() == '"') {
                    out.put(wanted[idx], string());
                } else {
                    skipValue(1);
                }
            } else {
                skipValue(1);
            }
            skipWhitespace();
            byte c = next();
            if (c == '}') {
                return;
            }
            if (c != ',') {
                throw bad();
            }
        }
    }

    /** Consumes a member name and returns the index of the wanted name it equals, or -1. */
    private int matchName(String[] wanted) {
        if (peek() != '"') {
            throw bad();
        }
        int start = ++pos;
        boolean escaped = false;
        while (true) {
            byte c = next();
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                escaped = true;
                next();
            } else if (c >= 0 && c < 0x20) {
                throw bad();
            }
        }
        int end = pos - 1;
        if (escaped) {
            String name = decode(start, end);
            for (int i = 0; i < wanted.length; i++) {
                if (wanted[i].equals(name)) {
                    return i;
                }
            }
            return -1;
        }
        for (int i = 0; i < wanted.length; i++) {
            if (bytesEqual(wanted[i], start, end)) {
                return i;
            }
        }
        return -1;
    }

    private boolean bytesEqual(String name, int start, int end) {
        if (name.length() != end - start) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (in[start + i] != (byte) name.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private String string() {
        int start = ++pos;
        boolean escaped = false;
        while (true) {
            byte c = next();
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                escaped = true;
                next();
            } else if (c >= 0 && c < 0x20) {
                throw bad();
            }
        }
        int end = pos - 1;
        return escaped ? decode(start, end) : new String(in, start, end - start, StandardCharsets.UTF_8);
    }

    /** Decodes the escape sequences of the string body {@code [start, end)}. */
    private String decode(int start, int end) {
        StringBuilder sb = new StringBuilder(end - start);
        int i = start;
        int run = start;
        while (i < end) {
            if (in[i] != '\\') {
                i++;
                continue;
            }
            sb.append(new String(in, run, i - run, StandardCharsets.UTF_8));
            char e = (char) in[i + 1];
            i += 2;
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 > end) {
                        throw bad();
                    }
                    int cp = 0;
                    for (int k = 0; k < 4; k++) {
                        int d = Character.digit((char) in[i + k], 16);
                        if (d < 0) {
                            throw bad();
                        }
                        cp = (cp << 4) | d;
                    }
                    sb.append((char) cp);
                    i += 4;
                }
                default -> throw bad();
            }
            run = i;
        }
        sb.append(new String(in, run, end - run, StandardCharsets.UTF_8));
        return sb.toString();
    }

    /** Skips one value, tracking nesting; {@code depth} is the depth of the value being skipped. */
    private void skipValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw bad();
        }
        byte c = peek();
        switch (c) {
            case '"' -> skipString();
            case '{' -> {
                pos++;
                skipWhitespace();
                if (peek() == '}') {
                    pos++;
                    return;
                }
                while (true) {
                    skipWhitespace();
                    if (peek() != '"') {
                        throw bad();
                    }
                    skipString();
                    skipWhitespace();
                    expect(':');
                    skipWhitespace();
                    skipValue(depth + 1);
                    skipWhitespace();
                    byte d = next();
                    if (d == '}') {
                        return;
                    }
                    if (d != ',') {
                        throw bad();
                    }
                }
            }
            case '[' -> {
                pos++;
                skipWhitespace();
                if (peek() == ']') {
                    pos++;
                    return;
                }
                while (true) {
                    skipWhitespace();
                    skipValue(depth + 1);
                    skipWhitespace();
                    byte d = next();
                    if (d == ']') {
                        return;
                    }
                    if (d != ',') {
                        throw bad();
                    }
                }
            }
            case 't' -> literal("true");
            case 'f' -> literal("false");
            case 'n' -> literal("null");
            default -> skipNumber();
        }
    }

    private void skipString() {
        pos++;
        while (true) {
            byte c = next();
            if (c == '"') {
                return;
            }
            if (c == '\\') {
                next();
            } else if (c >= 0 && c < 0x20) {
                throw bad();
            }
        }
    }

    private void skipNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        int digits = 0;
        while (pos < in.length && in[pos] >= '0' && in[pos] <= '9') {
            pos++;
            digits++;
        }
        if (digits == 0) {
            throw bad();
        }
        if (pos < in.length && in[pos] == '.') {
            pos++;
            if (!digitRun()) {
                throw bad();
            }
        }
        if (pos < in.length && (in[pos] == 'e' || in[pos] == 'E')) {
            pos++;
            if (pos < in.length && (in[pos] == '+' || in[pos] == '-')) {
                pos++;
            }
            if (!digitRun()) {
                throw bad();
            }
        }
        if (pos == start) {
            throw bad();
        }
    }

    private boolean digitRun() {
        int n = 0;
        while (pos < in.length && in[pos] >= '0' && in[pos] <= '9') {
            pos++;
            n++;
        }
        return n > 0;
    }

    private void literal(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (next() != word.charAt(i)) {
                throw bad();
            }
        }
    }

    private void skipWhitespace() {
        while (pos < in.length) {
            byte c = in[pos];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private byte peek() {
        if (pos >= in.length) {
            throw bad();
        }
        return in[pos];
    }

    private byte next() {
        if (pos >= in.length) {
            throw bad();
        }
        return in[pos++];
    }

    private void expect(char c) {
        if (next() != c) {
            throw bad();
        }
    }

    private static IllegalArgumentException bad() {
        return new IllegalArgumentException("malformed");
    }
}
