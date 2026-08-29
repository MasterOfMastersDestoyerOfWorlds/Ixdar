package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;

/**
 * Union-find collapse of T-mesh nodes connected by zero-quantized arcs: a path of
 * zero arcs merges all its nodes into one layout vertex. A cluster containing two
 * or more distinct singularity vertices is a quantization validity violation.
 *
 * <p>See also: Lyon 2021 Section 6
 */
public final class ZeroArcCollapse {

    public final ArcNetwork network;
    public final int[] quantizedLengthByArc;

    /** Collapse-cluster index per T-mesh node id. */
    public int[] clusterByNode;

    /** Number of collapse clusters (candidate layout vertices). */
    public int clusterCount;

    /** Clusters containing at least one singularity node. */
    public int singularClusterCount;

    /**
     * Distinct singularity vertex ids per cluster that merged two or more of them —
     * each entry is one validity violation.
     */
    public List<List<Integer>> mergedSingularityVertexIdsByCluster;

    /**
     * Stores inputs for a zero-arc collapse.
     *
     * @param network      built T-mesh whose nodes get clustered
     * @param quantizedLengthByArc solved quantization, one integer per arc id
     */
    public ZeroArcCollapse(ArcNetwork network, int[] quantizedLengthByArc) {
        this.network = network;
        this.quantizedLengthByArc = quantizedLengthByArc;
    }

    /**
     * Union nodes across zero arcs, then scan clusters for merged singularities.
     *
     * @return this, with all public products populated
     */
    public ZeroArcCollapse build() {
        int nodeCount = network.nodes.size();
        int[] parent = new int[nodeCount];
        for (int nodeId = 0; nodeId < nodeCount; nodeId++) {
            parent[nodeId] = nodeId;
        }
        for (EmbeddedArc arc : network.arcs) {
            if (quantizedLengthByArc[arc.arcId] == 0) {
                union(parent, arc.startNodeId, arc.endNodeId);
            }
        }
        clusterByNode = new int[nodeCount];
        int[] clusterOfRoot = new int[nodeCount];
        Arrays.fill(clusterOfRoot, -1);
        clusterCount = 0;
        for (int nodeId = 0; nodeId < nodeCount; nodeId++) {
            int root = find(parent, nodeId);
            if (clusterOfRoot[root] < 0) {
                clusterOfRoot[root] = clusterCount++;
            }
            clusterByNode[nodeId] = clusterOfRoot[root];
        }

        List<Set<Integer>> singularityVertexIdsByCluster = new ArrayList<>(clusterCount);
        for (int cluster = 0; cluster < clusterCount; cluster++) {
            singularityVertexIdsByCluster.add(new LinkedHashSet<>());
        }
        for (EmbeddedNode node : network.nodes) {
            if (node.critical && node.vertexId >= 0) {
                singularityVertexIdsByCluster.get(clusterByNode[node.nodeId])
                        .add(node.vertexId);
            }
        }
        singularClusterCount = 0;
        mergedSingularityVertexIdsByCluster = new ArrayList<>();
        for (Set<Integer> vertexIds : singularityVertexIdsByCluster) {
            if (!vertexIds.isEmpty()) {
                singularClusterCount++;
            }
            if (vertexIds.size() > 1) {
                mergedSingularityVertexIdsByCluster.add(new ArrayList<>(vertexIds));
            }
        }
        return this;
    }

    private int find(int[] parent, int nodeId) {
        int root = nodeId;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[nodeId] != root) {
            int next = parent[nodeId];
            parent[nodeId] = root;
            nodeId = next;
        }
        return root;
    }

    private void union(int[] parent, int nodeA, int nodeB) {
        int rootA = find(parent, nodeA);
        int rootB = find(parent, nodeB);
        if (rootA != rootB) {
            parent[rootB] = rootA;
        }
    }
}
