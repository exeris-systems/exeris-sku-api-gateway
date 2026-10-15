# exeris-sku-api-gateway

The API Gateway Platform SKU: a composition of Exeris capabilities that runs as a reverse proxy directly on the Exeris kernel's HTTP subsystem, with no Spring Framework, servlet container or Netty in the data plane.

Status: proof of concept, built against `exeris-kernel` 0.13.0-SNAPSHOT on JDK 25 LTS. Nothing here is stable and no artefact has been published.

## Where it sits

- **Layer:** Tier 3 — Platform SKU (Gateway family), in [`high-level-architecture.md`](https://github.com/exeris-systems/exeris-docs/blob/main/high-level-architecture.md) §3.3.
- **Composes** nine Tier 2 capabilities: `gateway-core`, `cors-policy`, `rate-limiting`, `jwt-validation`, `request-routing`, `upstream-pool`, `circuit-breaker`, `gateway-observability` and `waf-rules`. Eight are `commercial`; `cors-policy` is `community`.
- **Composition manifest:** `composition.json` at the repository root lists the nine modules and their `@Provides` services (ADR-053).
- **Entitlement:** the SKU's entitlement id is `exeris-sku-api-gateway`, its artifactId (ADR-089).
- **Wall (ADR-006):** the data plane is Exeris-native. No `org.springframework.*`, `jakarta.servlet.*`, `io.netty.*` or `reactor.*` type may appear.

## What the source contains

- `GatewaySkuApplication` is the runnable entry point. It starts the kernel's HTTP server, creates `GatewayCoreModule`, registers the policies below on its `PolicyChain`, registers reverse-proxy handlers on its `IngressBootstrap`, and exposes `/actuator/health` and `/health` from the gateway lifecycle.
- One adapter per composed capability implements `GatewayPolicy` and is registered on the chain: observability (outermost), CORS preflight, rate limiting, WAF, JWT admission, circuit breaker and request routing. Each adapter sits in its own package under `eu.exeris.sku.gateway`.
- `HttpReverseProxyHandler` forwards a request to a configured upstream URI through the kernel's `HttpClientEngine`; `RoutingReverseProxyHandler` resolves the destination with the `RequestRouter`, rewrites the path and applies the route's header mutations.
- The upstream URIs and the routing switches are read from environment variables.
- Each adapter has an integration test that exercises it against the chain.

## Build & test

Requires JDK 25 and Maven 3.9+.

```bash
mvn test
mvn package -DskipTests
```

The upstreams are not on Maven Central. The nine `exeris-caps-*` modules above are built from source first, `exeris-sdk` is built from source, and `exeris-kernel` 0.13.0-SNAPSHOT is resolved from GitHub Packages, which needs a token with `read:packages`.

## Documentation

- [`AGENTS.md`](AGENTS.md) — the repository's operating contract and invariants.
- [SKU conventions](https://github.com/exeris-systems/exeris-docs/blob/main/standards/sku-conventions.md) — the rules a Platform SKU repository follows.
- [Capability licence registry](https://github.com/exeris-systems/exeris-docs/blob/main/cap-license-registry.md) — the licence value of every cap.

Where they disagree, the ADRs below and the HLA outrank this README.

## Governing ADRs

- [ADR-006](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-006-spring-free-kernel-boundary.md) — Spring-free kernel boundary.
- [ADR-023](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-023-capability-licensing-taxonomy.md) — capability licensing taxonomy.
- [ADR-024](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-024-capability-composition-model.md) — capability composition model.
- [ADR-053](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-053-sku-composition-manifest-format.md) — composition manifest format.

Registry: [`adr-index.md`](https://github.com/exeris-systems/exeris-docs/blob/main/adr-index.md).

## License

Exeris Commercial License 1.1 — see [`LICENSE`](LICENSE). `exeris-sku-api-gateway` is a `commercial` SKU under [ADR-023](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-023-capability-licensing-taxonomy.md): the source is available to read, build, test and evaluate, and production use requires a commercial licence.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Report a suspected vulnerability as described in [`SECURITY.md`](SECURITY.md), never in a public issue.
