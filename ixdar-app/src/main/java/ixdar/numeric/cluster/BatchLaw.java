package ixdar.numeric.cluster;

/**
 * Which fitted scaling law picks e-means' batch size when none is given.
 */
public enum BatchLaw {
    /** The quality law at dimension 16 or less, the throughput law above. */
    AUTO,
    /** The batch with the best wall-clock time. */
    THROUGHPUT,
    /** A batch about eight times smaller, for the lowest loss. */
    QUALITY
}
