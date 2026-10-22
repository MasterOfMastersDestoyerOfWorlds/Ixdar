package ixdar.tools;

/**
 * What a {@link Tool} shows under the cursor before a click commits it: where the cursor hit,
 * whether the preview succeeded, what it cost and the tool's own diagnostics as one line of text.
 */
public final class Preview {

    /** Where the cursor hit, xyz. */
    public final float[] hitPoint;

    /** Whether the preview succeeded, so a click there would commit it. */
    public final boolean valid;

    /** Wall time the preview took, in milliseconds. */
    public final double millis;

    /** The tool's own diagnostics of the preview, one line, empty for none. */
    public final String detail;

    /**
     * A preview at {@code hitPoint}.
     *
     * @param hitPoint where the cursor hit, xyz
     * @param valid    whether the preview succeeded
     * @param millis   wall time it took, in milliseconds
     * @param detail   the tool's diagnostics, one line
     */
    public Preview(float[] hitPoint, boolean valid, double millis, String detail) {
        this.hitPoint = hitPoint;
        this.valid = valid;
        this.millis = millis;
        this.detail = detail;
    }
}
