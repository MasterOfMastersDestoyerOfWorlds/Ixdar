package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.CubeMeshNode;

public class CubeMeshNodeTest {

    @Test
    public void cubeNodeEvaluatesToExpectedMesh() {
        CubeMeshNode node = new CubeMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput("size", 2.0f);

        node.evaluate(context);

        HalfEdgeMesh mesh = context.getOutput("mesh", HalfEdgeMesh.class);
        assertEquals(8, mesh.vertexCount());
        assertEquals(12, mesh.edgeCount());
        assertEquals(6, mesh.faceCount());
        assertEquals(-1.0f, mesh.boundsMin(new Vector3f()).x, 0.0001f);
        assertEquals(1.0f, mesh.boundsMax(new Vector3f()).x, 0.0001f);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void meshNodeRegistryContainsCubeSupplier() throws Exception {
        Class<?> registryClass = Class.forName("ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes");
        Field mapField = registryClass.getField("MAP");
        Map<String, Supplier<? extends MeshNode>> map = (Map<String, Supplier<? extends MeshNode>>) mapField.get(null);

        assertTrue(map.containsKey("cube"));
        assertInstanceOf(CubeMeshNode.class, map.get("cube").get());
    }

    @Test
    public void portValidationRejectsMeshValueForFloatInput() {
        MeshNode floatInputNode = new MeshNode() {
            @Override
            public List<InputPort> inputs() {
                return List.of(new InputPort("value", PortType.FLOAT, 1.0f));
            }

            @Override
            public List<OutputPort> outputs() {
                return List.of(new OutputPort("result", PortType.FLOAT));
            }

            @Override
            public void evaluate(NodeContext ctx) {
            }
        };
        MapNodeContext context = new MapNodeContext(floatInputNode);
        HalfEdgeMesh mesh = HalfEdgeMesh.buildFromIndexedMesh(
                new float[] {
                        0f, 0f, 0f,
                        1f, 0f, 0f,
                        0f, 1f, 0f
                },
                new int[] { 0, 1, 2 });

        assertThrows(IllegalArgumentException.class, () -> context.setInput("value", mesh));
    }
}
