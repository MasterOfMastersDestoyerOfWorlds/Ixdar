package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.FeatureEdgeSpan;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.platform.Platforms;

/**
 * Applies the quantization to the T-mesh: every zero-quantized arc is
 * collapsed, unioning its two end nodes into one layout vertex, leaving the
 * positive arcs as the layout's separatrix skeleton.
 *
 * <p>
 * See also: Lyon 2021 Section 6
 */
public final class LayoutExtraction {

    public final ArcNetwork network;
    public final QuantizedMeshGrid quantization;

    /** Collapse-cluster index per T-mesh node id. */
    public int[] clusterByNode;

    /** Number of collapse clusters (candidate layout vertices). */
    public int clusterCount;

    /** Clusters containing at least one singularity node — the layout nodes. */
    public int singularClusterCount;

    /** Positive-quantized arcs, the layout's separatrix skeleton edges. */
    public List<EmbeddedArc> layoutArcs;

    /** Clusters with exactly three incident positive arc-ends. */
    public int remainingTJunctionCount;

    /**
     * Feature edges whose covering arc quantized to zero (demoted from barrier).
     */
    public int featureEdgesOpenCount;

    /**
     * Stores inputs for a §6 extraction over a solved quantization.
     *
     * @param quantization solved quantization whose T-mesh gets collapsed
     */
    public LayoutExtraction(QuantizedMeshGrid quantization) {
        this.quantization = quantization;
        this.network = quantization.network;
    }

    /**
     * Read the clusters off the solve's final zero-arc collapse, collect the positive
     * skeleton and count remaining T-junctions and opened feature edges.
     *
     * @return this, with all public products populated
     */
    public LayoutExtraction build() {
        ZeroArcCollapse collapse = quantization.collapse;
        clusterByNode = collapse.clusterByNode;
        clusterCount = collapse.clusterCount;
        singularClusterCount = collapse.singularClusterCount;

        layoutArcs = new ArrayList<>();
        int[] positiveEndsByCluster = new int[clusterCount];
        for (EmbeddedArc arc : network.arcs) {
            if (quantization.quantizedLengthByArc[arc.arcId] > 0) {
                layoutArcs.add(arc);
                positiveEndsByCluster[clusterByNode[arc.startNodeId]]++;
                positiveEndsByCluster[clusterByNode[arc.endNodeId]]++;
            }
        }
        remainingTJunctionCount = 0;
        for (int cluster = 0; cluster < clusterCount; cluster++) {
            if (positiveEndsByCluster[cluster] == 3) {
                remainingTJunctionCount++;
            }
        }

        countOpenFeatureEdges();
        Platforms.log(
                "[layout] clusters=%d singularClusters=%d skeletonArcs=%d tJunctions=%d"
                        + " featureEdgesOpen=%d%n",
                clusterCount, singularClusterCount, layoutArcs.size(), remainingTJunctionCount, featureEdgesOpenCount);
        return this;
    }

    /**
     * Counts the feature edges the layout no longer holds: the arc covering the
     * feature span quantized to zero, so it collapses and stops cutting the surface
     * there.
     */
    private void countOpenFeatureEdges() {
        featureEdgesOpenCount = 0;
        for (Map.Entry<Integer, FeatureEdgeSpan> entry : network.featureSpanByEdgeId.entrySet()) {
            FeatureEdgeSpan span = entry.getValue();
            Trace trace = network.traces.get(span.traceId);
            int arcId = trace.arcAtParametricLength(0.5 * (span.entryLength + span.exitLength));
            if (arcId < 0 || quantization.quantizedLengthByArc[arcId] == 0) {
                featureEdgesOpenCount++;
            }
        }
    }

}
