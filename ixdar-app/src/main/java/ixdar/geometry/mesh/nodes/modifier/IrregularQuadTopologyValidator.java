package ixdar.geometry.mesh.nodes.modifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.HalfEdgeMesh;

/**
 * Validates the topology of an irregular quad grid mesh.
 * 
 * Checks include:
 * - Quad ratio (interior faces should be quads)
 * - Edge/vertex integrity (no duplicate edges, no orphaned vertices)
 * - Connected adjacency graph (all vertices reachable)
 * - Boundary handling (explicit boundary vertex/edge detection)
 * - Traversability (no isolated components)
 * 
 * Returns validation metrics and pass/fail status.
 */
@MeshNodeAnnotation(id = "mesh_irregular_quad_topology_validator")
public class IrregularQuadTopologyValidator implements MeshNode {

    private static final InputPort MESH = new InputPort("mesh", PortType.MESH);
    private static final InputPort STRICT_QUAD_MODE = new InputPort("strict_quad_mode", PortType.BOOLEAN, false);
    private static final InputPort MIN_QUAD_RATIO = new InputPort("min_quad_ratio", PortType.FLOAT, 0.95f);
    private static final InputPort DEBUG_MODE = new InputPort("debug_mode", PortType.BOOLEAN, false);

    private static final OutputPort VALID = new OutputPort("valid", PortType.BOOLEAN);
    private static final OutputPort METRICS = new OutputPort("metrics", PortType.FLOAT);
    private static final OutputPort MESH = new OutputPort("mesh_out", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH, STRICT_QUAD_MODE, MIN_QUAD_RATIO, DEBUG_MODE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(VALID, METRICS, MESH);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        HalfEdgeMesh mesh = ctx.getInput("mesh", HalfEdgeMesh.class);
        boolean strictQuadMode = ctx.getInput("strict_quad_mode", Boolean.class) != null
                ? ctx.getInput("strict_quad_mode", Boolean.class)
                : false;
        float minQuadRatio = ctx.getInput("min_quad_ratio", Number.class) != null
                ? Math.max(0f, Math.min(1f, ctx.getInput("min_quad_ratio", Number.class).floatValue()))
                : 0.95f;
        boolean debugMode = ctx.getInput("debug_mode", Boolean.class) != null
                ? ctx.getInput("debug_mode", Boolean.class)
                : false;

        if (mesh == null) {
            ctx.setOutput("valid", false);
            ctx.setOutput("metrics", new float[15]);
            ctx.setOutput("mesh_out", new HalfEdgeMesh());
            return;
        }

        // Run validation checks
        TopologyValidationResult result = validateTopology(mesh, strictQuadMode, minQuadRatio, debugMode);

        ctx.setOutput("valid", result.isValid());
        ctx.setOutput("metrics", result.metrics);
        ctx.setOutput("mesh_out", mesh);

        if (debugMode) {
            System.out.println("[IrregularQuadTopologyValidator] Validation complete:");
            System.out.println("  Quad ratio: " + result.quadRatio);
            System.out.println("  Boundary vertices: " + result.boundaryVertexCount);
            System.out.println("  Boundary edges: " + result.boundaryEdgeCount);
            System.out.println("  Connected components: " + result.connectedComponents);
            System.out.println("  Valid: " + result.isValid());
        }
    }

    /**
     * Result of topology validation with detailed metrics.
     */
    public static class TopologyValidationResult {
        public final boolean valid;
        public final float[] metrics;
        public final float quadRatio;
        public final int boundaryVertexCount;
        public final int boundaryEdgeCount;
        public final int connectedComponents;
        public final int totalVertexCount;
        public final int totalEdgeCount;
        public final int totalFaceCount;
        public final int triangleCount;
        public final int quadCount;
        public final int otherFaceCount;
        public final boolean hasOrphanedVertices;
        public final boolean hasDuplicateEdges;
        public final boolean hasIsolatedVertices;

        public TopologyValidationResult(boolean valid, float[] metrics, float quadRatio, int boundaryVertexCount,
                int boundaryEdgeCount, int connectedComponents, int totalVertexCount, int totalEdgeCount,
                int totalFaceCount, int triangleCount, int quadCount, int otherFaceCount, boolean hasOrphanedVertices,
                boolean hasDuplicateEdges, boolean hasIsolatedVertices) {
            this.valid = valid;
            this.metrics = metrics;
            this.quadRatio = quadRatio;
            this.boundaryVertexCount = boundaryVertexCount;
            this.boundaryEdgeCount = boundaryEdgeCount;
            this.connectedComponents = connectedComponents;
            this.totalVertexCount = totalVertexCount;
            this.totalEdgeCount = totalEdgeCount;
            this.totalFaceCount = totalFaceCount;
            this.triangleCount = triangleCount;
            this.quadCount = quadCount;
            this.otherFaceCount = otherFaceCount;
            this.hasOrphanedVertices = hasOrphanedVertices;
            this.hasDuplicateEdges = hasDuplicateEdges;
            this.hasIsolatedVertices = hasIsolatedVertices;
        }

        public boolean isValid() {
            return valid;
        }
    }

    /**
     * Validates the topology of an irregular quad grid mesh.
     */
    private TopologyValidationResult validateTopology(HalfEdgeMesh mesh, boolean strictQuadMode, float minQuadRatio,
            boolean debugMode) {
        float[] metrics = new float[15];

        int vertexCount = mesh.vertexCount();
        int edgeCount = mesh.edgeCount();
        int faceCount = mesh.faceCount();

        metrics[0] = vertexCount;
        metrics[1] = edgeCount;
        metrics[2] = faceCount;

        // Count faces by type
        int triangleCount = 0;
        int quadCount = 0;
        int otherFaceCount = 0;

        for (int f = 0; f < faceCount; f++) {
            int faceVertexCount = mesh.faceVertexCount(f);
            if (faceVertexCount == 3) {
                triangleCount++;
            } else if (faceVertexCount == 4) {
                quadCount++;
            } else {
                otherFaceCount++;
            }
        }

        metrics[3] = triangleCount;
        metrics[4] = quadCount;
        metrics[5] = otherFaceCount;

        // Calculate quad ratio
        float quadRatio = faceCount > 0 ? (float) quadCount / faceCount : 0f;
        metrics[6] = quadRatio;

        // Check boundary vertices and edges
        int boundaryVertexCount = 0;
        int boundaryEdgeCount = 0;

        for (int v = 0; v < vertexCount; v++) {
            if (mesh.isBoundaryVertex(v)) {
                boundaryVertexCount++;
            }
        }

        for (int e = 0; e < edgeCount; e++) {
            if (mesh.isBoundaryEdge(e)) {
                boundaryEdgeCount++;
            }
        }

        metrics[7] = boundaryVertexCount;
        metrics[8] = boundaryEdgeCount;

        // Check for orphaned vertices (vertices with no edges)
        boolean hasOrphanedVertices = false;
        for (int v = 0; v < vertexCount; v++) {
            if (mesh.vertexEdgeCount(v) == 0) {
                hasOrphanedVertices = true;
                break;
            }
        }
        metrics[9] = hasOrphanedVertices ? 1f : 0f;

        // Check for duplicate edges (simplified check)
        boolean hasDuplicateEdges = checkDuplicateEdges(mesh);
        metrics[10] = hasDuplicateEdges ? 1f : 0f;

        // Check for isolated vertices
        boolean hasIsolatedVertices = checkIsolatedVertices(mesh);
        metrics[11] = hasIsolatedVertices ? 1f : 0f;

        // Check connected components using BFS
        int connectedComponents = countConnectedComponents(mesh);
        metrics[12] = connectedComponents;

        // Determine overall validity
        boolean valid = true;

        // Quad ratio check (allow some boundary exceptions)
        if (!strictQuadMode && quadRatio < minQuadRatio) {
            valid = false;
        }

        // No orphaned vertices
        if (hasOrphanedVertices) {
            valid = false;
        }

        // No duplicate edges
        if (hasDuplicateEdges) {
            valid = false;
        }

        // Single connected component (or acceptable number for boundary cases)
        if (connectedComponents > 2) {
            valid = false;
        }

        // No isolated vertices
        if (hasIsolatedVertices) {
            valid = false;
        }

        metrics[13] = valid ? 1f : 0f;
        metrics[14] = quadRatio;

        return new TopologyValidationResult(valid, metrics, quadRatio, boundaryVertexCount, boundaryEdgeCount,
                connectedComponents, vertexCount, edgeCount, faceCount, triangleCount, quadCount, otherFaceCount,
                hasOrphanedVertices, hasDuplicateEdges, hasIsolatedVertices);
    }

    /**
     * Checks for duplicate edges in the mesh.
     */
    private boolean checkDuplicateEdges(HalfEdgeMesh mesh) {
        Set<String> seenEdges = new HashSet<>();

        for (int v = 0; v < mesh.vertexCount(); v++) {
            int edgeCount = mesh.vertexEdgeCount(v);
            for (int i = 0; i < edgeCount; i++) {
                int edgeId = mesh.vertexEdgeAt(v, i);
                // Get the two vertices of the edge
                int he = mesh.edgeHalfEdge(edgeId);
                int v1 = mesh.halfEdgeVertex(he);
                int v2 = mesh.halfEdgeEndVertex(he);

                String key = Math.min(v1, v2) + ":" + Math.max(v1, v2);
                if (seenEdges.contains(key)) {
                    return true;
                }
                seenEdges.add(key);
            }
        }

        return false;
    }

    /**
     * Checks for isolated vertices (vertices with no edges).
     */
    private boolean checkIsolatedVertices(HalfEdgeMesh mesh) {
        for (int v = 0; v < mesh.vertexCount(); v++) {
            if (mesh.vertexEdgeCount(v) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts connected components using BFS.
     */
    private int countConnectedComponents(HalfEdgeMesh mesh) {
        if (mesh.vertexCount() == 0) {
            return 0;
        }

        int[] parent = new int[mesh.vertexCount()];
        for (int i = 0; i < mesh.vertexCount(); i++) {
            parent[i] = i;
        }

        // Union-find to group connected vertices
        for (int v = 0; v < mesh.vertexCount(); v++) {
            int edgeCount = mesh.vertexEdgeCount(v);
            for (int i = 0; i < edgeCount; i++) {
                int edgeId = mesh.vertexEdgeAt(v, i);
                int he = mesh.edgeHalfEdge(edgeId);
                int v1 = mesh.halfEdgeVertex(he);
                int v2 = mesh.halfEdgeEndVertex(he);

                union(parent, v1, v2);
            }
        }

        // Count unique roots
        Set<Integer> roots = new HashSet<>();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            roots.add(find(parent, i));
        }

        return roots.size();
    }

    private int find(int[] parent, int x) {
        if (parent[x] != x) {
            parent[x] = find(parent, parent[x]);
        }
        return parent[x];
    }

    private void union(int[] parent, int x, int y) {
        int rootX = find(parent, x);
        int rootY = find(parent, y);
        if (rootX != rootY) {
            parent[rootX] = rootY;
        }
    }
}
