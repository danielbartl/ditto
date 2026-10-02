package dev.jbaby.ditto.comparator.metrics;

import java.util.List;

import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.structure.StructureProfile;

/**
 * Raw counts of a scan, before rates, estimates and the verdict are derived.
 * In SAMPLE mode {@code matched}, {@code unchanged}, {@code changed} and {@code removed} refer to the baseline sample,
 * {@code added} and {@code candidateSampled} to the candidate sample.
 *
 * @param baselineDocsRead           baseline documents read
 * @param candidateDocsRead          candidate documents read
 * @param matched                    keys found on both sides
 * @param unchanged                  matched documents with equal content hash
 * @param changed                    matched documents with different content hash
 * @param added                      keys only in the candidate
 * @param removed                    keys only in the baseline
 * @param candidateSampled           candidate keys sampled to detect added documents (SAMPLE mode, else 0)
 * @param pathChanges                changed paths, most frequent first
 * @param untrackedPathChanges       changed-path occurrences not tracked because of the path cap
 * @param changedExamples            keys of changed documents
 * @param addedExamples              keys of added documents
 * @param removedExamples            keys of removed documents
 * @param baselineProfile            structure profile of the baseline documents read
 * @param candidateProfile           structure profile of the candidate documents read
 */
public record ScanResult(
        long baselineDocsRead,
        long candidateDocsRead,
        long matched,
        long unchanged,
        long changed,
        long added,
        long removed,
        long candidateSampled,
        List<PathChangeStats.PathChangeCount> pathChanges,
        long untrackedPathChanges,
        List<KeyRef> changedExamples,
        List<KeyRef> addedExamples,
        List<KeyRef> removedExamples,
        StructureProfile baselineProfile,
        StructureProfile candidateProfile) {

    public ScanResult {
        pathChanges = List.copyOf(pathChanges);
        changedExamples = List.copyOf(changedExamples);
        addedExamples = List.copyOf(addedExamples);
        removedExamples = List.copyOf(removedExamples);
    }
}
