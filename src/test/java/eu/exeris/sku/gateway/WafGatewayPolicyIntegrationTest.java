package eu.exeris.sku.gateway;

import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.waf.api.WafMode;
import eu.exeris.caps.waf.api.WafPolicies;
import eu.exeris.caps.waf.api.WafPolicy;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.sku.gateway.waf.WafGatewayPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WafGatewayPolicy Integration Tests")
class WafGatewayPolicyIntegrationTest {

    private final WafPolicy wafPolicy = WafPolicies.defaultCoreRules(WafMode.BLOCK);
    private final WafGatewayPolicy policy = new WafGatewayPolicy(wafPolicy);

    @Test
    @DisplayName("Benign request proceeds down the chain")
    void testBenignRequestProceeds() {
        HttpExchange exchange = createMockExchange("/api/items/small", List.of());
        PolicyResult result = policy.preRouting(exchange);
        assertThat(result.isShortCircuit()).isFalse();
    }

    @Test
    @DisplayName("SQL Injection in query parameter is short-circuited with 403 Forbidden")
    void testSqlInjectionBlocked() {
        HttpExchange exchange = createMockExchange("/api/v1/users?id=1%20union%20select%201,2", List.of());
        PolicyResult result = policy.preRouting(exchange);

        assertThat(result.isShortCircuit()).isTrue();
        HttpResponse resp = result.response();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.headers()).anyMatch(h -> "X-WAF-Blocked".equalsIgnoreCase(h.name()) && "true".equals(h.value()));
        assertThat(resp.headers()).anyMatch(h -> "X-WAF-Category".equalsIgnoreCase(h.name()) && "SQL_INJECTION".equals(h.value()));
    }

    @Test
    @DisplayName("Path Traversal attack in path is short-circuited with 403 Forbidden")
    void testPathTraversalBlocked() {
        HttpExchange exchange = createMockExchange("/static/../../etc/passwd", List.of());
        PolicyResult result = policy.preRouting(exchange);

        assertThat(result.isShortCircuit()).isTrue();
        HttpResponse resp = result.response();
        assertThat(resp.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.headers()).anyMatch(h -> "X-WAF-Category".equalsIgnoreCase(h.name()) && "PATH_TRAVERSAL".equals(h.value()));
    }

    @Test
    @DisplayName("Excluded path bypasses WAF inspection")
    void testExcludedPathBypassesWaf() {
        HttpExchange exchange = createMockExchange("/actuator/health", List.of());
        PolicyResult result = policy.preRouting(exchange);
        assertThat(result.isShortCircuit()).isFalse();
    }

    private HttpExchange createMockExchange(String path, List<HttpHeader> headers) {
        HttpRequest req = new HttpRequest(
                HttpMethod.GET,
                null,
                path,
                HttpVersion.HTTP_1_1,
                headers,
                null
        );

        return new HttpExchange() {
            @Override
            public HttpRequest request() {
                return req;
            }

            @Override
            public void respond(HttpResponse response) {}

            @Override
            public void respond(HttpTypedResponse typedResponse) {}

            @Override
            public void respond(HttpStatus status) {}
        };
    }
}
