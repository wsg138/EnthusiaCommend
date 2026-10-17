# EnthusiaCommend testing guide

This file is the worker/reviewer entry point for EnthusiaCommend automated testing.

The repository owns its handwritten command, reputation-policy, moderation, persistence, migration, GUI, placeholder, region, stalking, analytics, Discord, effects, API-contract and utility tests. Sentinel Sim is an additional built-artifact/runtime compatibility layer; it is not where normal Commend unit/regression tests live.

## What this hardening adds

The test-hardening branch adds focused regression coverage for previously untested deterministic contracts:

- `ReputationApiContractTest`
  - reputation-blacklist case-ID normalization, revision validation and exact expiration boundary;
  - entry category normalization, score polarity and timestamp invariants;
  - immutable state-snapshot ownership/checksum rules;
  - mutation-result success semantics.
- `RepIdentityStateTest`
  - immutable IP/vote history;
  - per-giver tarnish tracking and forgiveness;
  - legacy tarnish-source migration from negative commendations.
- `MiniMessageColorTagsTest`
  - canonical Bukkit/MiniMessage colors;
  - fail-safe white fallback for null/formatting codes;
  - escaping of user-controlled `<` and backslashes.
- `RepDateFormatsTest`
  - system-zone default and exact minute-level format contract.
- `CuboidRegionTest`
  - inclusive 3D/horizontal boundaries and world mismatch rejection.
- `GuiSnapshotTargetsTest`
  - rendered-list index bounds;
  - stale GUI revision rejection when reason, edit time, IP hash or score changes.
- `PluginSurfaceContractTest`
  - reviewed plugin identity, dependency surface, `/rep` alias and outer permission.
- `FullFeatureCoverageContractTest`
  - inventory guard requiring concrete test sources for the major deterministic feature families already expected to be protected in this repository.

The feature inventory is a guard, not proof that every behavior is correct. New/changed behavior must add real assertions in the owning suite; do not satisfy the inventory with empty or unrelated test classes.

## Existing strong coverage retained

The repository already has meaningful tests for:

- analytics records/service behavior;
- command permissions, player-name formatting and suggestion listening;
- category/legacy-region migration and configuration thresholds/customization;
- Discord webhook/head URL behavior;
- applied effects/effect manager behavior;
- GUI input safety, navigation, filters and leaderboard rendering;
- moderation service/store/persistence-failure behavior;
- PlaceholderAPI expansion metadata/output;
- logical-zone/default-region behavior;
- commendation migration/results, rules, leaderboard population/sorting, restoration and RepService policy/timestamps/hash behavior;
- stalking eligibility/movement/listener/zone transitions;
- ordered snapshot writing and YAML persistence compatibility.

Keep those tests close to the product code they protect.

## Focused commands

Run one suite:

```bash
mvn -B -Dtest=ReputationApiContractTest test
mvn -B -Dtest=RepIdentityStateTest test
mvn -B -Dtest=MiniMessageColorTagsTest test
mvn -B -Dtest=GuiSnapshotTargetsTest test
```

Run the coverage/surface guards:

```bash
mvn -B -Dtest=PluginSurfaceContractTest,FullFeatureCoverageContractTest test
```

Run all tests:

```bash
mvn -B test
```

Canonical repository validation:

```bash
mvn --batch-mode --no-transfer-progress clean verify
mvn --batch-mode --no-transfer-progress org.apache.maven.plugins:maven-pmd-plugin:3.26.0:pmd
```

The existing `.github/workflows/build.yml` runs those canonical validation steps for pull requests and uploads the built plugin JAR. The separate Sentinel artifact workflow proves an exact built artifact contract; do not treat artifact publication alone as a unit-test pass.

## Where results are written

Maven Surefire writes:

- XML/text results under `target/surefire-reports/`;
- build/package output under `target/`.

GitHub Actions is the durable exact-head evidence source. After any commit, older green runs are stale evidence for final review.

## Failure triage

### API/snapshot contract failure

Treat validation/immutability/checksum/expiration failures as moderation and persistence safety issues. Do not weaken an assertion merely to accept malformed state.

### Permission or plugin-surface failure

Confirm the command/dependency/permission change is intentional and covered by behavior tests. The plugin currently intentionally uses a mix of `true`, `op` and `false` permission defaults; this guide does not redefine that product policy.

### GUI snapshot failure

A stale rendered target must not silently match a changed commendation revision. Verify click-to-data binding rather than loosening the revision comparison.

### Persistence/moderation failure

Check replay/idempotency, snapshot compatibility, write ordering, failure behavior and restart safety before changing expectations.

### CI infrastructure failure

A job with no executed steps is not a pass or a test failure. Keep runner/infrastructure evidence distinct from Maven/JUnit evidence and do not manufacture commits merely to obtain reruns.

## Known boundaries / next coverage priorities

Important surfaces that still deserve deeper direct automated coverage include:

- full `CommendCommand` behavior beyond its permission-focused tests;
- Plan bootstrap/resolver/data-extension integration;
- EnthusiaTeleport and WarzoneDuels integration adapters;
- `PlaytimeService` provider-present/provider-missing and placeholder parsing paths;
- event payload/handler contracts;
- large `RepGuiManager` flows that require a realistic Paper inventory/session harness;
- ProtocolLib/glow behavior on a real supported Paper runtime;
- plugin startup/reload/shutdown orchestration and provider discovery.

Use repository-local tests for deterministic business logic. Use Sentinel or the `.enthusia-test.yml`/real-Paper path for built-artifact lifecycle/provider compatibility. Never claim a unit test proves live ProtocolLib, Plan, Vault, Paper scheduler, or network behavior.

## Review checklist

When reviewing a test change, verify:

1. the test would fail for the intended regression;
2. assertions cover externally meaningful behavior rather than implementation trivia;
3. malformed/null/stale/boundary inputs are included where relevant;
4. security/privacy-sensitive state never uses production data or credentials;
5. persistent collections are copied or otherwise protected from caller mutation;
6. provider-present and provider-missing behavior is tested at the correct layer;
7. no validation rule or analyzer is suppressed merely to get green CI;
8. final evidence belongs to the exact PR head;
9. `FullFeatureCoverageContractTest` is updated only when the reviewed feature map actually changes;
10. this document is updated when test commands/layout/evidence interpretation changes.
