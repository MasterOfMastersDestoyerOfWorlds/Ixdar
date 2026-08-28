package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.FeatureEdgeSpan;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;
import ixdar.platform.Platforms;

/**
 * Applies the quantization to the T-mesh: every zero-quantized arc is
 * collapsed, unioning its two end nodes into one layout vertex, leaving the
 * positive arcs as the layout's separatrix skeleton.
 *
 * <p>
 * Also produces a per-face render buffer shaped like
 * {@code MotorcycleGraph.traceRecordsByFace}, clipped to the positive arcs.
 *
 * <p>
 * See also: Lyon 2021 Section 6
 */
public final class LayoutExtraction {

    public static final int FLOATS_PER_RECORD = 4;

    public final EmbeddedTMesh network;
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

    /** Filtered per-face render records covering only positive arcs. */
    public float[][] layoutRecordsByFace;

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
     * Collapse zero arcs, collect the positive skeleton, count remaining
     * T-junctions, and build the filtered render buffer.
     *
     * @return this, with all public products populated
     */
    public LayoutExtraction build() {
        ZeroArcCollapse collapse = new ZeroArcCollapse(
                network, quantization.quantizedLengthByArc).build();
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

        buildLayoutRecords();
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

    /**
     * Per-trace positive parametric ranges, then per-face records for every segment
     * stretch inside a positive range, clipped to the range and capped at the
     * renderer's records-per-face limit.
     */
    private void buildLayoutRecords() {
        int faceCount = network.sourceMesh.faceCount();
        layoutRecordsByFace = new float[faceCount][MotorcycleGraph.MAX_TRACE_RECORDS_PER_FACE
                * FLOATS_PER_RECORD];
        Map<Integer, List<double[]>> positiveRangesByTrace = new HashMap<>();
        for (EmbeddedArc arc : layoutArcs) {
            Trace trace = network.traces.get(arc.traceId);
            int chainPosition = trace.chainArcIds.indexOf(arc.arcId);
            if (chainPosition < 0) {
                continue;
            }
            positiveRangesByTrace.computeIfAbsent(arc.traceId, traceId -> new ArrayList<>())
                    .add(new double[] {
                            trace.chainNodeLengths.get(chainPosition),
                            trace.chainNodeLengths.get(chainPosition + 1) });
        }

        int[] recordCountByFace = new int[faceCount];
        for (Map.Entry<Integer, List<double[]>> entry : positiveRangesByTrace.entrySet()) {
            Trace trace = network.traces.get(entry.getKey());
            for (TraceSegment segment : trace.segments) {
                double segmentStart = segment.parametricLengthAtEntry;
                double segmentEnd = segmentStart + segment.parametricLength();
                for (double[] range : entry.getValue()) {
                    double clipStart = Math.max(segmentStart, range[0]);
                    double clipEnd = Math.min(segmentEnd, range[1]);
                    if (clipEnd <= clipStart) {
                        continue;
                    }
                    int slot = recordCountByFace[segment.activeFace];
                    if (slot >= MotorcycleGraph.MAX_TRACE_RECORDS_PER_FACE) {
                        break;
                    }
                    double entrySpan = segment.axis.holdsUConstant() ? segment.entryV : segment.entryU;
                    double spanA = entrySpan + segment.sign * (clipStart - segmentStart);
                    double spanB = entrySpan + segment.sign * (clipEnd - segmentStart);
                    float[] row = layoutRecordsByFace[segment.activeFace];
                    int base = slot * FLOATS_PER_RECORD;
                    row[base] = segment.axis.holdsUConstant() ? 1f : 0f;
                    row[base + 1] = (float) segment.isoValue;
                    row[base + 2] = (float) Math.min(spanA, spanB);
                    row[base + 3] = (float) Math.max(spanA, spanB);
                    recordCountByFace[segment.activeFace] = slot + 1;
                }
            }
        }
    }

}
