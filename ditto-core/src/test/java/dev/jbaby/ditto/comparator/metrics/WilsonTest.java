package dev.jbaby.ditto.comparator.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class WilsonTest {

    @Test
    void matchesKnownValues() {
        // reference values from the Wilson score formula, z = 1.96
        var half = Wilson.interval(50, 100);
        assertThat(half.point()).isEqualTo(0.5);
        assertThat(half.lower()).isCloseTo(0.4038, within(1e-4));
        assertThat(half.upper()).isCloseTo(0.5962, within(1e-4));

        var none = Wilson.interval(0, 1000);
        assertThat(none.lower()).isZero();
        assertThat(none.upper()).isCloseTo(0.00383, within(1e-5));

        var all = Wilson.interval(1000, 1000);
        assertThat(all.upper()).isEqualTo(1.0);
        assertThat(all.lower()).isCloseTo(0.99617, within(1e-5));
    }

    @Test
    void undefinedWithoutTrials() {
        assertThat(Wilson.interval(0, 0)).isNull();
        assertThat(Wilson.estimate(0, 0).value()).isNull();
        assertThat(Wilson.estimate(3, 10).sampleSize()).isEqualTo(10);
    }

    @Test
    void intervalAlwaysContainsThePoint() {
        for (long n = 1; n < 200; n += 7) {
            for (long k = 0; k <= n; k++) {
                var interval = Wilson.interval(k, n);
                assertThat(interval.lower()).isLessThanOrEqualTo(interval.point());
                assertThat(interval.upper()).isGreaterThanOrEqualTo(interval.point());
                assertThat(interval.lower()).isGreaterThanOrEqualTo(0.0);
                assertThat(interval.upper()).isLessThanOrEqualTo(1.0);
            }
        }
    }
}
