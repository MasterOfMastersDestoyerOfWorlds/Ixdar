package ixdar.geometry.mesh.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeSchema;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.parsing.python.PythonParser;

/**
 * Graph analysis on parsed DSL DAGs to find natural abstraction boundaries.
 * 
 * A seam node N is one where all downstream consumers depend only on N's outputs,
 * not on anything upstream of N.
 * 
 * Algorithm:
 * 1. Build adjacency list from List<ParsedNode>
 * 2. Compute dominator tree (iterative dataflow)
 * 3. Identify seam nodes where dominated subgraph >= 3 nodes and produces MESH/GEOMETRY_BUNDLE
 */
public final class GraphAnalyzer {

    private GraphAnalyzer() {
    }

    /**
     * Result of seam analysis.
     */
    public static class SeamAnalysis {
        public final List<SeamNode> seams = new ArrayList<>();
    }

    /**
     * A seam node found in the graph.
     */
    public static class SeamNode {
        public final String nodeId;
        public final String nodeType;
        public final List<String> dominatedNodeIds;
        public final Map<String, PortType> boundaryPorts;
        public final String dslText;
        
        public SeamNode(String nodeId, String nodeType, List<String> dominatedNodeIds,
                       Map<String, PortType> boundaryPorts, String dslText) {
            this.nodeId = nodeId;
            this.nodeType = nodeType;
            this.dominatedNodeIds = dominatedNodeIds;
            this.boundaryPorts = boundaryPorts;
            this.dslText = dslText;
        }
    }

    /**
     * Analyze a parsed DSL graph for seam nodes.
     */
    public static SeamAnalysis analyze(List<PythonParser.ParsedNode> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            return new SeamAnalysis();
        }

        // Build node lookup
        Map<String, PythonParser.ParsedNode> byId = new HashMap<>();
        for (PythonParser.ParsedNode n : parsed) {
            byId.put(n.id, n);
        }

        // Build upstream (reverse) and downstream adjacency lists
        Map<String, List<String>> upstream = new HashMap<>();
        Map<String, List<String>> downstream = new HashMap<>();
        for (String id : byId.keySet()) {
            upstream.put(id, new ArrayList<>());
            downstream.put(id, new ArrayList<>());
        }

        // Track which nodes each node depends on (direct dependencies)
        Map<String, Set<String>> directDeps = new HashMap<>();
        for (PythonParser.ParsedNode n : parsed) {
            directDeps.put(n.id, new HashSet<>());
            for (Map.Entry<String, Object> arg : n.arguments.entrySet()) {
                Object val = arg.getValue();
                if (val instanceof PythonParser.NodeReference ref) {
                    directDeps.get(n.id).add(ref.nodeId);
                }
            }
        }

        // Build upstream/downstream edges
        for (Map.Entry<String, Set<String>> entry : directDeps.entrySet()) {
            String consumer = entry.getKey();
            for (String provider : entry.getValue()) {
                upstream.get(consumer).add(provider);
                downstream.get(provider).add(consumer);
            }
        }

        // Compute dominator tree using iterative dataflow
        Map<String, Set<String>> dominators = computeDominators(parsed, directDeps);

        // For each node, compute dominated subgraph (nodes it dominates)
        Map<String, Set<String>> dominatedSubgraphs = new HashMap<>();
        for (PythonParser.ParsedNode n : parsed) {
            dominatedSubgraphs.put(n.id, computeDominatedSubgraph(n.id, dominators, byId));
        }

        // Identify seam nodes
        SeamAnalysis result = new SeamAnalysis();
        for (PythonParser.ParsedNode n : parsed) {
            SeamNode seam = findSeam(n, byId, directDeps, downstream, dominatedSubgraphs, dominators);
            if (seam != null) {
                result.seams.add(seam);
            }
        }

        return result;
    }

    /**
     * Compute dominator sets for all nodes using iterative dataflow.
     * A node D dominates N if every path from entry to N goes through D.
     */
    private static Map<String, Set<String>> computeDominators(List<PythonParser.ParsedNode> parsed,
            Map<String, Set<String>> directDeps) {
        Map<String, Set<String>> dominators = new HashMap<>();
        Map<String, PythonParser.ParsedNode> byId = new HashMap<>();
        for (PythonParser.ParsedNode n : parsed) {
            byId.put(n.id, n);
        }

        // Entry nodes are nodes with no incoming edges (no dependencies)
        Set<String> entryNodes = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : directDeps.entrySet()) {
            if (entry.getValue().isEmpty()) {
                entryNodes.add(entry.getKey());
            }
        }

        // Initialize dominators
        for (String id : byId.keySet()) {
            if (entryNodes.contains(id)) {
                Set<String> dom = new HashSet<>();
                dom.add(id);
                dominators.put(id, dom);
            } else {
                Set<String> dom = new HashSet<>(byId.keySet());
                dominators.put(id, dom);
            }
        }

        // Iterative dataflow
        boolean changed = true;
        while (changed) {
            changed = false;
            for (PythonParser.ParsedNode n : parsed) {
                if (entryNodes.contains(n.id)) {
                    continue;
                }

                Set<String> newDom = new HashSet<>(byId.keySet());
                for (String pred : directDeps.get(n.id)) {
                    Set<String> predDom = dominators.get(pred);
                    if (predDom != null) {
                        newDom.retainAll(predDom);
                    }
                }
                newDom.add(n.id);

                if (!newDom.equals(dominators.get(n.id))) {
                    dominators.put(n.id, newDom);
                    changed = true;
                }
            }
        }

        return dominators;
    }

    /**
     * Compute the set of nodes dominated by the given node (including itself).
     * A node D dominates N if all paths from entry to N go through D.
     */
    private static Set<String> computeDominatedSubgraph(String nodeId, Map<String, Set<String>> dominators,
            Map<String, PythonParser.ParsedNode> byId) {
        Set<String> dominated = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : dominators.entrySet()) {
            if (entry.getValue().contains(nodeId)) {
                dominated.add(entry.getKey());
            }
        }
        return dominated;
    }

    /**
     * Check if a node is a seam node.
     * 
     * A seam node N must:
     * 1. Have a dominated subgraph >= 3 nodes
     * 2. Produce MESH or GEOMETRY_BUNDLE output
     * 3. All downstream consumers depend only on N's outputs, not on anything upstream of N
     */
    private static SeamNode findSeam(PythonParser.ParsedNode n, Map<String, PythonParser.ParsedNode> byId,
            Map<String, Set<String>> directDeps, Map<String, List<String>> downstream,
            Map<String, Set<String>> dominatedSubgraphs, Map<String, Set<String>> dominators) {
        
        // Check dominated subgraph size
        Set<String> dominated = dominatedSubgraphs.get(n.id);
        if (dominated == null || dominated.size() < 3) {
            return null;
        }

        // Check if node produces MESH or GEOMETRY_BUNDLE output
        Class<? extends MeshNode> nodeClass = findNodeClass(n.type);
        if (nodeClass == null) {
            return null;
        }
        
        MeshNode instance;
        try {
            instance = nodeClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            return null;
        }

        MeshNodeSchema schema = instance.schema();
        boolean hasGeometryOutput = false;
        for (OutputPort out : schema.outputs()) {
            if (out.type() == PortType.MESH || out.type() == PortType.GEOMETRY_BUNDLE) {
                hasGeometryOutput = true;
                break;
            }
        }
        if (!hasGeometryOutput) {
            return null;
        }

        // Check if all downstream consumers depend only on N's outputs
        // This means no downstream consumer should depend on any node upstream of N
        Set<String> upstreamNodes = computeUpstreamNodes(n.id, directDeps);
        
        for (String downstreamId : downstream.get(n.id)) {
            Set<String> downstreamDeps = computeTransitiveDownstreamDeps(downstreamId, directDeps);
            // Check if any downstream dependency is upstream of N (but not N itself)
            for (String dep : downstreamDeps) {
                if (upstreamNodes.contains(dep) && !dep.equals(n.id)) {
                    return null; // Not a seam - downstream depends on upstream
                }
            }
        }

        // This is a seam node - extract boundary port types
        Map<String, PortType> boundaryPorts = new HashMap<>();
        for (OutputPort out : schema.outputs()) {
            if (out.type() == PortType.MESH || out.type() == PortType.GEOMETRY_BUNDLE) {
                boundaryPorts.put(out.name(), out.type());
            }
        }

        // Extract DSL text for the dominated subgraph
        String dslText = extractSubgraphDsl(n, dominated, byId);

        return new SeamNode(n.id, n.type, new ArrayList<>(dominated), boundaryPorts, dslText);
    }

    /**
     * Find the MeshNode class for a given type name.
     */
    private static Class<? extends MeshNode> findNodeClass(String typeName) {
        try {
            Class<?> clazz = Class.forName("ixdar.mesh.nodes." + typeName);
            if (MeshNode.class.isAssignableFrom(clazz)) {
                @SuppressWarnings("unchecked")
                Class<? extends MeshNode> result = (Class<? extends MeshNode>) clazz;
                return result;
            }
        } catch (ClassNotFoundException e) {
            // Try alternative package
            try {
                Class<?> clazz = Class.forName("ixdar.geometry.mesh.nodes." + typeName);
                if (MeshNode.class.isAssignableFrom(clazz)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends MeshNode> result = (Class<? extends MeshNode>) clazz;
                    return result;
                }
            } catch (ClassNotFoundException e2) {
                // Try annotation registry
                try {
                    Class<?> runtimeClass = Class.forName("ixdar.geometry.mesh.graph.NodeGraphRuntime");
                    java.lang.reflect.Method method = runtimeClass.getMethod("annotationRegistryClasses");
                    @SuppressWarnings("unchecked")
                    Map<String, Class<? extends MeshNode>> registry = 
                        (Map<String, Class<? extends MeshNode>>) method.invoke(null);
                    return registry.get(typeName);
                } catch (Exception e3) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Compute transitive set of nodes that depend on a given node (downstream).
     */
    private static Set<String> computeTransitiveDownstreamDeps(String nodeId, 
            Map<String, Set<String>> directDeps) {
        Set<String> result = new HashSet<>();
        Set<String> visited = new HashSet<>();
        List<String> queue = new ArrayList<>();
        
        // Get direct consumers of this node
        for (Map.Entry<String, Set<String>> entry : directDeps.entrySet()) {
            if (entry.getValue().contains(nodeId)) {
                queue.add(entry.getKey());
            }
        }
        
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            if (visited.contains(current)) continue;
            visited.add(current);
            result.add(current);
            
            for (String dep : directDeps.get(current)) {
                if (!visited.contains(dep)) {
                    queue.add(dep);
                }
            }
        }
        
        return result;
    }

    /**
     * Compute transitive set of nodes upstream of a given node.
     */
    private static Set<String> computeUpstreamNodes(String nodeId, Map<String, Set<String>> directDeps) {
        Set<String> result = new HashSet<>();
        Set<String> visited = new HashSet<>();
        List<String> queue = new ArrayList<>();
        
        // Get direct providers of this node
        queue.addAll(directDeps.get(nodeId));
        
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            if (visited.contains(current)) continue;
            visited.add(current);
            result.add(current);
            
            for (String dep : directDeps.get(current)) {
                if (!visited.contains(dep)) {
                    queue.add(dep);
                }
            }
        }
        
        return result;
    }

    /**
     * Extract DSL text for a subgraph of nodes.
     */
    private static String extractSubgraphDsl(PythonParser.ParsedNode seamNode, Set<String> dominated,
            Map<String, PythonParser.ParsedNode> byId) {
        StringBuilder sb = new StringBuilder();
        
        // Collect all nodes in the dominated subgraph
        List<PythonParser.ParsedNode> subgraphNodes = new ArrayList<>();
        for (String id : dominated) {
            PythonParser.ParsedNode node = byId.get(id);
            if (node != null) {
                subgraphNodes.add(node);
            }
        }
        
        // Sort by appearance order (by ID match with original list)
        // For simplicity, just output in sorted order
        subgraphNodes.sort((a, b) -> a.id.compareTo(b.id));
        
        for (PythonParser.ParsedNode node : subgraphNodes) {
            sb.append(node.id).append(" = ").append(node.type).append("(");
            boolean first = true;
            for (Map.Entry<String, Object> arg : node.arguments.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(arg.getKey()).append("=").append(arg.getValue());
            }
            sb.append(")\n");
        }
        
        return sb.toString();
    }
}
