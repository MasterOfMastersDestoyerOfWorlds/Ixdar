package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.extraction.ExtractedPatchGrids;
import ixdar.geometry.mesh.quadlayout.extraction.ExtractedQuadMesh;
import ixdar.geometry.mesh.quadlayout.extraction.QuadMeshExtraction;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapAssembly;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapDofSystem;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapIsoSurface;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapOptimizer;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapVerification;
import ixdar.geometry.mesh.quadlayout.gridmap.IntegerGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutPatchMaps;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutResolution;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;

/**
 * QEx-style extraction of the torus layout's integer grid map. The unrelaxed
 * map puts every patch boundary exactly on integer iso-lines, hammering the
 * collinear and through-vertex branches; the relaxed map exercises the general
 * position case with the layout's nodes held pinned.
 */
class QuadMeshExtractionTest {

    /** A torus is genus 1, so V - E + F is zero for any cell decomposition of it. */
    private static final int TORUS_EULER_CHARACTERISTIC = 0;

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
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        NetworkContraction contraction = new NetworkContraction(fixtureNet);
        contraction.contract();
        contraction.conform();
        SeamlessUv seamless = new QuadLayoutEngine(fixtureNet.topology.sourceMesh, 0f)
                .buildSeamless();
        double targetEdgeLength = shortestArcLength(fixtureNet, seamless) / QUADS_ON_SHORTEST_ARC;
        LayoutPatchMaps patchMaps = new LayoutPatchMaps(fixtureNet, seamless,
                targetEdgeLength).build();
        IntegerGridMap frames = new IntegerGridMap(fixtureNet).build();
        GlobalGridMap gridMap = new GridMapAssembly().assemble(patchMaps, frames, seamless);
        GridMapAssembly.extractQuads(gridMap);
        if (relax) {
            GridMapDofSystem dofs = new GridMapDofSystem(gridMap);
            dofs.build();
            new GridMapOptimizer(dofs, seamless).build();
        }
        new GridMapVerification(gridMap).build();
        GridMapIsoSurface uvField = new GridMapIsoSurface(patchMaps, gridMap.uvByPatchId)
                .build();
        QuadMeshExtraction extraction = new QuadMeshExtraction(
                fixtureNet.topology.copy, uvField, fixtureNet);
        extraction.expectedQuadCount = quantizedQuadCount(fixtureNet);
        ExtractedQuadMesh quadMesh = extraction.build();
        assertEquals(quantizedQuadCount(fixtureNet), quadMesh.quadCount,
                "the extraction must produce exactly the quantization's quads");
        assertEquals(TORUS_EULER_CHARACTERISTIC,
                quadMesh.eulerCharacteristic(), "the quad mesh must close over the torus");
        ExtractedPatchGrids grids = new ExtractedPatchGrids(quadMesh, gridMap).build();
        for (EmbeddedPatch patch : fixtureNet.patches) {
            if (!patch.alive) {
                continue;
            }
            int columns = fixtureNet.sideQuadCount(patch.patchId, 0) + 1;
            int rows = fixtureNet.sideQuadCount(patch.patchId, 1) + 1;
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
     * @param fixtureNet the torus layout
     * @return quads over all live patches
     */
    private int quantizedQuadCount(ArcNetwork fixtureNet) {
        int total = 0;
        for (EmbeddedPatch patch : fixtureNet.patches) {
            if (patch.alive) {
                total += fixtureNet.sideQuadCount(patch.patchId, 0)
                        * fixtureNet.sideQuadCount(patch.patchId, 1);
            }
        }
        return total;
    }

    /**
     * The shortest live arc's parametric length, which sets a target edge length
     * no arc can round below one quad.
     *
     * @param fixtureNet the torus layout
     * @param seamless the parametrization to measure in
     * @return the shortest arc's parametric length
     */
    private double shortestArcLength(ArcNetwork fixtureNet,
            SeamlessUv seamless) {
        LayoutResolution measured = new LayoutResolution(fixtureNet, seamless, 1.0).build();
        double shortest = Double.MAX_VALUE;
        for (EmbeddedArc arc : fixtureNet.arcs) {
            if (arc.alive) {
                shortest = Math.min(shortest, measured.parametricLengthByArc[arc.arcId]);
            }
        }
        return shortest;
    }
}
