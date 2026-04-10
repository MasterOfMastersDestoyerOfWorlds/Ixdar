package ixdar.geometry.mesh.nodes.modifier;

import java.util.Arrays;
import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.ArrayMeshEngine;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Extrudes selected faces of a mesh along their normals.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>INDIVIDUAL</b> (default, region=false): each face extrudes independently</li>
 *   <li><b>REGION</b> (region=true): adjacent selected faces share extruded vertices;
 *       side walls are only created on boundary edges between selected and unselected faces</li>
 * </ul>
 */
@MeshNodeAnnotation(id = "extrude_mesh")
public class ExtrudeMeshNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort OFFSET = new InputPort("offset", PortType.FLOAT, 0.1f);
    private static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    private static final InputPort REGION = new InputPort("region", PortType.BOOLEAN, false);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, OFFSET, SELECTION, REGION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH_OUT, GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        MeshTopology in = base.mesh();
        if (in == null || in.vertexCount() == 0) {
            ctx.setOutput("mesh", null);
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }

        Object offObj = FieldBroadcast.getInputOrDefault(ctx, "offset", OFFSET.defaultValue());
        float offset = FieldBroadcast.floatScalarOrDefault(offObj, 0.1f);

        Object selObj = FieldBroadcast.getInputOrDefault(ctx, "selection", SELECTION.defaultValue());
        Object regObj = FieldBroadcast.getInputOrDefault(ctx, "region", REGION.defaultValue());
        boolean region = FieldBroadcast.boolAt(regObj, 0, false);

        ArrayMesh am = in instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(in);
        am.computeNormals();

        MeshTopology out = region
                ? extrudeRegion(am, offset, selObj)
                : extrudeFacesIndividual(am, offset, selObj);
        ctx.setOutput("mesh", out);
        ctx.setOutput("geometry", base.withMesh(out));
    }

    // ── Individual mode (original behavior) ──────────────────────────────

    private static MeshTopology extrudeFacesIndividual(ArrayMesh mesh, float offset, Object selection) {
        int vpf = mesh.getVertsPerFace();
        int vertCount = mesh.vertexCount();
        int faceCount = mesh.faceCount();
        float[] srcPos = mesh.copyPositions();
        int[] srcFaces = mesh.copyFaceIndices();

        boolean[] selected = new boolean[faceCount];
        int selectedCount = 0;
        for (int fi = 0; fi < faceCount; fi++) {
            boolean sel = FieldBroadcast.boolAt(selection, fi, true);
            selected[fi] = sel;
            if (sel) selectedCount++;
        }

        if (selectedCount == 0 || offset == 0f) {
            return new ArrayMesh(srcPos, null, srcFaces, vpf);
        }

        int newVertCount = selectedCount * vpf;
        int sideFaceCount = selectedCount * vpf;

        HalfEdgeMesh out = new HalfEdgeMesh(
                vertCount + newVertCount, 0,
                faceCount + sideFaceCount,
                (faceCount + sideFaceCount) * vpf * 2);

        for (int vi = 0; vi < vertCount; vi++) {
            out.addVertex(srcPos[vi * 3], srcPos[vi * 3 + 1], srcPos[vi * 3 + 2]);
        }

        Vector3f faceNormal = new Vector3f();
        int[][] faceNewVerts = new int[faceCount][];

        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            mesh.faceNormal(fi, faceNormal);
            int[] newVerts = new int[vpf];
            for (int k = 0; k < vpf; k++) {
                int origVid = srcFaces[fi * vpf + k];
                float nx = srcPos[origVid * 3] - faceNormal.x * offset;
                float ny = srcPos[origVid * 3 + 1] - faceNormal.y * offset;
                float nz = srcPos[origVid * 3 + 2] - faceNormal.z * offset;
                newVerts[k] = out.addVertex(nx, ny, nz);
            }
            faceNewVerts[fi] = newVerts;
        }

        for (int fi = 0; fi < faceCount; fi++) {
            if (selected[fi]) {
                out.addFace(faceNewVerts[fi]);
            } else {
                int[] vids = new int[vpf];
                for (int k = 0; k < vpf; k++) {
                    vids[k] = srcFaces[fi * vpf + k];
                }
                out.addFace(vids);
            }
        }

        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            int[] newVerts = faceNewVerts[fi];
            for (int k = 0; k < vpf; k++) {
                int next = (k + 1) % vpf;
                int origA = srcFaces[fi * vpf + k];
                int origB = srcFaces[fi * vpf + next];
                int newA = newVerts[k];
                int newB = newVerts[next];
                out.addFace(origA, origB, newB, newA);
            }
        }

        out.computeNormals();
        return out;
    }

    // ── Region mode ──────────────────────────────────────────────────────
    // Adjacent selected faces share extruded vertices. Side walls are only
    // created on edges where a selected face borders an unselected face
    // (or a mesh boundary).

    private static MeshTopology extrudeRegion(ArrayMesh mesh, float offset, Object selection) {
        int vpf = mesh.getVertsPerFace();
        int vertCount = mesh.vertexCount();
        int faceCount = mesh.faceCount();
        float[] srcPos = mesh.copyPositions();
        int[] srcFaces = mesh.copyFaceIndices();

        boolean[] selected = new boolean[faceCount];
        int selectedCount = 0;
        for (int fi = 0; fi < faceCount; fi++) {
            boolean sel = FieldBroadcast.boolAt(selection, fi, true);
            selected[fi] = sel;
            if (sel) selectedCount++;
        }

        if (selectedCount == 0 || offset == 0f) {
            return new ArrayMesh(srcPos, null, srcFaces, vpf);
        }

        // Identify which original vertices are used by selected faces
        boolean[] vertUsedBySelected = new boolean[vertCount];
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            for (int k = 0; k < vpf; k++) {
                vertUsedBySelected[srcFaces[fi * vpf + k]] = true;
            }
        }

        // Compute average normal of selected faces sharing each vertex
        // for smooth offset direction
        Vector3f faceNormal = new Vector3f();
        float[] vertNormals = new float[vertCount * 3]; // accumulated
        int[] vertNormalCount = new int[vertCount];

        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            mesh.faceNormal(fi, faceNormal);
            for (int k = 0; k < vpf; k++) {
                int vi = srcFaces[fi * vpf + k];
                vertNormals[vi * 3] += faceNormal.x;
                vertNormals[vi * 3 + 1] += faceNormal.y;
                vertNormals[vi * 3 + 2] += faceNormal.z;
                vertNormalCount[vi]++;
            }
        }

        // Normalize vertex normals
        for (int vi = 0; vi < vertCount; vi++) {
            if (vertNormalCount[vi] == 0) continue;
            float nx = vertNormals[vi * 3];
            float ny = vertNormals[vi * 3 + 1];
            float nz = vertNormals[vi * 3 + 2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-8f) {
                vertNormals[vi * 3] = nx / len;
                vertNormals[vi * 3 + 1] = ny / len;
                vertNormals[vi * 3 + 2] = nz / len;
            }
        }

        // Create new vertex IDs: one new vertex for each original vertex used by selected faces
        int[] vertNewId = new int[vertCount];
        Arrays.fill(vertNewId, -1);
        int newVertTotal = 0;
        for (int vi = 0; vi < vertCount; vi++) {
            if (vertUsedBySelected[vi]) {
                vertNewId[vi] = vertCount + newVertTotal;
                newVertTotal++;
            }
        }

        // Collect boundary edges of the selection region.
        // A boundary edge is one where a selected face meets an unselected face or the mesh boundary.
        // We store the edge as (va, vb) in the winding order of the SELECTED face.
        record BoundaryEdge(int va, int vb) {}
        java.util.List<BoundaryEdge> boundaryEdges = new java.util.ArrayList<>();
        java.util.Set<Long> edgeSeen = new java.util.HashSet<>();

        // Build edge-to-faces map for adjacency lookup
        java.util.Map<Long, java.util.List<Integer>> edgeFaces = new java.util.HashMap<>();
        for (int fi = 0; fi < faceCount; fi++) {
            for (int k = 0; k < vpf; k++) {
                int va = srcFaces[fi * vpf + k];
                int vb = srcFaces[fi * vpf + ((k + 1) % vpf)];
                long key = edgeKey(va, vb, vertCount);
                edgeFaces.computeIfAbsent(key, x -> new java.util.ArrayList<>()).add(fi);
            }
        }

        // Find boundary edges from the perspective of selected faces
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            for (int k = 0; k < vpf; k++) {
                int va = srcFaces[fi * vpf + k];
                int vb = srcFaces[fi * vpf + ((k + 1) % vpf)];
                long key = edgeKey(va, vb, vertCount);
                if (edgeSeen.contains(key)) continue;

                java.util.List<Integer> faces = edgeFaces.get(key);
                boolean allSelected = true;
                for (int f : faces) {
                    if (!selected[f]) { allSelected = false; break; }
                }
                // Boundary: not all adjacent faces are selected, or mesh boundary (1 face)
                if (!allSelected || faces.size() == 1) {
                    edgeSeen.add(key);
                    // Store with winding from the selected face
                    boundaryEdges.add(new BoundaryEdge(va, vb));
                }
            }
        }

        int sideQuadCount = boundaryEdges.size();
        int totalFaces = faceCount + sideQuadCount;

        HalfEdgeMesh out = new HalfEdgeMesh(
                vertCount + newVertTotal, 0,
                totalFaces, totalFaces * vpf * 2);

        // Copy all original vertices
        for (int vi = 0; vi < vertCount; vi++) {
            out.addVertex(srcPos[vi * 3], srcPos[vi * 3 + 1], srcPos[vi * 3 + 2]);
        }

        // Add new (extruded) vertices — offset along averaged normal
        // Negate normal for Ixdar's inward-facing normal convention
        for (int vi = 0; vi < vertCount; vi++) {
            if (!vertUsedBySelected[vi]) continue;
            float nx = srcPos[vi * 3] - vertNormals[vi * 3] * offset;
            float ny = srcPos[vi * 3 + 1] - vertNormals[vi * 3 + 1] * offset;
            float nz = srcPos[vi * 3 + 2] - vertNormals[vi * 3 + 2] * offset;
            out.addVertex(nx, ny, nz);
        }

        // Add faces: selected faces use new vertex IDs, unselected use original
        for (int fi = 0; fi < faceCount; fi++) {
            int[] vids = new int[vpf];
            for (int k = 0; k < vpf; k++) {
                int origV = srcFaces[fi * vpf + k];
                vids[k] = selected[fi] ? vertNewId[origV] : origV;
            }
            out.addFace(vids);
        }

        // Add side quads on boundary edges of the selection region.
        // The selected face originally had edge va→vb but now uses newA→newB.
        // The unselected neighbor (or mesh boundary) still has edge vb→va.
        // Side quad winding: va → vb → newB → newA
        // This creates half-edges:
        //   va→vb (twin of unselected face's vb→va)
        //   vb→newB (new wall edge)
        //   newB→newA (twin of selected face's newA→newB)
        //   newA→va (new wall edge)
        for (BoundaryEdge be : boundaryEdges) {
            int newA = vertNewId[be.va];
            int newB = vertNewId[be.vb];
            if (newA < 0 || newB < 0) continue;
            out.addFace(be.va, be.vb, newB, newA);
        }

        out.computeNormals();
        return out;
    }

    private static long edgeKey(int va, int vb, int vertCount) {
        int a = Math.min(va, vb);
        int b = Math.max(va, vb);
        return (long) a * vertCount + b;
    }
}
