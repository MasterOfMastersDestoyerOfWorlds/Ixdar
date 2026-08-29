package benchmark;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.embedding.records.PatchCorridor;
import ixdar.platform.Platforms;

/**
 * Measures how far the contraction's maintained patch-cover labels drift from
 * the covers a fresh flood finds, on a real mesh; pick it with
 * {@code -Dbenchmark.off}.
 *
 * <p>
 * The drags are restricted to the two patches flanking the arc being dragged,
 * and the ones with no route there escape to an unrestricted search. This
 * reports what the labels look like at the end of the contraction, which is the
 * evidence for what a fixture must reproduce.
 */
public final class PatchCoverDriftProbe {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/botijo_in_tri.off";

    /** Worst-drifting patches to name individually. */
    private static final int REPORTED_PATCHES = 8;

    /** Thieving patches to name per drifting patch. */
    private static final int REPORTED_THIEVES = 3;

    /**
     * Contracts the mesh's T-mesh and reports its escapes, orphaned labels and
     * per-patch cover drift.
     *
     * @throws IOException when the mesh file cannot be read
     */
    @Test
    public void reportCoverDrift() throws IOException {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);
        ArcNetwork tmesh = engine.buildTMesh();
        NetworkContraction contraction = new NetworkContraction(tmesh);
        contraction.contract();

        EmbeddedMeshTopology topology = tmesh.topology;
        HalfEdgeMesh copy = topology.copy;
        Platforms.log("[drift] %s blocked=%d unrestrictedExtensions=%d%n", offPath,
                contraction.collapseArc.blockedDragCount,
                contraction.extendTJunction.unrestrictedExtensionCount);

        int unlabeled = 0;
        int orphaned = 0;
        Map<Integer, Integer> labeledCountByPatch = new HashMap<>();
        for (int index = 0; index < copy.faceCount(); index++) {
            int faceId = copy.faceIdAt(index);
            int label = topology.resolvePatch(topology.patchLabelOf(faceId));
            if (label == EmbeddedMeshTopology.UNCLAIMED) {
                unlabeled++;
                continue;
            }
            if (!tmesh.patches.get(label).alive) {
                orphaned++;
                continue;
            }
            labeledCountByPatch.merge(label, 1, Integer::sum);
        }
        Platforms.log("[drift] faces=%d unlabeled=%d orphanedToDeadPatch=%d%n",
                copy.faceCount(), unlabeled, orphaned);

        PatchCorridor corridor = new PatchCorridor(tmesh);
        List<int[]> drifting = new ArrayList<>();
        Map<Integer, Map<Integer, Integer>> thievesByPatch = new HashMap<>();
        int alivePatches = 0;
        int stolenTotal = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            alivePatches++;
            IntIdList trueFaces = corridor.patchFaces(patch.patchId);
            Map<Integer, Integer> thieves = new HashMap<>();
            for (int index = 0; index < trueFaces.size(); index++) {
                int label = topology.resolvePatch(topology.patchLabelOf(trueFaces.get(index)));
                if (label != patch.patchId) {
                    thieves.merge(label, 1, Integer::sum);
                }
            }
            int stolen = 0;
            for (int count : thieves.values()) {
                stolen += count;
            }
            stolenTotal += stolen;
            if (stolen > 0) {
                drifting.add(new int[] { patch.patchId, trueFaces.size(),
                        labeledCountByPatch.getOrDefault(patch.patchId, 0), stolen });
                thievesByPatch.put(patch.patchId, thieves);
            }
        }
        Platforms.log("[drift] alivePatches=%d driftingPatches=%d stolenFaces=%d%n",
                alivePatches, drifting.size(), stolenTotal);

        drifting.sort(Comparator.comparingInt((int[] entry) -> -entry[3]));
        for (int index = 0; index < Math.min(REPORTED_PATCHES, drifting.size()); index++) {
            int[] entry = drifting.get(index);
            Platforms.log("[drift]   patch %d: trueFaces=%d labeledFaces=%d stolen=%d by %s%n",
                    entry[0], entry[1], entry[2], entry[3], worstThieves(thievesByPatch.get(entry[0])));
        }
    }

    /**
     * The patches holding the most of one patch's faces, as {@code patch=count}
     * text.
     *
     * @param thieves count of stolen faces per labelling patch
     * @return a short description of the biggest thieves
     */
    private String worstThieves(Map<Integer, Integer> thieves) {
        List<Map.Entry<Integer, Integer>> ranked = new ArrayList<>(thieves.entrySet());
        ranked.sort(Comparator.comparingInt((Map.Entry<Integer, Integer> entry) -> -entry.getValue()));
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < Math.min(REPORTED_THIEVES, ranked.size()); index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(ranked.get(index).getKey()).append('=').append(ranked.get(index).getValue());
        }
        return text.toString();
    }
}
