---
title: "exeris-sku-api-gateway — Roadmap"
type: roadmap
visibility: public
owning-repo: exeris-sku-api-gateway
status: active
last-verified: 2026-10-15
---

# exeris-sku-api-gateway — Roadmap

Status: **poc**. Version line: **0.1.x**. Licence: Exeris Commercial License (ADR-023), entitlement id
`exeris-sku-api-gateway`.

Milestones are versions, not dates. Dates appear only on what has shipped; nothing has shipped yet.

> **`1.0.0` is not on this roadmap.** It is a gate, described in the last section. Status moves
> `poc` → `snapshot` → `released`.

---

## Where this sits

Tier 3 Platform SKU of the Gateway family (HLA §3.3; ADR-024, ADR-053). A runnable application that boots
the kernel HTTP subsystem, instantiates `GatewayCoreModule`, registers per-cap policy adapters
(`CorsGatewayPolicy`, `RateLimitGatewayPolicy`, `WafGatewayPolicy`, `JwtGatewayPolicy`,
`CircuitBreakerGatewayPolicy`, `RoutingGatewayPolicy`, `ObservabilityGatewayPolicy`) and forwards through
a zero-copy reverse proxy on the kernel client engine. It composes nine caps: `gateway-core`, `cors-policy`,
`rate-limiting`, `jwt-validation`, `request-routing`, `upstream-pool`, `circuit-breaker`,
`gateway-observability` and `waf-rules`. 90 tests across 17 classes: one integration test per concern, the assembled gateway, configuration,
licence enforcement, a smoke test and the real application in its own JVM (`GatewayProcessIT`).

The import is on the local `feat/import-0.1.0` branch. It already: carries no application-specific routes
and no benchmark handlers, in code or in the image (routes come from `EXERIS_GATEWAY_ROUTES`, which is
required); serves observability and diagnostic endpoints only on a separate management listener, off by
default, bound to `127.0.0.1` by default, never on ingress and never in the JWT skip list; refuses to
start with JWT enabled and no signing key; keeps CORS off unless origins are configured; drops client
`Forwarded` / `X-Forwarded-*`; answers `413` over the request-size limit; reclaims an idle upstream
connection; refuses `https` upstreams (TLS is not provided); and declares its own entitlement
`exeris-sku-api-gateway`. This roadmap starts from that state.

Gaps between the inventory and the code, found in the code:

- HLA §3.3 and the whitepaper list `route-registry`, `policy-chain`, `backend-health`, `admin-control-plane`,
  `tls-termination` and `observability-bridge`. None is composed; `waf-rules` and `gateway-observability` are
  composed and not listed.
- Modules are constructed by hand in `main`, configured by environment variables; `composition.json`
  is generated but is not what drives the boot. The module `requires` lists in it are empty.
- `CircuitBreakerAdmin` is not wired in the SKU, and the management listener has no admission of its own.

**Upstream constraints this repo does not own:**

| Constraint | Effect here | Owner |
|---|---|---|
| Kernel licence gate (ADR-088, ADR-089) in kernel `0.13.0-SNAPSHOT` | Artefacts stay `0.1.0-SNAPSHOT` until kernel `0.13.0` is released | `exeris-kernel` |
| Each cap's own 0.1.0 / 0.2.0 milestones | The SKU consumes `[0.1.0,0.2.0)` ranges of all nine | the nine cap repos |
| `RequestFilterChain` contract (ADR-099) | 0.2.0 removes the SKU-side adapters | `exeris-caps-service-boundary-core` |
| ADR on the response-side seam (gateway-core 0.2.0) | Entry condition: no policy cap, and so no SKU migration, starts its 0.2.0 before it is accepted | `exeris-caps-gateway-core`, `exeris-docs` |
| Kernel `HttpExchange` does not expose the peer address yet | Needed for `X-Forwarded-For` and per-client rate-limit keys; until then the gateway sets no `X-Forwarded-For` | `exeris-kernel` |
| Kernel buffers HTTP/1.1 request bodies | The request-size limit bounds what is forwarded, not memory | `exeris-kernel` |
| No `composition.json` reader; manifest emitted to a source root, not the runtime classpath | The boot cannot be manifest-driven yet | SKU scaffold, `exeris-tooling` |
| HLA §3.3 composition differs from the code | Registry and whitepaper describe a different SKU | `exeris-docs` |
| Kernel TLS / transport configuration | TLS termination is a separate cap, not yet composed | `exeris-kernel` |

---

## 0.1.0 — the code PR

- [ ] Defects found in the pre-import review are fixed in the import, each with a test
- [ ] The `0.2.0-SNAPSHOT` working copy imported as the repository's first line; version becomes `0.1.0`
      (the earlier baseline is history, not a release)
- [ ] All cap dependencies on `[0.1.0,0.2.0)`; kernel `0.13.0-SNAPSHOT`; SDK `0.12.0` and tooling `0.10.0` pins aligned
- [ ] Licence enforcement: `ApiGatewaySkuEntitlement` (id `exeris-sku-api-gateway`) plus the caps' own
      requirements discovered through `META-INF/services`; a boot test against the kernel's Phase 0 gate
      with a manifest listing every commercial id, and one with a missing id that refuses to start
- [ ] `build.yml` green on JDK 25 and 26, resolving the cap snapshots; `Dockerfile` on the baseline JDK, no preview flag
- [ ] Artefacts stay `0.1.0-SNAPSHOT` until kernel `0.13.0` is released; a non-snapshot `0.1.0` is cut only after that
- [ ] Repository files (`README`, `CLAUDE.md`, `LICENSE`, `SECURITY`, `CONTRIBUTING`, `CODEOWNERS`, ADR link stubs)
- [ ] Product-specific routes and benchmark handlers absent from the code, the default boot and the container image;
      routes come from configuration (`EXERIS_GATEWAY_ROUTES`, required)
- [ ] Observability and diagnostic endpoints disabled by default, enabled only by explicit configuration on a
      separate management listener bound to loopback by default, never on ingress and never in the JWT skip
      list, with a test that a default boot answers on none of them

**Exit gate:** `mvn verify` green on both JDK rows; the container image starts with a valid manifest and refuses without one.

## 0.2.0 — filter chain, and a manifest-driven boot

- [ ] One SKU access policy for the management listener, which has no admission of its own today; the
      observability endpoints are enabled only through it
- [ ] `CircuitBreakerAdmin` wired, reachable only through the same SKU access policy, with its `AdminListener` audit
      listener wired (the SKU does not wire the admin in the import)
- [ ] Cap dependency ranges moved to `[0.2.0,0.3.0)` for every cap whose binding replaces an SKU adapter
- [ ] Per-client rate-limit keys and `X-Forwarded-For` use the peer address once the kernel exposes it (constraint above);
      the request-size limit stays documented as bounding what is forwarded, not memory
- [ ] Remove the seven `*GatewayPolicy` adapters; policies bind to `RequestFilterChain` through each cap's binding
- [ ] Response-side behaviour (CORS headers, outcome recording, access logging, header mutation) follows the
      response-side ADR (entry condition: accepted before this milestone starts; see gateway-core 0.2.0)
- [ ] Boot driven by the composition manifest rather than hand-constructed modules, once the SKU scaffold
      provides a reader; until then, document the wiring order and the module list as the single source
- [ ] `composition.json` `requires` edges populated and the build asserts it matches the composed set
- [ ] Align HLA §3.3 and the whitepaper SKU composition with the code through an `exeris-docs` PR

## 0.3.0 — Independent security review

One review for the SKU and all composed caps, scheduled after this repository's 0.2.0.

- [ ] Review scope agreed and published: the SKU and every composed cap, including each repository's public API and documented behaviour
- [ ] Threat model written and committed alongside the code
- [ ] Findings handled through `SECURITY.md` private reporting
- [ ] Fixes released, each with a regression test

## 0.4.0 — re-measurement and snapshot

- [ ] The gateway re-measured in the `exeris-benchmarks` harness on the reference profile, on a contract
      shared with the comparison targets named in the whitepaper §7; results published in `exeris-benchmarks`
- [ ] The existing local benchmark report is not cited; this repository links to the harness artefact and states no figure
- [ ] Snapshot artefact and container image published; status `poc` → `snapshot`

**Exit gate:** a resolvable snapshot exists and the benchmark artefact is linked.

---

## 1.0.0 — a gate, not a milestone

Entry condition: all nine caps have a snapshot, the independent security review is closed, the boot is manifest-driven,
TLS is decided, and a measurement on the reference profile is repeatable. Whitepaper §7 places the
API Gateway SKU on the H1 2027 horizon; that is a target for the gate, not a date on this roadmap.

## Deliberately out of scope

- `route-registry`, `backend-health`, `admin-control-plane`, `tls-termination`: separate caps, not in this repository; the SKU composes them when they exist.
- Edge Proxy and Bot Blocker SKUs: other members of the family.
- Any application-specific route set.
