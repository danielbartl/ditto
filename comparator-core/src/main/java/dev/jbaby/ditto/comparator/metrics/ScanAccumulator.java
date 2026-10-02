package dev.jbaby.ditto.comparator.metrics;

import java.util.SortedSet;

import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;

import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.canonical.CanonicalEncoder;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CDocument;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.canonical.Normalizer;
import dev.jbaby.ditto.comparator.flatten.DocumentDiff;
import dev.jbaby.ditto.comparator.flatten.Flattener;
import dev.jbaby.ditto.comparator.path.PathRules;
import dev.jbaby.ditto.comparator.structure.StructureProfiler;

/**
 * Classifies documents and accumulates every metric of a scan; used by both FULL and SAMPLE mode.
 * <ul>
 * <li>matched pairs: content hash comparison; for changed pairs the changed paths</li>
 * <li>every document read: structure profile of its side</li>
 * <li>example keys per category and per changed path</li>
 * </ul>
 * Not thread-safe; one instance per comparison run.
 */
public final class ScanAccumulator {

    private static final BsonDocumentCodec CODEC = new BsonDocumentCodec();

    private final Normalizer normalizer;
    private final Hasher hasher;
    private final DocumentDiff diff;
    private final StructureProfiler baselineProfiler;
    private final StructureProfiler candidateProfiler;
    private final PathChangeStats pathChanges;
    private final ExampleCollector changedExamples;
    private final ExampleCollector addedExamples;
    private final ExampleCollector removedExamples;

    private long matched;
    private long unchanged;
    private long changed;
    private long added;
    private long removed;

    public ScanAccumulator(ComparisonSettings settings, Hasher hasher) {
        PathRules rules = PathRules.compile(settings.ignoredPaths(), settings.orderSensitivePaths(),
                settings.wildcardPaths());
        Flattener flattener = new Flattener(rules, settings.nullEqualsMissing());
        ComparisonSettings.Tuning tuning = settings.tuning();
        this.normalizer = new Normalizer(rules, settings.keyField(), settings.nullEqualsMissing(),
                new CanonicalEncoder());
        this.hasher = hasher;
        this.diff = new DocumentDiff(rules, flattener);
        this.baselineProfiler = new StructureProfiler(flattener, tuning.maxTrackedPaths());
        this.candidateProfiler = new StructureProfiler(flattener, tuning.maxTrackedPaths());
        this.pathChanges = new PathChangeStats(tuning.maxTrackedPaths(), tuning.maxExamples());
        this.changedExamples = new ExampleCollector(tuning.maxExamples());
        this.addedExamples = new ExampleCollector(tuning.maxExamples());
        this.removedExamples = new ExampleCollector(tuning.maxExamples());
    }

    public void matched(BsonValue key, RawBsonDocument baselineRaw, RawBsonDocument candidateRaw) {
        matched++;
        BsonDocument baseline = baselineRaw.decode(CODEC);
        BsonDocument candidate = candidateRaw.decode(CODEC);
        baselineProfiler.profile(baseline);
        candidateProfiler.profile(candidate);
        if (sameBytes(baselineRaw, candidateRaw)) {
            unchanged++;
            return;
        }
        CDocument canonicalBaseline = normalizer.normalize(baseline);
        CDocument canonicalCandidate = normalizer.normalize(candidate);
        if (hasher.hash(canonicalBaseline).equals(hasher.hash(canonicalCandidate))) {
            unchanged++;
            return;
        }
        changed++;
        changedExamples.offer(key);
        SortedSet<String> changedPaths = diff.changedPaths(canonicalBaseline, canonicalCandidate);
        pathChanges.record(key, changedPaths);
    }

    public void removed(BsonValue key, RawBsonDocument baseline) {
        removed++;
        removedExamples.offer(key);
        baselineProfiler.profile(baseline.decode(CODEC));
    }

    public void added(BsonValue key, RawBsonDocument candidate) {
        added++;
        addedExamples.offer(key);
        candidateProfiler.profile(candidate.decode(CODEC));
    }

    /**
     * @param baselineDocsRead  baseline documents read by the scan
     * @param candidateDocsRead candidate documents read by the scan
     * @param candidateSampled  candidate keys sampled for added detection (SAMPLE mode), else 0
     */
    public ScanResult result(long baselineDocsRead, long candidateDocsRead, long candidateSampled) {
        return new ScanResult(baselineDocsRead, candidateDocsRead, matched, unchanged, changed, added, removed,
                candidateSampled, pathChanges.sorted(), pathChanges.untrackedOccurrences(), changedExamples.keys(),
                addedExamples.keys(), removedExamples.keys(), baselineProfiler.snapshot(),
                candidateProfiler.snapshot());
    }

    /** Byte-identical BSON is always unchanged content; skips normalization for the common case. */
    private static boolean sameBytes(RawBsonDocument a, RawBsonDocument b) {
        return a.getByteBuffer().asNIO().equals(b.getByteBuffer().asNIO());
    }
}
