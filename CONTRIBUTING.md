# Contributing to exeris-sku-api-gateway

This repository is offered under the Exeris Commercial License 1.1 (`commercial` under ADR-023, source-available); the terms are in [`LICENSE`](LICENSE). It is a
proof of concept, so open an issue before starting a non-trivial change.

## Build & test

```bash
mvn test
mvn package -DskipTests
```

Requires JDK 25 and Maven 3.9+. `README.md` explains how the upstreams are obtained.

## What a change has to preserve

- **Kernel-direct execution (ADR-006, ADR-024).** The data plane is Exeris-native: no Spring, servlet, Netty or reactive type anywhere.
- **The composition manifest.** `composition.json` at the repository root is the generated manifest (ADR-053), written by `CompositionManifestTest` and never hand-edited. A change to the set of composed capabilities regenerates it in the same commit (`-Dexeris.composition.regenerate=true`), and it keeps passing the composition stamp check.
- **Substrate driver-swap transparency.** The manifest and the code are identical for the Community and Enterprise substrates; name kernel SPIs, never a concrete driver.
- **The JDK baseline.** `maven.compiler.release` stays 25, and no preview flag appears anywhere.
- **Tests.** JUnit 5 and AssertJ only, no mocking framework: test doubles are written by hand.

## Conventions

- Code, identifiers, comments, commit messages, pull-request titles and bodies, and documentation are
  in English.
- Conventional-commit prefixes (`feat:`, `fix:`, `build:`, `ci:`, `docs:`, `chore:`).
- External contributions carry a `Signed-off-by:` trailer (`git commit -s`), a Developer Certificate
  of Origin sign-off. Contributor terms beyond the sign-off have not been published for this repository
  yet; ask in the issue before submitting a large change.
- AI-assisted work follows the organisation's
  [AI provenance standard](https://github.com/exeris-systems/exeris-docs/blob/main/standards/ai-provenance.md).

The documentation and commit standards live in
[`exeris-docs/standards/`](https://github.com/exeris-systems/exeris-docs/tree/main/standards).
Architecture decisions use the ecosystem-wide ADR registry in
[`exeris-docs/adr-index.md`](https://github.com/exeris-systems/exeris-docs/blob/main/adr-index.md);
reserve a number there before writing one.

## Conduct and security

This repository follows the
[Exeris Code of Conduct](https://github.com/exeris-systems/.github/blob/main/CODE_OF_CONDUCT.md).
Report vulnerabilities as described in [`SECURITY.md`](SECURITY.md), never in a public issue.
