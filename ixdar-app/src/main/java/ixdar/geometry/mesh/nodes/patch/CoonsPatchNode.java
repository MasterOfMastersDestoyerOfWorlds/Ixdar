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
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * Bilinearly blended Coons patch per quad face using cubic bezier boundaries
 * from {@link AssignBezierHandlesNode} slots. Each face is subdivided in
 * isolation (duplicate vertices on shared cube edges) so topology stays
 * manifold without resolving inter-face half-edge winding.
 */
@MeshNodeAnnotation(id = "coons_patch")
public class CoonsPatchNode implements MeshNode {
    public static final String GEOMETRY_2 = "geometry";
    public static final String SUBDIVISIONS_2 = "subdivisions";
    public static final int NUM_4 = 4;
    public static final int NUM_6 = 6;
    public static final int NUM_600_000 = 600_000;
    public static final double NUM_600_000_0 = 600_000.0;
    public static final int NUM_3 = 3;
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final float NUM_6_2 = 6f;
    public static final float NUM_15 = 15f;
    public static final float NUM_10 = 10f;
    public static final double NUM_2_0 = 2.0;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_3_2 = 3f;

    private static final InputPort GEOMETRY = new InputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort SUBDIVISIONS = new InputPort(SUBDIVISIONS_2, PortType.INT, 4, 1f, 6f);
    private static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Subdivides each face into a smooth surface patch using cubic bezier edge boundaries. Quad faces use a bilinearly blended Coons patch; 3-sided and 5+-sided faces use a Charrot-Gregory patch (n-sided generalization of the C0 Coons patch). Controlled by a subdivisions parameter.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY_2, "Input cage (must carry bezier handle slots from assign_bezier_handles) / output smooth high-poly surface mesh. DESTRUCTIVE: consumes the handle slots. Always follow with merge_by_distance(distance=0.0001) to weld duplicated seam vertices.",
                SUBDIVISIONS_2, "n×n samples per quad face. 4 = 16 quads per face (cheap); 8 = 64 (smooth). Capped internally so total output faces stay under 600k."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, SUBDIVISIONS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public boolean destructive() {
        return true;
    }

    @Override
    public List<String> consumes() {
        return List.of(
                AssignBezierHandlesNode.SLOT_HANDLES_START,
                AssignBezierHandlesNode.SLOT_HANDLES_END);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY_2, Object.class));
        Number subNum = ctx.getInput(SUBDIVISIONS_2, Number.class);
        int n = subNum == null ? NUM_4 : Math.max(1, Math.min(NUM_6, subNum.intValue()));

        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.faceCount() == 0) {
            ctx.setOutput(GEOMETRY_2, base);
            return;
        }

        // OOM guard: cap subdivisions so output stays under 600k faces
        int inputFaces = mesh.faceCount();
        if (inputFaces > 0) {
            long estimated = (long) inputFaces * n * n;
            if (estimated > NUM_600_000) {
                int safeN = Math.max(1, (int) Math.sqrt(NUM_600_000_0 / inputFaces));
                System.err.println("[coons_patch] Capped subdivisions from " + n + " to " + safeN
                        + " (" + inputFaces + " input faces × " + n + "² = " + estimated
                        + " would exceed 600k limit)");
                n = safeN;
            }
        }

        float[] hStart = slotFloat3(base, AssignBezierHandlesNode.SLOT_HANDLES_START, mesh);
        float[] hEnd = slotFloat3(base, AssignBezierHandlesNode.SLOT_HANDLES_END, mesh);

        // Grow-able output buffers. A typical subdivided voyage cage fits in
        // the default ArrayList capacity; non-quad n-gons produce triangles
        // rather than quads so the output mesh is mixed-vpf (handled via
        // HalfEdgeMeshEngine.bulkAllocateMixed).
        ArrayList<Float> positionsList = new ArrayList<>();
        ArrayList<Integer> faceIndicesList = new ArrayList<>();
        ArrayList<Integer> faceVertexCountsList = new ArrayList<>();
        int[] vertCountBox = new int[]{0};

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
            int vpf = mesh.faceVertexCount(fid);
            if (vpf == NUM_4) {
                // === Quad path: bilinear Coons patch, emitted as n² quads ===
                int e0 = mesh.faceEdgeAt(fid, 0);
                int e1 = mesh.faceEdgeAt(fid, 1);
                int e2 = mesh.faceEdgeAt(fid, 2);
                int e3 = mesh.faceEdgeAt(fid, NUM_3);
                int v0 = mesh.faceVertexAt(fid, 0);
                int v1 = mesh.faceVertexAt(fid, 1);
                int v3 = mesh.faceVertexAt(fid, NUM_3);

                evalFaceEdge(mesh, hStart, hEnd, e0, v0, NUM_0, p00, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2, tmp3);
                evalFaceEdge(mesh, hStart, hEnd, e0, v0, NUM_1, p10, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2, tmp3);
                evalFaceEdge(mesh, hStart, hEnd, e2, v3, NUM_0, p01, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2, tmp3);
                evalFaceEdge(mesh, hStart, hEnd, e2, v3, NUM_1, p11, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2, tmp3);

                int baseIdx = vertCountBox[0];
                float invN = NUM_1 / n;
                for (int j = 0; j <= n; j++) {
                    float v = j * invN;
                    float vS = smootherStep(v);
                    for (int i = 0; i <= n; i++) {
                        float u = i * invN;
                        float uS = smootherStep(u);

                        evalFaceEdge(mesh, hStart, hEnd, e0, v0, u, bottom, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2, tmp3);
                        evalFaceEdge(mesh, hStart, hEnd, e2, v3, u, top, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2, tmp3);
                        evalFaceEdge(mesh, hStart, hEnd, e3, v0, v, left, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2, tmp3);
                        evalFaceEdge(mesh, hStart, hEnd, e1, v1, v, right, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2, tmp3);

                        loftU.set(bottom).lerp(top, vS);
                        loftV.set(left).lerp(right, uS);
                        mix1.set(p00).lerp(p10, uS);
                        mix2.set(p01).lerp(p11, uS);
                        bilinear.set(mix1).lerp(mix2, vS);
                        out.set(loftU).add(loftV).sub(bilinear);

                        positionsList.add(out.x);
                        positionsList.add(out.y);
                        positionsList.add(out.z);
                        vertCountBox[0]++;
                    }
                }
                for (int j = 0; j < n; j++) {
                    for (int i = 0; i < n; i++) {
                        int i00 = baseIdx + j * (n + 1) + i;
                        int i10 = i00 + 1;
                        int i01 = i00 + (n + 1);
                        int i11 = i01 + 1;
                        faceIndicesList.add(i00);
                        faceIndicesList.add(i10);
                        faceIndicesList.add(i11);
                        faceIndicesList.add(i01);
                        faceVertexCountsList.add(NUM_4);
                    }
                }
            } else if (vpf >= NUM_3) {
                // === N-sided path: Charrot-Gregory patch, emitted as N·n tris ===
                emitGregoryFan(mesh, hStart, hEnd, fid, vpf, n,
                        positionsList, faceIndicesList, faceVertexCountsList, vertCountBox);
            }
            // vpf < 3 is malformed; ignore.
        }

        int vertCount = vertCountBox[0];
        if (vertCount == 0) {
            ctx.setOutput(GEOMETRY_2, base);
            return;
        }

        float[] positions = new float[positionsList.size()];
        for (int i = 0; i < positionsList.size(); i++) positions[i] = positionsList.get(i);
        int[] faceIdxFlat = new int[faceIndicesList.size()];
        for (int i = 0; i < faceIndicesList.size(); i++) faceIdxFlat[i] = faceIndicesList.get(i);
        int[] faceVertexCounts = new int[faceVertexCountsList.size()];
        for (int i = 0; i < faceVertexCountsList.size(); i++) faceVertexCounts[i] = faceVertexCountsList.get(i);

        // If every emitted face is a quad, keep the ArrayMesh output for
        // bitwise-compatibility with downstream consumers. Mixed output uses
        // HalfEdgeMesh via bulkAllocateMixed since ArrayMesh requires uniform vpf.
        MeshTopology outMesh;
        boolean allQuads = true;
        for (int c : faceVertexCounts) {
            if (c != NUM_4) { allQuads = false; break; }
        }
        if (allQuads) {
            ArrayMesh am = new ArrayMesh(positions, null, faceIdxFlat, NUM_4);
            am.computeNormals();
            outMesh = am;
        } else {
            HalfEdgeMesh hem = HalfEdgeMeshEngine.bulkAllocateMixed(positions, faceVertexCounts, faceIdxFlat);
            hem.computeNormals();
            outMesh = hem;
        }

        HashMap<String, Object> nextSlots = new HashMap<>(base.slots());
        nextSlots.remove(AssignBezierHandlesNode.SLOT_HANDLES_START);
        nextSlots.remove(AssignBezierHandlesNode.SLOT_HANDLES_END);
        GeometryBundle outBundle = new GeometryBundle(outMesh, Map.copyOf(nextSlots));
        ctx.setOutput(GEOMETRY_2, outBundle);
    }

    private static float[] slotFloat3(GeometryBundle base, String name, MeshTopology mesh) {
        Object o = base.slots().get(name);
        if (!(o instanceof float[] arr)) {
            return zeroHandles(mesh);
        }
        int maxEdgeId = maxEdgeId(mesh);
        int need = (maxEdgeId + 1) * NUM_3;
        if (arr.length < need) {
            float[] padded = new float[need];
            System.arraycopy(arr, 0, padded, 0, Math.min(arr.length, need));
            return padded;
        }
        return arr;
    }

    private static float[] zeroHandles(MeshTopology mesh) {
        int maxEdgeId = maxEdgeId(mesh);
        return new float[(maxEdgeId + 1) * NUM_3];
    }

    private static int maxEdgeId(MeshTopology mesh) {
        int max = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            max = Math.max(max, mesh.edgeIdAt(i));
        }
        return max;
    }

    private static float smootherStep(float t) {
        return t * t * t * (t * (t * NUM_6_2 - NUM_15) + NUM_10);
    }

    /**
     * Emit a Charrot-Gregory-subdivided n-sided fill patch for {@code fid}: one
     * centroid vertex plus {@code n} evenly spaced samples along each of the
     * {@code N} canonical edges, fanned into {@code N · n} triangles.
     */
    private static void emitGregoryFan(MeshTopology mesh, float[] hStart, float[] hEnd,
                                       int fid, int N, int n,
                                       ArrayList<Float> positionsList,
                                       ArrayList<Integer> faceIndicesList,
                                       ArrayList<Integer> faceVertexCountsList,
                                       int[] vertCountBox) {
        // Build N boundary bezier curves oriented face-CCW: each curve goes
        // from face corner k to face corner (k+1) % N.
        Vector3f[][] curves = new Vector3f[N][];
        for (int k = 0; k < N; k++) {
            int eid = mesh.faceEdgeAt(fid, k);
            int startVid = mesh.faceVertexAt(fid, k);
            int endVid = mesh.faceVertexAt(fid, (k + 1) % N);
            curves[k] = extractOrientedBezier(mesh, hStart, hEnd, eid, startVid, endVid);
        }

        // Canonical n-gon vertices: on the unit circle at angles
        // (i + 0.5) · 2π/N + π — matches CharrotGregoryPatch.
        float twoPi = (float) (NUM_2_0 * Math.PI);
        float pi = (float) Math.PI;
        float[] vertsU = new float[N];
        float[] vertsV = new float[N];
        for (int k = 0; k < N; k++) {
            float theta = (k + NUM_0_5) * twoPi / N + pi;
            vertsU[k] = (float) Math.cos(theta);
            vertsV[k] = (float) Math.sin(theta);
        }

        int baseIdx = vertCountBox[0];
        int centroidIdx = baseIdx;
        Vector3f sampled = new Vector3f();

        // Centroid (domain origin).
        CharrotGregoryPatch.evaluate(curves, NUM_0, NUM_0, sampled);
        positionsList.add(sampled.x);
        positionsList.add(sampled.y);
        positionsList.add(sampled.z);
        vertCountBox[0]++;

        // N · n boundary-step samples. boundarySampleIdx[k * n + step] holds
        // the flat position-array index of the sample at edge k, step /n.
        int[] boundarySampleIdx = new int[N * n];
        for (int k = 0; k < N; k++) {
            float uA = vertsU[k];
            float vA = vertsV[k];
            float uB = vertsU[(k + 1) % N];
            float vB = vertsV[(k + 1) % N];
            for (int step = 0; step < n; step++) {
                float s = (float) step / (float) n;
                float u = uA + (uB - uA) * s;
                float v = vA + (vB - vA) * s;
                CharrotGregoryPatch.evaluate(curves, u, v, sampled);
                positionsList.add(sampled.x);
                positionsList.add(sampled.y);
                positionsList.add(sampled.z);
                boundarySampleIdx[k * n + step] = vertCountBox[0];
                vertCountBox[0]++;
            }
        }

        // Emit the fan triangles.
        for (int k = 0; k < N; k++) {
            for (int step = 0; step < n - 1; step++) {
                int a = boundarySampleIdx[k * n + step];
                int b = boundarySampleIdx[k * n + step + 1];
                faceIndicesList.add(centroidIdx);
                faceIndicesList.add(a);
                faceIndicesList.add(b);
                faceVertexCountsList.add(NUM_3);
            }
            // Wrap-around: last sample of edge k → first sample of edge (k+1).
            int a = boundarySampleIdx[k * n + (n - 1)];
            int b = boundarySampleIdx[((k + 1) % N) * n + 0];
            faceIndicesList.add(centroidIdx);
            faceIndicesList.add(a);
            faceIndicesList.add(b);
            faceVertexCountsList.add(NUM_3);
        }
    }

    /**
     * Build a cubic bezier curve's 4 control points oriented
     * {@code startVid → endVid}, using the half-edge's canonical handles.
     * Returns {P0, P1, P2, P3} where P0 = startVid's position and P3 = endVid's.
     */
    private static Vector3f[] extractOrientedBezier(MeshTopology mesh, float[] hStart, float[] hEnd,
                                                    int eid, int startVid, int endVid) {
        int he = mesh.edgeHalfEdge(eid);
        int ca = mesh.halfEdgeVertex(he);
        int o = eid * NUM_3;
        Vector3f p0 = new Vector3f();
        Vector3f p3 = new Vector3f();
        mesh.vertexPosition(startVid, p0);
        mesh.vertexPosition(endVid, p3);

        Vector3f offStart = new Vector3f();
        Vector3f offEnd = new Vector3f();
        if (hStart != null && o + NUM_3 <= hStart.length) {
            offStart.set(hStart[o], hStart[o + 1], hStart[o + 2]);
        }
        if (hEnd != null && o + NUM_3 <= hEnd.length) {
            offEnd.set(hEnd[o], hEnd[o + 1], hEnd[o + 2]);
        }

        // Canonical handle direction is ca → cb. If we want startVid → endVid
        // and startVid is cb (reversed), swap the offsets.
        Vector3f p1 = new Vector3f(p0);
        Vector3f p2 = new Vector3f(p3);
        if (startVid == ca) {
            p1.add(offStart);
            p2.add(offEnd);
        } else {
            p1.add(offEnd);
            p2.add(offStart);
        }
        return new Vector3f[]{p0, p1, p2, p3};
    }

    /**
     * Evaluates the cubic bezier on undirected edge {@code eid} so that {@code t=0}
     * is at {@code expectedStartVertex}, matching face winding. When the face
     * traverses the edge against the canonical half-edge direction the parameter
     * is flipped to {@code 1-t} rather than the control points reversed, so both
     * faces sharing the edge produce bitwise-identical positions.
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
        int o = eid * NUM_3;
        mesh.vertexPosition(ca, posCa);
        mesh.vertexPosition(cb, posCb);
        handle(hStart, o, offStart);
        handle(hEnd, o, offEnd);

        // Always canonical direction: ca → cb
        p0.set(posCa);
        p1.set(posCa).add(offStart);
        p2.set(posCb).add(offEnd);
        p3.set(posCb);

        // Flip parameter when face winding reverses edge direction
        float evalT = (expectedStartVertex == cb) ? NUM_1 - t : t;
        cubicBezier(p0, p1, p2, p3, evalT, dest);
    }

    private static void handle(float[] arr, int o, Vector3f dest) {
        if (arr == null || o + NUM_3 > arr.length) {
            dest.set(NUM_0, NUM_0, NUM_0);
            return;
        }
        dest.set(arr[o], arr[o + 1], arr[o + 2]);
    }

    private static void cubicBezier(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t, Vector3f dest) {
        float u = NUM_1 - t;
        float uu = u * u;
        float tt = t * t;
        float c0 = uu * u;
        float c1 = NUM_3_2 * uu * t;
        float c2 = NUM_3_2 * u * tt;
        float c3 = t * tt;
        dest.x = c0 * p0.x + c1 * p1.x + c2 * p2.x + c3 * p3.x;
        dest.y = c0 * p0.y + c1 * p1.y + c2 * p2.y + c3 * p3.y;
        dest.z = c0 * p0.z + c1 * p1.z + c2 * p2.z + c3 * p3.z;
    }
}
