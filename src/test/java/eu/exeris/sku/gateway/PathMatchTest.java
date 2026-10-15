package eu.exeris.sku.gateway;

import eu.exeris.caps.jwt.api.JwtAdmissionPolicies;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.caps.waf.api.WafMode;
import eu.exeris.caps.waf.api.WafPolicies;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Config;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Contract;
import eu.exeris.sku.gateway.jwt.JwtGatewayPolicy;
import eu.exeris.sku.gateway.paths.PathMatch;
import eu.exeris.sku.gateway.routing.RoutingGatewayPolicy;
import eu.exeris.sku.gateway.waf.WafGatewayPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Health path exclusion, one comparison for every gate")
class PathMatchTest {

    private static final Set<String> HEALTH = Set.of("/health", "/actuator/health");
    private static final String[] HEALTH_ARRAY = PathMatch.toArray(HEALTH);

    @Test
    @DisplayName("The path part decides: the query and the fragment are ignored")
    void pathOnly() {
        assertThat(PathMatch.matchesAny("/health", HEALTH_ARRAY)).isTrue();
        assertThat(PathMatch.matchesAny("/health?x=1", HEALTH_ARRAY)).isTrue();
        assertThat(PathMatch.matchesAny("/health#frag", HEALTH_ARRAY)).isTrue();
        assertThat(PathMatch.matchesAny("/actuator/health", HEALTH_ARRAY)).isTrue();
        assertThat(PathMatch.matchesAny("/actuator/health?x=1", HEALTH_ARRAY)).isTrue();
        assertThat(PathMatch.matches("/health?x=1", "/health")).isTrue();
    }

    @Test
    @DisplayName("Anything but the exact path is not excluded")
    void notExcluded() {
        assertThat(PathMatch.matchesAny("/healthz", HEALTH_ARRAY)).isFalse();
        assertThat(PathMatch.matchesAny("/healthz?x=1", HEALTH_ARRAY)).isFalse();
        assertThat(PathMatch.matchesAny("/health/", HEALTH_ARRAY)).isFalse();
        assertThat(PathMatch.matchesAny("/health/x?y", HEALTH_ARRAY)).isFalse();
        assertThat(PathMatch.matchesAny("/x?/health", HEALTH_ARRAY)).isFalse();
        assertThat(PathMatch.matchesAny("/actuator", HEALTH_ARRAY)).isFalse();
        assertThat(PathMatch.matchesAny("/actuator/healthz", HEALTH_ARRAY)).isFalse();
        assertThat(PathMatch.matchesAny(null, HEALTH_ARRAY)).isFalse();
        assertThat(PathMatch.matches(null, "/health")).isFalse();
        assertThat(PathMatch.matchesAny("/health", PathMatch.toArray(null))).isFalse();
    }

    @Test
    @DisplayName("JWT admission leaves the health paths open, with or without a query, and nothing else")
    void jwtGate() {
        JwtGatewayPolicy policy = new JwtGatewayPolicy(JwtAdmissionPolicies.builder()
                .publicKey(EdDsaTokens.KID, new EdDsaTokens().publicKey())
                .expectedIssuer(EdDsaTokens.ISSUER).expectedAudience(EdDsaTokens.AUDIENCE).build(),
                HEALTH, List.of());
        for (String path : List.of("/health", "/health?x=1", "/actuator/health", "/actuator/health?x=1")) {
            assertThat(policy.preRouting(TestExchange.get(path)).isShortCircuit()).as(path).isFalse();
        }
        assertThat(policy.preRouting(TestExchange.get("/healthz")).isShortCircuit()).as("/healthz").isTrue();
        assertThat(policy.preRouting(TestExchange.get("/healthz?x=1")).isShortCircuit()).as("/healthz?x=1").isTrue();
    }

    @Test
    @DisplayName("The WAF skips the health paths, with or without a query, and inspects nothing else")
    void wafGate() {
        WafGatewayPolicy policy = new WafGatewayPolicy(WafPolicies.defaultCoreRules(WafMode.BLOCK), HEALTH,
                WafGatewayPolicy.DEFAULT_ORDER);
        String attack = "?id=1%20union%20select%201,2";
        for (String path : List.of("/health", "/health" + attack, "/actuator/health" + attack)) {
            assertThat(policy.preRouting(TestExchange.get(path)).isShortCircuit()).as(path).isFalse();
        }
        assertThat(policy.preRouting(TestExchange.get("/healthz" + attack)).isShortCircuit()).as("/healthz").isTrue();
    }

    @Test
    @DisplayName("Routing skips the health paths, with or without a query, and refuses an unrouted one")
    void routingGate() {
        RoutingGatewayPolicy policy = new RoutingGatewayPolicy(RequestRouters.builder()
                .route("api").pathPrefix("/api").targetUri("http://api:1").and().build(), HEALTH);
        for (String path : List.of("/health", "/health?x=1", "/actuator/health", "/actuator/health?x=1")) {
            assertThat(policy.preRouting(TestExchange.get(path)).isShortCircuit()).as(path).isFalse();
        }
        assertThat(policy.preRouting(TestExchange.get("/healthz")).isShortCircuit()).as("/healthz").isTrue();
        assertThat(policy.preRouting(TestExchange.get("/healthz?x=1")).isShortCircuit()).as("/healthz?x=1").isTrue();
    }

    @Test
    @DisplayName("The comparison allocates nothing, on a target with a query and on one without")
    void allocatesNothing() throws Exception {
        long[] excluded = new long[1];
        Config config = new Config("PathMatch", "PathMatchTest", 5_000, 10_000, Contract.zero());
        JfrAllocationMonitor.Result result = VirtualThreadAllocation.measure(config, i -> {
            if (PathMatch.matchesAny("/actuator/health?x=1", HEALTH_ARRAY)
                    && !PathMatch.matchesAny("/healthz?x=1", HEALTH_ARRAY)
                    && PathMatch.matchesAny("/health", HEALTH_ARRAY)) {
                excluded[0]++;
            }
        });
        assertThat(excluded[0]).isEqualTo(5_000 + 10_000);
        JfrAllocationMonitor.assertContract(config, result, "PathMatch.matchesAny");
    }

    @Test
    @DisplayName("pathOf keeps the path part and returns a target without a query as it is")
    void pathOfCutsQueryAndFragment() {
        String plain = "/api/items";
        assertThat(PathMatch.pathOf(plain)).isSameAs(plain);
        assertThat(PathMatch.pathOf("/api/items?a=1")).isEqualTo("/api/items");
        assertThat(PathMatch.pathOf("/api/items#f")).isEqualTo("/api/items");
        assertThat(PathMatch.pathOf("/api/items?a=1#f")).isEqualTo("/api/items");
        assertThat(PathMatch.pathOf("?a=1")).isEmpty();
        assertThat(PathMatch.pathOf(null)).isNull();
    }
}
