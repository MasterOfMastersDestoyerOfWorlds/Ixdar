package unit.mesh;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * Manual-ish geometry report: {@code mvn test -pl ixdar-app -Dtest=PetalMeshGeometryReportTest - Dixdar.petal.report=true}
 */
public class PetalMeshGeometryReportTest {

    @Test
    public void printPetalMeshGeometryReport() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty("ixdar.petal.report", "false")));

        String dsl = loadDsl();
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();
        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        MeshTopology mesh = runtime.executeGraphToMesh(ast, "petal", "geometry");

        Vector3f min = mesh.boundsMin(new Vector3f());
        Vector3f max = mesh.boundsMax(new Vector3f());
        int boundaryEdges = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            if (mesh.isBoundaryEdge(mesh.edgeIdAt(i))) {
                boundaryEdges++;
            }
        }
        int v = mesh.vertexCount();
        int e = mesh.edgeCount();
        int f = mesh.faceCount();
        int chi = v - e + f;

        System.out.println("=== petal.dsl result mesh ===");
        System.out.println("vertices=" + v + " edges=" + e + " faces=" + f + " eulerChi=" + chi + " boundaryEdges=" + boundaryEdges);
        System.out.println("AABB min " + fmt(min) + " max " + fmt(max));
        System.out.println("extents X=" + (max.x - min.x) + " Y=" + (max.y - min.y) + " Z=" + (max.z - min.z));
        System.out.println("center " + fmt(mesh.center(new Vector3f())) + " radius=" + mesh.radius());

        List<Vector3f> positions = new ArrayList<>(v);
        Vector3f p = new Vector3f();
        for (int i = 0; i < v; i++) {
            int vid = mesh.vertexIdAt(i);
            positions.add(new Vector3f(mesh.vertexPosition(vid, p)));
        }

        positions.sort(Comparator.comparingDouble(a -> a.z));
        Vector3f stemApprox = positions.get(0);
        Vector3f tipApprox = positions.get(positions.size() - 1);
        System.out.println("extreme-Z verts (approx stem / tip): stem " + fmt(stemApprox) + " tip " + fmt(tipApprox));

        Vector3f nearCenterAtTip = positions.stream().filter(pt -> pt.z > max.z - 1e-5f).min(Comparator.comparingDouble(a -> Math.abs(a.x)))
                .orElse(tipApprox);
        Vector3f nearCenterAtStem = positions.stream().filter(pt -> pt.z < min.z + 1e-5f).min(Comparator.comparingDouble(a -> Math.abs(a.x)))
                .orElse(stemApprox);
        System.out.println("centerline @ Z≈tip " + fmt(nearCenterAtTip) + " @ Z≈stem " + fmt(nearCenterAtStem));
        System.out.println("tip-stem delta Y (centerline): " + (nearCenterAtTip.y - nearCenterAtStem.y));

        float midZ = 0.5f * (min.z + max.z);
        List<Vector3f> midSlice = new ArrayList<>();
        float zBand = (max.z - min.z) * 0.02f + 1e-6f;
        for (Vector3f pt : positions) {
            if (Math.abs(pt.z - midZ) < zBand) {
                midSlice.add(pt);
            }
        }
        if (!midSlice.isEmpty()) {
            float minX = midSlice.stream().map(a -> a.x).min(Float::compare).orElse(0f);
            float maxX = midSlice.stream().map(a -> a.x).max(Float::compare).orElse(0f);
            float maxY = midSlice.stream().map(a -> a.y).max(Float::compare).orElse(0f);
            float minY = midSlice.stream().map(a -> a.y).min(Float::compare).orElse(0f);
            System.out.println("mid-length slice (~z=" + midZ + "): X in [" + minX + "," + maxX + "] width=" + (maxX - minX)
                    + " Y in [" + minY + "," + maxY + "]");
        }

    }

    private static String fmt(Vector3f v) {
        return String.format("(%.5f, %.5f, %.5f)", v.x, v.y, v.z);
    }

    private static String loadDsl() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("dsl/petal.dsl")) {
            return new String(Objects.requireNonNull(in, "dsl/petal.dsl").readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
