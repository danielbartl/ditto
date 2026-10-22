package dev.jbaby.ditto.comparator.metrics;

import org.jspecify.annotations.Nullable;

import dev.jbaby.ditto.comparator.api.Rate;

/**
 * Wilson score interval for a binomial proportion. Unlike the normal approximation it behaves well for rates near
 * 0 and 1, which are exactly the interesting ones here (e.g. 0 changes in 1,000 sampled documents).
 */
public final class Wilson {

    /** Two-sided 95%. */
    public static final double Z_95 = 1.959963984540054;

    private Wilson() {
    }

    public record Interval(double point, double lower, double upper) {
    }

    /** Interval for {@code successes / trials}; {@code null} if {@code trials == 0}. */
    public static @Nullable Interval interval(long successes, long trials) {
        if (trials <= 0) {
            return null;
        }
        if (successes < 0 || successes > trials) {
            throw new IllegalArgumentException("invalid proportion " + successes + "/" + trials);
        }
        double n = trials;
        double p = successes / n;
        double z2 = Z_95 * Z_95;
        double denominator = 1 + z2 / n;
        double center = (p + z2 / (2 * n)) / denominator;
        double half = Z_95 * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n)) / denominator;
        // the interval contains p analytically; clamp away floating-point noise
        double lower = Math.max(0.0, Math.min(p, center - half));
        double upper = Math.min(1.0, Math.max(p, center + half));
        return new Interval(p, lower, upper);
    }

    /** The interval as an estimated {@link Rate}, undefined if nothing was sampled. */
    public static Rate.Estimate estimate(long successes, long trials) {
        Interval interval = interval(successes, trials);
        return interval == null
                ? new Rate.Estimate(null, null, null, 0)
                : new Rate.Estimate(interval.point(), interval.lower(), interval.upper(), trials);
    }
}
