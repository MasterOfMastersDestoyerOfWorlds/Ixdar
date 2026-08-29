package benchmark;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.platform.Platforms;

/**
 * Runs the contraction operator by operator until the fixed point or the first
 * diagnostic, reporting how far it got — the ground truth the rewind workflow's
 * C press sees. Pick the mesh with {@code -Dbenchmark.off}.
 */
public final class ContractProgressProbe {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/botijo_in_tri.off";

    /** Operators between progress lines. */
    private static final int PROGRESS_INTERVAL = 100;

    /**
     * Steps the contraction to the end or the first thrown diagnostic, printing the
     * operator count either way.
     *
     * @throws IOException when the mesh file cannot be read
     */
    @Test
    public void probeContractProgress() throws IOException {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);
        ArcNetwork tmesh = engine.buildTMesh();
        NetworkContraction contraction = new NetworkContraction(tmesh);
        tmesh.labelPatchCovers();
        int op = 0;
        try {
            while (contraction.contractStep() != null) {
                op++;
                if (op % PROGRESS_INTERVAL == 0) {
                    Platforms.log("[probe] %d operators applied%n", op);
                }
            }
            Platforms.log("[probe] contraction reached its fixed point after %d operators"
                    + " (collapses=%d patchCollapses=%d patchSplits=%d)%n", op,
                    contraction.arcCollapseCount, contraction.patchCollapseCount, contraction.patchSplitCount);
        } catch (RuntimeException failure) {
            Platforms.log("[probe] failed after %d operators: %s%n", op,
                    failure.getMessage());
        }
    }
}
