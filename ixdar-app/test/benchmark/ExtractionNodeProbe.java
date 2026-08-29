package benchmark;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.platform.Platforms;

/**
 * Localizes the extraction ring-step failure: runs the pipeline through the
 * quad grid, reports the failure, then dumps the probed node's arcs and every
 * incident patch's side structure from the contracted T-mesh. Pick the mesh
 * with {@code -Dbenchmark.off}.
 */
public final class ExtractionNodeProbe {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/botijo_in_tri.off";

    /** Node named by the extraction failure being probed. */
    private static final int PROBED_NODE = 266;

    /**
     * Runs to the quad grid, then dumps the probed node's fan and its incident
     * patches' sides, so the wedge shape the ring step trips on is visible.
     *
     * @throws IOException when the mesh file cannot be read
     */
    @Test
    public void probeExtractionNode() throws IOException {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        QuadLayoutEngine engine = new QuadLayoutEngine(mesh,
                QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);
        try {
            engine.buildQuadGrid();
            System.out.println("[probe] extraction completed clean");
        } catch (RuntimeException failure) {
            Platforms.log("[probe] extraction failed: %s%n", failure.getMessage());
        }
        ArcNetwork tmesh = engine.buildPatchMaps().tmesh;
        EmbeddedNode node = tmesh.nodes.get(PROBED_NODE);
        Platforms.log("[probe] node %d alive=%b critical=%b border=%b copyVertex=%d%n",
                PROBED_NODE, node.alive, node.critical, node.border, node.copyVertex);
        List<Integer> incidentPatches = new ArrayList<>();
        for (int arcId : tmesh.arcEndsByNode.get(PROBED_NODE)) {
            EmbeddedArc arc = tmesh.arcs.get(arcId);
            Platforms.log("[probe] arc %d alive=%b q=%d quads=%d nodes %d->%d flanks %d|%d"
                    + " resolved %d|%d path(%d)%n", arcId, arc.alive, arc.quantizedLength,
                    arc.quadCount, arc.startNodeId, arc.endNodeId, arc.leftPatchId,
                    arc.rightPatchId, tmesh.topology.resolvePatch(arc.leftPatchId),
                    tmesh.topology.resolvePatch(arc.rightPatchId),
                    arc.path.copyVertexPath.size());
            for (int patchId : new int[] { arc.leftPatchId, arc.rightPatchId }) {
                if (patchId != ArcNetwork.NONE && !incidentPatches.contains(patchId)) {
                    incidentPatches.add(patchId);
                }
            }
        }
        for (int patchId : incidentPatches) {
            EmbeddedPatch patch = tmesh.patches.get(patchId);
            Platforms.log("[probe] patch %d alive=%b sides arcs=%s nodes=%s%n", patchId,
                    patch.alive, patch.sideArcIds, patch.sideNodeIds);
        }
    }
}
