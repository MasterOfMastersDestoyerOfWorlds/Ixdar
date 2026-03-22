package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.InputParameterDescriptor;
import ixdar.geometry.mesh.graph.InputParameterDescriptor.InputParameterKind;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

public class InputParameterDescriptorTest {

    @Test
    public void collectExtractsInputNodesAndFloatCurve() {
        String dsl = """
                f = input_float(name="sx", default=0.3, min=0.0, max=1.0)
                i = input_int(name="lv", default=3, min=0, max=8)
                b = input_boolean(name="on", default=true)
                c = float_curve(points="0,0,1,1")
                """;
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();
        List<InputParameterDescriptor> list = NodeGraphRuntime.collectInputParameters(ast);
        assertEquals(4, list.size());

        InputParameterDescriptor pf = list.get(0);
        assertEquals("f", pf.nodeId());
        assertEquals(InputParameterKind.FLOAT, pf.kind());
        assertEquals("sx", pf.name());
        assertEquals(0.3f, pf.floatDefault(), 1e-6f);

        InputParameterDescriptor pi = list.get(1);
        assertEquals(InputParameterKind.INT, pi.kind());
        assertEquals(3, pi.intDefault());

        InputParameterDescriptor pb = list.get(2);
        assertEquals(InputParameterKind.BOOLEAN, pb.kind());
        assertTrue(pb.booleanDefault());

        InputParameterDescriptor pc = list.get(3);
        assertEquals(InputParameterKind.CURVE, pc.kind());
        assertEquals("0,0,1,1", pc.curvePointsDefault());
    }

    @Test
    public void executeGraphOverridesInputFloatDefault() throws Exception {
        String dsl = """
                subdivisions_f = input_float(name="subdivisions", default=6.0, min=0.0, max=12.0)
                subdivisions = float_to_int(value=subdivisions_f.result, mode=ROUND)
                base_cube = cube(size=2.0)
                subdivided = subdivide_mesh(mesh=base_cube.mesh, levels=subdivisions.result)
                """;
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();
        NodeGraphRuntime rt = new NodeGraphRuntime();
        rt.registerAllFromAnnotationRegistry();

        MeshTopology low = rt.executeGraphToMesh(ast, "subdivided", "geometry", Map.of("subdivisions_f", 0.0f));
        MeshTopology high = rt.executeGraphToMesh(ast, "subdivided", "geometry", Map.of("subdivisions_f", 0.0f));
        assertEquals(low.vertexCount(), high.vertexCount());

        MeshTopology one = rt.executeGraphToMesh(ast, "subdivided", "geometry", Map.of("subdivisions_f", 1.0f));
        assertTrue(one.vertexCount() > low.vertexCount());
    }
}
