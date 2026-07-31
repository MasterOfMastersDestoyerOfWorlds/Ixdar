package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutPatchMaps;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutStripSizing;
import ixdar.geometry.mesh.quadlayout.embedding.PatchGridExtraction;

/**
 * The property LCBK19 §6.2 names when it says the union of the per-patch maps <em>"is guaranteed
 * to form a global integer grid map"</em>: the two patches meeting at an arc must read the same
 * quad-mesh vertices along it, or the extracted mesh is torn at every seam.
 */
class PatchGridSeamTest {

    /** Positions this far apart are the same point; the grids are built from the same floats. */
    private static final float TOLERANCE = 1e-6f;

    /** Parametric length given to every arc, so each is sized to {@link #QUADS_PER_ARC} quads. */
    private static final double ARC_LENGTH = 3.0;

    /** Quads laid along each arc, more than one so the seam check sees intra-arc points too. */
    private static final int QUADS_PER_ARC = 3;

    /**
     * Every arc of the conformed torus carries the same grid points into both of its patches.
     */
    @Test
    void adjacentPatchesShareTheirSeamPoints() {
        TorusLayoutFixture fixture = new TorusLayoutFixture();
        fixture.tmesh.contract();
        fixture.tmesh.conform();
        double[] lengthByArc = new double[fixture.tmesh.arcs.size()];
        Arrays.fill(lengthByArc, ARC_LENGTH);
        LayoutStripSizing sizing =
                new LayoutStripSizing(fixture.tmesh, lengthByArc, 1.0).build();
        PatchGridExtraction grid =
                new PatchGridExtraction(new LayoutPatchMaps(fixture.tmesh).build(), sizing).build();

        int checkedArcs = 0;
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
        }
        assertTrue(checkedArcs > 0, "the conformed torus should have arcs between two patches");
    }

    /**
     * The grid points a patch carries along one of its boundary arcs, read out of the patch's own
     * grid and oriented from the arc's start node to its end node.
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
                int samples = QUADS_PER_ARC;
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
            case 0: return offset;
            case 1: return offset * columns + columns - 1;
            case 2: return (rows - 1) * columns + (columns - 1 - offset);
            default: return (rows - 1 - offset) * columns;
        }
    }
}
