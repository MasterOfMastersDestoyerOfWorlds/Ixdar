package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.nodes.selection.NearestVertexNode;

/**
 * The nearest_vertex selection node on a flat grid carrier: exactly the one
 * nearest vertex is selected, the index output names the same vertex, and a
 * tied nearest pick throws instead of picking by id.
 */
class NearestVertexNodeTest {

    /** Grid tiles per axis; vertices sit on integer coordinates in x and z. */
    private static final int TILES = 4;

    @Test
    void selectsExactlyTheNearestVertexAndItsIndex() {
        MeshTopology carrier = grid();
        MapNodeContext ctx = run(carrier, new Vector3Value(1.2f, 0f, -0.7f));

        BoolField selection = ctx.getOutput("selection", BoolField.class);
        int index = ctx.getOutput("index", Integer.class);
        assertEquals(carrier.vertexCount(), selection.length(),
                "the selection is a per-vertex field over the carrier");
        int selectedCount = 0;
        for (int i = 0; i < selection.length(); i++) {
            if (selection.get(i)) {
                selectedCount++;
                assertEquals(index, i, "the index output names the selected vertex");
            }
        }
        assertEquals(1, selectedCount, "exactly one vertex is selected");
        Vector3f position = carrier.vertexPosition(carrier.vertexIdAt(index), new Vector3f());
        assertTrue(position.distance(1f, 0f, -1f) < 1e-5f,
                "the vertex nearest (1.2, 0, -0.7) is the grid vertex at (1, 0, -1), got "
                        + position);
    }

    @Test
    void tiedNearestVertexThrows() {
        MeshTopology carrier = grid();

        IllegalStateException tie = assertThrows(IllegalStateException.class,
                () -> run(carrier, new Vector3Value(0.5f, 0f, 0f)),
                "a point midway between two vertices has no nearest pick");
        assertTrue(tie.getMessage().contains("move the point"),
                "the throw asks for a moved point: " + tie.getMessage());
    }

    /**
     * A triangulated flat grid carrier, vertices on integer x and z.
     *
     * @return the carrier mesh
     */
    private static MeshTopology grid() {
        GridMeshNode node = new GridMeshNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("u_tiles", TILES);
        ctx.setInput("v_tiles", TILES);
        ctx.setInput("triangulate", true);
        node.evaluate(ctx);
        return ctx.getOutput("mesh", GeometryBundle.class).mesh();
    }

    private static MapNodeContext run(MeshTopology carrier, Vector3Value point) {
        NearestVertexNode node = new NearestVertexNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("geometry", GeometryBundle.ofMesh(carrier));
        ctx.setInput("point", point);
        node.evaluate(ctx);
        return ctx;
    }
}
