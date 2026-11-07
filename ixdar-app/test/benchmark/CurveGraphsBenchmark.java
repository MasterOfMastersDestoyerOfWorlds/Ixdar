package benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * Every geometry output of every node in the curve DSLs, one text file each; a closed curve's
 * first point is written again last, so {@code diff -r} compares runs by shape.
 *
 * <pre>
 * mvn test -Dtest=CurveGraphsBenchmark -Dbenchmark.out=tmp/mesh-93/before
 * </pre>
 */
public final class CurveGraphsBenchmark {

    private static final String OUT_PROPERTY = "benchmark.out";

    private static final String DEFAULT_OUT = "tmp/mesh-93/run";

    private static final String[] CURVE_GRAPHS = { "dsl/birail_loft_ribbon.dsl", "dsl/curve_sweep_tube.dsl",
        "dsl/neck_armor.dsl", "dsl/tool_quilt.dsl", "dsl/varying_tube_test.dsl",
        "dsl/fixtures/torus_loop_through_points.dsl", "dsl/fixtures/fertility_rings.dsl" };

    private static final int XYZ = 3;

    private static final String POINT_FORMAT = " %.9g %.9g %.9g%n";

    /**
     * Each curve graph's node outputs, written under {@code benchmark.out}.
     *
     * @throws IOException when a result cannot be written
     */
    @Test
    public void curveGraphs() throws IOException {
        Vector3f position = new Vector3f();
        for (String graph : CURVE_GRAPHS) {
            NodeGraphRuntime run = NodeGraphRuntime.executeResource(graph, Map.of());
            String graphName = Path.of(graph).getFileName().toString().replace(".dsl", "");
            for (GraphNodeContext record : run.treeRecords()) {
                for (Map.Entry<String, Object> output : record.getOutputsSnapshot().entrySet()) {
                    if (!(output.getValue() instanceof GeometryBundle bundle)) {
                        continue;
                    }
                    String file = graphName + "/" + record.path.replace('/', '.') + "." + output.getKey();
                    MeshTopology mesh = bundle.mesh();
                    if (mesh != null) {
                        StringBuilder text = new StringBuilder();
                        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
                            mesh.vertexPosition(mesh.vertexIdAt(activeVertex), position);
                            text.append('v').append(String.format(Locale.ROOT, POINT_FORMAT, position.x, position.y,
                                    position.z));
                        }
                        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
                            int faceId = mesh.faceIdAt(activeFace);
                            text.append('f');
                            for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                                text.append(' ').append(mesh.faceVertexAt(faceId, corner));
                            }
                            text.append('\n');
                        }
                        writeText(file + ".mesh", text.toString());
                    }
                    if (bundle.slots().get(CurveGeometry.SLOT) instanceof CurveGeometry curve) {
                        StringBuilder text = new StringBuilder();
                        for (int index = 0; index < curve.curveCount(); index++) {
                            text.append("curve ").append(index).append('\n');
                            float[] drawn = curve.drawnPositions(index);
                            for (int point = 0; point < drawn.length / XYZ; point++) {
                                text.append('p').append(String.format(Locale.ROOT, POINT_FORMAT, drawn[point * XYZ],
                                        drawn[point * XYZ + 1], drawn[point * XYZ + 2]));
                            }
                        }
                        writeText(file + ".curve", text.toString());
                    }
                }
            }
        }
    }

    private static void writeText(String relative, String text) throws IOException {
        Path file = MeshLoader.resolve(System.getProperty(OUT_PROPERTY, DEFAULT_OUT)).resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
