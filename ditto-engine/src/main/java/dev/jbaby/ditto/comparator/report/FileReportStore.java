package dev.jbaby.ditto.comparator.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.Labels;
import dev.jbaby.ditto.comparator.api.Level;

/**
 * Stores each report as a JSON file in a directory, so nothing is written into the compared databases. A file holds
 * the report as {@link ReportJson#writePretty} writes it, the JSON the CLI prints and the viewer opens, and is named
 * {@code <finishedAt>_<id>.json}, e.g. {@code 2026-10-23T08-15-02.311Z_647fc74d-d99a-4c98-95b8-c4f88264229f.json}, so
 * listing the directory sorts the reports by time.
 * <p>A report is written to a temporary file, flushed to disk and then renamed, so readers never see part of a
 * report. A file is never changed afterwards, so several applications (e.g. instances sharing a volume) and the CLI
 * can use the same directory at the same time. With a retention, each save deletes the report files older than the
 * retention.
 * <p>Queries list the directory, newest first, and read each file once to learn its baseline, candidate, verdict and
 * labels; returning a report reads its file. Other files in the directory are ignored, and unreadable reports are
 * skipped with a warning.
 * <p>A {@linkplain #readOnly read-only} store only reads: it neither creates the directory nor stores or deletes
 * anything.
 */
public final class FileReportStore implements ReportStore {

    private static final Logger log = LoggerFactory.getLogger(FileReportStore.class);
    // fixed width, so names sort by time; no colons, which some file systems reject
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String ID = "[A-Za-z0-9_-]{1,128}";
    private static final Pattern REPORT_FILE = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}\\.\\d{3}Z)_(" + ID + ")\\.json");
    private static final Summary UNREADABLE = new Summary("", "", Level.RED, Map.of());

    private final Path directory;
    private final @Nullable Duration retention;
    private final ReportJson json;
    private final boolean readOnly;
    // what the queries filter on, per file name; files never change, so each is read once
    private final Map<String, Summary> summaries = new ConcurrentHashMap<>();
    private volatile boolean announced;
    private volatile boolean warnedRetention;

    /**
     * @param directory where the report files are, relative to the working directory unless absolute; created on the
     *                  first save
     * @param retention how long a report is kept, {@code null} to keep reports forever
     */
    public FileReportStore(Path directory, @Nullable Duration retention, ReportJson json) {
        this(directory, retention, json, false);
    }

    private FileReportStore(Path directory, @Nullable Duration retention, ReportJson json, boolean readOnly) {
        if (retention != null && retention.toSeconds() < 1) {
            throw new IllegalArgumentException("retention must be at least one second, was " + retention);
        }
        this.directory = directory.toAbsolutePath().normalize();
        this.retention = retention;
        this.json = json;
        this.readOnly = readOnly;
    }

    /** A store that only reads reports: it creates no directory, deletes nothing, and {@link #save} fails. */
    public static FileReportStore readOnly(Path directory, ReportJson json) {
        return new FileReportStore(directory, null, json, true);
    }

    /** The directory of the report files, absolute. */
    public Path directory() {
        return directory;
    }

    /**
     * @throws UnsupportedOperationException if this store is {@linkplain #readOnly read-only}
     * @throws IllegalArgumentException      if the report id can't be part of a file name
     * @throws UncheckedIOException          if the file can't be written
     */
    @Override
    public void save(ComparisonReport report) {
        if (readOnly) {
            throw new UnsupportedOperationException("This report store is read-only");
        }
        if (!report.id().matches(ID)) {
            throw new IllegalArgumentException("Report id '" + report.id() + "' can't be part of a file name: use"
                    + " letters, digits, _ and -");
        }
        Path file = directory.resolve(TIME.format(report.run().finishedAt()) + "_" + report.id() + ".json");
        try {
            Files.createDirectories(directory);
            write(file, json.writePretty(report).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store report " + report.id() + " in " + directory, e);
        }
        if (!announced) {
            announced = true;
            log.info("Comparison reports are stored in {}", directory);
        }
        applyRetention();
    }

    @Override
    public Optional<ComparisonReport> findById(String id) {
        for (ReportFile file : list()) {
            if (file.id().equals(id)) {
                try {
                    return Optional.of(read(file.path()));
                } catch (NoSuchFileException e) {
                    // deleted since the listing, e.g. by the retention of another instance
                    return Optional.empty();
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not read report " + id + " in " + directory, e);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<ComparisonReport> findRecent(@Nullable String candidate, int limit) {
        return find(summary -> candidate == null || summary.candidate().equals(candidate), limit);
    }

    @Override
    public List<ComparisonReport> findByLabels(Map<String, String> labels, int limit) {
        Set<Map.Entry<String, String>> required = Labels.of(labels).entrySet();
        return find(summary -> summary.labels().entrySet().containsAll(required), limit);
    }

    @Override
    public List<ComparisonReport> findHistory(String baseline, String candidate, boolean includeRed, int limit) {
        return find(summary -> summary.baseline().equals(baseline) && summary.candidate().equals(candidate)
                && (includeRed || summary.verdict() != Level.RED), limit);
    }

    /** Most recent first. */
    @Override
    public void forEach(Consumer<? super ComparisonReport> action) {
        for (ReportFile file : list()) {
            ComparisonReport report = load(file);
            if (report != null) {
                action.accept(report);
            }
        }
    }

    /** The reports the filter accepts, most recent first. */
    private List<ComparisonReport> find(Predicate<Summary> filter, int limit) {
        List<ComparisonReport> reports = new ArrayList<>();
        for (ReportFile file : list()) {
            if (reports.size() >= limit) {
                break;
            }
            Summary known = summaries.get(file.name());
            if (known == UNREADABLE || (known != null && !filter.test(known))) {
                continue;
            }
            ComparisonReport report = load(file);
            if (report != null && filter.test(Summary.of(report))) {
                reports.add(report);
            }
        }
        return reports;
    }

    /** The report in the file, {@code null} if it is unreadable or was deleted since the listing. */
    private @Nullable ComparisonReport load(ReportFile file) {
        try {
            ComparisonReport report = read(file.path());
            summaries.put(file.name(), Summary.of(report));
            return report;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            summaries.put(file.name(), UNREADABLE);
            log.warn("Skipping unreadable report file {}: {}", file.path(), e.getMessage());
            return null;
        }
    }

    private ComparisonReport read(Path file) throws IOException {
        return json.read(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** The report files, newest first; none if the directory doesn't exist yet. */
    private List<ReportFile> list() {
        List<ReportFile> files = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            paths.forEach(path -> {
                Matcher name = REPORT_FILE.matcher(path.getFileName().toString());
                if (name.matches()) {
                    files.add(new ReportFile(path, name.group(), name.group(1), name.group(2)));
                }
            });
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list the reports in " + directory, e);
        }
        files.sort(Comparator.comparing(ReportFile::name).reversed());
        // forget deleted files
        summaries.keySet().retainAll(files.stream().map(ReportFile::name).collect(Collectors.toSet()));
        return files;
    }

    private void applyRetention() {
        if (retention == null) {
            return;
        }
        String oldest = TIME.format(Instant.now().minus(retention));
        for (ReportFile file : list()) {
            if (file.time().compareTo(oldest) < 0) {
                try {
                    Files.deleteIfExists(file.path());
                } catch (IOException e) {
                    if (!warnedRetention) {
                        warnedRetention = true;
                        log.warn("Could not delete report file {} after the retention of {}: {}", file.path(),
                                retention, e.getMessage());
                    }
                }
            }
        }
    }

    /** Written in full and flushed to disk under a temporary name, then renamed. */
    private static void write(Path file, byte[] content) throws IOException {
        Path temporary = file.resolveSibling("." + file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** @param time the file name's time, which sorts like the time itself */
    private record ReportFile(Path path, String name, String time, String id) {
    }

    private record Summary(String baseline, String candidate, Level verdict, Map<String, String> labels) {

        static Summary of(ComparisonReport report) {
            var settings = report.run().settings();
            return new Summary(settings.baseline().toString(), settings.candidate().toString(), report.verdict(),
                    report.labels());
        }
    }
}
