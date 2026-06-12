package ixdar.geometry.mesh.quadlayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.TMeshNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.TraceArc;

/**
 * Measures the separatrix deviation d of the quantized layout (LCK21a Table 1
 * columns d_mean / d_max): for every singularity trace whose chain reaches a
 * collapse cluster containing another singularity, the implied separatrix
 * connects the two singularities; its deviation from the parametrization's
 * iso-line is {@code atan2(perpendicular offset, parallel length)}. The
 * perpendicular offset is the parametric length of the zero-arc path from the
 * connecting chain node to the other singularity's node — an unsigned sum, so
 * the reported deviation is an upper bound on the true angle; the paper's
 * guarantee d_max ≤ α therefore holds whenever the bound does.
 */
public final class SeparatrixDeviation {

    public final MotorcycleGraph motorcycleGraph;
    public final QuantizedMeshGrid quantization;
    public final ZeroArcCollapse collapse;

    /** Number of singularity-to-singularity separatrices measured. */
    public int separatrixCount;

    /** Mean deviation in degrees over measured separatrices. */
    public double meanDegrees;

    /** Maximum deviation in degrees over measured separatrices. */
    public double maxDegrees;

    /**
     * Stores inputs for a deviation measurement.
     *
     * @param quantization solved quantization whose layout gets measured
     */
    public SeparatrixDeviation(QuantizedMeshGrid quantization) {
        this.quantization = quantization;
        this.motorcycleGraph = quantization.motorcycleGraph;
        this.collapse = new ZeroArcCollapse(motorcycleGraph,
                quantization.quantizedLengthByArc).build();
    }

    /**
     * Walk every singularity trace to its first singularity-bearing cluster
     * and accumulate the deviation statistics.
     *
     * @return this, with count/mean/max populated
     */
    public SeparatrixDeviation build() {
        Map<Integer, List<Integer>> singularityNodesByCluster = new HashMap<>();
        for (TMeshNode node : motorcycleGraph.nodes) {
            if (node.type == TMeshNode.TYPE_SINGULARITY && node.vertexId >= 0) {
                singularityNodesByCluster
                        .computeIfAbsent(collapse.clusterByNode[node.nodeId], cluster -> new ArrayList<>())
                        .add(node.nodeId);
            }
        }
        double totalDegrees = 0.0;
        for (Trace trace : motorcycleGraph.traces) {
            if (trace.featureTrace || trace.chainArcIds.isEmpty() || trace.singularityVertexId < 0) {
                continue;
            }
            for (int position = 1; position < trace.arcNodeIds.size(); position++) {
                int chainNodeId = trace.arcNodeIds.get(position);
                int cluster = collapse.clusterByNode[chainNodeId];
                List<Integer> singularityNodes = singularityNodesByCluster.get(cluster);
                if (singularityNodes == null) {
                    continue;
                }
                int targetNodeId = -1;
                for (int candidate : singularityNodes) {
                    if (motorcycleGraph.nodes.get(candidate).vertexId != trace.singularityVertexId) {
                        targetNodeId = candidate;
                        break;
                    }
                }
                if (targetNodeId < 0) {
                    continue;
                }
                double parallel = trace.chainNodeLengths.get(position);
                double perpendicular = zeroPathParametricLength(chainNodeId, targetNodeId);
                if (parallel <= 0.0 || perpendicular < 0.0) {
                    break;
                }
                double degrees = Math.toDegrees(Math.atan2(perpendicular, parallel));
                separatrixCount++;
                totalDegrees += degrees;
                maxDegrees = Math.max(maxDegrees, degrees);
                break;
            }
        }
        meanDegrees = separatrixCount == 0 ? 0.0 : totalDegrees / separatrixCount;
        return this;
    }

    /**
     * Total parametric length of the shortest-hop zero-arc path between two
     * nodes of one collapse cluster (BFS by hop count, parametric lengths
     * summed along the found path).
     *
     * @param startNodeId  path start node
     * @param targetNodeId path target node
     * @return summed parametric length, or -1 when not connected
     */
    private double zeroPathParametricLength(int startNodeId, int targetNodeId) {
        if (startNodeId == targetNodeId) {
            return 0.0;
        }
        Map<Integer, List<TraceArc>> zeroArcsByNode = new HashMap<>();
        int cluster = collapse.clusterByNode[startNodeId];
        for (TraceArc arc : motorcycleGraph.arcs) {
            if (quantization.quantizedLengthByArc[arc.arcId] != 0
                    || collapse.clusterByNode[arc.startNodeId] != cluster) {
                continue;
            }
            zeroArcsByNode.computeIfAbsent(arc.startNodeId, nodeId -> new ArrayList<>()).add(arc);
            zeroArcsByNode.computeIfAbsent(arc.endNodeId, nodeId -> new ArrayList<>()).add(arc);
        }
        Map<Integer, Integer> parentNode = new HashMap<>();
        Map<Integer, Double> parentArcLength = new HashMap<>();
        List<Integer> frontier = new ArrayList<>();
        frontier.add(startNodeId);
        parentNode.put(startNodeId, startNodeId);
        parentArcLength.put(startNodeId, 0.0);
        int head = 0;
        while (head < frontier.size()) {
            int nodeId = frontier.get(head++);
            if (nodeId == targetNodeId) {
                double total = 0.0;
                int walk = targetNodeId;
                while (walk != startNodeId) {
                    total += parentArcLength.get(walk);
                    walk = parentNode.get(walk);
                }
                return total;
            }
            for (TraceArc arc : zeroArcsByNode.getOrDefault(nodeId, List.of())) {
                int neighbor = arc.startNodeId == nodeId ? arc.endNodeId : arc.startNodeId;
                if (parentNode.containsKey(neighbor)) {
                    continue;
                }
                parentNode.put(neighbor, nodeId);
                parentArcLength.put(neighbor, arc.parametricLength);
                frontier.add(neighbor);
            }
        }
        return -1.0;
    }
}
