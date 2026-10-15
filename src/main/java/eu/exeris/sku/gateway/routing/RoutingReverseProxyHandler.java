package eu.exeris.sku.gateway.routing;

import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;
import eu.exeris.caps.pool.api.UpstreamPoolService;
import eu.exeris.caps.routing.api.HeaderEntry;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zero-copy L7 reverse-proxy handler.
 *
 * <p>Uses {@link RequestRouter} (radix tree) to resolve the destination upstream, rewrites the path and
 * applies the route's header mutations. The destination always comes from the route table, never from the
 * request.
 *
 * <p>Headers that describe the client connection or the client's own origin are not forwarded: hop-by-hop
 * headers, any header the {@code Connection} header names, and {@code Forwarded}, {@code X-Forwarded-*} and
 * {@code X-Real-IP}. A client's claim about where it came from is not evidence, so the upstream sees the
 * gateway's value or none.
 *
 * <p>A request that fails over to another host in a cluster is a safe method without a body: a request with
 * a body is not replayed, and a method with side effects is never sent twice.
 */
public final class RoutingReverseProxyHandler implements HttpHandler {

    private static final System.Logger LOG = System.getLogger(RoutingReverseProxyHandler.class.getName());

    private static final String CLUSTER_SCHEME = "cluster://";
    private static final String POOL_SCHEME = "pool://";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final RequestRouter router;
    private final HttpClientEngine injectedEngine;
    private final UpstreamPoolService poolService;
    private final boolean failoverEnabled;
    private final boolean claimsVerified;
    private final ConcurrentHashMap<String, TargetInfo> targetCache = new ConcurrentHashMap<>();

    public RoutingReverseProxyHandler(RequestRouter router) {
        this(router, null, null, false, false);
    }

    public RoutingReverseProxyHandler(RequestRouter router, HttpClientEngine clientEngine) {
        this(router, clientEngine, null, false, false);
    }

    /**
     * Creates the handler.
     *
     * @param router          the route table
     * @param clientEngine    the engine that carries the upstream request; the kernel-bound engine when {@code null}
     * @param poolService     the cluster registry for {@code cluster://} targets, or {@code null} when none is declared
     * @param failoverEnabled whether a safe, bodyless request is retried on another cluster host after a 5xx
     * @param claimsVerified  whether the request's bearer token has already been verified by an admission
     *                        policy. Claims for a route's claim-injecting header mutations are read from the
     *                        token only then; otherwise they are empty and those headers are not set.
     */
    public RoutingReverseProxyHandler(RequestRouter router, HttpClientEngine clientEngine,
                                      UpstreamPoolService poolService, boolean failoverEnabled,
                                      boolean claimsVerified) {
        this.router = Objects.requireNonNull(router, "router");
        this.injectedEngine = clientEngine;
        this.poolService = poolService;
        this.failoverEnabled = failoverEnabled;
        this.claimsVerified = claimsVerified;
    }

    private HttpClientEngine engine() {
        if (injectedEngine != null) {
            return injectedEngine;
        }
        return HttpKernelProviders.httpClientEngine()
                .orElseThrow(() -> new IllegalStateException("HttpClientEngine is not bound in HttpKernelProviders"));
    }

    private TargetInfo resolveTarget(String targetBaseUri) {
        return targetCache.computeIfAbsent(targetBaseUri, uri -> {
            String clean = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
            URI parsed = URI.create(clean);
            String host = parsed.getHost() != null ? parsed.getHost() : "localhost";
            int port = parsed.getPort();
            if (port <= 0) {
                port = 80;
            }
            String authority = (host.indexOf(':') >= 0 && !host.startsWith("["))
                    ? "[" + host + "]:" + port
                    : host + ":" + port;
            String p = parsed.getPath();
            String basePath = (p == null || p.isBlank() || p.equals("/")) ? "" : p;
            return new TargetInfo(authority, basePath);
        });
    }

    @Override
    public void handle(HttpExchange exchange) {
        HttpRequest clientReq = exchange.request();
        HttpResponse upstreamResp = null;
        String routeId = "unmatched";
        try {
            String method = clientReq.method() != null ? clientReq.method().name() : null;
            String originalPath = clientReq.path();

            RouteMatch match = router.match(method, originalPath);
            if (!match.isMatched()) {
                exchange.respond(HttpStatus.NOT_FOUND);
                return;
            }
            routeId = match.route().id();

            String targetUri = match.route().targetUri();
            UpstreamCluster cluster = null;
            UpstreamHost host = null;

            if (targetUri.startsWith(CLUSTER_SCHEME) || targetUri.startsWith(POOL_SCHEME)) {
                String clusterName = targetUri.startsWith(CLUSTER_SCHEME)
                        ? targetUri.substring(CLUSTER_SCHEME.length())
                        : targetUri.substring(POOL_SCHEME.length());
                cluster = poolService == null ? null : poolService.getCluster(clusterName).orElse(null);
                host = cluster == null ? null : cluster.selectHost();
                if (host == null) {
                    // No such cluster, or every host in it is draining or ejected: there is nothing to send to
                    exchange.respond(HttpStatus.SERVICE_UNAVAILABLE);
                    return;
                }
                host.incrementConnections();
                targetUri = host.endpoint().baseUri();
            }

            try {
                TargetInfo target = resolveTarget(targetUri);
                String forwardedPath = match.rewrittenPath();
                if (!target.basePath.isEmpty()) {
                    forwardedPath = target.basePath + (forwardedPath.startsWith("/") ? forwardedPath : "/" + forwardedPath);
                }

                List<HttpHeader> clientHeaders = clientReq.headers();
                Map<String, String> contextClaims = claimsVerified ? extractClaims(clientReq) : Map.of();

                Set<String> clientDropped = ForwardingHeaders.droppedFromClientRequest(clientHeaders);
                List<HeaderEntry> entries = new ArrayList<>(clientHeaders.size());
                for (int i = 0; i < clientHeaders.size(); i++) {
                    HttpHeader h = clientHeaders.get(i);
                    if (!clientDropped.contains(h.name().toLowerCase(Locale.ROOT))) {
                        entries.add(new HeaderEntry(h.name(), h.value()));
                    }
                }

                List<HeaderEntry> transformedEntries = match.transformRequestHeaders(entries, contextClaims);

                Set<String> hopByHop = ForwardingHeaders.hopByHopRequest(clientHeaders);
                List<HttpHeader> outboundHeaders = new ArrayList<>(transformedEntries.size());
                for (int i = 0; i < transformedEntries.size(); i++) {
                    HeaderEntry entry = transformedEntries.get(i);
                    if (!hopByHop.contains(entry.name().toLowerCase(Locale.ROOT))) {
                        outboundHeaders.add(new HttpHeader(entry.name(), entry.value()));
                    }
                }

                LoanedBuffer requestBody = clientReq.hasBody() ? clientReq.body() : null;

                HttpRequest outboundReq = new HttpRequest(
                        clientReq.method(),
                        target.authority,
                        forwardedPath,
                        HttpVersion.HTTP_1_1,
                        outboundHeaders,
                        requestBody
                );

                upstreamResp = engine().send(outboundReq);

                if (cluster != null) {
                    cluster.recordResult(host, upstreamResp.status().code(), false);
                    boolean replayable = requestBody == null && method != null && SAFE_METHODS.contains(method);
                    if (failoverEnabled && replayable && upstreamResp.status().code() >= 500
                            && cluster.healthyHostCount() > 0) {
                        UpstreamHost backupHost = cluster.selectHost();
                        if (backupHost != null && backupHost != host) {
                            host.decrementConnections();
                            host = backupHost;
                            host.incrementConnections();
                            TargetInfo backupTarget = resolveTarget(host.endpoint().baseUri());
                            String backupPath = match.rewrittenPath();
                            if (!backupTarget.basePath.isEmpty()) {
                                backupPath = backupTarget.basePath
                                        + (backupPath.startsWith("/") ? backupPath : "/" + backupPath);
                            }
                            HttpRequest retryReq = new HttpRequest(
                                    outboundReq.method(),
                                    backupTarget.authority,
                                    backupPath,
                                    HttpVersion.HTTP_1_1,
                                    outboundHeaders,
                                    null
                            );
                            try {
                                HttpResponse retryResp = engine().send(retryReq);
                                cluster.recordResult(host, retryResp.status().code(), false);
                                if (retryResp.status().code() < 500) {
                                    closeQuietly(upstreamResp);
                                    upstreamResp = retryResp;
                                } else {
                                    closeQuietly(retryResp);
                                }
                            } catch (Exception retryEx) {
                                cluster.recordResult(host, 0, true);
                            }
                        }
                    }
                }

                List<HttpHeader> upHeaders = upstreamResp.headers();
                List<HeaderEntry> respEntries = new ArrayList<>(upHeaders.size());
                for (int i = 0; i < upHeaders.size(); i++) {
                    HttpHeader h = upHeaders.get(i);
                    respEntries.add(new HeaderEntry(h.name(), h.value()));
                }

                List<HeaderEntry> transformedRespEntries = match.transformResponseHeaders(respEntries);

                Set<String> responseDropped = ForwardingHeaders.droppedFromResponse(upHeaders);
                List<HttpHeader> responseHeaders = new ArrayList<>(transformedRespEntries.size());
                for (int i = 0; i < transformedRespEntries.size(); i++) {
                    HeaderEntry entry = transformedRespEntries.get(i);
                    if (!responseDropped.contains(entry.name().toLowerCase(Locale.ROOT))) {
                        responseHeaders.add(new HttpHeader(entry.name(), entry.value()));
                    }
                }

                HttpResponse downstreamResp = new HttpResponse(
                        upstreamResp.status(),
                        HttpVersion.HTTP_1_1,
                        responseHeaders,
                        upstreamResp.body()
                );

                exchange.respond(downstreamResp);
            } finally {
                if (host != null) {
                    host.decrementConnections();
                }
            }
        } catch (Exception e) {
            closeQuietly(upstreamResp);
            // The exception text can carry upstream addresses and response fragments, so only its type is logged
            LOG.log(System.Logger.Level.WARNING, "Proxying {0} failed: {1}", routeId, e.getClass().getName());
            try {
                exchange.respond(HttpStatus.BAD_GATEWAY);
            } catch (Exception ignored) {
                // Best-effort failure response
            }
        }
    }

    private static void closeQuietly(HttpResponse response) {
        if (response != null && response.body() != null) {
            try {
                response.body().close();
            } catch (Exception ignored) {
                // Releasing a buffer that is already released
            }
        }
    }

    private static Map<String, String> extractClaims(HttpRequest request) {
        String auth = request.firstHeader("Authorization").orElse(null);
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Map.of();
        }
        String token = auth.substring(7).trim();
        int firstDot = token.indexOf('.');
        int secondDot = token.indexOf('.', firstDot + 1);
        if (firstDot < 0 || secondDot < 0) {
            return Map.of();
        }
        try {
            String payloadB64 = token.substring(firstDot + 1, secondDot);
            byte[] jsonBytes = Base64.getUrlDecoder().decode(payloadB64);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            Map<String, String> claims = new HashMap<>(4);
            extractJsonString(json, "sub", claims);
            extractJsonString(json, "iss", claims);
            extractJsonString(json, "tenant_id", claims);
            extractJsonString(json, "tid", claims);
            return claims;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void extractJsonString(String json, String key, Map<String, String> out) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx >= 0) {
            int start = idx + pattern.length();
            int end = json.indexOf('"', start);
            if (end > start) {
                out.put(key, json.substring(start, end));
            }
        }
    }

    private record TargetInfo(String authority, String basePath) {}
}
