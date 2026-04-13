package ixdar.geometry.mesh.nodes.patch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Bilinearly blended Coons patch per quad face using cubic bezier boundaries
 * from {@link AssignBezierHandlesNode} slots. Each face is subdivided in
 * isolation (duplicate vertices on shared cube edges) so topology stays
 * manifold without resolving inter-face half-edge winding.
 */
@MeshNodeAnnotation(id = "coons_patch")
public class CoonsPatchNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort SUBDIVISIONS = new InputPort("subdivisions", PortType.INT, 4, 1f, 64f);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, SUBDIVISIONS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        Number subNum = ctx.getInput("subdivisions", Number.class);
        int n = subNum == null ? 4 : Math.max(1, subNum.intValue());

        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.faceCount() == 0) {
            ctx.setOutput("geometry", base);
            return;
        }

        float[] hStart = slotFloat3(base, AssignBezierHandlesNode.SLOT_HANDLES_START, mesh);
        float[] hEnd = slotFloat3(base, AssignBezierHandlesNode.SLOT_HANDLES_END, mesh);

        ArrayList<Float> pos = new ArrayList<>(mesh.faceCount() * (n + 1) * (n + 1) * 3);
        ArrayList<int[]> quads = new ArrayList<>(mesh.faceCount() * n * n);

        Vector3f tmp0 = new Vector3f();
        Vector3f tmp1 = new Vector3f();
        Vector3f tmp2 = new Vector3f();
        Vector3f tmp3 = new Vector3f();
        Vector3f bottom = new Vector3f();
        Vector3f top = new Vector3f();
        Vector3f left = new Vector3f();
        Vector3f right = new Vector3f();
        Vector3f p00 = new Vector3f();
        Vector3f p10 = new Vector3f();
        Vector3f p01 = new Vector3f();
        Vector3f p11 = new Vector3f();
        Vector3f loftU = new Vector3f();
        Vector3f loftV = new Vector3f();
        Vector3f mix1 = new Vector3f();
        Vector3f mix2 = new Vector3f();
        Vector3f bilinear = new Vector3f();
        Vector3f out = new Vector3f();
        Vector3f edgePosA = new Vector3f();
        Vector3f edgePosB = new Vector3f();
        Vector3f edgeOff0 = new Vector3f();
        Vector3f edgeOff1 = new Vector3f();

        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            if (mesh.faceVertexCount(fid) != 4) {
                continue;
            }
            int e0 = mesh.faceEdgeAt(fid, 0);
            int e1 = mesh.faceEdgeAt(fid, 1);
            int e2 = mesh.faceEdgeAt(fid, 2);
            int e3 = mesh.faceEdgeAt(fid, 3);
            int v0 = mesh.faceVertexAt(fid, 0);
            int v1 = mesh.faceVertexAt(fid, 1);
            int v3 = mesh.faceVertexAt(fid, 3);

            evalFaceEdge(mesh, hStart, hEnd, e0, v0, 0f, p00, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2,
                    tmp3);
            evalFaceEdge(mesh, hStart, hEnd, e0, v0, 1f, p10, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2,
                    tmp3);
            evalFaceEdge(mesh, hStart, hEnd, e2, v3, 0f, p01, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2,
                    tmp3);
            evalFaceEdge(mesh, hStart, hEnd, e2, v3, 1f, p11, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2,
                    tmp3);

            int baseIdx = pos.size() / 3;
            float invN = 1f / n;

            for (int j = 0; j <= n; j++) {
                float v = j * invN;
                float vS = smootherStep(v);
                for (int i = 0; i <= n; i++) {
                    float u = i * invN;
                    float uS = smootherStep(u);

                    evalFaceEdge(mesh, hStart, hEnd, e0, v0, u, bottom, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0,
                            tmp1, tmp2, tmp3);
                    evalFaceEdge(mesh, hStart, hEnd, e2, v3, u, top, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1,
                            tmp2, tmp3);
                    evalFaceEdge(mesh, hStart, hEnd, e3, v0, v, left, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0,
                            tmp1, tmp2, tmp3);
                    evalFaceEdge(mesh, hStart, hEnd, e1, v1, v, right, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0,
                            tmp1, tmp2, tmp3);

                    loftU.set(bottom).lerp(top, vS);
                    loftV.set(left).lerp(right, uS);
                    mix1.set(p00).lerp(p10, uS);
                    mix2.set(p01).lerp(p11, uS);
                    bilinear.set(mix1).lerp(mix2, vS);
                    out.set(loftU).add(loftV).sub(bilinear);

                    pos.add(out.x);
                    pos.add(out.y);
                    pos.add(out.z);
                }
            }

            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int i00 = baseIdx + j * (n + 1) + i;
                    int i10 = i00 + 1;
                    int i01 = i00 + (n + 1);
                    int i11 = i01 + 1;
                    quads.add(new int[] { i00, i10, i11, i01 });
                }
            }
        }

        if (pos.isEmpty()) {
            ctx.setOutput("geometry", base);
            return;
        }

        float[] positions = new float[pos.size()];
        for (int i = 0; i < pos.size(); i++) {
            positions[i] = pos.get(i);
        }
        int[] faceIndices = new int[quads.size() * 4];
        int w = 0;
        for (int[] q : quads) {
            faceIndices[w++] = q[0];
            faceIndices[w++] = q[1];
            faceIndices[w++] = q[2];
            faceIndices[w++] = q[3];
        }
        HalfEdgeMesh outMesh = HalfEdgeMesh.bulkAllocate(positions, faceIndices, 4);
        outMesh.computeNormals();

        HashMap<String, Object> nextSlots = new HashMap<>(base.slots());
        nextSlots.remove(AssignBezierHandlesNode.SLOT_HANDLES_START);
        nextSlots.remove(AssignBezierHandlesNode.SLOT_HANDLES_END);
        GeometryBundle outBundle = new GeometryBundle(outMesh, Map.copyOf(nextSlots));
        ctx.setOutput("geometry", outBundle);
    }

    private static float[] slotFloat3(GeometryBundle base, String name, MeshTopology mesh) {
        Object o = base.slots().get(name);
        if (!(o instanceof float[] arr)) {
            return zeroHandles(mesh);
        }
        int maxEdgeId = maxEdgeId(mesh);
        int need = (maxEdgeId + 1) * 3;
        if (arr.length < need) {
            float[] padded = new float[need];
            System.arraycopy(arr, 0, padded, 0, Math.min(arr.length, need));
            return padded;
        }
        return arr;
    }

    private static float[] zeroHandles(MeshTopology mesh) {
        int maxEdgeId = maxEdgeId(mesh);
        return new float[(maxEdgeId + 1) * 3];
    }

    private static int maxEdgeId(MeshTopology mesh) {
        int max = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            max = Math.max(max, mesh.edgeIdAt(i));
        }
        return max;
    }

    private static float smootherStep(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    /**
     * Evaluates the cubic bezier on undirected edge {@code eid} so that {@code t=0}
     * is at {@code expectedStartVertex} (one of the edge endpoints), matching face
     * winding.
     */
    private static void evalFaceEdge(
            MeshTopology mesh,
            float[] hStart,
            float[] hEnd,
            int eid,
            int expectedStartVertex,
            float t,
            Vector3f dest,
            Vector3f posCa,
            Vector3f posCb,
            Vector3f offStart,
            Vector3f offEnd,
            Vector3f p0,
            Vector3f p1,
            Vector3f p2,
            Vector3f p3) {
        int he = mesh.edgeHalfEdge(eid);
        int ca = mesh.halfEdgeVertex(he);
        int cb = mesh.halfEdgeEndVertex(he);
        int o = eid * 3;
        mesh.vertexPosition(ca, posCa);
        mesh.vertexPosition(cb, posCb);
        handle(hStart, o, offStart);
        handle(hEnd, o, offEnd);

        if (expectedStartVertex == ca) {
            p0.set(posCa);
            p3.set(posCb);
            p1.set(posCa).add(offStart);
            p2.set(posCb).add(offEnd);
        } else if (expectedStartVertex == cb) {
            p0.set(posCb);
            p3.set(posCa);
            p1.set(posCb).add(offEnd);
            p2.set(posCa).add(offStart);
        } else {
            p0.set(posCa);
            p3.set(posCb);
            p1.set(posCa).add(offStart);
            p2.set(posCb).add(offEnd);
        }
        cubicBezier(p0, p1, p2, p3, t, dest);
    }

    private static void handle(float[] arr, int o, Vector3f dest) {
        if (arr == null || o + 3 > arr.length) {
            dest.set(0f, 0f, 0f);
            return;
        }
        dest.set(arr[o], arr[o + 1], arr[o + 2]);
    }

    private static void cubicBezier(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t, Vector3f dest) {
        float u = 1f - t;
        float uu = u * u;
        float tt = t * t;
        float c0 = uu * u;
        float c1 = 3f * uu * t;
        float c2 = 3f * u * tt;
        float c3 = t * tt;
        dest.x = c0 * p0.x + c1 * p1.x + c2 * p2.x + c3 * p3.x;
        dest.y = c0 * p0.y + c1 * p1.y + c2 * p2.y + c3 * p3.y;
        dest.z = c0 * p0.z + c1 * p1.z + c2 * p2.z + c3 * p3.z;
    }
}
