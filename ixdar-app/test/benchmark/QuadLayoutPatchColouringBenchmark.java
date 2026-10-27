package benchmark;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.QuadLayout;
import ixdar.geometry.mesh.quadlayout.extraction.PatchSurfaceGeometry;
import ixdar.graphics.render.color.Palettes;
import ixdar.graphics.render.color.RegionColouring;
import ixdar.nodegraph.field.IntField;
import ixdar.nodegraph.nodes.data.SceneModelNode;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * The quad-layout scene's patch fill on a real layout: colouring the T-mesh's patch adjacency
 * over the palette's categories, as the overlay does, never gives two patches across an arc
 * the same colour. Pick the mesh with {@code -Dbenchmark.off}.
 */
public final class QuadLayoutPatchColouringBenchmark {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_10/bolt_in_tri.off";

    /**
     * Builds the layout and checks every arc's two patches take different fill colours.
     *
     * @throws Exception when the mesh file cannot be read or a stage fails
     */
    @Test
    public void neighbouringPatchesNeverShareAFillColour() throws Exception {
        HalfEdgeMesh mesh = (HalfEdgeMesh) MeshLoader.load(System.getProperty(OFF_PROPERTY,
                DEFAULT_OFF));
        mesh.computeNormals();
        NodeGraphRuntime graph = new NodeGraphRuntime(QuadLayoutNode.overSceneModel());
        graph.graphInputs.put(SceneModelNode.BINDING, GeometryBundle.ofMesh(mesh));
        QuadLayout layout = (QuadLayout) graph.pull(
                QuadLayoutNode.sceneOutput(QuadLayoutNode.LAYOUT));
        IntField patchIds = PatchSurfaceGeometry.patchIds(layout.patchSurfaces);
        int[] facesByPatchId = new int[layout.tmesh.patches.size()];
        for (int face = 0; face < patchIds.length(); face++) {
            facesByPatchId[patchIds.get(face)]++;
        }
        int[][] neighbours = layout.tmesh.patchNeighbours();
        RegionColouring colouring = new RegionColouring();
        colouring.colour(neighbours, facesByPatchId, null, null, Palettes.DARK.categories);
        int pairs = 0;
        for (int patchId = 0; patchId < neighbours.length; patchId++) {
            for (int neighbour : neighbours[patchId]) {
                assertNotEquals(colouring.colourByNode[patchId], colouring.colourByNode[neighbour],
                        "patches " + patchId + " and " + neighbour);
                pairs++;
            }
        }
        assertTrue(pairs > 0, "the layout has no neighbouring patches");
    }
}
