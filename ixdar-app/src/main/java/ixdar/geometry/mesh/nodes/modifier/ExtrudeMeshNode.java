package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.BoolField;
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
 * Extrudes selected faces of a mesh along their normals by a given offset.
 * <p>
 * For each selected face, duplicates its vertices and offsets them along the
 * face normal. The original face is replaced by a face at the new (extruded)
 * position, and side quads connect the original boundary to the extruded boundary.
 * <p>
 * Operates in FACES mode: each selected face extrudes independently along its normal.
 */
@MeshNodeAnnotation(id = "extrude_mesh")
public class ExtrudeMeshNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort OFFSET = new InputPort("offset", PortType.FLOAT, 0.1f);
    private static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, OFFSET, SELECTION);
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

        ArrayMesh am = in instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(in);
        am.computeNormals();

        MeshTopology out = extrudeFaces(am, offset, selObj);
        ctx.setOutput("mesh", out);
        ctx.setOutput("geometry", base.withMesh(out));
    }

    private static MeshTopology extrudeFaces(ArrayMesh mesh, float offset, Object selection) {
        int vpf = mesh.getVertsPerFace();
        int vertCount = mesh.vertexCount();
        int faceCount = mesh.faceCount();
        float[] srcPos = mesh.copyPositions();
        int[] srcFaces = mesh.copyFaceIndices();

        // Determine which faces are selected
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

        // For each selected face: duplicate its vertices offset along face normal.
        // The original face is rewritten to use the new vertices (the "top").
        // Side quads connect original boundary edges to the extruded edges.
        // This is "Individual" extrude mode — each face extrudes independently.

        // New vertices: each selected face contributes vpf new vertices
        int newVertCount = selectedCount * vpf;
        // Side faces: each selected face contributes vpf side quads
        int sideFaceCount = selectedCount * vpf;

        // Use HalfEdgeMesh for output since side faces are always quads
        // even when input faces are triangles (mixed face sizes)
        HalfEdgeMesh out = new HalfEdgeMesh(
                vertCount + newVertCount,
                0, // edges computed by addFace
                faceCount + sideFaceCount,
                (faceCount + sideFaceCount) * vpf * 2
        );

        // Copy all original vertices
        for (int vi = 0; vi < vertCount; vi++) {
            out.addVertex(srcPos[vi * 3], srcPos[vi * 3 + 1], srcPos[vi * 3 + 2]);
        }

        Vector3f faceNormal = new Vector3f();

        // Per-face: track the new vertex IDs for selected faces
        int[][] faceNewVerts = new int[faceCount][];

        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;

            mesh.faceNormal(fi, faceNormal);

            // Ixdar's cross-product winding produces inward-facing normals for
            // standard primitives. Negate so positive offset = outward extrusion
            // (Ixdar convention: positive offset moves geometry outward).
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

        // Add all faces
        for (int fi = 0; fi < faceCount; fi++) {
            if (selected[fi]) {
                // Top face uses the new extruded vertices
                out.addFace(faceNewVerts[fi]);
            } else {
                // Unselected face uses original vertices
                int[] vids = new int[vpf];
                for (int k = 0; k < vpf; k++) {
                    vids[k] = srcFaces[fi * vpf + k];
                }
                out.addFace(vids);
            }
        }

        // Side quads for selected faces
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            int[] newVerts = faceNewVerts[fi];
            for (int k = 0; k < vpf; k++) {
                int next = (k + 1) % vpf;
                int origA = srcFaces[fi * vpf + k];
                int origB = srcFaces[fi * vpf + next];
                int newA = newVerts[k];
                int newB = newVerts[next];
                // Side quad: origA -> origB -> newB -> newA
                out.addFace(origA, origB, newB, newA);
            }
        }

        out.computeNormals();
        return out;
    }
}
