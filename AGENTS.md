---
title: "exeris-sku-api-gateway: agent operating contract"
type: reference
visibility: public
owning-repo: exeris-sku-api-gateway
status: active
last-verified: 2026-10-15
---

# exeris-sku-api-gateway

Operating contract for AI agents working in this repository.

## 1. Mission and scope

`exeris-sku-api-gateway` is the API Gateway Platform SKU (Tier 3, Gateway family): a composition of `gateway-core`, `cors-policy`, `rate-limiting`, `jwt-validation`, `request-routing`, `upstream-pool`, `circuit-breaker`, `gateway-observability` and `waf-rules`. Licence: `commercial` — Exeris Commercial License 1.1, source-available
(ADR-023). It is a proof of concept built against `exeris-kernel` 0.13.0-SNAPSHOT; `README.md` describes
what it does and does not do.

## 2. Operating contract and boundaries

- **Kernel-direct execution (ADR-006, ADR-024).** No `org.springframework.*`, `jakarta.servlet.*`, `io.netty.*` or
  `reactor.*` import, in main or test sources.
- **The composition manifest (ADR-053).** `composition.json` at the repository root is the authored manifest. A change to the composed capabilities updates it in the same commit.
- **Substrate driver-swap transparency.** Manifest and code are identical for the Community and Enterprise substrates; name kernel SPIs, never a concrete driver.
- **Entitlement.** The SKU's entitlement id is `exeris-sku-api-gateway`, its artifactId (ADR-089).
- **JDK 25, no preview.** `maven.compiler.release` stays 25; no `--enable-preview` in any scope.
- **Tests.** JUnit 5 and AssertJ only; no mocking framework.
- **Safety.** Do not push, tag, publish or change repository settings unless the user asks. The vendored
  bundle policy `bundle:agent-safety-and-autonomy` applies.

## 3. Architecture and documentation entry points

- [`README.md`](README.md) — what the SKU composes, build prerequisites, licence.
- [SKU conventions](https://github.com/exeris-systems/exeris-docs/blob/main/standards/sku-conventions.md).
- [High-Level Architecture](https://github.com/exeris-systems/exeris-docs/blob/main/high-level-architecture.md) §3.3.
- ADRs: ADR-006, ADR-023, ADR-024, ADR-053, ADR-089 —
  indexed in [`adr-index.md`](https://github.com/exeris-systems/exeris-docs/blob/main/adr-index.md).

## 4. `.agents/` discovery

- `.agents/manifest.yaml` — imports the organisation bundle `exeris-agents`, pinned by version, commit
  and digest.
- `.agents/vendor/exeris-agents-2.1.0/` — the vendored bundle: policies, base schemas and the hook
  dispatcher. Never edit it in place; bumping it means re-vendoring.

This repository authors no profiles, skills, workflows or hooks of its own.

## 5. Verification and reporting

```bash
mvn test
```

Report what ran and its result; do not claim a check that was not run.

## 6. Provider adapters

`CLAUDE.md` is a pointer to this file. No other provider adapter is rendered in this repository.
