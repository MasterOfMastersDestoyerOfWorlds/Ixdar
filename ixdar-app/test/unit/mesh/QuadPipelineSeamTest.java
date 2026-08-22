package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.Singularity;

/**
 * Characterization of the quad-layout pipeline at the seams the 7.2 node
 * migration will cut, on real pipeline data: cross field, seamless
 * parametrization, embedded contract+conform, Newton relaxation, QEx
 * extraction, and patch surfaces. {@link EmbeddedTMeshPipelineTest} covers the
 * motorcycle/quantization/carve seams; this test pins down the stages that had
 * no direct coverage so the migration can prove it changed nothing.
 *
 * <p>One engine run feeds every assertion. Point at another mesh with
 * {@code -DquadPipelineSeam.off=...}.
 */
class QuadPipelineSeamTest {

    private static final String OFF_PROPERTY = "quadPipelineSeam.off";
    private static final String DEFAULT_OFF =
            "test/resources/quadlayout/figure_7/sphere_base_in_tri.off";
    private static final double ALPHA_RADIANS = Math.toRadians(15.0);

    /** Quarter-index units per unit of Euler characteristic (Poincaré-Hopf for cross fields). */
    private static final int QUARTER_INDICES_PER_EULER = 4;

    @Test
    void pipelineSeamsHoldOnRealData() throws Exception {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        HalfEdgeMesh mesh = loadMesh(offPath);
        int meshEuler = mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();

        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, (float) ALPHA_RADIANS);
        engine.buildPatchSurfaces();

        int indexSum = 0;
        for (Singularity singularity : engine.crossField.singularities) {
            indexSum += singularity.index4();
        }
        assertEquals(QUARTER_INDICES_PER_EULER * meshEuler, indexSum,
                "cross-field singularity indices sum to the Euler characteristic");

        assertEquals(0, engine.seamlessMetrics.flippedTriangleCount,
                "the seamless parametrization flips no triangle");
        assertTrue(engine.seamless.injective, "the seamless parametrization is injective");

        assertTrue(engine.contracted, "the surfaces build ran the contraction");
        assertFalse(engine.conforming,
                "characterized: the engine never conforms; only tests and the scene call"
                        + " conform() (see REFACTOR-PLAN 6.12)");
        assertEquals(0, countZeroArcs(engine), "contraction left no live zero arc");

        assertTrue(engine.globalGrid.gridOptimizer.energyAfter
                        <= engine.globalGrid.gridOptimizer.energyBefore,
                "the Newton relaxation does not raise the energy");
        assertEquals(0, engine.globalGrid.isoSurfaceRelaxed.flippedFaceCount,
                "the relaxed grid map flips no face");

        assertEquals(engine.globalGrid.quadGridInitial.quadCount,
                engine.globalGrid.quadMesh.quadCount,
                "relaxation preserves the extracted quad count");
        assertEquals(meshEuler, engine.globalGrid.quadMesh.eulerCharacteristic(),
                "the extracted quad mesh closes to the surface's Euler characteristic");

        int livePatches = 0;
        for (int patchId = 0; patchId < engine.tmesh.patches.size(); patchId++) {
            if (engine.tmesh.patches.get(patchId).alive) {
                livePatches++;
            }
        }
        assertEquals(livePatches, engine.patchSurfaces.patches.size(),
                "one patch surface per live patch");
        assertTrue(engine.patchSurfaces.patches.size() > 0, "the layout has patch surfaces");
    }

    /**
     * Loads a mesh file into a half-edge mesh.
     *
     * @param offPath path to the mesh file
     * @return the loaded half-edge mesh
     * @throws Exception propagated from the loader
     */
    private HalfEdgeMesh loadMesh(String offPath) throws Exception {
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        return HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
    }

    /**
     * The number of live zero-length arcs left in the engine's T-mesh.
     *
     * @param engine the pipeline, built through contraction
     * @return count of live arcs whose quantized length is zero
     */
    private int countZeroArcs(QuadLayoutEngine engine) {
        int zero = 0;
        for (int arcId = 0; arcId < engine.tmesh.arcs.size(); arcId++) {
            if (engine.tmesh.arcs.get(arcId).alive
                    && engine.tmesh.arcs.get(arcId).quantizedLength == 0) {
                zero++;
            }
        }
        return zero;
    }
}
