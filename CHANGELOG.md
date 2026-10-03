# Changelog

All notable changes to ditto. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project uses [semantic versioning](https://semver.org): before 1.0, a minor release may break the public API (see
[Compatibility and versioning](README.md#compatibility-and-versioning)).

## [Unreleased]

Planned as 0.3.0, the first release on Maven Central. It runs without any configuration: point it at a collection
and its backup, and the report tells you what to configure next.

### Breaking changes

- **New artifact names.** `comparator-core` is now `dev.jbaby.ditto:ditto-core`, and the CLI jar is `ditto-cli.jar`
  (was `comparator-cli.jar`). Packages and class names are unchanged.
- **Properties use the prefix `ditto`** (was `comparator`), e.g. `ditto.persistence.enabled` and, on the CLI,
  `--ditto.thresholds.key-similarity.green=0.995`. Rename the `comparator:` block in your `application.yml`. Old
  `comparator.*` properties are no longer read.
- **AUTO is the default mode** (was FULL). It scans fully up to `full-scan-limit` (5,000,000 documents per side) and
  samples above that. Set `mode: full` for the old behaviour.
- **The default sample size is 20,000 keys** per side (was 10,000).
- **`_class` is always ignored**, through the new `always-ignored-paths` (default `_class`, Spring Data's type hint).
  Set it to an empty list to compare `_class` again.
- **Map detection is on by default.** Objects with dynamic keys are treated as wildcard paths. Switch it off with
  `map-detection.enabled: false`.
- **Persisting reports switches on learned thresholds.** `adaptive-thresholds.enabled` now follows
  `persistence.enabled` unless it is set explicitly.
- **`ComparisonReport` has a new first component, `schemaVersion`.** Code that constructs reports itself must pass
  it; reading reports is unaffected.
- **Internal packages are marked as such.** Only the API listed in the README is covered by compatibility promises.

### Added

- `compareWithBackup("products")` compares `products_backup` (baseline) with `products` (candidate);
  `backup-suffix` changes the suffix. Also `compare(String, String)`, `backupRequest(...)` and the CLI option
  `--collection`.
- `ComparisonMode.Auto` and the CLI option `--mode=auto`. `run.decisions` in the report explains which mode ran
  and why.
- Automatic detection of maps with dynamic keys, from a small `$sample` of both collections
  (`map-detection.*`). Each detection is listed in `run.decisions`.
- Configuration hints in every report (`hints`): fields that look like technical timestamps, fields that should be
  declared as expected changes, maps, and a larger sample size when a SAMPLE verdict can't be confirmed. Each hint
  comes with the property and the CLI option to apply it. The comparator logs them and the CLI prints them. ditto
  never applies them on its own.
- Indexes on the report collection for the history and recent-reports queries, created on first use, and an optional
  `persistence.retention` (e.g. `365d`) after which MongoDB deletes stored reports through a TTL index. Without it,
  reports are kept forever.
- `schemaVersion` in every report (currently 1), so the report format can evolve without breaking stored reports
  and JSON consumers. Reports stored by earlier versions read as version 1.

### Changed

- Java 21 is now the minimum (was 25). CI builds and tests on Java 21 and 25.
- The README and the home page start with the path that works without configuration. The configuration reference is
  split into essentials and advanced options.
- The javadoc separates the public API from internal packages.

## [0.2.0] - 2026-10-02

### Added

- **Value examples.** Every changed path carries up to `max-value-examples` (default 3) examples of the values that
  only the baseline or only the candidate holds there. Values at `redacted-paths` show as `***`.
- **Expected-change paths** (`expected-change-paths`, CLI `--expected`) for fields that are supposed to change, such
  as prices and counters. They stay in the report, flagged as expected, but don't count against `maxPathChangeRate`,
  and documents changed only there count as unchanged for the `unchangedRate` rule.
- **Thresholds learned from history** (`adaptive-thresholds.*`): bands for `keySimilarity`, `unchangedRate` and
  `maxPathChangeRate` derived from the stored non-RED reports of the same collection pair (mean ± k standard
  deviations). The report records where its thresholds came from.
- **Spring events**: `ComparisonCompletedEvent` and `ComparisonFailedEvent` for every comparison.
- **Micrometer meters** (`ditto.comparison.*`) and a read-only **Actuator endpoint** `comparisons`, active only if the
  host application has Micrometer or Actuator.
- Releases are published to GitHub Packages, with the CLI jar attached to the GitHub Release. Maven Central
  publishing is prepared but switched off.

### Changed

- Reading reports is lenient: missing fields get defaults and unknown fields are ignored, so reports stored by 0.1.0
  stay readable.

## [0.1.0] - 2026-10-02

First release.

- Compares two MongoDB collections on BSON level and gives a GREEN / YELLOW / RED verdict with the metrics behind
  it: key similarity, unchanged rate, most changed paths, and structure differences (vanished and new paths, type
  shifts, presence changes).
- FULL mode (streaming merge-join over both collections, sorted by key) and SAMPLE mode (random keys looked up on both
  sides, with confidence intervals and a conservative verdict basis).
- Canonical comparison: field order and numeric types (`int 42` vs `double 42.0`) don't count as changes, and
  arrays are compared regardless of order unless configured as order-sensitive. Ignored, order-sensitive and
  wildcard paths.
- Spring Boot auto-configuration with `CollectionComparator` and `comparator.*` properties, progress listeners,
  optional persistence of reports in MongoDB.
- CLI with exit codes 0/1/2/3 (GREEN/YELLOW/RED/error), and a test-data generator.

[Unreleased]: https://github.com/danielbartl/ditto/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/danielbartl/ditto/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/danielbartl/ditto/releases/tag/v0.1.0
