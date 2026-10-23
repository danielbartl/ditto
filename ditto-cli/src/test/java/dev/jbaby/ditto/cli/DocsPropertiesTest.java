package dev.jbaby.ditto.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every {@code ditto.*} property the documentation names exists, so renamed or removed properties can't linger in
 * the README or on the site: dotted names anywhere in the text, and the first column of every table headed
 * "Property", whose names leave out the {@code ditto.} prefix. Nested YAML examples are not checked.
 */
class DocsPropertiesTest {

    private static final Path ROOT = Path.of("..");
    private static final List<Path> DOCS = List.of(ROOT.resolve("README.md"), ROOT.resolve("docs/index.html"),
            ROOT.resolve("docs/guide.html"));
    private static final Pattern DOTTED = Pattern.compile("(?<![\\w./-])ditto\\.[a-z][a-z0-9.-]*[a-z0-9*]");
    /** Micrometer meter names, not properties. */
    private static final String METERS = "ditto.comparison";
    private static final Pattern HTML_TABLE = Pattern.compile("<table>(.*?)</table>", Pattern.DOTALL);
    private static final Pattern HTML_FIRST_HEADER = Pattern.compile("<th>(.*?)</th>");
    private static final Pattern HTML_FIRST_CELL = Pattern.compile("<tr><td>(.*?)</td>");
    private static final Pattern HTML_CODE = Pattern.compile("<code>(.*?)</code>");
    private static final Pattern MARKDOWN_CODE = Pattern.compile("`([^`]+)`");

    @Test
    void everyPropertyTheDocsNameExists() throws IOException {
        Known known = Known.load();
        assertThat(known.names).as("configuration metadata on the classpath").contains("ditto.persistence.storage",
                "ditto.opensearch.uris");

        List<String> unknown = new ArrayList<>();
        for (Path doc : DOCS) {
            for (String name : mentioned(doc)) {
                if (!known.covers(name)) {
                    unknown.add(doc.getFileName() + ": " + name);
                }
            }
        }
        assertThat(unknown).isEmpty();
    }

    @Test
    void readsTheTableForms() {
        assertThat(names("thresholds.key-similarity.green / .yellow"))
                .containsExactly("ditto.thresholds.key-similarity.green", "ditto.thresholds.key-similarity.yellow");
        assertThat(names("thresholds.max-path-change-rate.green/yellow")).containsExactly(
                "ditto.thresholds.max-path-change-rate.green", "ditto.thresholds.max-path-change-rate.yellow");
        assertThat(names("persistence.collection / .database"))
                .containsExactly("ditto.persistence.collection", "ditto.persistence.database");
        assertThat(names("ditto.opensearch.uris")).containsExactly("ditto.opensearch.uris");
        assertThat(names("mode")).containsExactly("ditto.mode");
    }

    private static Set<String> mentioned(Path doc) throws IOException {
        String text = Files.readString(doc);
        Set<String> names = new LinkedHashSet<>();
        Matcher dotted = DOTTED.matcher(text);
        while (dotted.find()) {
            if (!dotted.group().startsWith(METERS)) {
                names.add(dotted.group());
            }
        }
        if (doc.toString().endsWith(".md")) {
            markdownTables(text, names);
        } else {
            htmlTables(text, names);
        }
        return names;
    }

    private static void markdownTables(String text, Set<String> names) {
        boolean inPropertyTable = false;
        for (String line : text.split("\n")) {
            if (!line.startsWith("|")) {
                inPropertyTable = false;
            } else if (line.matches("\\|\\s*Property\\s*\\|.*")) {
                inPropertyTable = true;
            } else if (inPropertyTable) {
                String firstCell = line.substring(1, line.indexOf('|', 1));
                Matcher code = MARKDOWN_CODE.matcher(firstCell);
                while (code.find()) {
                    names.addAll(names(code.group(1)));
                }
            }
        }
    }

    private static void htmlTables(String text, Set<String> names) {
        Matcher table = HTML_TABLE.matcher(text);
        while (table.find()) {
            Matcher header = HTML_FIRST_HEADER.matcher(table.group(1));
            if (!header.find() || !header.group(1).equals("Property")) {
                continue;
            }
            Matcher row = HTML_FIRST_CELL.matcher(table.group(1));
            while (row.find()) {
                List<String> codes = new ArrayList<>();
                Matcher code = HTML_CODE.matcher(row.group(1));
                while (code.find()) {
                    codes.add(code.group(1).replace("&lt;", "<").replace("&gt;", ">"));
                }
                if (!codes.isEmpty()) {
                    names.addAll(names(String.join(" / ", codes)));
                }
            }
        }
    }

    /** {@code a.b.c / .d} (or {@code a.b.c/d}) names {@code ditto.a.b.c} and {@code ditto.a.b.d}. */
    static List<String> names(String cell) {
        List<String> names = new ArrayList<>();
        String[] parts = cell.split("/");
        String first = parts[0].strip();
        String parent = first.contains(".") ? first.substring(0, first.lastIndexOf('.') + 1) : "";
        names.add(first);
        for (int i = 1; i < parts.length; i++) {
            names.add(parent + parts[i].strip().replaceFirst("^\\.", ""));
        }
        return names.stream().map(name -> name.startsWith("ditto.") ? name : "ditto." + name).toList();
    }

    /** The {@code ditto.*} properties of the configuration metadata of all modules. */
    private record Known(Set<String> names, Set<String> maps) {

        static Known load() throws IOException {
            Set<String> names = new TreeSet<>();
            Set<String> maps = new TreeSet<>();
            JsonMapper mapper = JsonMapper.builder().build();
            for (URL url : Collections.list(DocsPropertiesTest.class.getClassLoader()
                    .getResources("META-INF/spring-configuration-metadata.json"))) {
                try (InputStream in = url.openStream()) {
                    for (JsonNode property : mapper.readTree(in).path("properties")) {
                        String name = property.path("name").asString();
                        if (name.startsWith("ditto.")) {
                            names.add(name);
                            if (property.path("type").asString().startsWith("java.util.Map")) {
                                maps.add(name);
                            }
                        }
                    }
                }
            }
            return new Known(names, maps);
        }

        /** An exact property, a key of a map property, a group of properties, or a {@code group.*} pattern. */
        boolean covers(String name) {
            String prefix = name.endsWith(".*") ? name.substring(0, name.length() - 1) : name + ".";
            return names.contains(name)
                    || maps.stream().anyMatch(map -> name.startsWith(map + "."))
                    || names.stream().anyMatch(known -> known.startsWith(prefix));
        }
    }
}
