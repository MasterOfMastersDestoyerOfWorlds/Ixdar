package ixdar.geometry.mesh.nodes.modifier;

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
 * Insets selected faces by creating a smaller inner face connected to the
 * original boundary by side quads.
 * <p>
 * For each selected face, creates inner vertices by lerping each vertex
 * toward the face center. The original face is replaced by the inner face,
 * and side quads connect the original boundary to the inner boundary.
 * All-quad topology is preserved when input is all-quad.
 */
@MeshNodeAnnotation(id = "inset_faces")
public class InsetFacesNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort INSET = new InputPort("inset", PortType.FLOAT, 0.1f, 0f, 1f);
    private static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, INSET, SELECTION);
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

        Object insetObj = FieldBroadcast.getInputOrDefault(ctx, "inset", INSET.defaultValue());
        float inset = FieldBroadcast.floatScalarOrDefault(insetObj, 0.1f);

        Object selObj = FieldBroadcast.getInputOrDefault(ctx, "selection", SELECTION.defaultValue());

        ArrayMesh am = in instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(in);

        MeshTopology out = insetFaces(am, inset, selObj);
        ctx.setOutput("mesh", out);
        ctx.setOutput("geometry", base.withMesh(out));
    }

    private static MeshTopology insetFaces(ArrayMesh mesh, float inset, Object selection) {
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

        if (selectedCount == 0 || inset <= 0f) {
            return new ArrayMesh(srcPos, null, srcFaces, vpf);
        }

        // Each selected face: vpf new inner vertices + vpf side quads
        int newVertCount = selectedCount * vpf;
        int sideFaceCount = selectedCount * vpf;

        HalfEdgeMesh out = new HalfEdgeMesh(
                vertCount + newVertCount,
                0,
                faceCount + sideFaceCount,
                (faceCount + sideFaceCount) * vpf * 2
        );

        // Copy all original vertices
        for (int vi = 0; vi < vertCount; vi++) {
            out.addVertex(srcPos[vi * 3], srcPos[vi * 3 + 1], srcPos[vi * 3 + 2]);
        }

        Vector3f center = new Vector3f();
        int[][] faceInnerVerts = new int[faceCount][];

        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;

            // Compute face center
            center.set(0f, 0f, 0f);
            for (int k = 0; k < vpf; k++) {
                int vid = srcFaces[fi * vpf + k];
                center.add(srcPos[vid * 3], srcPos[vid * 3 + 1], srcPos[vid * 3 + 2]);
            }
            center.div(vpf);

            // Create inner vertices by lerping toward center
            int[] innerVerts = new int[vpf];
            for (int k = 0; k < vpf; k++) {
                int vid = srcFaces[fi * vpf + k];
                float ox = srcPos[vid * 3];
                float oy = srcPos[vid * 3 + 1];
                float oz = srcPos[vid * 3 + 2];
                // Lerp toward center by inset factor (clamped to [0, 1])
                float t = Math.min(inset, 1.0f);
                float nx = ox + (center.x - ox) * t;
                float ny = oy + (center.y - oy) * t;
                float nz = oz + (center.z - oz) * t;
                innerVerts[k] = out.addVertex(nx, ny, nz);
            }
            faceInnerVerts[fi] = innerVerts;
        }

        // Add faces: inner faces for selected, original for unselected
        for (int fi = 0; fi < faceCount; fi++) {
            if (selected[fi]) {
                out.addFace(faceInnerVerts[fi]);
            } else {
                int[] vids = new int[vpf];
                for (int k = 0; k < vpf; k++) {
                    vids[k] = srcFaces[fi * vpf + k];
                }
                out.addFace(vids);
            }
        }

        // Side quads connecting original boundary to inner boundary
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            int[] innerVerts = faceInnerVerts[fi];
            for (int k = 0; k < vpf; k++) {
                int next = (k + 1) % vpf;
                int origA = srcFaces[fi * vpf + k];
                int origB = srcFaces[fi * vpf + next];
                int innerA = innerVerts[k];
                int innerB = innerVerts[next];
                // Side quad: origA -> origB -> innerB -> innerA
                out.addFace(origA, origB, innerB, innerA);
            }
        }

        out.computeNormals();
        return out;
    }
}
