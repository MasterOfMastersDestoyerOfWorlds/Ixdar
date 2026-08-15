package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.fixtures.TorusLayoutFixture;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.extraction.ExtractedPatchGrids;
import ixdar.geometry.mesh.quadlayout.extraction.ExtractedQuadMesh;
import ixdar.geometry.mesh.quadlayout.extraction.QuadMeshExtraction;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapDofSystem;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapOptimizer;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapVerification;
import ixdar.geometry.mesh.quadlayout.gridmap.IntegerGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutPatchMaps;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutResolution;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * QEx-style extraction of the torus layout's integer grid map. The unrelaxed
 * map puts every patch boundary exactly on integer iso-lines, hammering the
 * collinear and through-vertex branches; the relaxed map exercises the general
 * position case with the layout's nodes held pinned.
 */
class QuadMeshExtractionTest {

    /** Quads the shortest arc is sized to, so arcs carry interior grid points. */
    private static final int QUADS_ON_SHORTEST_ARC = 3;

    /**
     * The unrelaxed map extracts the quantization's exact quad mesh with every
     * degenerate branch on the table: iso-lines along mesh edges and through
     * vertices at every patch boundary.
     */
    @Test
    void unrelaxedTorusExtractsTheQuantizedQuadMesh() {
        extractAndCheck(false);
    }

    /**
     * The relaxed map, nodes pinned, still extracts the same quad structure with
     * every vertex re-fitted by the optimization.
     */
    @Test
    void relaxedTorusExtractsTheQuantizedQuadMesh() {
        extractAndCheck(true);
    }

    /**
     * Builds the torus layout's global grid map, optionally relaxes it, extracts
     * the quad mesh, regroups it onto the layout, and checks every invariant the
     * quantization prescribes.
     *
     * @param relax whether to run the Newton relaxation before extracting
     */
    private void extractAndCheck(boolean relax) {
        TorusLayoutFixture fixture = new TorusLayoutFixture();
        fixture.tmesh.contract();
        fixture.tmesh.conform();
        SeamlessParameterization seamless = new QuadLayoutEngine(fixture.torus, 0f)
                .buildSeamless();
        double targetEdgeLength = shortestArcLength(fixture, seamless) / QUADS_ON_SHORTEST_ARC;
        LayoutPatchMaps patchMaps = new LayoutPatchMaps(fixture.tmesh, seamless,
                targetEdgeLength).build();
        IntegerGridMap frames = new IntegerGridMap(fixture.tmesh).build();
        GlobalGridMap gridMap = new GlobalGridMap(patchMaps, frames, seamless).build();
        if (relax) {
            GridMapDofSystem dofs = new GridMapDofSystem(gridMap);
            dofs.build();
            new GridMapOptimizer(dofs, seamless).build();
        }
        GridMapVerification verification = new GridMapVerification(gridMap).build();
        QuadMeshExtraction extraction = new QuadMeshExtraction(gridMap, verification);
        extraction.expectedQuadCount = quantizedQuadCount(fixture);
        ExtractedQuadMesh quadMesh = extraction.build();
        assertEquals(quantizedQuadCount(fixture), quadMesh.quadCount,
                "the extraction must produce exactly the quantization's quads");
        assertEquals(TorusLayoutFixture.TORUS_EULER_CHARACTERISTIC,
                quadMesh.eulerCharacteristic(), "the quad mesh must close over the torus");
        ExtractedPatchGrids grids = new ExtractedPatchGrids(quadMesh, gridMap).build();
        for (EmbeddedPatch patch : fixture.tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            int columns = fixture.tmesh.sideQuadCount(patch.patchId, 0) + 1;
            int rows = fixture.tmesh.sideQuadCount(patch.patchId, 1) + 1;
            assertNotNull(grids.gridByPatchId[patch.patchId],
                    "patch " + patch.patchId + " has no grid");
            assertEquals(columns * rows, grids.gridByPatchId[patch.patchId].length,
                    "patch " + patch.patchId + " grid has the wrong site count");
        }
        for (int quad = 0; quad < quadMesh.quadCount; quad++) {
            assertTrue(grids.patchIdByQuad[quad] >= 0,
                    "quad " + quad + " was not assigned to a patch");
        }
    }

    /**
     * The quad count the quantization prescribes, summed over the live patches.
     *
     * @param fixture the torus layout
     * @return quads over all live patches
     */
    private int quantizedQuadCount(TorusLayoutFixture fixture) {
        int total = 0;
        for (EmbeddedPatch patch : fixture.tmesh.patches) {
            if (patch.alive) {
                total += fixture.tmesh.sideQuadCount(patch.patchId, 0)
                        * fixture.tmesh.sideQuadCount(patch.patchId, 1);
            }
        }
        return total;
    }

    /**
     * The shortest live arc's parametric length, which sets a target edge length
     * no arc can round below one quad.
     *
     * @param fixture  the torus layout
     * @param seamless the parametrization to measure in
     * @return the shortest arc's parametric length
     */
    private double shortestArcLength(TorusLayoutFixture fixture,
            SeamlessParameterization seamless) {
        LayoutResolution measured = new LayoutResolution(fixture.tmesh, seamless, 1.0).build();
        double shortest = Double.MAX_VALUE;
        for (EmbeddedArc arc : fixture.tmesh.arcs) {
            if (arc.alive) {
                shortest = Math.min(shortest, measured.parametricLengthByArc[arc.arcId]);
            }
        }
        return shortest;
    }
}
