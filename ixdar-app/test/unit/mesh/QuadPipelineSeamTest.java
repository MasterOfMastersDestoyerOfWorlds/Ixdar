package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.field.IntField;
import ixdar.nodegraph.nodes.data.SceneModelNode;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
import ixdar.geometry.mesh.quadlayout.QuadLayout;
import ixdar.geometry.mesh.quadlayout.extraction.PatchSurfaceGeometry;

/**
 * Characterization of the quad-layout stages {@link EmbeddedTMeshPipelineTest} does not cover, on
 * real pipeline data: cross field, seamless map, contraction, relaxation, extraction, patch
 * surfaces.
 *
 * <p>One layout run feeds every assertion. Point at another mesh with
 * {@code -DquadPipelineSeam.off=...}.
 */
class QuadPipelineSeamTest {

    private static final String OFF_PROPERTY = "quadPipelineSeam.off";
    private static final String DEFAULT_OFF =
            "test/resources/quadlayout/figure_7/sphere_base_in_tri.off";
    private static final double ALPHA_RADIANS = Math.toRadians(15.0);

    // Poincaré-Hopf for cross fields: quarter-index units per unit of Euler characteristic.
    private static final int QUARTER_INDICES_PER_EULER = 4;

    @Test
    void pipelineSeamsHoldOnRealData() throws Exception {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        HalfEdgeMesh mesh = loadMesh(offPath);
        int meshEuler = mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();

        NodeGraphRuntime graph = new NodeGraphRuntime(QuadLayoutNode.overSceneModel());
        graph.graphInputs.put(SceneModelNode.BINDING, GeometryBundle.ofMesh(mesh));
        graph.setOverride(QuadLayoutNode.ALPHA_DEGREES_OVERRIDE, (float) Math.toDegrees(ALPHA_RADIANS));
        QuadLayout layout = (QuadLayout) graph.pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.LAYOUT));

        int indexSum = 0;
        for (int v = 0; v < layout.crossField.singularityIndex4.length(); v++) {
            indexSum += layout.crossField.singularityIndex4.get(v);
        }
        assertEquals(QUARTER_INDICES_PER_EULER * meshEuler, indexSum,
                "cross-field singularity indices sum to the Euler characteristic");

        assertEquals(0, graph.pull("layout/seamless.flipped_triangles"),
                "the seamless parametrization flips no triangle");
        assertTrue(layout.seamless.injective, "the seamless parametrization is injective");

        int zeroArcs = 0;
        for (int arcId = 0; arcId < layout.tmesh.arcs.size(); arcId++) {
            if (layout.tmesh.arcs.get(arcId).alive
                    && layout.tmesh.arcs.get(arcId).quantizedLength == 0) {
                zeroArcs++;
            }
        }
        assertEquals(0, zeroArcs, "contraction left no live zero arc");

        assertTrue(layout.globalGrid.gridOptimizer.energyAfter
                        <= layout.globalGrid.gridOptimizer.energyBefore,
                "the Newton relaxation does not raise the energy");
        assertEquals(0, layout.globalGrid.isoSurfaceRelaxed.flippedFaceCount,
                "the relaxed grid map flips no face");

        assertEquals(layout.globalGrid.quadGridInitial.quadCount,
                layout.globalGrid.quadMesh.quadCount,
                "relaxation preserves the extracted quad count");
        assertEquals(meshEuler, layout.globalGrid.quadMesh.eulerCharacteristic(),
                "the extracted quad mesh closes to the surface's Euler characteristic");

        int livePatches = 0;
        for (int patchId = 0; patchId < layout.tmesh.patches.size(); patchId++) {
            if (layout.tmesh.patches.get(patchId).alive) {
                livePatches++;
            }
        }
        IntField patchIds = PatchSurfaceGeometry.patchIds(layout.patchSurfaces);
        Set<Integer> surfacePatchIds = new HashSet<>();
        for (int face = 0; face < patchIds.length(); face++) {
            surfacePatchIds.add(patchIds.get(face));
        }
        assertEquals(livePatches, surfacePatchIds.size(),
                "one patch-surface grid per live patch");
        assertTrue(surfacePatchIds.size() > 0, "the layout has patch surfaces");
    }

    /**
     * Loads a mesh file into a half-edge mesh.
     *
     * @param offPath path to the mesh file
     * @return the loaded half-edge mesh
     * @throws Exception propagated from the loader
     */
    private HalfEdgeMesh loadMesh(String offPath) throws Exception {
        HalfEdgeMesh mesh = (HalfEdgeMesh) MeshLoader.load(offPath);
        mesh.computeNormals();
        return mesh;
    }
}
