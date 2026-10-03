package dev.jbaby.ditto.comparator.api;

/**
 * Which value of an estimated rate (SAMPLE mode) the verdict rules evaluate. Exact rates (FULL mode) are not affected.
 */
public enum VerdictBasis {
    /** The point estimate. */
    POINT,
    /** The bound of the 95% confidence interval that is worse for the rule, so GREEN means "confidently GREEN". */
    CONSERVATIVE
}
