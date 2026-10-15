package eu.exeris.sku.gateway.routing;

import eu.exeris.kernel.spi.http.HttpHeader;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which headers the gateway does not copy from one side of the proxy to the other.
 *
 * <p>Two groups. Hop-by-hop headers describe one connection (RFC 9110 section 7.6.1) and are not
 * forwarded, nor is any header the {@code Connection} header names. Forwarding headers
 * ({@code Forwarded}, {@code X-Forwarded-*}, {@code X-Real-IP}) tell an upstream where the request
 * came from; a value a client sent about itself is not evidence of that, so none is passed on.
 * The gateway sets such a header only through a route's own header mutation.
 */
final class ForwardingHeaders {

    private static final Set<String> REQUEST_NOT_FORWARDED = Set.of(
            "connection", "content-length", "expect", "host", ":authority", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade");

    private static final Set<String> RESPONSE_NOT_FORWARDED = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade");

    private static final Set<String> CLIENT_SUPPLIED_ORIGIN = Set.of(
            "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port",
            "x-forwarded-prefix", "x-forwarded-server", "x-forwarded-scheme", "x-forwarded-ssl",
            "x-real-ip", "x-original-forwarded-for", "front-end-https");

    private ForwardingHeaders() {
    }

    /**
     * The lower-cased names to drop from the client's own request headers before they are forwarded:
     * the hop-by-hop set, the forwarding set and whatever the {@code Connection} header lists.
     */
    static Set<String> droppedFromClientRequest(List<HttpHeader> headers) {
        Set<String> dropped = hopByHopRequest(headers);
        dropped.addAll(CLIENT_SUPPLIED_ORIGIN);
        return dropped;
    }

    /**
     * The lower-cased names to drop from the request after a route's mutations are applied: the
     * hop-by-hop set only, so a forwarding header a route sets itself survives.
     */
    static Set<String> hopByHopRequest(List<HttpHeader> clientHeaders) {
        Set<String> dropped = new HashSet<>(REQUEST_NOT_FORWARDED);
        addConnectionTokens(clientHeaders, dropped);
        return dropped;
    }

    /** The lower-cased names a response must not carry downstream. */
    static Set<String> droppedFromResponse(List<HttpHeader> upstreamHeaders) {
        Set<String> dropped = new HashSet<>(RESPONSE_NOT_FORWARDED);
        addConnectionTokens(upstreamHeaders, dropped);
        return dropped;
    }

    private static void addConnectionTokens(List<HttpHeader> headers, Set<String> into) {
        for (int i = 0; i < headers.size(); i++) {
            HttpHeader header = headers.get(i);
            if (!header.nameEqualsIgnoreCase("connection")) {
                continue;
            }
            for (String token : header.value().split(",")) {
                String name = token.trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    into.add(name);
                }
            }
        }
    }
}
