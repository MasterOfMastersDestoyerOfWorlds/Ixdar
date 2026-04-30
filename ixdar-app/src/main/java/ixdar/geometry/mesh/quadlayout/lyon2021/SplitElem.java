package ixdar.geometry.mesh.quadlayout.lyon2021;

import org.joml.Vector3f;

/**
 * One element of a per-Tquad split table — a {@link SplitVert} together
 * with its cumulative parametric distance along the Tquad's side.
 * Mirrors metriko's {@code visualizer::SplitElem}.
 *
 * <p>Stages D-E use {@code distance} to compute split-arc tracing
 * directions across the patch interior.
 */
public record SplitElem(int arcId,
                        int stepIndex,
                        float u,
                        float v,
                        Vector3f position,
                        float distance) {
}
