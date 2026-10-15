package eu.exeris.sku.gateway;

import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import eu.exeris.caps.observability.api.GatewayObservabilityService;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The gateway's own endpoints.
 *
 * <p>Health is served on the ingress: it says only whether the gateway accepts traffic. The diagnostic
 * endpoints disclose process internals (allocation, request metrics, recent paths); they are reachable only
 * through {@link #management}, which the management listener serves.
 */
final class Endpoints {

    /** Liveness and readiness paths; always served. */
    static final Set<String> HEALTH_PATHS = Set.of("/health", "/actuator/health");

    /** Allocation diagnostics. */
    static final String ALLOCATIONS_PATH = "/actuator/allocations";

    private Endpoints() {
    }

    static void registerHealth(IngressBootstrap ingress, GatewayLifecycle lifecycle) {
        HttpHandler handler = ex -> {
            boolean accepting = lifecycle.isAcceptingTraffic();
            HttpStatus status = accepting ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
            json(ex, status, accepting ? "{\"status\":\"UP\"}" : "{\"status\":\"DOWN\"}");
        };
        for (String path : HEALTH_PATHS) {
            ingress.registerHandler(path, handler);
        }
    }

    /**
     * The handler of the management listener: the one place diagnostics are routed. Metrics and the
     * dashboard exist only when observability is on.
     */
    static HttpHandler management(GatewayObservabilityService observability) {
        Map<String, HttpHandler> routes = new HashMap<>();
        routes.put(ALLOCATIONS_PATH, Endpoints::allocations);
        if (observability != null) {
            routes.put("/metrics", ex ->
                    respond(ex, "text/plain; version=0.0.4; charset=utf-8", observability.exportPrometheus()));
            routes.put("/actuator/metrics", ex ->
                    respond(ex, "application/json", observability.exportJson()));
            HttpHandler dashboard = ex ->
                    respond(ex, "text/html; charset=utf-8", observability.exportDashboardHtml());
            routes.put("/dashboard", dashboard);
            routes.put("/observability", dashboard);
        }
        return ex -> {
            String path = ex.request().path();
            int query = path.indexOf('?');
            HttpHandler handler = routes.get(query < 0 ? path : path.substring(0, query));
            if (handler == null) {
                ex.respond(HttpStatus.NOT_FOUND);
            } else {
                handler.handle(ex);
            }
        };
    }

    private static void allocations(HttpExchange ex) {
        long totalAllocated = 0;
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean sunBean) {
            for (long id : sunBean.getAllThreadIds()) {
                long bytes = sunBean.getThreadAllocatedBytes(id);
                if (bytes > 0) {
                    totalAllocated += bytes;
                }
            }
        }
        long heapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        json(ex, HttpStatus.OK, "{\"allocatedBytes\":" + totalAllocated + ",\"heapUsedBytes\":" + heapUsed + "}");
    }

    private static void json(HttpExchange ex, HttpStatus status, String body) {
        respond(ex, status, "application/json", body);
    }

    private static void respond(HttpExchange ex, String contentType, String body) {
        respond(ex, HttpStatus.OK, contentType, body);
    }

    private static void respond(HttpExchange ex, HttpStatus status, String contentType, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        List<HttpHeader> headers = List.of(
                new HttpHeader("Content-Type", contentType),
                new HttpHeader("Content-Length", String.valueOf(bytes.length)),
                new HttpHeader("Cache-Control", "no-store")
        );
        ex.respond(new HttpResponse(status, HttpVersion.HTTP_1_1, headers, new ByteArrayLoanedBuffer(bytes)));
    }
}
