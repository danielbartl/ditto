# ditto: MongoDB collection comparator

[![CI](https://github.com/danielbartl/ditto/actions/workflows/ci.yml/badge.svg)](https://github.com/danielbartl/ditto/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/danielbartl/ditto)](https://github.com/danielbartl/ditto/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

ditto compares two MongoDB collections that have the same or a very similar document structure, and tells you how
similar they are. Each comparison gives a **GREEN / YELLOW / RED** verdict plus the metrics behind it.

The typical case is a batch job that fully replicates data into MongoDB:

1. Before the run, the active collection is copied to a backup (the **baseline**).
2. The run upserts the active collection and deletes stale documents (the **candidate**).
3. ditto compares baseline and candidate and tells you whether the new data looks usable.

The tool is fully generic. It works on raw BSON (`RawBsonDocument` / `BsonDocument`) and knows nothing about what the
documents mean.

| Module            | What it is                                                                                     |
|-------------------|------------------------------------------------------------------------------------------------|
| `comparator-core` | The library. Spring Boot auto-configuration: add the dependency, inject `CollectionComparator`. |
| `comparator-cli`  | A runnable jar: compares two collections, prints the report as JSON, exits 0/1/2/3.            |

Requirements: Java 25, Spring Boot 4.1, MongoDB 4.4+ (tested with 8.0). Integration tests need Docker.

**Home page:** https://danielbartl.github.io/ditto/

---

## Contents

- [Quick start](#quick-start)
- [What ditto measures](#what-ditto-measures)
- [Running the CLI](#running-the-cli)
- [Embedding the library](#embedding-the-library)
- [Configuration reference](#configuration-reference)
- [Interpreting a report](#interpreting-a-report)
- [Running it as a JobRunr job after a batch run](#running-it-as-a-jobrunr-job-after-a-batch-run)
- [Performance and operational notes](#performance-and-operational-notes)
- [Limitations](#limitations)
- [Building and testing](#building-and-testing)
- [License](#license)

---

## Quick start

Download `comparator-cli.jar` from the [latest release](https://github.com/danielbartl/ditto/releases/latest), or
build it from source as shown below.

```bash
docker compose up -d mongo                         # MongoDB 8 on localhost:27017
./mvnw package -DskipTests                         # builds comparator-cli/target/comparator-cli.jar

# baseline "demo_backup" and a modified copy "demo" in database "ditto"
SEED_ARGS="--docs=50000 --changes=modify:price:0.03,delete-docs:0.005,add-docs:0.005" \
  docker compose run --rm seed

java -jar comparator-cli/target/comparator-cli.jar --db=ditto \
  --baseline=demo_backup --candidate=demo \
  --ignore=meta.syncedAt --wildcard='attributes.*' --ordered=history > report.json
echo $?    # 0 = GREEN, 1 = YELLOW, 2 = RED, 3 = error
```

You can also run the generator without Docker:
`java -jar comparator-cli/target/comparator-cli.jar generate --db=ditto --docs=50000 --changes=...`
See [generate](#generate).

---

## What ditto measures

### 1. Keys

Documents are matched by a **key field** (default `_id`). Each key falls into one of four categories:

| Category            | Meaning                                       |
|---------------------|-----------------------------------------------|
| `MATCHED_UNCHANGED` | Key on both sides, same canonical content     |
| `MATCHED_CHANGED`   | Key on both sides, different canonical content |
| `ADDED`             | Key only in the candidate                     |
| `REMOVED`           | Key only in the baseline                      |

**keySimilarity** = matched / (matched + added + removed).

### 2. Content

Matched documents are compared in a **canonical form**. Their content is equal when the SHA-256 of a deterministic
byte encoding of that canonical form is equal. The canonical form is built like this:

- The key field and the ignored paths are removed.
- Object fields are sorted by name, so field order never matters.
- Arrays are compared as multisets: each element is canonicalized, then elements are sorted by their encoding. The
  exception is arrays configured as order-sensitive.
- Numbers are compared by value: `int32 42`, `int64 42`, `double 42.0` and `Decimal128 42.000` are equal. Doubles
  use their shortest decimal form, so `0.1` (double) equals `0.1` (Decimal128).
- `-0` equals `0`. All NaNs are equal to each other.
- Dates are compared as epoch millis. A date never equals a number.
- `null` and a missing field are **different** by default. This is configurable with `null-equals-missing`.

**unchangedRate** = unchanged / matched. Documents whose changes are all in
[expected-change paths](#expected-changes-and-value-examples) count as unchanged for the verdict
(`unchangedOrExpectedRate`).

Type changes such as `int → double` do not change the content. They are reported by the structure check instead.

### 3. Changed paths

For each changed document, ditto finds the **leaf paths** that differ, and counts each path once per document.

Arrays are handled smartly. Elements that are equal on both sides cancel out first. So a price change in one element
of a reordered `items` array is reported as `items[].price` only.

The report lists the most frequently changed paths with their change rate (changed docs / matched docs), example
keys and a few **before/after values**. The verdict looks at the **highest single-path change rate**, ignoring
expected-change paths. A field that changed in every document is a strong signal of a broken mapping, even when
everything else looks fine.

### Expected changes and value examples

Some fields are supposed to change on every run: prices, stock levels, counters, computed scores. Without
configuration, they make the verdict RED, because the field changes in most documents. List them as
**expected-change paths**:

```java
ComparisonRequest.builder("products_backup", "products")
        .expectedChangePaths("price", "stock")       // stock covers stock.qty, stock.updatedAt, ...
        .build();
```

What an expected-change path does:

- It is still reported in `topChangedPaths`, flagged with `"expected": true`.
- It doesn't count for `maxPathChangeRate`.
- A document whose changes are all in expected paths counts as unchanged for the `unchangedRate` rule
  (`content.unchangedOrExpectedRate`).
- The raw `unchangedRate` stays in the report unchanged.

Each changed path carries up to `max-value-examples` (default 3) **value examples**. They show which values only the
baseline has at that path and which only the candidate has, e.g. `{"baseline": ["19.9"], "candidate": ["0"]}`.

For array paths, only the differing values are listed. An empty side means the field is missing on that side.

Values at **redacted paths** (`redacted-paths`, e.g. `customer` for all personal data below it) are shown as `***`.
Set `max-value-examples: 0` to leave values out of reports entirely.

Expected-change and redacted patterns also cover descendants: `customer` matches `customer.email`.

### 4. Structure

For every document on both sides, ditto records each node as `path → BSON type`, counted once per document. This
includes intermediate objects and arrays, for example `items` ARRAY, `items[]` DOCUMENT, `items[].sku` STRING.
Comparing the two histograms gives four findings:

| Finding           | Meaning                                                                                  | Default level |
|-------------------|------------------------------------------------------------------------------------------|---------------|
| `missingPaths`    | Path present in the baseline but in no candidate document (vanished)                     | RED           |
| `typeShifts`      | A BSON type appeared or disappeared on a path, or its share moved by more than 0.01      | RED           |
| `newPaths`        | Path only present in the candidate                                                       | YELLOW        |
| `presenceDeltas`  | Path on both sides, but the fraction of documents containing it changed by more than 0.05 | YELLOW        |

### Path syntax

Paths are used in the configuration and in the report:

| Pattern          | Meaning                                                                                  |
|------------------|------------------------------------------------------------------------------------------|
| `meta.syncedAt`  | Nested field                                                                             |
| `items[].price`  | Field of the elements of array `items` (`matrix[][]` for nested arrays)                  |
| `attributes.*`   | `*` matches exactly one field name                                                       |

How the three path options use this syntax:

- **Ignored paths** are removed before anything is compared or profiled.
- **Order-sensitive paths** name arrays, e.g. `history`, whose element order matters.
- **Wildcard paths** name maps with dynamic keys, and must end in `.*`. With `attributes.*`, the paths
  `attributes.color` and `attributes.size` are reported as `attributes.*`. This keeps path statistics small and
  meaningful. Content is still compared on the real keys.

---

## Running the CLI

```
java -jar comparator-cli.jar [compare] --baseline=<collection> --candidate=<collection> [options]
java -jar comparator-cli.jar generate [options]
java -jar comparator-cli.jar --help
```

The report goes to **stdout** as JSON, and nothing else does. Logs and a one-line summary go to **stderr**, so
`> report.json` and pipes into `jq` work.

| Exit code | Meaning                                                                                            |
|-----------|----------------------------------------------------------------------------------------------------|
| 0         | GREEN                                                                                              |
| 1         | YELLOW                                                                                             |
| 2         | RED                                                                                                |
| 3         | Error: bad options, missing collection, unusable keys (mixed types, duplicates), connection failure |

### compare options

| Option                          | Meaning                                                                    |
|---------------------------------|----------------------------------------------------------------------------|
| `--uri=<uri>`                   | MongoDB connection string. Default `mongodb://localhost:27017`             |
| `--db=<database>`               | Default database. Default `test`                                           |
| `--baseline=<collection>`       | Reference collection, e.g. the backup. **Required**                        |
| `--candidate=<collection>`      | Collection to judge. **Required**                                          |
| `--baseline-db` / `--candidate-db` | Database per side, if it differs from `--db`                           |
| `--key=<field>`                 | Top-level key field. Default `_id`                                         |
| `--ignore=<path,...>`           | Ignored paths, e.g. `meta.syncedAt,items[].etag`                           |
| `--ordered=<path,...>`          | Order-sensitive arrays                                                     |
| `--wildcard=<path,...>`         | Maps with dynamic keys, e.g. `attributes.*`                                |
| `--expected=<path,...>`         | Paths that are supposed to change, see [expected changes](#expected-changes-and-value-examples) |
| `--redact=<path,...>`           | Paths whose values are shown as `***` in value examples                    |
| `--mode=full\|sample`           | Default `full`                                                             |
| `--sample-size=<n>`             | Sample size. Implies `--mode=sample`                                       |
| `--null-equals-missing`         | Treat `null` fields like missing fields                                    |
| `--mixed-key-types=reject\|compare` | See [mixed key types](#keys-and-sort-order)                            |
| `--verdict-basis=conservative\|point` | SAMPLE mode only, see [SAMPLE mode](#sample-mode)                    |
| `--persist`                     | Also store the report in MongoDB                                           |
| `--out=<file>`                  | Also write the report to a file                                            |
| `--comparator.<property>=...`   | Any [library property](#configuration-reference), e.g. `--comparator.thresholds.key-similarity.green=0.995` |
| `--spring.mongodb.<property>=...` | Any Spring Boot MongoDB property, e.g. credentials                       |

Examples:

```bash
# collections in two databases, custom key, stricter key similarity
java -jar comparator-cli.jar --uri="$MONGO_URI" \
  --baseline-db=archive --baseline=products --candidate-db=shop --candidate=products \
  --key=sku --comparator.thresholds.key-similarity.green=0.995

# quick estimate on a huge collection
java -jar comparator-cli.jar --uri="$MONGO_URI" --db=shop \
  --baseline=products_backup --candidate=products --sample-size=20000 --ignore=meta.syncedAt

# the verdict and which rules fired
java -jar comparator-cli.jar ... | jq '{verdict, rules: [.rules[] | select(.level != "GREEN") | {rule, level, reason}]}'
```

### generate

`generate` writes a baseline collection and a modified candidate copy, for manual experiments.

The documents look like products. They contain an ObjectId `_id`, decimals, ints, longs, doubles, dates, booleans,
nested objects, a map with dynamic keys (`attributes`), arrays of scalars and of objects, an ordered event list
(`history`), a sometimes-null field (`note`) and a rare field (`discontinuedAt`).

| Option                 | Meaning                                                                                       |
|------------------------|-----------------------------------------------------------------------------------------------|
| `--baseline` / `--candidate` | Collection names. Default `demo_backup` / `demo`. Both are dropped and recreated        |
| `--docs=<n>`           | Baseline size. Default 10000                                                                  |
| `--seed=<n>`           | Random seed. The same settings always produce the same data. Default 42                       |
| `--touch-sync=true\|false` | Sets a new `meta.syncedAt` in every candidate document, like a real sync. Default `true`. Use `--ignore=meta.syncedAt` when comparing |
| `--changes=<spec,...>` | Changes applied to the candidate, see below                                                  |

Each change spec has the form `kind[:path]:fraction`. Paths are dotted field paths without arrays.

| Change                       | Effect on the chosen fraction of documents                                              |
|------------------------------|-----------------------------------------------------------------------------------------|
| `modify:<path>:<f>`          | Changes the value: numbers +1, strings suffixed, booleans flipped, dates +1 day, ...    |
| `drop-field:<path>:<f>`      | Removes the field                                                                       |
| `add-field:<path>:<f>`       | Adds a new string field                                                                 |
| `set-null:<path>:<f>`        | Sets the field to `null`                                                                |
| `int-to-double:<path>:<f>`   | Same value as a double: a type shift with unchanged content                             |
| `to-string:<path>:<f>`       | Same value as a string                                                                  |
| `shuffle-arrays:<f>`         | Shuffles all arrays. Content-neutral, unless the array is order-sensitive               |
| `delete-docs:<f>`            | Deletes documents                                                                       |
| `add-docs:<f>`               | Adds `f × docs` new documents                                                           |

Example:
`generate --docs=100000 --changes=modify:price:0.05,drop-field:legacyCode:1,int-to-double:qty:0.5,delete-docs:0.01`

In docker compose, the `seed` service runs `generate` against the compose MongoDB. Pass arguments with `SEED_ARGS`.

---

## Embedding the library

### Add the dependency

Releases are published to **GitHub Packages**. Maven needs a token to read from GitHub Packages, even for public
packages: create a GitHub personal access token (classic) with the `read:packages` scope and add it to
`~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>github-ditto</id>
            <username>YOUR_GITHUB_USERNAME</username>
            <password>YOUR_TOKEN</password>
        </server>
    </servers>
</settings>
```

Then add the repository and the dependency to your project:

```xml
<repositories>
    <repository>
        <id>github-ditto</id>
        <url>https://maven.pkg.github.com/danielbartl/ditto</url>
    </repository>
</repositories>

<dependency>
    <groupId>dev.jbaby.ditto</groupId>
    <artifactId>comparator-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

Alternatively, build it yourself with `./mvnw install -DskipTests`, which installs `0.1.0-SNAPSHOT` into your local
repository.

The host needs Spring Boot 4 with a configured MongoDB (`spring.mongodb.*`). The auto-configuration
`ComparatorAutoConfiguration` registers a `CollectionComparator` that uses the host's `MongoDatabaseFactory`. Every
bean is `@ConditionalOnMissingBean`, so you can replace any of them.

### Call it

```java
@Service
class ReplicationCheck {

    private final CollectionComparator comparator;

    ReplicationCheck(CollectionComparator comparator) {
        this.comparator = comparator;
    }

    Level check() {
        ComparisonReport report = comparator.compare(
                ComparisonRequest.builder("products_backup", "products")
                        .ignoredPaths("meta.syncedAt", "_class")
                        .wildcardPaths("attributes.*")
                        .orderSensitivePaths("history")
                        .build());
        return report.verdict();
    }
}
```

Notes on the API:

- **`ComparisonRequest`**: every option you don't set falls back to the `comparator.*` properties. `report.run().settings()`
  shows the effective configuration.
- **Other databases**: use `ComparisonRequest.builder(CollectionRef.of("archive", "products"), CollectionRef.of("products"))`
  to compare collections in other databases of the same cluster.
- **Thresholds per request**: `.thresholds(Thresholds.DEFAULTS.withKeySimilarity(new Thresholds.AtLeast(0.995, 0.98)))`.
- **SAMPLE mode**: `.sample(20_000)`.
- **Progress**: `comparator.compare(request, progress -> ...)` receives a `Progress` snapshot every
  `comparator.progress-interval`, with documents read, rate and `fractionDone()`.
- **Blocking and cancellable**: `compare` runs on the calling thread. It stops when that thread is interrupted and
  then throws `ComparisonException`. Concurrent comparisons are independent.
- **Errors**:
  - `ComparisonException`: the comparison could not be done meaningfully (missing collection, duplicate or
    mixed-type keys, interruption).
  - Spring `DataAccessException`: database errors.
  - A RED verdict is **not** an exception.
- **JSON**: `ReportJson` (a bean) serializes reports with its own Jackson 3 mapper, independent of the host's Jackson
  setup. `ComparisonReport` is a plain record tree, so `ReportJson.read(...)` reads it back.

### Persisting reports

Set `comparator.persistence.enabled=true` and every report is stored in `comparator.persistence.collection` (default
`comparison_reports`).

Each stored document is the report JSON with these top-level fields added: `_id` (the report id), `createdAt` (a
date), `baseline` and `candidate` (`db.collection`).

`ReportRepository.findRecent(candidate, limit)` reads reports back. You can also query directly, for example to
derive thresholds from historical runs:

```js
db.comparison_reports.find({candidate: "shop.products"}, {verdict: 1, "keys.keySimilarity.value": 1, createdAt: 1})
  .sort({createdAt: -1}).limit(30)
```

If storing a report fails, the failure is logged and the report is still returned.

---

## Configuration reference

All properties have the prefix `comparator`. Request values take precedence where the request has a matching option.

| Property                                   | Default              | Meaning                                                                         |
|--------------------------------------------|----------------------|---------------------------------------------------------------------------------|
| `key-field`                                | `_id`                | Top-level key field. Must be unique on both sides                               |
| `ignored-paths`                            | –                    | Paths removed before comparing                                                  |
| `order-sensitive-paths`                    | –                    | Arrays whose order matters                                                      |
| `wildcard-paths`                           | –                    | Maps with dynamic keys, `….*`                                                   |
| `expected-change-paths`                    | –                    | Paths that are supposed to change; not counted against the verdict              |
| `redacted-paths`                           | –                    | Paths whose values are shown as `***` in value examples                         |
| `null-equals-missing`                      | `false`              | Treat `null` fields as missing                                                  |
| `mixed-key-types`                          | `REJECT`             | `REJECT` or `COMPARE`, see below                                                |
| `mode`                                     | `FULL`               | `FULL` or `SAMPLE`                                                              |
| `sample.size`                              | `10000`              | Sample size per side when the mode is SAMPLE                                    |
| `sample.verdict-basis`                     | `CONSERVATIVE`       | `CONSERVATIVE` (worse confidence bound) or `POINT`                              |
| `sample.lookup-batch-size`                 | `500`                | Keys per `$in` lookup                                                           |
| `batch-size`                               | `1000`               | Cursor batch size                                                               |
| `no-cursor-timeout`                        | `false`              | Keep idle server cursors alive, see [operational notes](#performance-and-operational-notes) |
| `max-examples`                             | `20`                 | Example keys per category and per changed path                                  |
| `max-value-examples`                       | `3`                  | Before/after value examples per changed path; `0` disables them                 |
| `max-tracked-paths`                        | `10000`              | Distinct paths tracked per side. Further paths are counted, and a warning is added |
| `top-changed-paths`                        | `50`                 | Changed paths listed in the report                                              |
| `progress-interval`                        | `10s`                | Progress logging and listener interval                                          |
| `thresholds.key-similarity.green/yellow`   | `0.99` / `0.97`      | GREEN if ≥ green, YELLOW if ≥ yellow, else RED                                  |
| `thresholds.unchanged-rate.green/yellow`   | `0.95` / `0.85`      | GREEN if ≥ green, YELLOW if ≥ yellow, else RED                                  |
| `thresholds.max-path-change-rate.green/yellow` | `0.05` / `0.20`  | GREEN if < green, YELLOW if < yellow, else RED                                  |
| `thresholds.structure.type-share-delta`    | `0.01`               | Type-share change that counts as a type shift                                   |
| `thresholds.structure.presence-delta`      | `0.05`               | Presence change reported as YELLOW                                              |
| `thresholds.structure.vanished-min-presence` | `0.0`              | Vanished paths present in at most this fraction of baseline documents are YELLOW instead of RED |
| `persistence.enabled`                      | `false`              | Store reports                                                                   |
| `persistence.collection`                   | `comparison_reports` | Report collection                                                               |
| `persistence.database`                     | default database     | Report database                                                                 |

The MongoDB connection itself is configured with Spring Boot's own properties (`spring.mongodb.uri`,
`spring.mongodb.database`, …).

---

## Interpreting a report

Below is an abbreviated real report (FULL mode, 20,000 generated documents, 3% price changes, 0.5% deleted, 0.5% added):

```jsonc
{
  "id": "647fc74d-d99a-4c98-95b8-c4f88264229f",
  "verdict": "GREEN",                       // worst level of all rules
  "rules": [                                // every rule, with level, value, threshold and reason
    { "rule": "keySimilarity", "level": "GREEN", "observed": 0.99005,
      "threshold": "GREEN >= 0.99, YELLOW >= 0.97",
      "reason": "0.9900 meets GREEN (>= 0.9900) (matched 19900, added 100, removed 100)", "details": [] },
    { "rule": "maxPathChangeRate", "level": "GREEN", "observed": 0.0296,
      "reason": "Path 'price' changed in 0.0296 of matched documents, below the GREEN limit 0.0500", ... },
    { "rule": "structure.vanishedPaths", "level": "GREEN", "reason": "No path vanished", ... }
    // unchangedRate, structure.typeShifts, structure.newPaths, structure.presenceDeltas
  ],
  "keys": {
    "matched": 19900, "added": 100, "removed": 100,
    "keySimilarity": { "kind": "exact", "count": 19900, "total": 20100, "value": 0.99005 },
    "addedRate":     { "kind": "exact", "count": 100, "total": 20000, "value": 0.005 },
    "removedRate":   { "kind": "exact", "count": 100, "total": 20000, "value": 0.005 }
  },
  "content": { "unchanged": 19311, "changed": 589, "changedExpectedOnly": 0,
               "unchangedRate": { ... "value": 0.9704 }, "changedRate": { ... }, "unchangedOrExpectedRate": { ... } },
  "topChangedPaths": [
    { "path": "price", "changedDocs": 589, "changeRate": { "kind": "exact", "value": 0.0296, ... }, "expected": false,
      "examples": [ { "type": "OBJECT_ID", "value": "{\"$oid\": \"6553f1212161972337cc2db4\"}" } ],
      "valueExamples": [ { "key": { "type": "OBJECT_ID", "value": "{\"$oid\": \"6553f1212161972337cc2db4\"}" },
                           "baseline": [ "412.07" ], "candidate": [ "413.07" ] } ] }
  ],
  "structure": {
    "baselineDocs": 20000, "candidateDocs": 20000, "baselinePaths": 34, "candidatePaths": 34,
    "newPaths": [], "missingPaths": [], "typeShifts": [], "presenceDeltas": [],
    "pathCapReached": false, "untrackedPathOccurrences": 0
  },
  "examples": { "changed": [ ... ], "added": [ ... ], "removed": [ ... ] },
  "run": {
    "startedAt": "2026-10-02T13:39:02.586Z", "finishedAt": "2026-10-02T13:39:03.272Z", "durationMillis": 686,
    "baselineCount": 20000, "candidateCount": 20000, "baselineDocsRead": 20000, "candidateDocsRead": 20000,
    "settings": { /* effective configuration incl. thresholds */ }
  },
  "warnings": []
}
```

How to read it:

1. **`verdict` and `rules`**: start here. Rules that aren't GREEN explain themselves in `reason`, and list the
   offending paths (up to 20) in `details`.
2. **`keys`**: low key similarity means documents went missing or appeared. Compare `added` with `removed`:
   - Many removals and no additions usually means a partial or aborted load.
   - Both high usually means the key values changed format, e.g. `"123"` vs `123`, or a new ID scheme.
3. **`content` and `topChangedPaths`**: what changed inside matched documents.
   - A single path changed in close to 100% of documents usually means a mapping or format change in that field, or
     a technical field that should be ignored. Its `valueExamples` usually show which one at a glance.
   - Many paths at low rates are normal data churn.
   - Fields that legitimately change every run belong in `expected-change-paths`.
4. **`structure`**: schema drift independent of values.
   - `missingPaths` means a field disappeared everywhere.
   - `typeShifts` shows the type distribution per side, e.g. `qty: INT32 1.0 → DOUBLE 1.0`. That is the case where
     content is "unchanged" by value, but a consumer reading `getInteger("qty")` would break.
   - `newPaths` and `presenceDeltas` are softer signals.
5. **`examples`**: keys as relaxed Extended JSON, ready for mongosh:
   `db.products.find({_id: {"$oid": "6553f1212161972337cc2db4"}})`. Look at the same key in both collections.
6. **`warnings`**: conditions that limit the result. Examples: more distinct paths than `max-tracked-paths` (use
   wildcard paths for maps), no usable index on a custom key field, mixed key types compared under `COMPARE`.

### SAMPLE mode

In SAMPLE mode, ditto works as follows:

1. It draws `$sample` keys from the baseline and looks up the candidate documents with those keys in batches. This
   gives the removed rate, the content rates and the changed paths.
2. It draws a separate sample from the candidate, to measure the added rate.
3. All rates become `{"kind": "estimate", "value", "lower", "upper", "sampleSize"}`, where `lower` and `upper` are a
   **95% Wilson confidence interval**.
4. Counts are extrapolated to the collection sizes.
5. keySimilarity combines the removed and added estimates. Its interval uses the worse bound of both, which is
   conservative.

With the default `verdict-basis: CONSERVATIVE`, each rule evaluates the bound that is worse for it: the lower bound
for key similarity and unchanged rate, the upper bound for change rates. **GREEN therefore means "GREEN with 95%
confidence."**

A value close to a threshold can give YELLOW in SAMPLE mode and GREEN in FULL mode. Use a larger sample, or
`POINT`, if you want point estimates.

Structure is profiled on paired documents: the baseline sample and the candidate documents with the same keys. A
rare field therefore doesn't look "vanished" just because the two sides drew different random documents.

---

## Running it as a JobRunr job after a batch run

*This section describes how a host application could integrate ditto. It is not implemented here, and the JobRunr
calls are a sketch to adapt to your JobRunr version.*

A batch replication job with a comparison step usually looks like this:

1. **Backup**: copy `products` to `products_backup`, e.g. with an `$out` aggregation.
2. **Sync**: upsert everything into `products` and delete stale documents.
3. **Verify**: run the comparison, and react to the verdict.

Enqueue step 3 as a separate JobRunr job, triggered after step 2 finishes successfully, for example from the end of
the sync job or as a continuation. A separate job has its own state, retries and dashboard entry, and can be
re-run by hand.

```java
@Component
class ReplicationVerificationJob {

    private final CollectionComparator comparator;
    private final ReplicationRollback rollback;   // host code, see below
    private final Alerts alerts;                  // host code

    @Job(name = "Verify replication of %0", retries = 2)
    public void verify(String collection, JobContext jobContext) {
        var progressBar = jobContext.progressBar(100);
        ComparisonReport report = comparator.compare(
                ComparisonRequest.builder(collection + "_backup", collection)
                        .ignoredPaths("meta.syncedAt", "_class")
                        .build(),
                progress -> {
                    Double done = progress.fractionDone();
                    if (done != null) {
                        progressBar.setValue(Math.round(done * 100));
                    }
                });

        switch (report.verdict()) {
            case GREEN -> jobContext.logger().info("Replication of " + collection + " verified");
            case YELLOW -> alerts.warn(collection, report);          // usable, but someone should look
            case RED -> {
                rollback.restoreFromBackup(collection, report.id());  // see below
                alerts.critical(collection, report);
            }
        }
    }
}
```

Points to consider:

- **The verdict is a value, not an exception.** A RED report completes the job normally after the rollback. Use
  JobRunr retries only for exceptions:
  - `DataAccessException` and connection problems are transient and worth retrying.
  - `ComparisonException` (missing backup, duplicate keys, mixed key types) is a configuration problem. Retrying won't
    help, so consider catching it, alerting, and not rolling back automatically.
- **Rollback** belongs in the host: only the host knows what "usable" means for its consumers. Two options in
  MongoDB:
  - `renameCollection` of the backup over the active collection with `dropTarget: true`. This is atomic within one
    database, but the backup is consumed.
  - Copying the backup back with `$out` / `$merge`. This keeps the backup for analysis.
- **Make sure the next scheduled sync can't overwrite the backup before verification and rollback are done.** Use a
  JobRunr mutex/label, or chain the jobs.
- **Persist reports** (`comparator.persistence.enabled=true`). Put the report id in alerts, so people can open the
  report with its example keys. After a few weeks of history, tune the thresholds to your normal churn.
- **Progress and cancellation**: the `ProgressListener` maps onto the dashboard progress bar. If the job is deleted
  while it's running, JobRunr interrupts the worker thread. The comparison then stops within a few hundred documents and throws
  `ComparisonException`.
- **Large collections**: FULL mode reads both collections once (see below). If the job has a tight time budget, a
  SAMPLE comparison right after the sync can give a quick verdict, and a FULL comparison can follow later.

---

## Performance and operational notes

### FULL mode

FULL mode is one streaming merge-join pass. Both collections are read once, sorted by key, with batch size
`batch-size`. There is no `$lookup`, and collections are never loaded into memory.

Memory use is bounded by the path statistics (`max-tracked-paths`) and the example keys, not by the collection size.

Byte-identical documents skip canonicalization entirely. In a quick local test, 20,000 product documents per side
took about 0.7 s.

### Keys and sort order

The merge-join relies on the Java key order matching MongoDB's sort order exactly. ditto handles this as follows:

- **Simple collation**: both cursors sort with `collation: simple`. `BsonKeyOrder` implements the server's order:
  - Type brackets first. Then numbers by exact value across int/long/double/decimal.
  - Strings by UTF-8 bytes, documents element by element, binaries by length, then subtype, then bytes.
  - An integration test checks this against a real server with randomly generated keys of all types.
- **Mixed key types**: if keys of different type brackets exist, e.g. strings and ObjectIds, the comparison is rejected
  by default. This takes two indexed queries per side.
  - `mixed-key-types=COMPARE` compares them anyway, using the cross-type order, and adds a warning.
  - Array keys and documents without the key field are always rejected.
- **Guard**: every key read must be strictly greater than the previous one. A duplicate key (a non-unique custom key
  field) or any ordering surprise aborts the comparison, instead of producing wrong numbers.
- **Indexes**:
  - `_id` is always indexed.
  - A **custom key field needs an index** (without a non-simple collation). Otherwise the server sorts the whole
    collection, which works (`allowDiskUse`) but is slow. ditto warns about this.
  - A collection with a non-simple **default collation** has the same problem for `_id`.

### Cursors

On large collections with long runs of added or removed keys, one cursor can sit idle long enough to hit the server's
10-minute cursor timeout. Enable `no-cursor-timeout` in that case.

### Consistency

The collections are read with ordinary queries, not a snapshot. Compare after the batch job is done writing.

---

## Limitations

- **Field names**: paths can't address field names that contain `.`, `[` or `]`. Such fields are still compared,
  but they show up in the report under an ambiguous path.
- **Swapped values**: if values move between elements of an order-insensitive array, e.g. two items swap their
  prices, the change is reported as the array path (`items[]`), not as the individual fields.
- **Duplicate keys in SAMPLE mode**: duplicate keys are only detected on the looked-up side. FULL mode detects them
  on both sides.
- **Estimated counts**: SAMPLE mode extrapolates with `estimatedDocumentCount()` (collection metadata), which can be
  slightly off after an unclean shutdown.
- **Changing the encoding**: the canonical byte encoding is pinned by a unit test. Changing it changes all content
  hashes.

---

## Building and testing

CI runs `./mvnw verify` on every push and pull request.

```bash
./mvnw verify            # unit tests (surefire, *Test) + integration tests (failsafe, *IT, needs Docker)
./mvnw test              # unit tests only
./mvnw package -DskipTests
```

The integration tests start `mongo:8.0` through Testcontainers. Highlights:

- `ComparisonScenariosIT`: end-to-end behaviour. Covers identical collections, reordered arrays, int vs double, a
  vanished field, a field changed everywhere, added and removed documents, nested arrays, null vs missing, empty
  collections, mixed-type keys, ignored and wildcard paths.
- `KeyOrderIT`: Java key order vs. server sort order.
- `SampleModeIT`: SAMPLE estimates vs. the FULL result.
- `CliIT`: exit codes, stdout JSON, generate → compare.

Package layout of `comparator-core` (`dev.jbaby.ditto.comparator`):

| Package          | Responsibility                                                                               |
|------------------|----------------------------------------------------------------------------------------------|
| (root)           | `CollectionComparator`, the facade                                                           |
| `api`            | Request, settings, report records, `Rate`, `Thresholds`, `Level`, `ComparisonException`       |
| `path`           | Path patterns compiled into a trie (`PathRules`)                                              |
| `canonical`      | `Normalizer`, `CanonicalEncoder`, `Hasher`                                                   |
| `flatten`        | `Flattener` (path/type sets, leaves), `DocumentDiff` (changed paths)                         |
| `key`            | `BsonKeyOrder`, `KeyInspector`, `KeyOrderGuard`                                              |
| `scan`           | `MergeJoinComparator` (FULL), `SampleComparator` (SAMPLE), `Preflight`, `ProgressReporter`   |
| `metrics`        | `ScanAccumulator`, `PathChangeStats`, `ExampleCollector`, `Wilson`                           |
| `structure`      | `StructureProfiler`, `StructureDiff`                                                         |
| `verdict`        | `Rule` (sealed), `VerdictEvaluator`                                                          |
| `report`         | `ReportAssembler`, `ReportJson`, `ReportRepository`                                          |
| `autoconfigure`  | `ComparatorAutoConfiguration`, `ComparatorProperties`                                        |

### Releasing

Push a tag `vX.Y.Z`. The release workflow then:

1. Takes the version from the tag. `master` stays on `-SNAPSHOT`.
2. Runs the full build.
3. Deploys the parent POM and `comparator-core` (with sources and javadoc) to GitHub Packages.
4. Creates a GitHub Release with `comparator-cli.jar` attached.

Publishing to Maven Central is prepared, but switched off. It needs four things:

- a Sonatype Central Portal account with the verified namespace `dev.jbaby`;
- a GPG key;
- the repository secrets `CENTRAL_USERNAME`, `CENTRAL_TOKEN`, `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE`;
- the repository variable `MAVEN_CENTRAL_ENABLED=true`.

Locally the equivalent command is `./mvnw -Prelease,central deploy`.

---

## License

[MIT](LICENSE) © 2026 Daniel Bartl
