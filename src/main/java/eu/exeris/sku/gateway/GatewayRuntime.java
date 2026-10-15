package eu.exeris.sku.gateway;

import eu.exeris.caps.breaker.api.CircuitBreakerConfig;
import eu.exeris.caps.breaker.api.CircuitBreakerRegistry;
import eu.exeris.caps.breaker.api.CircuitBreakers;
import eu.exeris.caps.corspolicy.api.CorsPolicies;
import eu.exeris.caps.corspolicy.api.CorsPolicy;
import eu.exeris.caps.gatewaycore.GatewayCoreModule;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import eu.exeris.caps.gatewaycore.api.PolicyChain;
import eu.exeris.caps.jwt.api.JwtAdmissionPolicies;
import eu.exeris.caps.jwt.api.JwtAdmissionPolicy;
import eu.exeris.caps.observability.api.GatewayObservabilities;
import eu.exeris.caps.observability.api.GatewayObservabilityService;
import eu.exeris.caps.pool.api.ActiveHealthCheckConfig;
import eu.exeris.caps.pool.api.LoadBalancingAlgorithm;
import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamPoolService;
import eu.exeris.caps.pool.api.UpstreamPools;
import eu.exeris.caps.ratelimit.api.RateLimitPolicies;
import eu.exeris.caps.ratelimit.api.RateLimitPolicy;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.caps.waf.api.WafMode;
import eu.exeris.caps.waf.api.WafPolicies;
import eu.exeris.caps.waf.api.WafPolicy;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.sku.gateway.breaker.CircuitBreakerIngress;
import eu.exeris.sku.gateway.config.GatewayConfig;
import eu.exeris.sku.gateway.cors.CorsGatewayPolicy;
import eu.exeris.sku.gateway.jwt.JwtGatewayPolicy;
import eu.exeris.sku.gateway.limits.RequestSizeGatewayPolicy;
import eu.exeris.sku.gateway.observability.ObservabilityIngress;
import eu.exeris.sku.gateway.ratelimit.RateLimitGatewayPolicy;
import eu.exeris.sku.gateway.routing.RoutingGatewayPolicy;
import eu.exeris.sku.gateway.routing.RoutingReverseProxyHandler;
import eu.exeris.sku.gateway.waf.WafGatewayPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The gateway assembled from a {@link GatewayConfig}: the core module, one policy adapter per enabled
 * capability, the route table, the cluster registry and the gateway's own endpoints.
 *
 * <p>It does not start the kernel. {@link GatewaySkuApplication} binds {@link #ingress()} to the kernel's
 * HTTP server and calls {@link #attach(HttpClientEngine)} once the client engine exists, which is what
 * lets a test drive the same assembly with a stand-in engine.
 *
 * <p>Policy order (ascending, per {@link eu.exeris.caps.gatewaycore.api.GatewayPolicy#order()}): request size
 * (-200), CORS
 * (-100), rate limiting (-50), WAF (-45), JWT admission (-40), routing (-10). The circuit breaker and the observability measurement wrap the
 * ingress rather than sit in the chain. A policy that refuses short-circuits, so a request is never
 * forwarded after one has.
 */
public final class GatewayRuntime implements AutoCloseable {

    private final GatewayConfig config;
    private final GatewayCoreModule module;
    private final RequestRouter router;
    private final UpstreamPoolService poolService;
    private final CircuitBreakerRegistry breakerRegistry;
    private final GatewayObservabilityService observability;
    private final HttpHandler ingress;
    private final HttpHandler management;

    private GatewayRuntime(GatewayConfig config, GatewayCoreModule module, RequestRouter router,
                           UpstreamPoolService poolService, CircuitBreakerRegistry breakerRegistry,
                           GatewayObservabilityService observability, HttpHandler ingress,
                           HttpHandler management) {
        this.config = config;
        this.module = module;
        this.router = router;
        this.poolService = poolService;
        this.breakerRegistry = breakerRegistry;
        this.observability = observability;
        this.ingress = ingress;
        this.management = management;
    }

    /**
     * Assembles the gateway.
     *
     * @param config the validated configuration
     * @return the assembled gateway, not yet attached to a client engine nor started
     */
    public static GatewayRuntime create(GatewayConfig config) {
        GatewayCoreModule module = new GatewayCoreModule();
        IngressBootstrap bootstrap = module.ingressBootstrap();
        PolicyChain chain = module.policyChain();

        GatewayObservabilityService observability =
                config.observability() ? GatewayObservabilities.newService() : null;

        // Only health is served on the ingress and open to admission and inspection; the diagnostics
        // are on the management listener, which has its own handler
        Set<String> openPaths = Endpoints.HEALTH_PATHS;

        chain.addPolicy(new RequestSizeGatewayPolicy(config.http().maxRequestBodyBytes()));

        if (!config.corsOrigins().isEmpty()) {
            chain.addPolicy(new CorsGatewayPolicy(corsPolicy(config.corsOrigins())));
        }

        if (config.rateLimit() != null) {
            GatewayConfig.RateLimit limit = config.rateLimit();
            RateLimitPolicy policy = RateLimitPolicies.builder()
                    .rate(limit.requestsPerSecond(), Duration.ofSeconds(1))
                    .burst(limit.burst())
                    .capacity(limit.capacity())
                    .build();
            chain.addPolicy(new RateLimitGatewayPolicy(
                    policy,
                    limit.keyHeader() == null
                            ? RateLimitGatewayPolicy.sharedKeyExtractor()
                            : RateLimitGatewayPolicy.headerKeyExtractor(limit.keyHeader()),
                    limit.responseHeaders()));
        }

        if (config.waf() != null) {
            WafMode mode = config.waf().detectOnly() ? WafMode.DETECT : WafMode.BLOCK;
            WafPolicy policy = WafPolicies.defaultCoreRules(mode);
            chain.addPolicy(new WafGatewayPolicy(policy, openPaths, WafGatewayPolicy.DEFAULT_ORDER));
        }

        if (config.jwt() != null) {
            GatewayConfig.Jwt jwt = config.jwt();
            JwtAdmissionPolicies.Builder builder = JwtAdmissionPolicies.builder()
                    .expectedIssuer(jwt.issuer())
                    .expectedAudience(jwt.audience());
            if (jwt.jwksUri() != null) {
                builder.jwksUri(jwt.jwksUri());
            } else {
                builder.publicKey(jwt.keyId(), jwt.publicKey());
            }
            if (jwt.acceptRs256()) {
                builder.acceptRs256();
            }
            JwtAdmissionPolicy policy = builder.build();
            chain.addPolicy(new JwtGatewayPolicy(policy, openPaths, List.of()));
        }

        RequestRouter router = buildRouter(config.routes());

        CircuitBreakerRegistry breakerRegistry = null;
        if (config.circuitBreaker() != null) {
            GatewayConfig.CircuitBreaker breaker = config.circuitBreaker();
            CircuitBreakerConfig defaults = CircuitBreakerConfig.builder()
                    .slidingWindowSize(breaker.slidingWindow())
                    .minimumNumberOfCalls(breaker.minimumCalls())
                    .failureRateThreshold(breaker.failureRatePercent())
                    .waitDurationInOpenState(Duration.ofSeconds(breaker.waitSeconds()))
                    .permittedNumberOfCallsInHalfOpenState(breaker.halfOpenPermits())
                    .build();
            breakerRegistry = CircuitBreakers.newRegistry(defaults);
        }

        chain.addPolicy(new RoutingGatewayPolicy(router, openPaths));

        UpstreamPoolService poolService = config.clusters().isEmpty() ? null : buildPool(config);

        Endpoints.registerHealth(bootstrap, module.gatewayLifecycle());
        HttpHandler management = config.management() == null ? null : Endpoints.management(observability);

        HttpHandler ingress = bootstrap;
        if (breakerRegistry != null) {
            ingress = new CircuitBreakerIngress(ingress, breakerRegistry, router, Endpoints.HEALTH_PATHS);
        }
        if (observability != null) {
            ingress = new ObservabilityIngress(ingress, observability, Set.of());
        }

        return new GatewayRuntime(config, module, router, poolService, breakerRegistry, observability,
                ingress, management);
    }

    static CorsPolicy corsPolicy(List<String> origins) {
        CorsPolicies.Builder builder = CorsPolicies.builder()
                .methods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
                .headers(List.of("Content-Type", "Authorization"))
                .maxAge(Duration.ofMinutes(10));
        // A wildcard is served only when the operator wrote it, and never together with credentials
        if (origins.size() == 1 && origins.get(0).equals("*")) {
            return builder.allowAnyOrigin().build();
        }
        return builder.allowOrigins(origins).allowCredentials(true).build();
    }

    private static RequestRouter buildRouter(List<GatewayConfig.Route> routes) {
        RequestRouters.Builder builder = RequestRouters.builder();
        for (GatewayConfig.Route route : routes) {
            RequestRouters.RouteBuilder rb = builder.route(route.id())
                    .pathPrefix(route.prefix())
                    .targetUri(route.target());
            if (route.stripPrefix()) {
                rb.stripPrefix(route.prefix());
            }
            rb.and();
        }
        return builder.build();
    }

    private static UpstreamPoolService buildPool(GatewayConfig config) {
        UpstreamPoolService service = UpstreamPools.newService();
        GatewayConfig.Pool pool = config.pool();
        LoadBalancingAlgorithm algorithm = LoadBalancingAlgorithm.valueOf(pool.algorithm());
        PassiveHealthCheckConfig passive = pool.passiveHealthCheck()
                ? PassiveHealthCheckConfig.of(pool.consecutiveErrors(), Duration.ofSeconds(pool.ejectionSeconds()),
                        pool.maxEjectionPercent())
                : PassiveHealthCheckConfig.DISABLED;
        GatewayConfig.ActiveHealthCheck probe = pool.activeHealthCheck();
        ActiveHealthCheckConfig active = probe == null
                ? ActiveHealthCheckConfig.DISABLED
                : new ActiveHealthCheckConfig(true, probe.intervalMillis(), probe.timeoutMillis(), probe.path(),
                        probe.healthyThreshold(), probe.unhealthyThreshold());
        for (GatewayConfig.Cluster cluster : config.clusters()) {
            UpstreamPools.ClusterBuilder builder = UpstreamPools.cluster(cluster.name())
                    .algorithm(algorithm)
                    .passiveHealthCheck(passive)
                    .activeHealthCheck(active);
            for (String endpoint : cluster.endpoints()) {
                builder.addEndpoint(endpoint);
            }
            UpstreamCluster built = builder.build();
            service.registerCluster(built);
        }
        return service;
    }

    /**
     * The handler to bind to the kernel's HTTP server: the gateway's ingress, wrapped by the
     * observability measurement when that is on.
     *
     * @return the server handler
     */
    public HttpHandler ingress() {
        return ingress;
    }

    /** The core module, for lifecycle calls. */
    public GatewayCoreModule module() {
        return module;
    }

    /** The route table. */
    public RequestRouter router() {
        return router;
    }

    /**
     * The handler for the management listener, or {@code null} when management is off. It is the only
     * place the diagnostic and metrics endpoints are served.
     *
     * @return the management handler, or {@code null}
     */
    public HttpHandler management() {
        return management;
    }

    /**
     * Installs the reverse proxy that carries requests no gateway endpoint serves.
     *
     * @param clientEngine the engine that sends requests upstream
     */
    public void attach(HttpClientEngine clientEngine) {
        module.ingressBootstrap().setDelegateHandler(new RoutingReverseProxyHandler(
                router, clientEngine, poolService, config.pool().failover(), config.jwt() != null));
    }

    /**
     * Releases the pool, which stops the clusters' health probes, the breakers and the observability service.
     */
    @Override
    public void close() {
        List<AutoCloseable> closeables = new ArrayList<>();
        closeables.add(poolService);
        closeables.add(breakerRegistry);
        closeables.add(observability);
        for (AutoCloseable closeable : closeables) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    System.getLogger(GatewayRuntime.class.getName()).log(System.Logger.Level.WARNING,
                            "Closing {0} failed: {1}", closeable.getClass().getSimpleName(), e.getClass().getName());
                }
            }
        }
    }
}
