package dev.jbaby.ditto.comparator.path;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PathMatcherTest {

    @Test
    void matchesPathsAndTheirDescendants() {
        var matcher = PathMatcher.of(List.of("price", "customer", "items[].stock", "attributes.*"));

        assertThat(matcher.matches("price")).isTrue();
        assertThat(matcher.matches("customer.email")).isTrue();
        assertThat(matcher.matches("items[].stock")).isTrue();
        assertThat(matcher.matches("items[].stock.warehouse")).isTrue();
        assertThat(matcher.matches("attributes.color")).isTrue();
        assertThat(matcher.matches("attributes.*")).as("collapsed wildcard path").isTrue();

        assertThat(matcher.matches("priceList")).isFalse();
        assertThat(matcher.matches("items[].sku")).isFalse();
        assertThat(matcher.matches("items")).isFalse();
        assertThat(matcher.matches("attributes")).isFalse();
    }

    @Test
    void wildcardDoesNotMatchArrayElements() {
        var matcher = PathMatcher.of(List.of("*.amount"));

        assertThat(matcher.matches("total.amount")).isTrue();
        assertThat(matcher.matches("lines[].amount")).isFalse();
    }

    @Test
    void unaddressablePathsAndEmptyMatchersNeverMatch() {
        assertThat(PathMatcher.of(List.of("a")).matches("a..b")).isFalse();
        assertThat(PathMatcher.NONE.matches("anything")).isFalse();
        assertThat(PathMatcher.of(List.of()).isEmpty()).isTrue();
    }
}
