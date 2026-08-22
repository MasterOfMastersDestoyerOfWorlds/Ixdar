package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.nodes.data.LoadMeshNode;

/**
 * The {@code load_mesh} node loads a mesh file through its port interface and
 * matches what {@link MeshLoader} returns directly.
 */
class LoadMeshNodeTest {

    private static final String OFF_PATH =
            "test/resources/quadlayout/figure_7/sphere_base_in_tri.off";

    @Test
    void loadsAnOffFileIntoAGeometryBundle() throws Exception {
        ArrayMesh expected = MeshLoader.load(OFF_PATH);

        LoadMeshNode node = new LoadMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(LoadMeshNode.PATH.name, OFF_PATH);
        node.evaluate(context);
        GeometryBundle bundle = context.getOutput(LoadMeshNode.GEOMETRY.name, GeometryBundle.class);

        assertEquals(expected.vertexCount(), bundle.mesh().vertexCount(),
                "the node loads the same vertices as the loader");
        assertEquals(expected.faceCount(), bundle.mesh().faceCount(),
                "the node loads the same faces as the loader");
        assertTrue(bundle.mesh().vertexCount() > 0, "the loaded mesh is not empty");
    }

    @Test
    void emptyPathReturnsAnEmptyBundle() {
        LoadMeshNode node = new LoadMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        node.evaluate(context);
        GeometryBundle bundle = context.getOutput(LoadMeshNode.GEOMETRY.name, GeometryBundle.class);

        assertEquals(0, bundle.mesh().vertexCount(), "no path loads nothing");
    }

    @Test
    void missingFileThrows() {
        LoadMeshNode node = new LoadMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(LoadMeshNode.PATH.name, "no/such/mesh.off");

        assertThrows(UncheckedIOException.class, () -> node.evaluate(context));
    }
}
