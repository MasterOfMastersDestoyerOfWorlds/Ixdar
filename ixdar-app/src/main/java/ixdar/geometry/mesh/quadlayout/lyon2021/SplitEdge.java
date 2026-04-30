package ixdar.geometry.mesh.quadlayout.lyon2021;

/**
 * One straight segment of a {@link SplitArcTracer.SplitArc} living inside
 * a single mesh face. {@code (u1, v1)} and {@code (u2, v2)} are in the
 * face's UV frame.
 */
public record SplitEdge(int faceId,
                        float u1, float v1,
                        float u2, float v2) {
}
