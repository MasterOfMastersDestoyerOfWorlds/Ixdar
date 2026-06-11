package ixdar.geometry.mesh.quadlayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.TMeshNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.TraceArc;
import ixdar.geometry.mesh.quadlayout.motorcycle.TraceSegment;

/**
 * Lyon 2021 §6 layout extraction, first half: apply the quantization to the
 * T-mesh by collapsing every zero-quantized arc (union of its two end nodes —
 * the parametric distance between them is zero, so they become one layout
 * vertex) and keeping the positively quantized arcs as the layout's
 * separatrix skeleton. Reports the remaining T-junction count; connecting or
 * splitting opposite sides to clear those T-junctions (the LCBK19 re-embedding
 * half of §6) operates on this collapsed complex.
 *
 * <p>
 * Also produces a per-face render buffer in the same format as
 * {@code MotorcycleGraph.traceRecordsByFace} but containing only the segment
 * stretches covered by positive arcs, clipped to their parametric ranges — the
 * final-output scene swaps it in to draw the layout instead of the full
 * trace web.
 */
public final class LayoutExtraction {

    public static final int FLOATS_PER_RECORD = 4;

    public final MotorcycleGraph motorcycleGraph;
    public final QuantizedMeshGrid quantization;

    /** Collapse-cluster index per T-mesh node id. */
    public int[] clusterByNode;

    /** Number of collapse clusters (candidate layout vertices). */
    public int clusterCount;

    /** Clusters containing at least one singularity node — the layout nodes. */
    public int singularClusterCount;

    /** Positive-quantized arcs, the layout's separatrix skeleton edges. */
    public List<TraceArc> layoutArcs;

    /** Clusters with exactly three incident positive arc-ends. */
    public int remainingTJunctionCount;

    /** Filtered per-face render records covering only positive arcs. */
    public float[][] layoutRecordsByFace;

    /**
     * Stores inputs for a §6 extraction over a solved quantization.
     *
     * @param quantization solved quantization whose T-mesh gets collapsed
     */
    public LayoutExtraction(QuantizedMeshGrid quantization) {
        this.quantization = quantization;
        this.motorcycleGraph = quantization.motorcycleGraph;
    }

    /**
     * Collapse zero arcs, collect the positive skeleton, count remaining
     * T-junctions, and build the filtered render buffer.
     *
     * @return this, with all public products populated
     */
    public LayoutExtraction build() {
        int nodeCount = motorcycleGraph.nodes.size();
        int[] parent = new int[nodeCount];
        for (int nodeId = 0; nodeId < nodeCount; nodeId++) {
            parent[nodeId] = nodeId;
        }
        for (TraceArc arc : motorcycleGraph.arcs) {
            if (quantization.quantizedLengthByArc[arc.arcId] == 0) {
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

        boolean[] clusterIsSingular = new boolean[clusterCount];
        for (TMeshNode node : motorcycleGraph.nodes) {
            if (node.type == TMeshNode.TYPE_SINGULARITY && node.singularityVertexId >= 0) {
                clusterIsSingular[clusterByNode[node.nodeId]] = true;
            }
        }
        singularClusterCount = 0;
        for (boolean singular : clusterIsSingular) {
            if (singular) {
                singularClusterCount++;
            }
        }

        layoutArcs = new ArrayList<>();
        int[] positiveEndsByCluster = new int[clusterCount];
        for (TraceArc arc : motorcycleGraph.arcs) {
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
        System.out.printf(
                "[layout] clusters=%d singularClusters=%d skeletonArcs=%d tJunctions=%d%n",
                clusterCount, singularClusterCount, layoutArcs.size(), remainingTJunctionCount);
        return this;
    }

    /**
     * Per-trace positive parametric ranges, then per-face records for every
     * segment stretch inside a positive range, clipped to the range and capped
     * at the renderer's records-per-face limit.
     */
    private void buildLayoutRecords() {
        int faceCount = motorcycleGraph.seamless.mesh.faceCount();
        layoutRecordsByFace = new float[faceCount][MotorcycleGraph.MAX_TRACE_RECORDS_PER_FACE
                * FLOATS_PER_RECORD];
        Map<Integer, List<double[]>> positiveRangesByTrace = new HashMap<>();
        for (TraceArc arc : layoutArcs) {
            Trace trace = motorcycleGraph.traces.get(arc.traceId);
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
            Trace trace = motorcycleGraph.traces.get(entry.getKey());
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
