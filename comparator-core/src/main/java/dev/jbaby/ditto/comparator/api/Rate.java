package dev.jbaby.ditto.comparator.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

/**
 * A fraction in [0, 1]: exactly counted (FULL mode) or estimated from a sample with a 95% confidence interval.
 * {@link #value()} is {@code null} if the rate is undefined because its denominator is zero.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Rate.Exact.class, name = "exact"),
        @JsonSubTypes.Type(value = Rate.Estimate.class, name = "estimate")
})
public sealed interface Rate {

    @Nullable Double value();

    @JsonIgnore
    default boolean defined() {
        return value() != null;
    }

    /**
     * The value a verdict rule should evaluate: the point value, or with {@link VerdictBasis#CONSERVATIVE} the bound
     * of the confidence interval that is worse for the rule.
     *
     * @param higherIsBetter whether larger values are better for the rule (e.g. key similarity)
     */
    default @Nullable Double valueFor(VerdictBasis basis, boolean higherIsBetter) {
        return switch (this) {
            case Exact exact -> exact.value();
            case Estimate estimate when basis == VerdictBasis.POINT -> estimate.value();
            case Estimate estimate -> higherIsBetter ? estimate.lower() : estimate.upper();
        };
    }

    /**
     * @param count numerator
     * @param total denominator
     * @param value {@code count / total}, {@code null} if {@code total == 0}
     */
    record Exact(long count, long total, @Nullable Double value) implements Rate {

        public Exact {
            if (count < 0 || total < 0 || count > total) {
                throw new IllegalArgumentException("invalid rate " + count + "/" + total);
            }
        }
    }

    /**
     * @param value      point estimate, {@code null} if nothing was sampled
     * @param lower      lower bound of the 95% confidence interval
     * @param upper      upper bound of the 95% confidence interval
     * @param sampleSize number of observations the estimate is based on
     */
    record Estimate(@Nullable Double value, @Nullable Double lower, @Nullable Double upper, long sampleSize)
            implements Rate {

        public Estimate {
            if ((value == null) != (lower == null) || (value == null) != (upper == null)) {
                throw new IllegalArgumentException("value, lower and upper must be all null or all set");
            }
            if (value != null && !(lower <= value && value <= upper)) {
                throw new IllegalArgumentException("interval [" + lower + ", " + upper + "] does not contain " + value);
            }
        }
    }

    static Exact exact(long count, long total) {
        return new Exact(count, total, total == 0 ? null : (double) count / total);
    }
}
