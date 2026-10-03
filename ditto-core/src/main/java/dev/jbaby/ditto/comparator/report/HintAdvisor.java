package dev.jbaby.ditto.comparator.report;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bson.BsonType;

import dev.jbaby.ditto.comparator.api.ComparisonMode;
import dev.jbaby.ditto.comparator.api.ComparisonReport.PathChange;
import dev.jbaby.ditto.comparator.api.ComparisonReport.RuleResult;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.Hint;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.api.VerdictBasis;
import dev.jbaby.ditto.comparator.path.PathPattern;
import dev.jbaby.ditto.comparator.path.Paths;
import dev.jbaby.ditto.comparator.structure.StructureProfile;
import dev.jbaby.ditto.comparator.verdict.RuleInput;
import dev.jbaby.ditto.comparator.verdict.VerdictEvaluator;

/**
 * Derives {@link Hint}s from a finished comparison, so a first run without configuration tells the user what to
 * configure. Conservative on purpose: it only suggests, and only where the evidence is clear.
 */
public final class HintAdvisor {

    /** Change rate from which a path counts as "changed everywhere". */
    static final double EVERYWHERE = 0.9;
    /** Child paths below one object from which it looks like a map. */
    static final int MANY_CHILDREN = 100;
    private static final int MAX_PER_KIND = 5;
    private static final Set<BsonType> TIME_TYPES = EnumSet.of(BsonType.DATE_TIME, BsonType.TIMESTAMP);

    private final VerdictEvaluator verdictEvaluator;

    public HintAdvisor(VerdictEvaluator verdictEvaluator) {
        this.verdictEvaluator = verdictEvaluator;
    }

    public List<Hint> advise(ComparisonSettings settings, RuleInput input, List<RuleResult> rules,
                             StructureProfile baseline, StructureProfile candidate) {
        List<Hint> hints = new ArrayList<>();
        changedPaths(settings, input, candidate, hints);
        replacedKeys(input, hints);
        maps(settings, baseline, candidate, hints);
        sample(settings, input, rules, hints);
        return hints;
    }

    private static void changedPaths(ComparisonSettings settings, RuleInput input, StructureProfile candidate,
                                     List<Hint> hints) {
        double greenLimit = settings.thresholds().maxPathChangeRate().green();
        int ignore = 0;
        int expected = 0;
        int investigate = 0;
        for (PathChange change : input.changedPaths()) {
            Double rate = change.changeRate().value();
            if (change.expected() || rate == null) {
                continue;
            }
            String percent = percent(rate);
            if (rate >= EVERYWHERE && isTime(candidate, change.path()) && ignore++ < MAX_PER_KIND) {
                hints.add(new Hint(Hint.Kind.IGNORE_TECHNICAL_FIELD, change.path(), change.path() + " changed in "
                        + percent + " of matched documents and holds dates: it looks like a technical timestamp"
                        + " written by every run. If so, ignore it.",
                        "comparator.ignored-paths=" + change.path(), "--ignore=" + change.path()));
            } else if (rate >= EVERYWHERE && investigate++ < MAX_PER_KIND) {
                hints.add(new Hint(Hint.Kind.INVESTIGATE, change.path(), change.path() + " changed in " + percent
                        + " of matched documents. A change in nearly every document usually means a mapping or format"
                        + " change; its valueExamples show before and after.", null, null));
            } else if (rate >= greenLimit && rate < EVERYWHERE && expected++ < MAX_PER_KIND) {
                hints.add(new Hint(Hint.Kind.EXPECTED_CHANGE, change.path(), change.path() + " changed in " + percent
                        + " of matched documents. If this field is supposed to change between runs (prices, stock,"
                        + " counters), declare it as an expected change so it does not count against the verdict.",
                        "comparator.expected-change-paths=" + change.path(), "--expected=" + change.path()));
            }
        }
    }

    private static void replacedKeys(RuleInput input, List<Hint> hints) {
        Double added = input.keys().addedRate().value();
        Double removed = input.keys().removedRate().value();
        if (added != null && removed != null && added > 0.5 && removed > 0.5) {
            hints.add(new Hint(Hint.Kind.INVESTIGATE, null, "Most keys were replaced: " + percent(removed)
                    + " of the baseline keys are gone and " + percent(added) + " of the candidate keys are new. This"
                    + " usually means the key values changed format (e.g. \"123\" vs 123) or a new ID scheme; compare"
                    + " examples.added with examples.removed.", null, null));
        }
    }

    private static void maps(ComparisonSettings settings, StructureProfile baseline, StructureProfile candidate,
                             List<Hint> hints) {
        Map<String, Set<String>> children = new HashMap<>();
        Set<String> paths = new HashSet<>(baseline.paths().keySet());
        paths.addAll(candidate.paths().keySet());
        for (String path : paths) {
            int dot = path.lastIndexOf('.');
            if (dot > 0) {
                children.computeIfAbsent(path.substring(0, dot), key -> new HashSet<>()).add(path);
            }
        }
        children.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= MANY_CHILDREN)
                .map(Map.Entry::getKey)
                .filter(parent -> !parent.endsWith(Paths.WILDCARD) && isAddressable(parent + ".*"))
                .filter(parent -> !settings.wildcardPaths().contains(parent + ".*"))
                .sorted()
                .limit(MAX_PER_KIND)
                .forEach(parent -> hints.add(new Hint(Hint.Kind.WILDCARD, parent, parent + " has "
                        + children.get(parent).size() + " different child paths: it looks like a map with dynamic"
                        + " keys. Treat it as one path.", "comparator.wildcard-paths=" + parent + ".*",
                        "--wildcard=" + parent + ".*")));
    }

    private void sample(ComparisonSettings settings, RuleInput input, List<RuleResult> rules, List<Hint> hints) {
        if (!(settings.mode() instanceof ComparisonMode.Sample(int size))
                || input.basis() != VerdictBasis.CONSERVATIVE) {
            return;
        }
        var point = verdictEvaluator.evaluate(new RuleInput(input.keys(), input.content(), input.changedPaths(),
                input.structure(), input.thresholds(), VerdictBasis.POINT));
        List<String> unconfirmed = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            Level conservative = rules.get(i).level();
            Level estimated = point.rules().get(i).level();
            if (conservative.compareTo(estimated) > 0) {
                unconfirmed.add(rules.get(i).rule() + " (estimate " + estimated + ", confirmed " + conservative + ")");
            }
        }
        if (!unconfirmed.isEmpty()) {
            int larger = size * 4;
            hints.add(new Hint(Hint.Kind.LARGER_SAMPLE, null, "The sample of " + size + " keys per side cannot confirm"
                    + " the estimated level of " + String.join(", ", unconfirmed) + ". A larger sample narrows the"
                    + " confidence intervals; a full scan removes them.",
                    "comparator.sample.size=" + larger, "--sample-size=" + larger));
        }
    }

    private static boolean isTime(StructureProfile profile, String path) {
        StructureProfile.PathStat stat = profile.paths().get(path);
        if (stat == null || stat.documents() == 0) {
            return false;
        }
        long time = TIME_TYPES.stream().mapToLong(type -> stat.types().getOrDefault(type, 0L)).sum();
        return time >= EVERYWHERE * stat.documents();
    }

    private static boolean isAddressable(String pattern) {
        try {
            PathPattern.parse(pattern);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String percent(double rate) {
        return String.format(Locale.ROOT, "%.0f%%", rate * 100);
    }
}
