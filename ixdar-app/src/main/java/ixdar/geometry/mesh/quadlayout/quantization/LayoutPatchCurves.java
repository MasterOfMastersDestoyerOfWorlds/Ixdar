package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.List;

import org.joml.Vector3f;

/**
 * Geometric boundary curves of one final layout patch: four side polylines in
 * canonical orientation (side 0 A→B, side 1 B→C, side 2 D→C, side 3 A→D), the
 * four corner positions, and validation counters. Clean four-cornered quads also
 * carry a tessellated Coons sample grid.
 */
public final class LayoutPatchCurves {

    public final int rectangleId;
    public final int rootPatchId;

    /** 4 boundary polylines in canonical side order and orientation. */
    public final List<List<Vector3f>> sidePolylines;

    /** Corner positions A, B, C, D averaged from the side endpoints meeting there. */
    public final Vector3f[] cornerPositions;

    /** Side segments with no backing arc (inserted edges drawn as straight lines). */
    public final int syntheticSegmentCount;

    /** Side segments covering only part of their backing arc (split products). */
    public final int partialSegmentCount;

    /** Arcs whose parametric range could not be found on their trace chain. */
    public final int missingArcCount;

    /** Sides whose traced polyline is empty or shorter than the epsilon. */
    public final int degenerateSideCount;

    /** Largest disagreement between the two side endpoints meeting at a corner. */
    public final float maxCornerMismatch;

    /** True when all four sides traced and all four corners agree. */
    public final boolean cleanQuad;

    /**
     * Coons sample grid (samples × samples xyz triples, row-major in (v, u))
     * for clean quads; {@code null} otherwise. Filled by the tessellation
     * step after construction.
     */
    public float[] coonsGrid;

    /**
     * Creates one patch-curves record.
     *
     * @param rectangleId           id of the layout rectangle
     * @param rootPatchId           originating valid T-mesh patch id
     * @param sidePolylines         4 canonical side polylines
     * @param cornerPositions       averaged corner positions A, B, C, D
     * @param syntheticSegmentCount segments without a backing arc
     * @param partialSegmentCount   segments covering part of their arc
     * @param missingArcCount       arcs missing from their trace chain
     * @param degenerateSideCount   empty or near-zero-length sides
     * @param maxCornerMismatch     largest corner endpoint disagreement
     * @param cleanQuad             whether the patch validated as a clean quad
     */
    public LayoutPatchCurves(int rectangleId, int rootPatchId,
            List<List<Vector3f>> sidePolylines, Vector3f[] cornerPositions,
            int syntheticSegmentCount, int partialSegmentCount, int missingArcCount,
            int degenerateSideCount, float maxCornerMismatch, boolean cleanQuad) {
        this.rectangleId = rectangleId;
        this.rootPatchId = rootPatchId;
        this.sidePolylines = sidePolylines;
        this.cornerPositions = cornerPositions;
        this.syntheticSegmentCount = syntheticSegmentCount;
        this.partialSegmentCount = partialSegmentCount;
        this.missingArcCount = missingArcCount;
        this.degenerateSideCount = degenerateSideCount;
        this.maxCornerMismatch = maxCornerMismatch;
        this.cleanQuad = cleanQuad;
    }
}
