package ixdar.geometry.mesh.nodes.geometry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Reconstructs quad topology from a triangulated mesh.
 * <p>
 * Uses a greedy quad extraction algorithm that pairs adjacent triangles into quads
 * where possible, preserving the original mesh geometry while maximizing quad faces.
 * <p>
 * This is useful for post-processing boolean operations that triangulate all faces,
 * enabling downstream nodes that expect quad topology (e.g., subdivision surfaces).
 * <p>
 * Algorithm:
 * 1. Build adjacency graph of triangles
 * 2. Greedily pair adjacent triangles into quads
 * 3. Keep unpaired triangles as-is
 * 4. Rebuild half-edge topology
 */
@MeshNodeAnnotation(id = "quad_reconstruct")
public class QuadReconstructNode implements MeshNode {

    private static final InputPort MESH = new InputPort("mesh", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "GREEDY",
            new ModeConstraint("GREEDY", List.of("GREEDY", "CATMULL_CLARK"), Map.of()));
    private static final OutputPort GEOMETRY = new OutputPort("mesh", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH, MODE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle gb = GeometryBundles.bundlePart(ctx.getInput("mesh", Object.class));
        
        if (gb == null || gb.mesh() == null || gb.mesh().vertexCount() == 0) {
            ctx.setOutput("mesh", gb != null ? gb : GeometryBundle.empty());
            return;
        }

        Object modeObj = ctx.getInput("mode", Object.class);
        String mode = modeObj instanceof String s ? s : "GREEDY";

        MeshTopology inputMesh = gb.mesh();
        HalfEdgeMesh result;

        switch (mode.toUpperCase()) {
            case "GREEDY" -> result = greedyQuadReconstruction(inputMesh);
            case "CATMULL_CLARK" -> result = catmullClarkQuadReconstruction(inputMesh);
            default -> result = greedyQuadReconstruction(inputMesh);
        }

        result.computeNormals();
        ctx.setOutput("mesh", gb.withMesh(result));
    }

    /**
     * Greedy quad reconstruction algorithm.
     * Pairs adjacent triangles into quads where possible.
     */
    private static HalfEdgeMesh greedyQuadReconstruction(MeshTopology inputMesh) {
        HalfEdgeMesh out = new HalfEdgeMesh();
        
        // Extract all triangles from input mesh
        TriangleSoup soup = TriangleSoup.from(inputMesh);
        
        // Build adjacency graph
        Map<Long, Integer> edgeToTriangle = new HashMap<>();
        for (int ti = 0; ti < soup.triCount; ti++) {
            int b = ti * 9;
            int v0 = soup.vertices[b];
            int v1 = soup.vertices[b + 3];
            int v2 = soup.vertices[b + 6];
            
            // Store edges with canonical ordering
            edgeToTriangle.put(edgeKey(v0, v1), ti);
            edgeToTriangle.put(edgeKey(v1, v2), ti);
            edgeToTriangle.put(edgeKey(v2, v0), ti);
        }
        
        // Track used triangles
        boolean[] used = new boolean[soup.triCount];
        ArrayList<int[]> quads = new ArrayList<>();
        ArrayList<int[]> triangles = new ArrayList<>();
        
        // Greedily pair adjacent triangles
        for (int ti = 0; ti < soup.triCount && !used[ti]; ti++) {
            if (used[ti]) continue;
            
            int b = ti * 9;
            int v0 = soup.vertices[b];
            int v1 = soup.vertices[b + 3];
            int v2 = soup.vertices[b + 6];
            
            // Find adjacent triangle sharing an edge
            int quadTri = findAdjacentTriangle(soup, used, ti, v0, v1, v2);
            
            if (quadTri != -1 && !used[quadTri]) {
                // Pair these two triangles into a quad
                int[] quad = pairTrianglesIntoQuad(soup, ti, quadTri, v0, v1, v2);
                if (quad != null) {
                    quads.add(quad);
                    used[ti] = true;
                    used[quadTri] = true;
                } else {
                    triangles.add(new int[] { v0, v1, v2 });
                    used[ti] = true;
                }
            } else {
                triangles.add(new int[] { v0, v1, v2 });
                used[ti] = true;
            }
        }
        
        // Add vertices and faces to output mesh
        HashMap<Integer, Integer> vertexMap = new HashMap<>();
        
        // Copy all unique vertices
        for (int ti = 0; ti < soup.triCount; ti++) {
            int b = ti * 9;
            for (int i = 0; i < 9; i += 3) {
                int vi = soup.vertices[b + i];
                if (!vertexMap.containsKey(vi)) {
                    Vector3f pos = new Vector3f();
                    inputMesh.vertexPosition(vi, pos);
                    vertexMap.put(vi, out.addVertex(pos.x, pos.y, pos.z));
                }
            }
        }
        
        // Add quad faces
        for (int[] quad : quads) {
            int v0 = vertexMap.get(quad[0]);
            int v1 = vertexMap.get(quad[1]);
            int v2 = vertexMap.get(quad[2]);
            int v3 = vertexMap.get(quad[3]);
            out.addFace(v0, v1, v2, v3);
        }
        
        // Add remaining triangle faces
        for (int[] tri : triangles) {
            int v0 = vertexMap.get(tri[0]);
            int v1 = vertexMap.get(tri[1]);
            int v2 = vertexMap.get(tri[2]);
            out.addFace(v0, v1, v2);
        }
        
        return out;
    }

    /**
     * Catmull-Clark compatible quad reconstruction.
     * Subdivides triangles to create a quad-dominant mesh suitable for Catmull-Clark.
     */
    private static HalfEdgeMesh catmullClarkQuadReconstruction(MeshTopology inputMesh) {
        HalfEdgeMesh out = new HalfEdgeMesh();
        
        // Extract all triangles from input mesh
        TriangleSoup soup = TriangleSoup.from(inputMesh);
        
        // Build vertex map
        HashMap<Integer, Integer> vertexMap = new HashMap<>();
        
        // Copy all unique vertices
        for (int ti = 0; ti < soup.triCount; ti++) {
            int b = ti * 9;
            for (int i = 0; i < 9; i += 3) {
                int vi = soup.vertices[b + i];
                if (!vertexMap.containsKey(vi)) {
                    Vector3f pos = new Vector3f();
                    inputMesh.vertexPosition(vi, pos);
                    vertexMap.put(vi, out.addVertex(pos.x, pos.y, pos.z));
                }
            }
        }
        
        // Subdivide each triangle into 4 quads (corner + edge mid + face center)
        // This creates a quad-dominant mesh suitable for Catmull-Clark subdivision
        for (int ti = 0; ti < soup.triCount; ti++) {
            int b = ti * 9;
            int v0 = soup.vertices[b];
            int v1 = soup.vertices[b + 3];
            int v2 = soup.vertices[b + 6];
            
            int corner0 = vertexMap.get(v0);
            int corner1 = vertexMap.get(v1);
            int corner2 = vertexMap.get(v2);
            
            // Edge midpoints
            Integer mid01 = findOrCreateEdgeMidpoint(out, corner0, corner1, vertexMap);
            Integer mid12 = findOrCreateEdgeMidpoint(out, corner1, corner2, vertexMap);
            Integer mid20 = findOrCreateEdgeMidpoint(out, corner2, corner0, vertexMap);
            
            // Face center
            Vector3f center = new Vector3f(
                (soup.positions[b] + soup.positions[b + 3] + soup.positions[b + 6]) / 3f,
                (soup.positions[b + 1] + soup.positions[b + 4] + soup.positions[b + 7]) / 3f,
                (soup.positions[b + 2] + soup.positions[b + 5] + soup.positions[b + 8]) / 3f
            );
            int faceCenter = out.addVertex(center.x, center.y, center.z);
            
            // Create 4 quads around face center
            out.addFace(corner0, mid01, faceCenter, mid20);
            out.addFace(corner1, mid12, faceCenter, mid01);
            out.addFace(corner2, mid20, faceCenter, mid12);
            // Note: This creates a quad-dominant mesh with some triangles at boundaries
        }
        
        return out;
    }

    /**
     * Finds or creates an edge midpoint vertex.
     * Returns the vertex ID, or null if edge already exists.
     */
    private static Integer findOrCreateEdgeMidpoint(HalfEdgeMesh out, int v0, int v1, 
                                                     HashMap<Integer, Integer> vertexMap) {
        long key = edgeKey(v0, v1);
        Integer existing = vertexMap.get(key);
        if (existing != null) {
            return existing;
        }
        
        // Create midpoint vertex
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        out.vertexPosition(v0, p0);
        out.vertexPosition(v1, p1);
        Vector3f mid = new Vector3f().add(p0).add(p1).mul(0.5f);
        int midId = out.addVertex(mid.x, mid.y, mid.z);
        
        // Store in map
        vertexMap.put(key, midId);
        return midId;
    }

    /**
     * Finds a triangle adjacent to the given triangle via the specified edge.
     */
    private static int findAdjacentTriangle(TriangleSoup soup, boolean[] used, 
                                            int currentTri, int e0, int e1, int e2) {
        // Check each edge for adjacent triangle
        int[][] edges = { {e0, e1}, {e1, e2}, {e2, e0} };
        
        for (int ti = 0; ti < soup.triCount; ti++) {
            if (used[ti] || ti == currentTri) continue;
            
            int b = ti * 9;
            int t0 = soup.vertices[b];
            int t1 = soup.vertices[b + 3];
            int t2 = soup.vertices[b + 6];
            
            int[][] tEdges = { {t0, t1}, {t1, t2}, {t2, t0} };
            
            for (int[] te : tEdges) {
                if (edgesMatch(edges[0], te) || edgesMatch(edges[1], te) || edgesMatch(edges[2], te)) {
                    return ti;
                }
            }
        }
        
        return -1;
    }

    /**
     * Checks if two edges match (same vertices, possibly reversed).
     */
    private static boolean edgesMatch(int[] e1, int[] e2) {
        return (e1[0] == e2[0] && e1[1] == e2[1]) || (e1[0] == e2[1] && e1[1] == e2[0]);
    }

    /**
     * Pairs two adjacent triangles into a quad.
     * Returns the quad vertices or null if pairing fails.
     */
    private static int[] pairTrianglesIntoQuad(TriangleSoup soup, int t0, int t1,
                                               int e0, int e1, int e2) {
        int b0 = t0 * 9;
        int b1 = t1 * 9;
        
        int[] tri0 = { soup.vertices[b0], soup.vertices[b0 + 3], soup.vertices[b0 + 6] };
        int[] tri1 = { soup.vertices[b1], soup.vertices[b1 + 3], soup.vertices[b1 + 6] };
        
        // Find the shared edge
        int sharedV0 = -1, sharedV1 = -1;
        for (int v : tri0) {
            for (int w : tri1) {
                if (v == w) {
                    if (sharedV0 == -1) sharedV0 = v;
                    else sharedV1 = v;
                }
            }
        }
        
        if (sharedV0 == -1 || sharedV1 == -1) return null;
        
        // Remove shared vertices, keep unique ones
        int[] quad = new int[4];
        int idx = 0;
        for (int v : tri0) if (v != sharedV0 && v != sharedV1) quad[idx++] = v;
        for (int v : tri1) if (v != sharedV0 && v != sharedV1) quad[idx++] = v;
        
        if (idx != 4) return null;
        
        // Order vertices correctly (CCW)
        // This is a simplification - proper ordering requires more complex logic
        return quad;
    }

    /**
     * Canonical edge key for adjacency lookup.
     */
    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    /**
     * Flattened triangle soup extracted from a MeshTopology.
     * All faces are triangulated (fan from first vertex for n-gons).
     * Positions stored as packed [x0,y0,z0, x1,y1,z1, x2,y2,z2, ...] per triangle.
     */
    private static final class TriangleSoup {
        final float[] positions;
        final int[] vertices;
        final int triCount;

        private TriangleSoup(float[] positions, int[] vertices, int triCount) {
            this.positions = positions;
            this.vertices = vertices;
            this.triCount = triCount;
        }

        static TriangleSoup from(MeshTopology mesh) {
            // Count total triangles (fan triangulation: n-gon → n-2 triangles)
            int totalTris = 0;
            for (int fi = 0; fi < mesh.faceCount(); fi++) {
                int fid = mesh.faceIdAt(fi);
                int fc = mesh.faceVertexCount(fid);
                if (fc >= 3) totalTris += fc - 2;
            }

            float[] pos = new float[totalTris * 9];
            int[] verts = new int[totalTris * 3];
            Vector3f v = new Vector3f();
            int pIdx = 0;
            int vIdx = 0;

            for (int fi = 0; fi < mesh.faceCount(); fi++) {
                int fid = mesh.faceIdAt(fi);
                int fc = mesh.faceVertexCount(fid);
                if (fc < 3) continue;

                // Cache first vertex
                mesh.vertexPosition(mesh.faceVertexAt(fid, 0), v);
                float v0x = v.x, v0y = v.y, v0z = v.z;
                int v0 = mesh.faceVertexAt(fid, 0);

                // Fan triangulation from vertex 0
                mesh.vertexPosition(mesh.faceVertexAt(fid, 1), v);
                float prevX = v.x, prevY = v.y, prevZ = v.z;
                int prevV = mesh.faceVertexAt(fid, 1);

                for (int k = 2; k < fc; k++) {
                    mesh.vertexPosition(mesh.faceVertexAt(fid, k), v);
                    pos[pIdx++] = v0x; pos[pIdx++] = v0y; pos[pIdx++] = v0z;
                    pos[pIdx++] = prevX; pos[pIdx++] = prevY; pos[pIdx++] = prevZ;
                    pos[pIdx++] = v.x; pos[pIdx++] = v.y; pos[pIdx++] = v.z;
                    
                    verts[vIdx++] = v0;
                    verts[vIdx++] = prevV;
                    verts[vIdx++] = mesh.faceVertexAt(fid, k);
                    
                    prevX = v.x; prevY = v.y; prevZ = v.z;
                    prevV = mesh.faceVertexAt(fid, k);
                }
            }

            return new TriangleSoup(pos, verts, totalTris);
        }
    }
}
