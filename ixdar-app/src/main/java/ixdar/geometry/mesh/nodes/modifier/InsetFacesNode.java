package ixdar.geometry.mesh.nodes.modifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import ixdar.geometry.mesh.nodes.patch.AssignBezierHandlesNode;
import ixdar.geometry.mesh.nodes.patch.CoonsHandleBuilder;

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
    private static final OutputPort GENERATED_OUT = new OutputPort("generated", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, INSET, SELECTION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH_OUT, GEOMETRY_OUT, GENERATED_OUT);
    }

    @Override
    public String description() {
        return "Insets selected faces by creating a smaller inner face connected to the original boundary by side quads, useful for preparing faces for extrusion.";
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        MeshTopology in = base.mesh();
        if (in == null || in.vertexCount() == 0) {
            ctx.setOutput("mesh", null);
            ctx.setOutput("geometry", GeometryBundle.empty());
            ctx.setOutput("generated", new BoolField(new boolean[0]));
            return;
        }

        Object insetObj = FieldBroadcast.getInputOrDefault(ctx, "inset", INSET.defaultValue());
        float inset = FieldBroadcast.floatScalarOrDefault(insetObj, 0.1f);

        Object selObj = FieldBroadcast.getInputOrDefault(ctx, "selection", SELECTION.defaultValue());

        ArrayMesh am = in instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(in);

        // Compute selection mask before we lose it (insetFaces takes selObj directly).
        int origFaceCount = am.faceCount();
        boolean[] selected = new boolean[origFaceCount];
        for (int fi = 0; fi < origFaceCount; fi++) {
            selected[fi] = FieldBroadcast.boolAt(selObj, fi, true);
        }

        MeshTopology out = insetFaces(am, inset, selObj);

        // Generated mask: the inner face (replacing the selected face) lives at
        // the original face's index; side walls appear after origFaceCount.
        int outFaceCount = out == null ? 0 : out.faceCount();
        boolean[] genMask = new boolean[outFaceCount];
        for (int fi = 0; fi < Math.min(origFaceCount, outFaceCount); fi++) {
            genMask[fi] = selected[fi];
        }

        GeometryBundle outBundle = base.withMesh(out);

        // Handle preservation: when input carries bezier handle slots, preserve
        // handles on edges whose endpoints are both still original vertices
        // (i.e. outer boundary edges of the side quads). New edges (inner-face
        // boundary, radial bridges) get zero handles — yielding straight
        // geometry at the inset boundary, acceptable for flat depressions.
        if (CoonsHandleBuilder.hasHandles(base) && out != null) {
            outBundle = preserveOuterHandles(base, in, out, outBundle);
        }

        ctx.setOutput("mesh", out);
        ctx.setOutput("geometry", outBundle);
        ctx.setOutput("generated", new BoolField(genMask));
    }

    /**
     * Copies input handles onto output edges whose both endpoints are original
     * vertex IDs (i.e. the outer boundary edges that were not replaced by
     * inner geometry). Edges involving extruded/inset inner vertices get zero
     * handles.
     */
    private static GeometryBundle preserveOuterHandles(GeometryBundle base,
            MeshTopology inMesh, MeshTopology outMesh, GeometryBundle outBundle) {
        float[] inHS = CoonsHandleBuilder.readHandleSlot(base,
                AssignBezierHandlesNode.SLOT_HANDLES_START, inMesh);
        float[] inHE = CoonsHandleBuilder.readHandleSlot(base,
                AssignBezierHandlesNode.SLOT_HANDLES_END, inMesh);

        Map<Long, float[]> inEdgeToStart = new HashMap<>();
        Map<Long, float[]> inEdgeToEnd = new HashMap<>();
        for (int ei = 0; ei < inMesh.edgeCount(); ei++) {
            int eid = inMesh.edgeIdAt(ei);
            int he = inMesh.edgeHalfEdge(eid);
            int va = inMesh.halfEdgeVertex(he);
            int vb = inMesh.halfEdgeEndVertex(he);
            int o = eid * 3;
            inEdgeToStart.put(CoonsHandleBuilder.dirPack(va, vb),
                    new float[]{inHS[o], inHS[o + 1], inHS[o + 2]});
            inEdgeToEnd.put(CoonsHandleBuilder.dirPack(va, vb),
                    new float[]{inHE[o], inHE[o + 1], inHE[o + 2]});
        }

        int origVc = inMesh.vertexCount();
        Map<Long, float[]> dh = new HashMap<>();
        for (int ei = 0; ei < outMesh.edgeCount(); ei++) {
            int eid = outMesh.edgeIdAt(ei);
            int he = outMesh.edgeHalfEdge(eid);
            int a = outMesh.halfEdgeVertex(he);
            int b = outMesh.halfEdgeEndVertex(he);
            if (a >= origVc || b >= origVc) continue;

            long keyAB = CoonsHandleBuilder.dirPack(a, b);
            long keyBA = CoonsHandleBuilder.dirPack(b, a);
            float[] startAB = inEdgeToStart.get(keyAB);
            float[] endAB = inEdgeToEnd.get(keyAB);
            if (startAB == null) {
                startAB = inEdgeToEnd.get(keyBA);
                endAB = inEdgeToStart.get(keyBA);
            }
            if (startAB == null) continue;
            dh.put(CoonsHandleBuilder.dirPack(a, b),
                    new float[]{startAB[0], startAB[1], startAB[2]});
            dh.put(CoonsHandleBuilder.dirPack(b, a),
                    new float[]{endAB[0], endAB[1], endAB[2]});
        }

        float[][] handles = CoonsHandleBuilder.flushDirectedHandles(outMesh, dh);
        return outBundle
                .withSlot(AssignBezierHandlesNode.SLOT_HANDLES_START, handles[0])
                .withSlot(AssignBezierHandlesNode.SLOT_HANDLES_END, handles[1]);
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

        // Fast path: when vpf == 4, side quads match the inner-face vpf (always 4 from quad geometry),
        // so output is uniform-quad and we can build ArrayMesh with primitive arrays.
        if (vpf == 4) {
            int outV = vertCount + newVertCount;
            int outF = faceCount + sideFaceCount;
            float[] outPos = new float[outV * 3];
            int[] outFaces = new int[outF * 4];

            System.arraycopy(srcPos, 0, outPos, 0, vertCount * 3);

            int[][] faceInnerVerts = new int[faceCount][];
            int nextVert = vertCount;
            float cx, cy, cz;
            for (int fi = 0; fi < faceCount; fi++) {
                if (!selected[fi]) {
                    continue;
                }
                cx = 0f;
                cy = 0f;
                cz = 0f;
                int fb = fi * 4;
                for (int k = 0; k < 4; k++) {
                    int vid = srcFaces[fb + k];
                    cx += srcPos[vid * 3];
                    cy += srcPos[vid * 3 + 1];
                    cz += srcPos[vid * 3 + 2];
                }
                cx *= 0.25f;
                cy *= 0.25f;
                cz *= 0.25f;
                float t = Math.min(inset, 1.0f);
                int[] innerVerts = new int[4];
                for (int k = 0; k < 4; k++) {
                    int vid = srcFaces[fb + k];
                    float ox = srcPos[vid * 3];
                    float oy = srcPos[vid * 3 + 1];
                    float oz = srcPos[vid * 3 + 2];
                    outPos[nextVert * 3] = ox + (cx - ox) * t;
                    outPos[nextVert * 3 + 1] = oy + (cy - oy) * t;
                    outPos[nextVert * 3 + 2] = oz + (cz - oz) * t;
                    innerVerts[k] = nextVert++;
                }
                faceInnerVerts[fi] = innerVerts;
            }

            int fWrite = 0;
            for (int fi = 0; fi < faceCount; fi++) {
                int fo = fWrite * 4;
                if (selected[fi]) {
                    int[] iv = faceInnerVerts[fi];
                    outFaces[fo] = iv[0];
                    outFaces[fo + 1] = iv[1];
                    outFaces[fo + 2] = iv[2];
                    outFaces[fo + 3] = iv[3];
                } else {
                    int fb = fi * 4;
                    outFaces[fo] = srcFaces[fb];
                    outFaces[fo + 1] = srcFaces[fb + 1];
                    outFaces[fo + 2] = srcFaces[fb + 2];
                    outFaces[fo + 3] = srcFaces[fb + 3];
                }
                fWrite++;
            }

            for (int fi = 0; fi < faceCount; fi++) {
                if (!selected[fi]) {
                    continue;
                }
                int[] iv = faceInnerVerts[fi];
                int fb = fi * 4;
                for (int k = 0; k < 4; k++) {
                    int next = (k + 1) & 3;
                    int fo = fWrite * 4;
                    outFaces[fo] = srcFaces[fb + k];
                    outFaces[fo + 1] = srcFaces[fb + next];
                    outFaces[fo + 2] = iv[next];
                    outFaces[fo + 3] = iv[k];
                    fWrite++;
                }
            }

            ArrayMesh out = new ArrayMesh(outPos, null, outFaces, 4);
            out.computeNormals();
            return out;
        }

        // Fallback for non-quad input (triangles etc): sides would be quads breaking uniformity
        HalfEdgeMesh out = new HalfEdgeMesh(
                vertCount + newVertCount,
                0,
                faceCount + sideFaceCount,
                (faceCount + sideFaceCount) * vpf * 2
        );

        for (int vi = 0; vi < vertCount; vi++) {
            out.addVertex(srcPos[vi * 3], srcPos[vi * 3 + 1], srcPos[vi * 3 + 2]);
        }

        Vector3f center = new Vector3f();
        int[][] faceInnerVerts = new int[faceCount][];

        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;

            center.set(0f, 0f, 0f);
            for (int k = 0; k < vpf; k++) {
                int vid = srcFaces[fi * vpf + k];
                center.add(srcPos[vid * 3], srcPos[vid * 3 + 1], srcPos[vid * 3 + 2]);
            }
            center.div(vpf);

            int[] innerVerts = new int[vpf];
            for (int k = 0; k < vpf; k++) {
                int vid = srcFaces[fi * vpf + k];
                float ox = srcPos[vid * 3];
                float oy = srcPos[vid * 3 + 1];
                float oz = srcPos[vid * 3 + 2];
                float t = Math.min(inset, 1.0f);
                float nx = ox + (center.x - ox) * t;
                float ny = oy + (center.y - oy) * t;
                float nz = oz + (center.z - oz) * t;
                innerVerts[k] = out.addVertex(nx, ny, nz);
            }
            faceInnerVerts[fi] = innerVerts;
        }

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

        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            int[] innerVerts = faceInnerVerts[fi];
            for (int k = 0; k < vpf; k++) {
                int next = (k + 1) % vpf;
                int origA = srcFaces[fi * vpf + k];
                int origB = srcFaces[fi * vpf + next];
                int innerA = innerVerts[k];
                int innerB = innerVerts[next];
                out.addFace(origA, origB, innerB, innerA);
            }
        }

        out.computeNormals();
        return out;
    }
}
