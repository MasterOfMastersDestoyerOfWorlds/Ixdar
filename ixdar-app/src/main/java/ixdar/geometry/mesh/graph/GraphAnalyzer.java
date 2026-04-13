package ixdar.geometry.mesh.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeSchema;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.parsing.python.PythonParser;

/**
 * DAG analysis for DSL graphs: adjacency, dominator tree, and seam detection.
 *
 * A <b>seam node</b> is one where all downstream consumers depend only on that
 * node's outputs, not on anything upstream of it. The upstream subgraph at a seam
 * is self-contained and can be extracted as a reusable skill.
 *
 * Algorithm: build adjacency from {@code List<ParsedNode>}, compute dominator tree
 * via iterative dataflow (graphs are small, 20-200 nodes), identify seam nodes where
 * the dominated subgraph is >= {@code minSubgraphSize} and produces MESH or
 * GEOMETRY_BUNDLE output.
 */
public final class GraphAnalyzer {

    private GraphAnalyzer() {}

    /** Edge from source node/port to consumer node/port. */
    public static class Edge {
        public final String sourceId;
        public final String sourcePort;
        public final String targetId;
        public final String targetPort;

        public Edge(String sourceId, String sourcePort, String targetId, String targetPort) {
            this.sourceId = sourceId;
            this.sourcePort = sourcePort;
            this.targetId = targetId;
            this.targetPort = targetPort;
        }
    }

    /** Result of full graph analysis. */
    public static class AnalysisResult {
        public final List<Edge> edges;
        /** nodeId -> list of predecessor nodeIds */
        public final Map<String, List<String>> predecessors;
        /** nodeId -> list of successor nodeIds */
        public final Map<String, List<String>> successors;
        /** nodeId -> immediate dominator nodeId (null for root) */
        public final Map<String, String> idom;
        /** Detected seam nodes with their upstream subgraphs. */
        public final List<SeamNode> seams;

        public AnalysisResult(List<Edge> edges, Map<String, List<String>> predecessors,
                Map<String, List<String>> successors, Map<String, String> idom,
                List<SeamNode> seams) {
            this.edges = edges;
            this.predecessors = predecessors;
            this.successors = successors;
            this.idom = idom;
            this.seams = seams;
        }
    }

    /** A seam node and its extractable upstream subgraph. */
    public static class SeamNode {
        public final String nodeId;
        public final String nodeType;
        /** Nodes in the upstream subgraph (topological order). */
        public final List<String> subgraphNodeIds;
        /** Output port types produced by this seam node. */
        public final List<String> outputPortTypes;
        /** Input ports consumed from outside the subgraph (the function parameters). */
        public final List<ExternalInput> externalInputs;

        public SeamNode(String nodeId, String nodeType, List<String> subgraphNodeIds,
                List<String> outputPortTypes, List<ExternalInput> externalInputs) {
            this.nodeId = nodeId;
            this.nodeType = nodeType;
            this.subgraphNodeIds = subgraphNodeIds;
            this.outputPortTypes = outputPortTypes;
            this.externalInputs = externalInputs;
        }
    }

    /** An input that a subgraph consumes from outside its boundary. */
    public static class ExternalInput {
        public final String sourceNodeId;
        public final String sourcePort;
        public final String consumedByNodeId;
        public final String consumedByPort;

        public ExternalInput(String sourceNodeId, String sourcePort,
                String consumedByNodeId, String consumedByPort) {
            this.sourceNodeId = sourceNodeId;
            this.sourcePort = sourcePort;
            this.consumedByNodeId = consumedByNodeId;
            this.consumedByPort = consumedByPort;
        }
    }

    /**
     * Analyze a parsed DSL graph: build adjacency, compute dominators, find seams.
     *
     * @param parsed           parsed node list (topological order)
     * @param registry         node type registry (for port metadata)
     * @param minSubgraphSize  minimum upstream subgraph size for a seam (default 3)
     */
    public static AnalysisResult analyze(List<PythonParser.ParsedNode> parsed,
            Map<String, Class<? extends MeshNode>> registry, int minSubgraphSize) {

        // 1. Build adjacency
        Map<String, Integer> idToIndex = new LinkedHashMap<>();
        List<String> nodeIds = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            idToIndex.put(parsed.get(i).id, i);
            nodeIds.add(parsed.get(i).id);
        }

        List<Edge> edges = new ArrayList<>();
        Map<String, List<String>> predecessors = new HashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        for (String id : nodeIds) {
            predecessors.put(id, new ArrayList<>());
            successors.put(id, new ArrayList<>());
        }

        for (PythonParser.ParsedNode node : parsed) {
            for (Map.Entry<String, Object> arg : node.arguments.entrySet()) {
                if (arg.getValue() instanceof PythonParser.NodeReference ref) {
                    if (idToIndex.containsKey(ref.nodeId)) {
                        edges.add(new Edge(ref.nodeId, ref.portName, node.id, arg.getKey()));
                        predecessors.get(node.id).add(ref.nodeId);
                        successors.get(ref.nodeId).add(node.id);
                    }
                }
            }
        }

        // 2. Compute dominator tree (iterative algorithm)
        // Add virtual root that dominates all nodes with no predecessors
        Map<String, String> idom = computeDominators(nodeIds, predecessors);

        // 3. Find seam nodes
        List<SeamNode> seams = findSeams(parsed, nodeIds, idToIndex, predecessors, successors,
                idom, registry, minSubgraphSize);

        return new AnalysisResult(edges, predecessors, successors, idom, seams);
    }

    /** Overload with default minSubgraphSize = 3. */
    public static AnalysisResult analyze(List<PythonParser.ParsedNode> parsed,
            Map<String, Class<? extends MeshNode>> registry) {
        return analyze(parsed, registry, 3);
    }

    /**
     * Iterative dominator computation (Cooper, Harvey, Kennedy algorithm).
     * Nodes are in topological order (index 0 = root-like, highest = sinks).
     */
    private static Map<String, String> computeDominators(List<String> nodeIds,
            Map<String, List<String>> predecessors) {
        int n = nodeIds.size();
        if (n == 0) return Map.of();

        Map<String, Integer> idToIdx = new HashMap<>();
        for (int i = 0; i < n; i++) {
            idToIdx.put(nodeIds.get(i), i);
        }

        // idomIdx[i] = index of immediate dominator, -1 = undefined
        int[] idomIdx = new int[n];
        for (int i = 0; i < n; i++) idomIdx[i] = -1;

        // Find entry nodes (no predecessors within the graph)
        // They dominate themselves
        List<Integer> entries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (predecessors.get(nodeIds.get(i)).isEmpty()) {
                idomIdx[i] = i;
                entries.add(i);
            }
        }

        // If there are multiple entry nodes, create a virtual root concept:
        // all entries are dominated by index 0 (or themselves if they're the first).
        // For simplicity, treat all entry nodes as self-dominating (they're roots).

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                List<String> preds = predecessors.get(nodeIds.get(i));
                if (preds.isEmpty()) continue; // entry node

                int newIdom = -1;
                for (String predId : preds) {
                    int predIdx = idToIdx.getOrDefault(predId, -1);
                    if (predIdx < 0 || idomIdx[predIdx] == -1) continue;
                    if (newIdom == -1) {
                        newIdom = predIdx;
                    } else {
                        newIdom = intersect(idomIdx, newIdom, predIdx);
                    }
                }

                if (newIdom != -1 && newIdom != idomIdx[i]) {
                    idomIdx[i] = newIdom;
                    changed = true;
                }
            }
        }

        // Convert to string map
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (idomIdx[i] == -1 || idomIdx[i] == i) {
                result.put(nodeIds.get(i), null); // root / entry node
            } else {
                result.put(nodeIds.get(i), nodeIds.get(idomIdx[i]));
            }
        }
        return result;
    }

    /** Intersect two dominators using finger-walking up the dom tree. */
    private static int intersect(int[] idom, int b1, int b2) {
        while (b1 != b2) {
            while (b1 > b2) {
                if (idom[b1] == b1 || idom[b1] == -1) return b2; // root reached
                b1 = idom[b1];
            }
            while (b2 > b1) {
                if (idom[b2] == b2 || idom[b2] == -1) return b1; // root reached
                b2 = idom[b2];
            }
        }
        return b1;
    }

    /**
     * Find seam nodes: a node N is a seam if every path from any node in its
     * upstream subgraph to any node outside the subgraph passes through N.
     *
     * Practically: collect the set of nodes dominated by N (its dominator subtree).
     * N is a seam if no node in that subtree has an edge to a node outside the
     * subtree, except through N itself.
     */
    private static List<SeamNode> findSeams(List<PythonParser.ParsedNode> parsed,
            List<String> nodeIds, Map<String, Integer> idToIndex,
            Map<String, List<String>> predecessors, Map<String, List<String>> successors,
            Map<String, String> idom, Map<String, Class<? extends MeshNode>> registry,
            int minSubgraphSize) {

        // Build dominator tree children: nodeId -> list of nodes it immediately dominates
        Map<String, List<String>> domChildren = new HashMap<>();
        for (String id : nodeIds) {
            domChildren.put(id, new ArrayList<>());
        }
        for (Map.Entry<String, String> e : idom.entrySet()) {
            if (e.getValue() != null) {
                domChildren.get(e.getValue()).add(e.getKey());
            }
        }

        // Map nodeId -> ParsedNode for type lookup
        Map<String, PythonParser.ParsedNode> byId = new LinkedHashMap<>();
        for (PythonParser.ParsedNode node : parsed) {
            byId.put(node.id, node);
        }

        List<SeamNode> seams = new ArrayList<>();

        for (String candidateId : nodeIds) {
            // Collect dominator subtree rooted at candidate
            Set<String> subtree = new HashSet<>();
            collectDomSubtree(candidateId, domChildren, subtree);

            if (subtree.size() < minSubgraphSize) continue;

            // Check seam condition: no node in subtree (except candidate) has
            // successors outside the subtree
            boolean isSeam = true;
            for (String subId : subtree) {
                if (subId.equals(candidateId)) continue;
                for (String succId : successors.get(subId)) {
                    if (!subtree.contains(succId)) {
                        isSeam = false;
                        break;
                    }
                }
                if (!isSeam) break;
            }

            if (!isSeam) continue;

            // Check that seam produces MESH or GEOMETRY_BUNDLE
            PythonParser.ParsedNode seamParsed = byId.get(candidateId);
            List<String> outputTypes = getOutputPortTypes(seamParsed.type, registry);
            boolean producesMesh = outputTypes.stream().anyMatch(
                    t -> "MESH".equals(t) || "GEOMETRY_BUNDLE".equals(t));
            if (!producesMesh) continue;

            // Collect external inputs (edges into subtree from outside)
            List<ExternalInput> externalInputs = new ArrayList<>();
            for (String subId : subtree) {
                PythonParser.ParsedNode subNode = byId.get(subId);
                if (subNode == null) continue;
                for (Map.Entry<String, Object> arg : subNode.arguments.entrySet()) {
                    if (arg.getValue() instanceof PythonParser.NodeReference ref) {
                        if (!subtree.contains(ref.nodeId)) {
                            externalInputs.add(new ExternalInput(
                                    ref.nodeId, ref.portName, subId, arg.getKey()));
                        }
                    }
                }
            }

            // Subgraph node IDs in topological order
            List<String> subgraphOrdered = new ArrayList<>();
            for (String id : nodeIds) {
                if (subtree.contains(id)) {
                    subgraphOrdered.add(id);
                }
            }

            seams.add(new SeamNode(candidateId, seamParsed.type, subgraphOrdered,
                    outputTypes, externalInputs));
        }

        return seams;
    }

    /** Recursively collect all nodes in the dominator subtree rooted at nodeId. */
    private static void collectDomSubtree(String nodeId, Map<String, List<String>> domChildren,
            Set<String> result) {
        result.add(nodeId);
        for (String child : domChildren.get(nodeId)) {
            collectDomSubtree(child, domChildren, result);
        }
    }

    /** Get output port type names for a node type from the registry. */
    private static List<String> getOutputPortTypes(String nodeType,
            Map<String, Class<? extends MeshNode>> registry) {
        Class<? extends MeshNode> clazz = registry.get(nodeType);
        if (clazz == null) return List.of();
        try {
            MeshNode instance = clazz.getDeclaredConstructor().newInstance();
            MeshNodeSchema schema = instance.schema();
            List<String> types = new ArrayList<>();
            for (OutputPort op : schema.outputs()) {
                types.add(op.type().name());
            }
            return types;
        } catch (ReflectiveOperationException e) {
            return List.of();
        }
    }
}
