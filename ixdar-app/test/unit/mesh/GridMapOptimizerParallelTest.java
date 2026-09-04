package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapDofSystem;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapOptimizer;
import ixdar.geometry.mesh.quadlayout.gridmap.IntegerGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutPatchMaps;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutResolution;
import ixdar.geometry.mesh.quadlayout.gridmap.PatchRectangleMap;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;

/**
 * The Newton relaxation on the torus layout must stay flip-free and land on the
 * same energy whether its per-triangle assembly runs on one worker or many.
 */
class GridMapOptimizerParallelTest {

    /** Quads the shortest arc is sized to, so arcs carry interior grid points. */
    private static final int QUADS_ON_SHORTEST_ARC = 3;

    /**
     * Relative energy agreement demanded between the single- and multi-threaded
     * runs, loose only for the reduction's changed summation order.
     */
    private static final double ENERGY_RELATIVE_TOLERANCE = 1.0e-8;

    /** Iteration-count slack between the single- and multi-threaded runs. */
    private static final int ITERATION_SLACK = 3;

    @Test
    void multiThreadedRelaxationMatchesSingleThreaded() {
        GridMapOptimizer single = relaxTorus(1);
        GridMapOptimizer multi = relaxTorus(0);
        assertEquals(0, single.flippedTriangleCount, "single-threaded run folded triangles");
        assertEquals(0, multi.flippedTriangleCount, "multi-threaded run folded triangles");
        assertTrue(single.energyAfter <= single.energyBefore,
                "the relaxation raised the energy");
        assertEquals(single.energyAfter, multi.energyAfter,
                ENERGY_RELATIVE_TOLERANCE * Math.abs(single.energyAfter),
                "single- and multi-threaded runs disagree on the relaxed energy");
        assertTrue(Math.abs(single.iterationCount - multi.iterationCount) <= ITERATION_SLACK,
                "iteration counts diverged: " + single.iterationCount + " vs "
                        + multi.iterationCount);
    }

    /**
     * Builds the torus layout up to the grid map and relaxes it. The full
     * {@link GlobalGridMap#build()} also runs the extraction stages, which fail on
     * this fixture before the optimizer's results can be read, so only the grid
     * coordinates the optimizer needs are filled in.
     *
     * @param workerThreads worker threads for the Newton assembly, {@code 0} for
     *                      the optimizer's default
     * @return the solved optimizer, for its result fields
     */
    private GridMapOptimizer relaxTorus(int workerThreads) {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        NetworkContraction contraction = new NetworkContraction(fixtureNet);
        contraction.contract();
        contraction.conform();
        SeamlessUv seamless = new QuadLayoutEngine(fixtureNet.topology.sourceMesh, 0f)
                .buildSeamless();
        double targetEdgeLength = shortestArcLength(fixtureNet, seamless)
                / QUADS_ON_SHORTEST_ARC;
        LayoutPatchMaps patchMaps = new LayoutPatchMaps(fixtureNet, seamless,
                targetEdgeLength).build();
        IntegerGridMap frames = new IntegerGridMap(fixtureNet).build();
        GlobalGridMap gridMap = new GlobalGridMap(patchMaps, frames, seamless);
        gridMap.uvByPatchId = new double[fixtureNet.patches.size()][];
        double[] grid = new double[GlobalGridMap.GRID_COORDINATES];
        for (EmbeddedPatch patch : fixtureNet.patches) {
            if (!patch.alive) {
                continue;
            }
            PatchRectangleMap map = patchMaps.mapByPatchId[patch.patchId];
            double[] uv = new double[map.positions.length * GlobalGridMap.GRID_COORDINATES];
            for (int dense = 0; dense < map.positions.length; dense++) {
                frames.toGrid(patch.patchId, map.rectangleU[dense], map.rectangleV[dense], grid);
                uv[dense * GlobalGridMap.GRID_COORDINATES] = grid[0];
                uv[dense * GlobalGridMap.GRID_COORDINATES + 1] = grid[1];
            }
            gridMap.uvByPatchId[patch.patchId] = uv;
        }
        GridMapDofSystem dofs = new GridMapDofSystem(gridMap);
        dofs.seamCouplingPinned = false;
        dofs.nodeFreedomPinned = false;
        dofs.build();
        GridMapOptimizer optimizer = new GridMapOptimizer(dofs, seamless);
        if (workerThreads > 0) {
            optimizer.workerThreads = workerThreads;
        }
        return optimizer.build();
    }

    /**
     * The shortest live arc's parametric length, which sets a target edge length
     * no arc can round below one quad.
     *
     * @param tmesh    the conformed T-mesh
     * @param seamless the parametrization to measure in
     * @return the shortest arc's parametric length
     */
    private double shortestArcLength(ArcNetwork tmesh, SeamlessUv seamless) {
        LayoutResolution measured = new LayoutResolution(tmesh, seamless, 1.0).build();
        double shortest = Double.MAX_VALUE;
        for (EmbeddedArc arc : tmesh.arcs) {
            if (arc.alive) {
                shortest = Math.min(shortest, measured.parametricLengthByArc[arc.arcId]);
            }
        }
        return shortest;
    }
}
