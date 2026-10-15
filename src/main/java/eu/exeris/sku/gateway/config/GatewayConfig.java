package eu.exeris.sku.gateway.config;

import eu.exeris.caps.breaker.api.CircuitBreakerConfig;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The gateway's runtime configuration, read from environment variables and an optional properties file.
 *
 * <p>Every setting has one name. The properties file named by {@value #CONFIG_FILE_KEY} uses the same
 * names as the environment, and an environment variable overrides the file. A value that cannot be used
 * raises a {@link GatewayConfigException} that names the setting, so the gateway refuses to start
 * rather than start with something the operator did not choose.
 *
 * <p>Nothing here is specific to an application: the routes and clusters are the operator's.
 *
 * @param http          listener and request limits
 * @param upstream      limits on the connection to an upstream
 * @param routes        the route table, in declaration order; never empty
 * @param clusters      the upstream clusters a route may target as {@code cluster://name}
 * @param pool          upstream pool behaviour, used when at least one cluster is declared
 * @param management    the management listener that serves the diagnostic and metrics endpoints, {@code null} when off
 * @param corsOrigins   allowed CORS origins; empty when CORS is off
 * @param rateLimit     rate limiting, {@code null} when off
 * @param waf           the WAF, {@code null} when off
 * @param jwt           JWT admission, {@code null} when off
 * @param circuitBreaker the circuit breaker, {@code null} when off
 * @param observability whether request metrics, tracing and access recording are on
 */
public record GatewayConfig(
        Http http,
        Upstream upstream,
        List<Route> routes,
        List<Cluster> clusters,
        Pool pool,
        Management management,
        List<String> corsOrigins,
        RateLimit rateLimit,
        Waf waf,
        Jwt jwt,
        CircuitBreaker circuitBreaker,
        boolean observability) {

    /** Key naming a properties file that carries any of the settings below. */
    public static final String CONFIG_FILE_KEY = "EXERIS_GATEWAY_CONFIG";

    /** Smallest HMAC secret accepted, in bytes: the output size of HMAC-SHA-256 (RFC 7518 section 3.2). */
    public static final int MIN_JWT_SECRET_BYTES = 32;

    static final String HTTP_BIND_HOST = "EXERIS_HTTP_BIND_HOST";
    static final String HTTP_PORT = "EXERIS_HTTP_PORT";
    static final String HTTP_PORT_ALIAS = "PORT";
    static final String HTTP_MAX_REQUEST_BODY_BYTES = "EXERIS_HTTP_MAX_REQUEST_BODY_BYTES";
    static final String HTTP_MAX_REQUEST_HEADER_COUNT = "EXERIS_HTTP_MAX_REQUEST_HEADER_COUNT";
    static final String HTTP_MAX_REQUEST_HEADER_SIZE = "EXERIS_HTTP_MAX_REQUEST_HEADER_SIZE";
    static final String UPSTREAM_TIMEOUT_MS = "EXERIS_UPSTREAM_TIMEOUT_MS";
    static final String UPSTREAM_MAX_RESPONSE_BYTES = "EXERIS_UPSTREAM_MAX_RESPONSE_BYTES";
    static final String ROUTES = "EXERIS_GATEWAY_ROUTES";
    static final String CLUSTERS = "EXERIS_GATEWAY_CLUSTERS";
    static final String LB_ALGORITHM = "EXERIS_LB_ALGORITHM";
    static final String FAILOVER_ENABLED = "EXERIS_FAILOVER_ENABLED";
    static final String PASSIVE_HEALTH_CHECK_ENABLED = "EXERIS_PASSIVE_HEALTH_CHECK_ENABLED";
    static final String PASSIVE_CONSECUTIVE_ERRORS = "EXERIS_PASSIVE_CONSECUTIVE_ERRORS";
    static final String PASSIVE_EJECTION_SEC = "EXERIS_PASSIVE_EJECTION_SEC";
    static final String PASSIVE_MAX_EJECTION_PERCENT = "EXERIS_PASSIVE_MAX_EJECTION_PERCENT";
    static final String ACTIVE_HEALTH_CHECK_ENABLED = "EXERIS_ACTIVE_HEALTH_CHECK_ENABLED";
    static final String ACTIVE_HEALTH_CHECK_PATH = "EXERIS_ACTIVE_HEALTH_CHECK_PATH";
    static final String ACTIVE_HEALTH_CHECK_INTERVAL_MS = "EXERIS_ACTIVE_HEALTH_CHECK_INTERVAL_MS";
    static final String ACTIVE_HEALTH_CHECK_TIMEOUT_MS = "EXERIS_ACTIVE_HEALTH_CHECK_TIMEOUT_MS";
    static final String ACTIVE_HEALTHY_THRESHOLD = "EXERIS_ACTIVE_HEALTHY_THRESHOLD";
    static final String ACTIVE_UNHEALTHY_THRESHOLD = "EXERIS_ACTIVE_UNHEALTHY_THRESHOLD";
    static final String MANAGEMENT_ENABLED = "EXERIS_MANAGEMENT_ENABLED";
    static final String MANAGEMENT_BIND_HOST = "EXERIS_MANAGEMENT_BIND_HOST";
    static final String MANAGEMENT_PORT = "EXERIS_MANAGEMENT_PORT";
    static final String CORS_ALLOWED_ORIGINS = "EXERIS_CORS_ALLOWED_ORIGINS";
    static final String RATELIMIT_ENABLED = "EXERIS_RATELIMIT_ENABLED";
    static final String RATELIMIT_RPS = "EXERIS_RATELIMIT_RPS";
    static final String RATELIMIT_BURST = "EXERIS_RATELIMIT_BURST";
    static final String RATELIMIT_CAPACITY = "EXERIS_RATELIMIT_CAPACITY";
    static final String RATELIMIT_HEADERS = "EXERIS_RATELIMIT_HEADERS";
    static final String RATELIMIT_KEY_HEADER = "EXERIS_RATELIMIT_KEY_HEADER";
    static final String WAF_ENABLED = "EXERIS_WAF_ENABLED";
    static final String WAF_MODE = "EXERIS_WAF_MODE";
    static final String JWT_ENABLED = "EXERIS_JWT_ENABLED";
    static final String JWT_SECRET = "EXERIS_JWT_SECRET";
    static final String JWT_ISSUER = "EXERIS_JWT_ISSUER";
    static final String JWT_AUDIENCE = "EXERIS_JWT_AUDIENCE";
    static final String JWT_CACHE_CAPACITY = "EXERIS_JWT_CACHE_CAPACITY";
    static final String CB_ENABLED = "EXERIS_CIRCUIT_BREAKER_ENABLED";
    static final String CB_SLIDING_WINDOW = "EXERIS_CB_SLIDING_WINDOW";
    static final String CB_MIN_CALLS = "EXERIS_CB_MIN_CALLS";
    static final String CB_FAILURE_RATE = "EXERIS_CB_FAILURE_RATE";
    static final String CB_WAIT_DURATION_SEC = "EXERIS_CB_WAIT_DURATION_SEC";
    static final String CB_PERMITTED_HALF_OPEN = "EXERIS_CB_PERMITTED_HALF_OPEN";
    static final String OBSERVABILITY_ENABLED = "EXERIS_OBSERVABILITY_ENABLED";

    private static final Set<String> FILE_KEYS = Set.of(
            HTTP_BIND_HOST, HTTP_PORT, HTTP_PORT_ALIAS, HTTP_MAX_REQUEST_BODY_BYTES,
            HTTP_MAX_REQUEST_HEADER_COUNT, HTTP_MAX_REQUEST_HEADER_SIZE, UPSTREAM_TIMEOUT_MS,
            UPSTREAM_MAX_RESPONSE_BYTES, ROUTES, CLUSTERS, LB_ALGORITHM, FAILOVER_ENABLED,
            PASSIVE_HEALTH_CHECK_ENABLED, PASSIVE_CONSECUTIVE_ERRORS, PASSIVE_EJECTION_SEC,
            PASSIVE_MAX_EJECTION_PERCENT, ACTIVE_HEALTH_CHECK_ENABLED, ACTIVE_HEALTH_CHECK_PATH,
            ACTIVE_HEALTH_CHECK_INTERVAL_MS, ACTIVE_HEALTH_CHECK_TIMEOUT_MS, ACTIVE_HEALTHY_THRESHOLD,
            ACTIVE_UNHEALTHY_THRESHOLD,
            MANAGEMENT_ENABLED, MANAGEMENT_BIND_HOST, MANAGEMENT_PORT, CORS_ALLOWED_ORIGINS, RATELIMIT_ENABLED, RATELIMIT_RPS, RATELIMIT_BURST,
            RATELIMIT_CAPACITY, RATELIMIT_HEADERS, RATELIMIT_KEY_HEADER, WAF_ENABLED, WAF_MODE, JWT_ENABLED,
            JWT_SECRET, JWT_ISSUER, JWT_AUDIENCE, JWT_CACHE_CAPACITY, CB_ENABLED, CB_SLIDING_WINDOW,
            CB_MIN_CALLS, CB_FAILURE_RATE, CB_WAIT_DURATION_SEC, CB_PERMITTED_HALF_OPEN, OBSERVABILITY_ENABLED);

    private static final Pattern CLUSTER_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    /**
     * Listener settings, applied to the kernel's HTTP server.
     *
     * @param bindHost                address to bind
     * @param port                    TCP port
     * @param maxRequestBodyBytes     largest request body the server accepts
     * @param maxRequestHeaderCount   most headers a request may carry
     * @param maxRequestHeaderSize    largest single header, in bytes
     */
    public record Http(String bindHost, int port, long maxRequestBodyBytes,
                       int maxRequestHeaderCount, int maxRequestHeaderSize) {
    }

    /**
     * Limits on the gateway's connection to an upstream.
     *
     * @param timeoutMillis     how long an upstream connection may move no bytes before it is reclaimed
     * @param maxResponseBytes  largest upstream response the gateway accepts
     */
    public record Upstream(long timeoutMillis, long maxResponseBytes) {
    }

    /**
     * One route: requests whose path starts with {@code prefix} at a segment boundary go to {@code target}.
     *
     * @param prefix       the path prefix, starting with {@code /}, without a trailing {@code /} unless it is the root
     * @param target       an {@code http://} base URI, or {@code cluster://name}
     * @param stripPrefix  whether the prefix is removed from the path sent upstream
     */
    public record Route(String prefix, String target, boolean stripPrefix) {

        /** The route id: stable, derived from the prefix. */
        public String id() {
            return "route:" + prefix;
        }

        /** Whether the target is a cluster rather than a single upstream. */
        public boolean targetsCluster() {
            return target.startsWith(CLUSTER_SCHEME);
        }

        /** The cluster name; only meaningful when {@link #targetsCluster()}. */
        public String clusterName() {
            return target.substring(CLUSTER_SCHEME.length());
        }
    }

    /**
     * A named group of upstream endpoints.
     *
     * @param name       the cluster name
     * @param endpoints  {@code http://} base URIs; never empty
     */
    public record Cluster(String name, List<String> endpoints) {
    }

    /**
     * The management listener: a second HTTP listener, separate from the ingress, that serves the
     * diagnostic and metrics endpoints and nothing else. It is the single access policy for them.
     *
     * @param bindHost  address to bind; loopback unless the operator chose otherwise
     * @param port      TCP port, different from the ingress port
     */
    public record Management(String bindHost, int port) {
    }

    /**
     * Upstream pool behaviour.
     *
     * @param algorithm                {@code ROUND_ROBIN}, {@code LEAST_CONNECTIONS}, {@code WEIGHTED_ROUND_ROBIN},
     *                                 {@code RANDOM} or {@code IP_HASH}
     * @param failover                 whether a safe, bodyless request is retried on another host after a 5xx
     * @param passiveHealthCheck       whether a host is ejected after consecutive errors
     * @param consecutiveErrors        errors that eject a host
     * @param ejectionSeconds          how long an ejected host stays out
     * @param maxEjectionPercent       the share of a cluster's hosts that may be ejected at once, 1 to 100; at
     *                                 least one host always stays in rotation
     * @param activeHealthCheck        the background probe, or {@code null} when it is off
     */
    public record Pool(String algorithm, boolean failover, boolean passiveHealthCheck,
                       int consecutiveErrors, int ejectionSeconds, int maxEjectionPercent,
                       ActiveHealthCheck activeHealthCheck) {
    }

    /**
     * Active health checking: a background probe of every host of a cluster.
     *
     * @param path                the absolute path probed on each host
     * @param intervalMillis      time between probe rounds
     * @param timeoutMillis       time a probe may take before it counts as failed
     * @param healthyThreshold    consecutive successful probes that return an ejected host
     * @param unhealthyThreshold  consecutive failed probes that eject a host
     */
    public record ActiveHealthCheck(String path, long intervalMillis, long timeoutMillis,
                                    int healthyThreshold, int unhealthyThreshold) {
    }

    /**
     * Rate limiting.
     *
     * @param requestsPerSecond  sustained rate
     * @param burst              burst size
     * @param capacity           number of distinct keys tracked
     * @param responseHeaders    whether allowed responses carry quota headers
     * @param keyHeader          a request header whose value keys the limit, or {@code null} for one shared limit
     */
    public record RateLimit(long requestsPerSecond, long burst, int capacity,
                            boolean responseHeaders, String keyHeader) {
    }

    /**
     * The WAF.
     *
     * @param detectOnly  {@code true} to record a match and let the request through, {@code false} to block
     */
    public record Waf(boolean detectOnly) {
    }

    /**
     * JWT admission with a shared HMAC secret.
     *
     * @param secret         the HMAC secret; at least {@value #MIN_JWT_SECRET_BYTES} bytes
     * @param issuer         the required issuer, or {@code null}
     * @param audience       the required audience, or {@code null}
     * @param cacheCapacity  validated-token cache size
     */
    public record Jwt(String secret, String issuer, String audience, int cacheCapacity) {

        @Override
        public String toString() {
            return "Jwt[secret=<redacted>, issuer=" + issuer + ", audience=" + audience
                    + ", cacheCapacity=" + cacheCapacity + "]";
        }
    }

    /**
     * The circuit breaker, one breaker per route.
     *
     * @param slidingWindow         calls in the sliding window
     * @param minimumCalls          calls before the failure rate is judged
     * @param failureRatePercent    failure rate that opens the breaker
     * @param waitSeconds           how long the breaker stays open
     * @param halfOpenPermits       probe calls allowed while half-open
     */
    public record CircuitBreaker(int slidingWindow, int minimumCalls, float failureRatePercent,
                                 int waitSeconds, int halfOpenPermits) {
    }

    private static final String CLUSTER_SCHEME = "cluster://";

    /**
     * Reads the configuration from the process environment and the file it names.
     *
     * @return the validated configuration
     * @throws GatewayConfigException when a setting is missing or unusable
     */
    public static GatewayConfig load() {
        return load(System.getenv());
    }

    /**
     * Reads the configuration from {@code environment} and the file it names.
     *
     * @param environment the environment variables; they override the file
     * @return the validated configuration
     * @throws GatewayConfigException when a setting is missing or unusable
     */
    public static GatewayConfig load(Map<String, String> environment) {
        Map<String, String> values = new LinkedHashMap<>();
        String file = environment.get(CONFIG_FILE_KEY);
        if (file != null && !file.isBlank()) {
            values.putAll(readFile(Path.of(file.trim())));
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (FILE_KEYS.contains(entry.getKey()) && entry.getValue() != null && !entry.getValue().isBlank()) {
                values.put(entry.getKey(), entry.getValue().trim());
            }
        }
        return from(values);
    }

    /**
     * Builds the configuration from already merged settings.
     *
     * @param values setting name to value; a blank value counts as unset
     * @return the validated configuration
     * @throws GatewayConfigException when a setting is missing or unusable
     */
    public static GatewayConfig from(Map<String, String> values) {
        Source in = new Source(values);

        Http http = new Http(
                in.string(HTTP_BIND_HOST, "0.0.0.0"),
                in.integer(HTTP_PORT, in.integer(HTTP_PORT_ALIAS, 8080, 1, 65_535), 1, 65_535),
                in.longValue(HTTP_MAX_REQUEST_BODY_BYTES, 10L * 1024 * 1024, 1, Long.MAX_VALUE),
                in.integer(HTTP_MAX_REQUEST_HEADER_COUNT, 100, 1, 10_000),
                in.integer(HTTP_MAX_REQUEST_HEADER_SIZE, 8_192, 1, 1_048_576));

        Upstream upstream = new Upstream(
                in.longValue(UPSTREAM_TIMEOUT_MS, 30_000L, 1, Long.MAX_VALUE),
                in.longValue(UPSTREAM_MAX_RESPONSE_BYTES, 10L * 1024 * 1024, 1, Long.MAX_VALUE));

        List<Cluster> clusters = parseClusters(in.string(CLUSTERS, ""));
        List<Route> routes = parseRoutes(in.string(ROUTES, ""));
        if (routes.isEmpty()) {
            throw new GatewayConfigException(ROUTES + " is required: declare at least one route, "
                    + "for example /api=http://backend:8080");
        }
        Set<String> clusterNames = new HashSet<>();
        for (Cluster cluster : clusters) {
            clusterNames.add(cluster.name());
        }
        for (Route route : routes) {
            if (route.targetsCluster() && !clusterNames.contains(route.clusterName())) {
                throw new GatewayConfigException(ROUTES + ": route " + route.prefix()
                        + " targets cluster '" + route.clusterName() + "', which " + CLUSTERS + " does not declare");
            }
        }

        Pool pool = new Pool(
                poolAlgorithm(in.string(LB_ALGORITHM, "ROUND_ROBIN")),
                in.bool(FAILOVER_ENABLED, true),
                in.bool(PASSIVE_HEALTH_CHECK_ENABLED, true),
                in.integer(PASSIVE_CONSECUTIVE_ERRORS, 3, 1, 1_000),
                in.integer(PASSIVE_EJECTION_SEC, 5, 1, 86_400),
                in.integer(PASSIVE_MAX_EJECTION_PERCENT, 100, 1, 100),
                activeHealthCheck(in));

        List<String> corsOrigins = parseOrigins(in.string(CORS_ALLOWED_ORIGINS, ""));

        RateLimit rateLimit = null;
        if (in.bool(RATELIMIT_ENABLED, false)) {
            long rps = in.longValue(RATELIMIT_RPS, 10_000L, 1, Long.MAX_VALUE);
            String keyHeader = in.string(RATELIMIT_KEY_HEADER, "");
            if (!keyHeader.isEmpty() && !HEADER_NAME.matcher(keyHeader).matches()) {
                throw new GatewayConfigException(RATELIMIT_KEY_HEADER + " is not a header name: " + keyHeader);
            }
            rateLimit = new RateLimit(
                    rps,
                    in.longValue(RATELIMIT_BURST, rps, 1, Long.MAX_VALUE),
                    in.integer(RATELIMIT_CAPACITY, 65_536, 1, 100_000_000),
                    in.bool(RATELIMIT_HEADERS, false),
                    keyHeader.isEmpty() ? null : keyHeader);
        }

        Waf waf = null;
        if (in.bool(WAF_ENABLED, false)) {
            String mode = in.string(WAF_MODE, "BLOCK").toUpperCase(Locale.ROOT);
            if (!mode.equals("BLOCK") && !mode.equals("DETECT")) {
                throw new GatewayConfigException(WAF_MODE + " must be BLOCK or DETECT, not '" + mode + "'");
            }
            waf = new Waf(mode.equals("DETECT"));
        }

        Jwt jwt = null;
        if (in.bool(JWT_ENABLED, false)) {
            String secret = in.string(JWT_SECRET, "");
            if (secret.isEmpty()) {
                throw new GatewayConfigException(JWT_ENABLED + " is true but " + JWT_SECRET
                        + " is not set: the gateway has no built-in secret and will not start without one");
            }
            if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
                throw new GatewayConfigException(JWT_SECRET + " is shorter than " + MIN_JWT_SECRET_BYTES
                        + " bytes, the size an HMAC-SHA-256 key needs");
            }
            String issuer = in.string(JWT_ISSUER, "");
            String audience = in.string(JWT_AUDIENCE, "");
            jwt = new Jwt(secret, issuer.isEmpty() ? null : issuer, audience.isEmpty() ? null : audience,
                    in.integer(JWT_CACHE_CAPACITY, 16_384, 1, 100_000_000));
        }

        CircuitBreaker circuitBreaker = null;
        if (in.bool(CB_ENABLED, false)) {
            int window = in.integer(CB_SLIDING_WINDOW, 20, 1, 1_000_000);
            int minCalls = in.integer(CB_MIN_CALLS, 5, 1, 1_000_000);
            if (minCalls > window) {
                throw new GatewayConfigException(CB_MIN_CALLS + " (" + minCalls + ") must not exceed "
                        + CB_SLIDING_WINDOW + " (" + window + ")");
            }
            circuitBreaker = new CircuitBreaker(
                    window,
                    minCalls,
                    (float) in.decimal(CB_FAILURE_RATE, 50.0, 0.0, 100.0),
                    in.integer(CB_WAIT_DURATION_SEC, 3, 1, 86_400),
                    in.integer(CB_PERMITTED_HALF_OPEN, 3, 1,
                            CircuitBreakerConfig.MAX_PERMITTED_CALLS_IN_HALF_OPEN_STATE));
        }

        Management management = null;
        if (in.bool(MANAGEMENT_ENABLED, false)) {
            management = new Management(in.string(MANAGEMENT_BIND_HOST, "127.0.0.1"),
                    in.integer(MANAGEMENT_PORT, 9090, 1, 65_535));
            if (management.port() == http.port()) {
                throw new GatewayConfigException(MANAGEMENT_PORT + " must differ from the ingress port "
                        + http.port() + ": diagnostics are never served on the ingress");
            }
        }

        return new GatewayConfig(http, upstream, routes, clusters, pool,
                management, corsOrigins, rateLimit, waf, jwt, circuitBreaker,
                in.bool(OBSERVABILITY_ENABLED, false));
    }

    private static Map<String, String> readFile(Path path) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new GatewayConfigException(CONFIG_FILE_KEY + " names a file that cannot be read: " + path, e);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!FILE_KEYS.contains(key)) {
                throw new GatewayConfigException(path + " carries an unknown setting: " + key);
            }
            String value = properties.getProperty(key).trim();
            if (!value.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    static List<Route> parseRoutes(String spec) {
        List<Route> routes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String entry : entries(spec)) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                throw new GatewayConfigException(ROUTES + ": '" + entry + "' is not PREFIX=TARGET");
            }
            String prefix = entry.substring(0, eq).trim();
            String rest = entry.substring(eq + 1).trim();
            boolean strip = false;
            int comma = rest.indexOf(',');
            String target = comma < 0 ? rest : rest.substring(0, comma).trim();
            if (comma >= 0) {
                String option = rest.substring(comma + 1).trim();
                if (!option.equals("strip")) {
                    throw new GatewayConfigException(ROUTES + ": unknown route option '" + option
                            + "' on " + prefix + " (the only option is strip)");
                }
                strip = true;
            }
            prefix = normalisePrefix(prefix);
            if (!seen.add(prefix)) {
                throw new GatewayConfigException(ROUTES + ": prefix " + prefix + " is declared twice");
            }
            routes.add(new Route(prefix, target.startsWith(CLUSTER_SCHEME) ? clusterTarget(target) : upstreamUri(ROUTES, target), strip));
        }
        return List.copyOf(routes);
    }

    static List<Cluster> parseClusters(String spec) {
        List<Cluster> clusters = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String entry : entries(spec)) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                throw new GatewayConfigException(CLUSTERS + ": '" + entry + "' is not NAME=URL,URL");
            }
            String name = entry.substring(0, eq).trim();
            if (!CLUSTER_NAME.matcher(name).matches()) {
                throw new GatewayConfigException(CLUSTERS + ": '" + name + "' is not a cluster name");
            }
            if (!seen.add(name)) {
                throw new GatewayConfigException(CLUSTERS + ": cluster " + name + " is declared twice");
            }
            List<String> endpoints = new ArrayList<>();
            for (String endpoint : entry.substring(eq + 1).split(",")) {
                if (!endpoint.isBlank()) {
                    endpoints.add(upstreamUri(CLUSTERS, endpoint.trim()));
                }
            }
            if (endpoints.isEmpty()) {
                throw new GatewayConfigException(CLUSTERS + ": cluster " + name + " has no endpoint");
            }
            clusters.add(new Cluster(name, List.copyOf(endpoints)));
        }
        return List.copyOf(clusters);
    }

    private static List<String> entries(String spec) {
        List<String> entries = new ArrayList<>();
        for (String entry : spec.split("[;\\n]")) {
            if (!entry.isBlank()) {
                entries.add(entry.trim());
            }
        }
        return entries;
    }

    private static String normalisePrefix(String prefix) {
        if (!prefix.startsWith("/") || prefix.contains("?") || prefix.contains("#")
                || prefix.chars().anyMatch(Character::isWhitespace) || prefix.contains("//")
                || prefix.contains("..")) {
            throw new GatewayConfigException(ROUTES + ": '" + prefix
                    + "' is not a path prefix (it must start with / and be a plain canonical path)");
        }
        return prefix.length() > 1 && prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    private static String clusterTarget(String target) {
        String name = target.substring(CLUSTER_SCHEME.length());
        if (!CLUSTER_NAME.matcher(name).matches()) {
            throw new GatewayConfigException(ROUTES + ": '" + target + "' does not name a cluster");
        }
        return target;
    }

    /**
     * Validates an upstream base URI. Only plaintext {@code http} is accepted: the gateway does not
     * originate TLS to an upstream, and a scheme it would silently send in the clear is refused.
     */
    private static String upstreamUri(String setting, String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new GatewayConfigException(setting + ": '" + value + "' is not a URI");
        }
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            throw new GatewayConfigException(setting + ": '" + value
                    + "' is https, but the gateway does not provide TLS to an upstream; use http on a trusted network");
        }
        if (!"http".equalsIgnoreCase(scheme) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new GatewayConfigException(setting + ": '" + value
                    + "' is not an http://host[:port][/base] URI or a cluster://name target");
        }
        int port = uri.getPort();
        if (port == 0 || port > 65_535) {
            throw new GatewayConfigException(setting + ": '" + value + "' has an invalid port");
        }
        String clean = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return clean;
    }

    private static String poolAlgorithm(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "ROUND_ROBIN" -> "ROUND_ROBIN";
            case "LEAST_CONN", "LEAST_CONNECTIONS" -> "LEAST_CONNECTIONS";
            case "WEIGHTED", "WEIGHTED_ROUND_ROBIN" -> "WEIGHTED_ROUND_ROBIN";
            case "RANDOM" -> "RANDOM";
            case "IP_HASH" -> "IP_HASH";
            default -> throw new GatewayConfigException(LB_ALGORITHM + " must be ROUND_ROBIN, LEAST_CONNECTIONS, "
                    + "WEIGHTED_ROUND_ROBIN, RANDOM or IP_HASH, not '" + value + "'");
        };
    }

    private static ActiveHealthCheck activeHealthCheck(Source in) {
        if (!in.bool(ACTIVE_HEALTH_CHECK_ENABLED, false)) {
            return null;
        }
        String path = in.string(ACTIVE_HEALTH_CHECK_PATH, "/health");
        boolean originForm = !path.isEmpty() && path.charAt(0) == '/' && !path.startsWith("//")
                && path.chars().allMatch(c -> c > 0x20 && c != 0x7f && c != '#' && c != '?' && c != '\\'
                && c != '@');
        if (!originForm) {
            throw new GatewayConfigException(ACTIVE_HEALTH_CHECK_PATH
                    + " must be an absolute path such as /health, without authority, query or fragment, not '"
                    + path + "'");
        }
        return new ActiveHealthCheck(path,
                in.longValue(ACTIVE_HEALTH_CHECK_INTERVAL_MS, 1_000, 1, 3_600_000),
                in.longValue(ACTIVE_HEALTH_CHECK_TIMEOUT_MS, 500, 1, 600_000),
                in.integer(ACTIVE_HEALTHY_THRESHOLD, 2, 1, 1_000),
                in.integer(ACTIVE_UNHEALTHY_THRESHOLD, 3, 1, 1_000));
    }

    private static List<String> parseOrigins(String spec) {
        List<String> origins = new ArrayList<>();
        for (String origin : spec.split(",")) {
            if (!origin.isBlank()) {
                origins.add(origin.trim());
            }
        }
        return List.copyOf(origins);
    }

    /** Typed access to the merged settings that names the setting in every error. */
    private static final class Source {
        private final Map<String, String> values;

        Source(Map<String, String> values) {
            this.values = values;
        }

        String string(String key, String fallback) {
            String value = values.get(key);
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        boolean bool(String key, boolean fallback) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new GatewayConfigException(key + " must be true or false, not '" + value + "'");
            };
        }

        int integer(String key, int fallback, int min, int max) {
            return (int) longValue(key, fallback, min, max);
        }

        long longValue(String key, long fallback, long min, long max) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            long parsed;
            try {
                parsed = Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                throw new GatewayConfigException(key + " must be a whole number, not '" + value + "'");
            }
            if (parsed < min || parsed > max) {
                throw new GatewayConfigException(key + " must be between " + min + " and " + max + ", not " + parsed);
            }
            return parsed;
        }

        double decimal(String key, double fallback, double min, double max) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            double parsed;
            try {
                parsed = Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                throw new GatewayConfigException(key + " must be a number, not '" + value + "'");
            }
            if (parsed < min || parsed > max) {
                throw new GatewayConfigException(key + " must be between " + min + " and " + max + ", not " + parsed);
            }
            return parsed;
        }
    }
}
