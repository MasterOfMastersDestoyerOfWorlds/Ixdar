package benchmark;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.platform.Platforms;

/**
 * Attributes the dense-mesh edge splits of DenseMeshFewSplitsTest to the
 * operator steps that make them: steps the scale-8 contraction and logs every
 * step that grew the working copy. Run explicitly; package {@code benchmark}
 * is outside the default test globs.
 */
public final class DenseSplitAttributionProbe {

    @Test
    public void attributeSplits() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/scaled_torus.dsl", Map.of(
                "carrier.major_segments", 12 * 8, "carrier.minor_segments", 8 * 8));
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        NetworkContraction contraction = new NetworkContraction(fixtureNet);
        fixtureNet.labelPatchCovers();
        int initialVertices = fixtureNet.topology.copy.vertexCount();
        int vertices = initialVertices;
        int step = 0;
        while (true) {
            String applied = contraction.contractStep();
            if (applied == null) {
                break;
            }
            step++;
            int now = fixtureNet.topology.copy.vertexCount();
            if (now != vertices) {
                Platforms.log("[split-probe] step %d: +%d vertices | %s%n",
                        step, now - vertices, applied);
                vertices = now;
            }
        }
        Platforms.log("[split-probe] total steps=%d total splits=%d%n", step,
                fixtureNet.topology.copy.vertexCount() - initialVertices);
    }
}
