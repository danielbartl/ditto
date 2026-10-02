package dev.jbaby.ditto.comparator.api;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Result of one comparison: the overall verdict, the rules that produced it and the metrics behind them.
 * All path-keyed data is held in lists (never maps), so the report can be stored in MongoDB despite dots in paths.
 * In SAMPLE mode counts are extrapolated to the whole collections and rates are {@link Rate.Estimate}s.
 *
 * @param id              unique id of this comparison run
 * @param verdict         the worst level of all rules
 * @param rules           every evaluated rule with its level and reasoning
 * @param keys            key-level metrics
 * @param content         content metrics of the matched documents
 * @param topChangedPaths leaf paths that differ most often in changed documents, most frequent first
 * @param structure       structural differences between the collections
 * @param examples        example keys per category, for manual inspection
 * @param run             timing, document counts and the effective settings
 * @param warnings        conditions that limit the meaning of the report, e.g. a path cap was hit
 */
public record ComparisonReport(
        String id,
        Level verdict,
        List<RuleResult> rules,
        KeyMetrics keys,
        ContentMetrics content,
        List<PathChange> topChangedPaths,
        StructureMetrics structure,
        Examples examples,
        RunMetadata run,
        List<String> warnings) {

    public ComparisonReport {
        rules = List.copyOf(rules);
        topChangedPaths = List.copyOf(topChangedPaths);
        warnings = List.copyOf(warnings);
    }

    /**
     * @param rule      rule name, e.g. {@code keySimilarity}
     * @param level     level this rule assigned
     * @param observed  the evaluated value, {@code null} if not applicable or not numeric
     * @param threshold human-readable thresholds of the rule
     * @param reason    why the rule assigned this level
     * @param details   offending items, e.g. vanished paths (capped)
     */
    public record RuleResult(String rule, Level level, @Nullable Double observed, String threshold, String reason,
                             List<String> details) {

        public RuleResult {
            details = List.copyOf(details);
        }
    }

    /**
     * @param matched       keys present in both collections
     * @param added         keys only in the candidate
     * @param removed       keys only in the baseline
     * @param keySimilarity {@code matched / (matched + added + removed)}
     * @param addedRate     {@code added / candidate documents}
     * @param removedRate   {@code removed / baseline documents}
     */
    public record KeyMetrics(long matched, long added, long removed, Rate keySimilarity, Rate addedRate,
                             Rate removedRate) {
    }

    /**
     * @param unchanged               matched documents with identical canonical content
     * @param changed                 matched documents with different canonical content
     * @param changedExpectedOnly     changed documents whose changes are all in expected-change paths
     * @param unchangedRate           {@code unchanged / matched}
     * @param changedRate             {@code changed / matched}
     * @param unchangedOrExpectedRate {@code (unchanged + changedExpectedOnly) / matched}; what the unchangedRate rule
     *                                evaluates. Equals {@code unchangedRate} without expected-change paths
     */
    public record ContentMetrics(long unchanged, long changed, long changedExpectedOnly, Rate unchangedRate,
                                 Rate changedRate, Rate unchangedOrExpectedRate) {

        public ContentMetrics {
            // reports stored before expected-change paths existed
            if (unchangedOrExpectedRate == null) {
                unchangedOrExpectedRate = unchangedRate;
            }
        }
    }

    /**
     * @param path          leaf path, e.g. {@code items[].price}
     * @param changedDocs   matched documents in which this path differs
     * @param changeRate    {@code changedDocs / matched}
     * @param expected      whether the path is an expected-change path; those do not count for maxPathChangeRate
     * @param examples      keys of documents in which this path differs
     * @param valueExamples before/after values of some of these documents
     */
    public record PathChange(String path, long changedDocs, Rate changeRate, boolean expected, List<KeyRef> examples,
                             List<ValueChange> valueExamples) {

        public PathChange {
            examples = List.copyOf(examples);
            // reports stored before value examples existed
            valueExamples = valueExamples == null ? List.of() : List.copyOf(valueExamples);
        }
    }

    /**
     * @param baselineDocs             baseline documents profiled
     * @param candidateDocs            candidate documents profiled
     * @param baselinePaths            distinct paths seen in the baseline
     * @param candidatePaths           distinct paths seen in the candidate
     * @param newPaths                 paths only present in the candidate
     * @param missingPaths             paths only present in the baseline (vanished)
     * @param typeShifts               paths whose BSON type distribution changed
     * @param presenceDeltas           paths present in both whose presence rate changed beyond the threshold
     * @param pathCapReached           whether more distinct paths occurred than are tracked
     * @param untrackedPathOccurrences path occurrences not tracked because of the cap
     */
    public record StructureMetrics(long baselineDocs, long candidateDocs, int baselinePaths, int candidatePaths,
                                   List<PathPresence> newPaths, List<PathPresence> missingPaths,
                                   List<TypeShift> typeShifts, List<PathPresence> presenceDeltas,
                                   boolean pathCapReached, long untrackedPathOccurrences) {

        public StructureMetrics {
            newPaths = List.copyOf(newPaths);
            missingPaths = List.copyOf(missingPaths);
            typeShifts = List.copyOf(typeShifts);
            presenceDeltas = List.copyOf(presenceDeltas);
        }
    }

    /**
     * How often a path occurs on each side, counted once per document.
     *
     * @param presenceDelta {@code candidatePresence - baselinePresence}
     */
    public record PathPresence(String path, long baselineDocs, long candidateDocs, double baselinePresence,
                               double candidatePresence, double presenceDelta) {

        public static PathPresence of(String path, long baselineDocs, long baselineTotal, long candidateDocs,
                                      long candidateTotal) {
            double baselinePresence = baselineTotal == 0 ? 0.0 : (double) baselineDocs / baselineTotal;
            double candidatePresence = candidateTotal == 0 ? 0.0 : (double) candidateDocs / candidateTotal;
            return new PathPresence(path, baselineDocs, candidateDocs, baselinePresence, candidatePresence,
                    candidatePresence - baselinePresence);
        }
    }

    /** BSON type distribution of a path on both sides. */
    public record TypeShift(String path, List<TypeShare> baseline, List<TypeShare> candidate) {

        public TypeShift {
            baseline = List.copyOf(baseline);
            candidate = List.copyOf(candidate);
        }
    }

    /**
     * @param type  BSON type name, e.g. {@code INT32}
     * @param docs  documents in which the path has this type
     * @param share {@code docs / documents having the path}
     */
    public record TypeShare(String type, long docs, double share) {
    }

    /** Example keys per category, capped at {@code maxExamples} each. */
    public record Examples(List<KeyRef> changed, List<KeyRef> added, List<KeyRef> removed) {

        public Examples {
            changed = List.copyOf(changed);
            added = List.copyOf(added);
            removed = List.copyOf(removed);
        }
    }

    /**
     * @param startedAt         start of the comparison
     * @param finishedAt        end of the comparison
     * @param durationMillis    wall-clock duration
     * @param baselineCount     documents in the baseline collection
     * @param candidateCount    documents in the candidate collection
     * @param baselineDocsRead  baseline documents actually read (equals the count in FULL mode)
     * @param candidateDocsRead candidate documents actually read
     * @param settings          the effective settings
     * @param thresholdSource   where {@code settings.thresholds()} came from
     * @param decisions         what ditto decided by convention, e.g. which mode AUTO chose and why
     */
    public record RunMetadata(Instant startedAt, Instant finishedAt, long durationMillis, long baselineCount,
                              long candidateCount, long baselineDocsRead, long candidateDocsRead,
                              ComparisonSettings settings, ThresholdSource thresholdSource, List<String> decisions) {

        public RunMetadata {
            // reports stored before threshold sources and decisions existed
            if (thresholdSource == null) {
                thresholdSource = ThresholdSource.configured();
            }
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }
}
