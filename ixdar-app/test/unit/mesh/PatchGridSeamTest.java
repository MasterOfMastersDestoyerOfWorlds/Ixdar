package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.extraction.PatchGridExtraction;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutPatchMaps;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutResolution;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * The property LCBK19 §6.2 names when it says the union of the per-patch maps
 * <em>"is guaranteed to form a global integer grid map"</em>: the two patches
 * meeting at an arc must read the same quad-mesh vertices along it, or the
 * extracted mesh is torn at every seam.
 */
class PatchGridSeamTest {

    /**
     * Positions this far apart are the same point; the grids are built from the
     * same floats.
     */
    private static final float TOLERANCE = 1e-6f;

    /**
     * Quads the shortest arc is sized to, which fixes the target edge length so
     * every arc carries interior points and the seam check sees more than its two
     * end nodes.
     */
    private static final int QUADS_ON_SHORTEST_ARC = 3;

    /**
     * Every arc of the conformed torus carries the same grid points into both of
     * its patches.
     */
    @Test
    void adjacentPatchesShareTheirSeamPoints() {
        TorusLayoutFixture fixture = new TorusLayoutFixture();
        fixture.tmesh.contract();
        fixture.tmesh.conform();
        SeamlessParameterization seamless = new QuadLayoutEngine(fixture.torus, 0f).buildSeamless();
        double targetEdgeLength =
                shortestArcLength(fixture.tmesh, seamless) / QUADS_ON_SHORTEST_ARC;
        PatchGridExtraction grid = new PatchGridExtraction(
                new LayoutPatchMaps(fixture.tmesh, seamless, targetEdgeLength).build()).build();

        int checkedArcs = 0;
        int checkedInteriorPoints = 0;
        for (EmbeddedArc arc : fixture.tmesh.arcs) {
            if (!arc.alive || arc.leftPatchId == EmbeddedTMesh.NONE
                    || arc.rightPatchId == EmbeddedTMesh.NONE) {
                continue;
            }
            Vector3f[] fromLeft = seamPoints(fixture.tmesh, grid, arc, arc.leftPatchId);
            Vector3f[] fromRight = seamPoints(fixture.tmesh, grid, arc, arc.rightPatchId);
            assertEquals(fromLeft.length, fromRight.length,
                    "arc " + arc.arcId + " has a different number of grid points in its two"
                            + " patches");
            for (int lattice = 0; lattice < fromLeft.length; lattice++) {
                assertTrue(fromLeft[lattice].distance(fromRight[lattice]) < TOLERANCE,
                        "arc " + arc.arcId + " grid point " + lattice + " is at "
                                + fromLeft[lattice] + " in patch " + arc.leftPatchId + " but "
                                + fromRight[lattice] + " in patch " + arc.rightPatchId);
            }
            checkedArcs++;
            checkedInteriorPoints += Math.max(0, fromLeft.length - 2);
        }
        assertTrue(checkedArcs > 0, "the conformed torus should have arcs between two patches");
        assertTrue(checkedInteriorPoints > 0,
                "every arc carried only its two end nodes, so the check never compared a point"
                        + " the two patches had to agree on independently");
    }

    /**
     * The shortest live arc's length in the parametrization, which sets a target
     * edge length no arc can round below one quad.
     *
     * @param tmesh    the conformed T-mesh
     * @param seamless the parametrization to measure in
     * @return the shortest arc's parametric length
     */
    private double shortestArcLength(EmbeddedTMesh tmesh, SeamlessParameterization seamless) {
        LayoutResolution measured = new LayoutResolution(tmesh, seamless, 1.0).build();
        double shortest = Double.MAX_VALUE;
        for (EmbeddedArc arc : tmesh.arcs) {
            if (arc.alive) {
                shortest = Math.min(shortest, measured.parametricLengthByArc[arc.arcId]);
            }
        }
        return shortest;
    }

    /**
     * The grid points a patch carries along one of its boundary arcs, read out of
     * the patch's own grid and oriented from the arc's start node to its end node.
     *
     * @param tmesh   the conformed T-mesh
     * @param grid    the extracted grids
     * @param arc     arc to read along
     * @param patchId one of the arc's two patches
     * @throws IllegalStateException when the arc is not on any side of the patch
     * @return the arc's grid points, one per quantized step plus the two ends
     */
    private Vector3f[] seamPoints(EmbeddedTMesh tmesh, PatchGridExtraction grid, EmbeddedArc arc,
            int patchId) {
        EmbeddedPatch patch = tmesh.patches.get(patchId);
        int columns = grid.gridColumns(patchId);
        int rows = grid.gridRows(patchId);
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideArcs = patch.sideArcIds.get(side);
            List<Integer> sideNodes = patch.sideNodeIds.get(side);
            int offset = 0;
            for (int arcIndex = 0; arcIndex < sideArcs.size(); arcIndex++) {
                int samples = tmesh.arcs.get(sideArcs.get(arcIndex)).quadCount;
                if (sideArcs.get(arcIndex) != arc.arcId) {
                    offset += samples;
                    continue;
                }
                boolean forward = arc.startNodeId == sideNodes.get(arcIndex);
                Vector3f[] points = new Vector3f[samples + 1];
                for (int sample = 0; sample <= samples; sample++) {
                    int alongSide = offset + (forward ? sample : samples - sample);
                    points[sample] = grid.gridByPatchId[patchId][borderIndex(side, alongSide,
                            columns, rows)];
                }
                return points;
            }
        }
        throw new IllegalStateException(
                "arc " + arc.arcId + " is not on any side of patch " + patchId);
    }

    /**
     * The grid index of the point at a sample offset along one side of a patch.
     *
     * @param side    side index in {@code [0, 4)}
     * @param offset  sample offset from the side's start
     * @param columns the patch's grid columns
     * @param rows    the patch's grid rows
     * @return the index into the row-major grid
     */
    private int borderIndex(int side, int offset, int columns, int rows) {
        switch (side) {
        case 0:
            return offset;
        case 1:
            return offset * columns + columns - 1;
        case 2:
            return (rows - 1) * columns + (columns - 1 - offset);
        default:
            return (rows - 1 - offset) * columns;
        }
    }
}
