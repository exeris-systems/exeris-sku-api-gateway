# exeris-sku-api-gateway

The API Gateway Platform SKU: a composition of Exeris capabilities that runs as a reverse proxy directly on the Exeris kernel's HTTP subsystem, with no Spring Framework, servlet container or Netty in the data plane.

Status: proof of concept, built against `exeris-kernel` 0.13.0-SNAPSHOT on JDK 25 LTS. Nothing here is stable and no artefact has been published.

## Where it sits

- **Layer:** Tier 3 — Platform SKU (Gateway family), in [`high-level-architecture.md`](https://github.com/exeris-systems/exeris-docs/blob/main/high-level-architecture.md) §3.3.
- **Composes** nine Tier 2 capabilities: `gateway-core`, `cors-policy`, `rate-limiting`, `jwt-validation`, `request-routing`, `upstream-pool`, `circuit-breaker`, `gateway-observability` and `waf-rules`. Eight are `commercial`; `cors-policy` is `community`.
- **Composition manifest:** `composition.json` at the repository root lists the nine modules and their `@Provides` services (ADR-053); it is generated, see below.
- **Entitlement:** the SKU's entitlement id is `exeris-sku-api-gateway`, its artifactId (ADR-089).
- **Wall (ADR-006):** the data plane is Exeris-native. No `org.springframework.*`, `jakarta.servlet.*`, `io.netty.*` or `reactor.*` type may appear.

## What the source contains

- `GatewaySkuApplication` is the runnable entry point. It reads the configuration, starts the kernel's HTTP server, and hands the kernel the ingress that `GatewayRuntime` assembles from `GatewayCoreModule`, the enabled policies and the route table. A configuration it cannot use ends the process with status 78 before the kernel starts.
- One adapter per composed capability implements `GatewayPolicy` and is registered on the chain, in this order: request size (-200), CORS (-100), rate limiting (-50), WAF (-45), JWT admission (-40) and request routing (-10). The circuit breaker and the observability measurement wrap the ingress instead of sitting in the chain, so a response a policy produces by refusing is counted and traced like any other. Each adapter sits in its own package under `eu.exeris.sku.gateway`.
- `RoutingReverseProxyHandler` resolves the destination with the `RequestRouter`, rewrites the path, applies the route's header mutations and forwards through the kernel's `HttpClientEngine`. The destination always comes from the route table, never from the request.
- `composition.json` is generated: `CompositionManifestTest` builds the capability graph from the composed capabilities' own metadata and fails if the committed file differs. After the composed set changes, regenerate it with `mvn test -Dtest=CompositionManifestTest -Dexeris.composition.regenerate=true`. The test checks the manifest's stamp and consistency; it does not start the modules, because `rate-limiting` refuses to initialise without an admission host and this SKU installs the limit through its own policy adapter.
- Allocation: on the request path the SKU reuses one `RouteLookup` per thread at each of its three route matches, reads the circuit-breaker permit as a primitive, reports outcomes as preallocated values, and selects cluster hosts with `selectHost()`, never `healthyHosts()`. An admitted request still allocates (its guarded exchange, the forwarded request and header lists, the response), and the WAF adapter copies the request for the WAF policy. The one allocation claim that is proved is for `RoutingGatewayPolicy` on a matched path, by `RoutingGatewayPolicyAllocationTest` with the kernel allocation TCK. The gateway as a whole makes no allocation claim.
- The tests drive each adapter against the chain, the assembled gateway with a stand-in upstream, and the real application in its own JVM (`GatewayProcessIT`).

## Configuration

Every setting is an environment variable. `EXERIS_GATEWAY_CONFIG` may name a properties file that uses the same names; an environment variable overrides the file, and an unknown name in the file is refused. A value that cannot be used stops the gateway at start and the message names the setting.

**Routes are required; nothing is built in.** The gateway has no default route and no application-specific content.

| Setting | Meaning | Default |
|---|---|---|
| `EXERIS_GATEWAY_ROUTES` | `PREFIX=TARGET` entries separated by `;`. `TARGET` is `http://host[:port][/base]` or `cluster://name`. Append `,strip` to remove the prefix before forwarding. A prefix matches at a path-segment boundary. Example: `/api/users=http://users:8080;/legacy=http://old:9000,strip` | none, required |
| `EXERIS_GATEWAY_CLUSTERS` | `NAME=URL,URL` entries separated by `;`, the endpoints a `cluster://name` route balances over | none |
| `EXERIS_LB_ALGORITHM` | `ROUND_ROBIN`, `LEAST_CONNECTIONS`, `WEIGHTED_ROUND_ROBIN`, `RANDOM` or `IP_HASH`. `IP_HASH` selects by a client-address key, and the kernel does not expose the peer address to a handler yet, so until it does the gateway selects without a key and `IP_HASH` rotates over the available hosts like `ROUND_ROBIN`. A header is never used as the key, because the client writes every header it sends | `ROUND_ROBIN` |
| `EXERIS_FAILOVER_ENABLED` | retry a `GET`, `HEAD` or `OPTIONS` without a body on another cluster host after a 5xx or a transport failure (connection refused, reset, idle timeout) | `true` |
| `EXERIS_PASSIVE_HEALTH_CHECK_ENABLED`, `EXERIS_PASSIVE_CONSECUTIVE_ERRORS`, `EXERIS_PASSIVE_EJECTION_SEC` | eject a host after consecutive errors, a 5xx answer or a transport failure alike, for a number of seconds | `true`, `3`, `5` |
| `EXERIS_PASSIVE_MAX_EJECTION_PERCENT` | the share of a cluster's hosts that may be ejected at once, 1 to 100; at least one host always stays in rotation, so a one-host cluster never ejects its host | `100` |
| `EXERIS_ACTIVE_HEALTH_CHECK_ENABLED`, `_PATH`, `_INTERVAL_MS`, `_TIMEOUT_MS`, and `EXERIS_ACTIVE_HEALTHY_THRESHOLD`, `EXERIS_ACTIVE_UNHEALTHY_THRESHOLD` | probe every cluster host in the background: a host that fails the unhealthy number of probes in a row is ejected, and returns after the healthy number of successes. A probe succeeds on a 2xx answer. The path must be absolute, without authority, query or fragment. The probes stop when the gateway closes | `false`, `/health`, `1000`, `500`, `2`, `3` |
| `EXERIS_HTTP_BIND_HOST`, `EXERIS_HTTP_PORT` (or `PORT`) | the ingress listener | `0.0.0.0`, `8080` |

**Limits.**

| Setting | Meaning | Default |
|---|---|---|
| `EXERIS_HTTP_MAX_REQUEST_BODY_BYTES` | largest request body; a larger one is answered `413` and not forwarded | 10 MiB |
| `EXERIS_HTTP_MAX_REQUEST_HEADER_COUNT`, `EXERIS_HTTP_MAX_REQUEST_HEADER_SIZE` | handed to the kernel's HTTP server | 100, 8192 |
| `EXERIS_UPSTREAM_TIMEOUT_MS` | an upstream connection that moves no bytes for this long is reclaimed and the request is answered `502`. It is an idle limit, not a deadline on the whole exchange | 30000 |
| `EXERIS_UPSTREAM_MAX_RESPONSE_BYTES` | largest upstream response accepted | 10 MiB |

The kernel's HTTP/1.1 server has already read a request body when the gateway sees it, so the body limit bounds what is forwarded, not what is buffered.

**Policies, all off unless enabled.**

| Setting | Meaning |
|---|---|
| `EXERIS_CORS_ALLOWED_ORIGINS` | comma-separated origins. CORS is off while this is unset; `*` is served only if written |
| `EXERIS_RATELIMIT_ENABLED`, `_RPS`, `_BURST`, `_CAPACITY`, `_HEADERS` | token-bucket rate limit. It is one shared limit unless `EXERIS_RATELIMIT_KEY_HEADER` names a header. Use that only behind a trusted proxy that sets the header and replaces any client value, because the kernel does not tell a handler which address a request came from and a client chooses every header it sends |
| `EXERIS_WAF_ENABLED`, `EXERIS_WAF_MODE` | `BLOCK` (default) or `DETECT`, which logs a match and lets the request through |
| `EXERIS_JWT_ENABLED`, `EXERIS_JWT_SECRET`, `_ISSUER`, `_AUDIENCE`, `_CACHE_CAPACITY` | HS256 admission. The gateway has no built-in secret: with JWT enabled and no secret, or one shorter than 32 bytes, it refuses to start. `/health` and `/actuator/health` are the only paths that skip admission |
| `EXERIS_CIRCUIT_BREAKER_ENABLED`, `EXERIS_CB_SLIDING_WINDOW`, `_MIN_CALLS`, `_FAILURE_RATE`, `_WAIT_DURATION_SEC`, `_PERMITTED_HALF_OPEN` | one breaker per route. `_PERMITTED_HALF_OPEN` is 1 to 1024, and `_MIN_CALLS` may not exceed `_SLIDING_WINDOW`; a value outside that stops the gateway at start |
| `EXERIS_OBSERVABILITY_ENABLED` | request metrics, W3C trace propagation and access recording |

**Forwarding headers.** `Forwarded`, `X-Forwarded-*` and `X-Real-IP` sent by a client are not passed upstream, and neither are hop-by-hop headers or the headers a `Connection` header names. The gateway sets no `X-Forwarded-For` of its own: the kernel does not expose the peer address to a handler.

**Diagnostics: expose only on a trusted network.** `/health` and `/actuator/health` are served on the ingress. Every other diagnostic endpoint (`/metrics`, `/actuator/metrics`, `/actuator/allocations`, `/dashboard`, `/observability`) is served by one handler on a separate management listener, and by nothing else.

| Setting | Meaning | Default |
|---|---|---|
| `EXERIS_MANAGEMENT_ENABLED` | start the management listener | `false` |
| `EXERIS_MANAGEMENT_BIND_HOST` | address to bind. Anything but loopback is an explicit decision to expose process internals to that network | `127.0.0.1` |
| `EXERIS_MANAGEMENT_PORT` | port; it must differ from the ingress port | `9090` |

The metrics and dashboard endpoints exist only when `EXERIS_OBSERVABILITY_ENABLED` is also true. The management listener has no admission of its own, and its paths are never in the JWT bypass list because they are never on the ingress.

**Licence.** The SKU and each composed commercial capability declare an entitlement requirement. The kernel reads the environment from `EXERIS_ENVIRONMENT`; in a production class it refuses to start unless `EXERIS_LICENSE_MANIFEST_PATH` (or a `license-manifest.json` in the working directory) names a signed manifest that lists `exeris-sku-api-gateway` and every composed commercial capability. `cors-policy` is community and needs no entry.

**TLS is not provided**, in either direction. The gateway listens in plaintext and its upstream connections are plaintext, so an `https://` upstream is refused at start. Terminate TLS in front of it and keep the upstreams on a trusted network. The gateway sets no TLS property on the kernel.

**JVM settings.** No workload-profiling pass has been run for this SKU, so the image bakes in no collector or heap size. It passes `--enable-native-access=ALL-UNNAMED` and appends `JAVA_OPTS`.

## Build & test

Requires JDK 25 and Maven 3.9+.

```bash
mvn verify
mvn package -DskipTests && docker build -t exeris-sku-api-gateway .
```

The upstreams are not on Maven Central. The ten `exeris-caps-*` modules the build needs (the nine composed ones and `service-boundary-core`, which `gateway-core`'s build uses) and `exeris-sdk` are built from source and installed first, `exeris-tooling` supplies the codegen chain, and `exeris-kernel` 0.13.0-SNAPSHOT is resolved from GitHub Packages, which needs a token with `read:packages`. `.github/workflows/build.yml` shows the order. The Dockerfile packages the built jar rather than building inside the image for the same reason.

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
