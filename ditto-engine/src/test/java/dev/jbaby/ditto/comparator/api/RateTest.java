package dev.jbaby.ditto.comparator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class RateTest {

    @Test
    void exactRateIsUndefinedForZeroDenominator() {
        assertThat(Rate.exact(3, 4).value()).isEqualTo(0.75);
        assertThat(Rate.exact(0, 0).value()).isNull();
        assertThat(Rate.exact(0, 0).defined()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> Rate.exact(5, 4));
    }

    @Test
    void conservativeBasisPicksTheWorseBound() {
        Rate estimate = new Rate.Estimate(0.96, 0.94, 0.98, 1000);

        assertThat(estimate.valueFor(VerdictBasis.POINT, true)).isEqualTo(0.96);
        assertThat(estimate.valueFor(VerdictBasis.CONSERVATIVE, true)).isEqualTo(0.94);
        assertThat(estimate.valueFor(VerdictBasis.CONSERVATIVE, false)).isEqualTo(0.98);
        assertThat(Rate.exact(96, 100).valueFor(VerdictBasis.CONSERVATIVE, true)).isEqualTo(0.96);
    }

    @Test
    void estimateIntervalMustContainValue() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Rate.Estimate(0.5, 0.6, 0.7, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new Rate.Estimate(0.5, null, 0.7, 10));
    }
}
