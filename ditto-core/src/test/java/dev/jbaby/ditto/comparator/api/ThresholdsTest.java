package dev.jbaby.ditto.comparator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;

import org.junit.jupiter.api.Test;

class ThresholdsTest {

    @Test
    void atLeastIsInclusiveAtBothBounds() {
        var band = new Thresholds.AtLeast(0.99, 0.97);

        assertThat(band.levelOf(1.0)).isEqualTo(Level.GREEN);
        assertThat(band.levelOf(0.99)).isEqualTo(Level.GREEN);
        assertThat(band.levelOf(0.9899)).isEqualTo(Level.YELLOW);
        assertThat(band.levelOf(0.97)).isEqualTo(Level.YELLOW);
        assertThat(band.levelOf(0.9699)).isEqualTo(Level.RED);
    }

    @Test
    void belowIsExclusiveAtBothBounds() {
        var band = new Thresholds.Below(0.05, 0.20);

        assertThat(band.levelOf(0.0)).isEqualTo(Level.GREEN);
        assertThat(band.levelOf(0.0499)).isEqualTo(Level.GREEN);
        assertThat(band.levelOf(0.05)).isEqualTo(Level.YELLOW);
        assertThat(band.levelOf(0.1999)).isEqualTo(Level.YELLOW);
        assertThat(band.levelOf(0.20)).isEqualTo(Level.RED);
    }

    @Test
    void rejectsInvertedOrOutOfRangeBands() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Thresholds.AtLeast(0.90, 0.95));
        assertThatIllegalArgumentException().isThrownBy(() -> new Thresholds.Below(0.30, 0.20));
        assertThatIllegalArgumentException().isThrownBy(() -> new Thresholds.AtLeast(1.5, 0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> new Thresholds.Below(Double.NaN, 0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> new Thresholds.StructureThresholds(-0.1, 0, 0));
    }

    @Test
    void worstLevelWins() {
        assertThat(Level.worstOf(List.of())).isEqualTo(Level.GREEN);
        assertThat(Level.worstOf(List.of(Level.GREEN, Level.YELLOW, Level.GREEN))).isEqualTo(Level.YELLOW);
        assertThat(Level.YELLOW.worst(Level.RED)).isEqualTo(Level.RED);
        assertThat(Level.RED.worst(Level.GREEN)).isEqualTo(Level.RED);
    }
}
