package unit.mesh.ringbench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.rings.RegionClusterFit;
import ixdar.nodegraph.nodes.data.SurfaceCreasesNode;
import ixdar.nodegraph.nodes.data.SurfaceMetricNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/** Scratch: the bench with the Voronoi split on and off, written to tmp/. */
class VoronoiBenchScratchTest {

    @Test
    void writeBench() throws IOException {
        StringBuilder out = new StringBuilder();
        for (double[] setting : new double[][] { { 1e5, 1024 }, { 1e5, 1e9 }, { 100, 1024 }, { 100, 1e9 } }) {
            boolean voronoi = true;
            double lengthWeight = setting[0];
            StringBuilder moves = new StringBuilder();
            RingFitBench.RingFit fit = drawn -> {
                GeometryBundle bundle = (GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY);
                String port = SurfaceCreasesNode.CREASES.name;
                RegionClusterFit clusterFit = new RegionClusterFit(bundle,
                        (SurfaceCreases) drawn.getNodeOutput(port, port));
                clusterFit.voronoi = voronoi;
                clusterFit.crossingWeight = setting[0];
                clusterFit.batchFaces = (int) Math.min(Integer.MAX_VALUE, setting[1]);
                clusterFit.run();
                moves.append(clusterFit.moves).append('/').append(clusterFit.voronoiMoves).append(' ');
                return clusterFit.fitted(bundle,
                        (SurfaceMetric) drawn.getNodeOutput("surface", SurfaceMetricNode.METRIC.name));
            };
            List<RingFitBench.Result> results = RingFitBench.run(fit);
            out.append("== crossing=").append(lengthWeight).append(" batch=").append(setting[1]).append(' ')
                    .append(results.stream().filter(RingFitBench.Result::passes).count()).append(" of ")
                    .append(results.size()).append("  moves/voronoi: ").append(moves).append('\n');
            for (RingFitBench.Result result : results) {
                out.append(result.passes() ? "PASS " : "fail ").append(result).append('\n');
            }
        }
        Files.createDirectories(Path.of("tmp"));
        Files.writeString(Path.of("tmp/craw-112-bench-voronoi.txt"), out.toString());
        Map.of();
    }
}
