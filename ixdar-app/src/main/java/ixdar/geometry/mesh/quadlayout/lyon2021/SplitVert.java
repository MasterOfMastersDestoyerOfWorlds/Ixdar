package ixdar.geometry.mesh.quadlayout.lyon2021;

import org.joml.Vector3f;

/**
 * One integer-quantized split vertex along a {@link
 * ixdar.geometry.mesh.quadlayout.tmesh.TArc}. Mirrors metriko's
 * {@code visualizer::SplitVert}: a parametric (u, v) position on a specific
 * step of the arc, with the corresponding 3D mesh position.
 *
 * <p>{@code arcId} = the {@code TArc.id()} this split lives on.
 * {@code stepIndex} = the index into the arc's per-step list (which
 * mesh face the split lies in).
 * {@code u}, {@code v} = parametric coordinate in that face's UV frame.
 * {@code position} = 3D point on the mesh, barycentrically interpolated.
 */
public record SplitVert(int arcId,
                        int stepIndex,
                        float u,
                        float v,
                        Vector3f position) {
}
