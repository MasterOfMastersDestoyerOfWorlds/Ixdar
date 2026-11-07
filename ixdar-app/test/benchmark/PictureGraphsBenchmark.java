package benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * The skull_from_pictures graph's stages written out for comparison with what the trace routes
 * wrote: the fitted outlines template, the base's size, the strokes and the sculpt's size. With
 * {@code benchmark.base} and {@code benchmark.template}, the strokes land on that base through that
 * template instead.
 *
 * <pre>
 * mvn test -Dtest=PictureGraphsBenchmark -Dbenchmark.out=tmp/mesh-94/after
 * </pre>
 */
public final class PictureGraphsBenchmark {

    private static final String GRAPH = "dsl/skull_from_pictures.dsl";

    private static final String OUT_PROPERTY = "benchmark.out";

    private static final String BASE_PROPERTY = "benchmark.base";

    private static final String TEMPLATE_PROPERTY = "benchmark.template";

    private static final String COMPARED_BASE_PROPERTY = "benchmark.compare";

    private static final String HULL_SVG_PROPERTY = "benchmark.hull.svg";

    private static final String SVG = "svg";

    private static final String REPORT = "report";

    private static final String SCULPT = "sculpt";

    private static final String BASE = "base";

    /**
     * Run the graph and write its stages under {@code benchmark.out}.
     *
     * @throws IOException when a result cannot be written or the base read
     */
    @Test
    public void skullFromPictures() throws IOException {
        Map<String, Object> overrides = new HashMap<>();
        String base = System.getProperty(BASE_PROPERTY, "");
        if (!base.isEmpty()) {
            overrides.put("strokes.base", MeshLoader.loadBundle(base));
            overrides.put("strokes.template", MeshLoader.resolve(System.getProperty(TEMPLATE_PROPERTY)).toString());
        }
        String hullSvg = System.getProperty(HULL_SVG_PROPERTY, "");
        if (!hullSvg.isEmpty()) {
            overrides.put("hull.svg", MeshLoader.resolve(hullSvg).toString());
            overrides.put("hull.outlines", GeometryBundle.empty());
        }
        long start = System.nanoTime();
        NodeGraphRuntime run = NodeGraphRuntime.executeResource(GRAPH, overrides);
        StringBuilder sizes = new StringBuilder(String.format(Locale.ROOT, "seconds %.1f%n",
                (System.nanoTime() - start) / 1e9));
        Path out = MeshLoader.resolve(System.getProperty(OUT_PROPERTY, "tmp/mesh-94/after"));
        Files.createDirectories(out);
        String compared = System.getProperty(COMPARED_BASE_PROPERTY, "");
        Vector3f here = new Vector3f();
        Vector3f there = new Vector3f();
        for (GraphNodeContext record : run.treeRecords()) {
            Map<String, Object> outputs = record.getOutputsSnapshot();
            if ("fit".equals(record.path)) {
                Files.write(out.resolve("outlines.svg"), ((String) outputs.get(SVG)).getBytes(StandardCharsets.UTF_8));
            }
            if ("strokes".equals(record.path)) {
                Files.write(out.resolve("strokes.svg"), ((String) outputs.get(SVG)).getBytes(StandardCharsets.UTF_8));
                Files.write(out.resolve("strokes_report.txt"),
                        ((String) outputs.get(REPORT)).getBytes(StandardCharsets.UTF_8));
            }
            if ((BASE.equals(record.path) || SCULPT.equals(record.path))
                    && outputs.get("geometry") instanceof GeometryBundle bundle) {
                MeshTopology mesh = bundle.mesh();
                sizes.append(String.format(Locale.ROOT, "%s vertices %d faces %d%n", record.path,
                        mesh.vertexCount(), mesh.faceCount()));
                if (BASE.equals(record.path) && !compared.isEmpty()) {
                    // Two-way nearest-vertex distance: the meshes' vertex orders need not agree.
                    MeshTopology other = MeshLoader.loadBundle(compared).mesh();
                    double farthest = 0.0;
                    for (MeshTopology[] pair : new MeshTopology[][] { { mesh, other }, { other, mesh } }) {
                        for (int vertex = 0; vertex < pair[0].vertexCount(); vertex++) {
                            pair[0].vertexPosition(pair[0].vertexIdAt(vertex), here);
                            double nearest = Double.MAX_VALUE;
                            for (int candidate = 0; candidate < pair[1].vertexCount(); candidate++) {
                                pair[1].vertexPosition(pair[1].vertexIdAt(candidate), there);
                                nearest = Math.min(nearest, here.distanceSquared(there));
                            }
                            farthest = Math.max(farthest, Math.sqrt(nearest));
                        }
                    }
                    sizes.append(String.format(Locale.ROOT, "base against %s: %d vertices there, Hausdorff over "
                            + "vertices %.3g%n", compared, other.vertexCount(), farthest));
                }
            }
            if (SCULPT.equals(record.path)) {
                Files.write(out.resolve("sculpt_report.txt"),
                        ((String) outputs.get(REPORT)).getBytes(StandardCharsets.UTF_8));
            }
        }
        Files.write(out.resolve("sizes.txt"), sizes.toString().getBytes(StandardCharsets.UTF_8));
    }
}
