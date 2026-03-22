package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.geometry.mesh.MeshCanonicalFingerprint;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.GraphValidator;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

public class ToolQuiltDslTest {

    @Test
    public void toolQuiltDslValidatesAndExecutes() throws Exception {
        String dsl = loadDsl();
        dsl = dsl.replace(
                "subdivisions_f = input_float(name=\"subdivisions\", default=6.0, min=0.0, max=12.0)",
                "subdivisions_f = input_float(name=\"subdivisions\", default=0.0, min=0.0, max=12.0)");

        List<PythonParser.ParsedNode> ast = parseDsl(dsl);
        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();
        List<String> errors = GraphValidator.validate(ast, registry);
        assertTrue(errors.isEmpty(), errors::toString);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        MeshTopology mesh = runtime.executeGraphToMesh(ast, "quilt_out", "geometry");
        assertNotNull(mesh);
        assertTrue(mesh.vertexCount() > 0);
    }

    @Test
    public void quiltDisplacesVerticesAlongNormals() throws Exception {
        String dsl = loadDsl();
        dsl = dsl.replace(
                "subdivisions_f = input_float(name=\"subdivisions\", default=6.0, min=0.0, max=12.0)",
                "subdivisions_f = input_float(name=\"subdivisions\", default=1.0, min=0.0, max=12.0)");
        dsl = dsl.replace(
                "stitches = input_boolean(name=\"stitches\", default=false)",
                "stitches = input_boolean(name=\"stitches\", default=true)");

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        List<PythonParser.ParsedNode> ast = parseDsl(dsl);
        MeshTopology mesh = runtime.executeGraphToMesh(ast, "quilt_out", "geometry");
        assertNotNull(mesh);

        int vc = mesh.vertexCount();
        assertTrue(vc > 24, "subdivisions=1 on a quad sphere should produce > 24 verts, got " + vc);

        Vector3f p = new Vector3f();
        float minR = Float.MAX_VALUE;
        float maxR = -Float.MAX_VALUE;
        for (int i = 0; i < vc; i++) {
            mesh.vertexPosition(mesh.vertexIdAt(i), p);
            float r = p.length();
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
        }
        float range = maxR - minR;
        assertTrue(range > 0.001f,
                "Quilted mesh should show displacement (range=" + range + "), "
                        + "minR=" + minR + " maxR=" + maxR);
    }

    @Test
    public void subdivisionLevelAffectsVertexCount() throws Exception {
        String baseDsl = loadDsl();
        baseDsl = baseDsl.replace(
                "stitches = input_boolean(name=\"stitches\", default=false)",
                "stitches = input_boolean(name=\"stitches\", default=true)");

        NodeGraphRuntime rt0 = new NodeGraphRuntime();
        rt0.registerAllFromAnnotationRegistry();
        String dsl0 = baseDsl.replace(
                "subdivisions_f = input_float(name=\"subdivisions\", default=6.0, min=0.0, max=12.0)",
                "subdivisions_f = input_float(name=\"subdivisions\", default=0.0, min=0.0, max=12.0)");
        MeshTopology mesh0 = rt0.executeGraphToMesh(parseDsl(dsl0), "quilt_out", "geometry");

        NodeGraphRuntime rt1 = new NodeGraphRuntime();
        rt1.registerAllFromAnnotationRegistry();
        String dsl1 = baseDsl.replace(
                "subdivisions_f = input_float(name=\"subdivisions\", default=6.0, min=0.0, max=12.0)",
                "subdivisions_f = input_float(name=\"subdivisions\", default=1.0, min=0.0, max=12.0)");
        MeshTopology mesh1 = rt1.executeGraphToMesh(parseDsl(dsl1), "quilt_out", "geometry");

        assertTrue(mesh1.vertexCount() > mesh0.vertexCount(),
                "subdivisions=1 (" + mesh1.vertexCount()
                        + ") should have more verts than subdivisions=0 (" + mesh0.vertexCount() + ")");
    }

    /**
     * Regression: {@code cube(size=2.0)} matches Blender's default 2m cube (-1..1), fixing absolute
     * displacement vs a 1m cube; fingerprint locks output for subdivisions=0 and default curves.
     */
    @Test
    public void quiltParityFingerprintAtDefaultQuiltParameters() throws Exception {
        String dsl = loadDsl();
        dsl = dsl.replace(
                "subdivisions_f = input_float(name=\"subdivisions\", default=6.0, min=0.0, max=12.0)",
                "subdivisions_f = input_float(name=\"subdivisions\", default=0.0, min=0.0, max=12.0)");

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        MeshTopology mesh = runtime.executeGraphToMesh(parseDsl(dsl), "quilt_out", "geometry");
        assertNotNull(mesh);
        String fp = MeshCanonicalFingerprint.sha256Hex(mesh);
        assertEquals("fdb11fd22217fc24a18b6b44f25e75fcb55fe57318e6e7a1a7b8c968b3a4cb3a", fp);
    }

    private static String loadDsl() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("dsl/tool_quilt.dsl")) {
            return new String(Objects.requireNonNull(in, "dsl/tool_quilt.dsl").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<PythonParser.ParsedNode> parseDsl(String dsl) {
        return new PythonParser(new PythonLexer(dsl)).parseGraph();
    }
}
