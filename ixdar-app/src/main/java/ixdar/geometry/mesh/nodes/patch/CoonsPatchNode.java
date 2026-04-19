package ixdar.geometry.mesh.nodes.patch;

import java.util.ArrayList;
import java.util.Arrays;
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
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.HalfEdgeMeshEngine;
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
    private static final InputPort SUBDIVISIONS = new InputPort("subdivisions", PortType.INT, 4, 1f, 6f);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Subdivides each face into a smooth surface patch using cubic bezier edge boundaries. Quad faces use a bilinearly blended Coons patch; 3-sided and 5+-sided faces use a Charrot-Gregory patch (n-sided generalization of the C0 Coons patch). Controlled by a subdivisions parameter.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "geometry", "Input cage (must carry bezier handle slots from assign_bezier_handles) / output smooth high-poly surface mesh. DESTRUCTIVE: consumes the handle slots. Always follow with merge_by_distance(distance=0.0001) to weld duplicated seam vertices.",
                "subdivisions", "n×n samples per quad face. 4 = 16 quads per face (cheap); 8 = 64 (smooth). Capped internally so total output faces stay under 600k."
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
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        Number subNum = ctx.getInput("subdivisions", Number.class);
        int n = subNum == null ? 4 : Math.max(1, Math.min(6, subNum.intValue()));

        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.faceCount() == 0) {
            ctx.setOutput("geometry", base);
            return;
        }

        // OOM guard: cap subdivisions so output stays under 600k faces
        int inputFaces = mesh.faceCount();
        if (inputFaces > 0) {
            long estimated = (long) inputFaces * n * n;
            if (estimated > 600_000) {
                int safeN = Math.max(1, (int) Math.sqrt(600_000.0 / inputFaces));
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
        java.util.ArrayList<Float> positionsList = new ArrayList<>();
        java.util.ArrayList<Integer> faceIndicesList = new ArrayList<>();
        java.util.ArrayList<Integer> faceVertexCountsList = new ArrayList<>();
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
            if (vpf == 4) {
                // === Quad path: bilinear Coons patch, emitted as n² quads ===
                int e0 = mesh.faceEdgeAt(fid, 0);
                int e1 = mesh.faceEdgeAt(fid, 1);
                int e2 = mesh.faceEdgeAt(fid, 2);
                int e3 = mesh.faceEdgeAt(fid, 3);
                int v0 = mesh.faceVertexAt(fid, 0);
                int v1 = mesh.faceVertexAt(fid, 1);
                int v3 = mesh.faceVertexAt(fid, 3);

                evalFaceEdge(mesh, hStart, hEnd, e0, v0, 0f, p00, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2, tmp3);
                evalFaceEdge(mesh, hStart, hEnd, e0, v0, 1f, p10, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2, tmp3);
                evalFaceEdge(mesh, hStart, hEnd, e2, v3, 0f, p01, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2, tmp3);
                evalFaceEdge(mesh, hStart, hEnd, e2, v3, 1f, p11, edgePosA, edgePosB, edgeOff0, edgeOff1, tmp0, tmp1, tmp2, tmp3);

                int baseIdx = vertCountBox[0];
                float invN = 1f / n;
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
                        faceVertexCountsList.add(4);
                    }
                }
            } else if (vpf >= 3) {
                // === N-sided path: Charrot-Gregory patch, emitted as N·n tris ===
                emitGregoryFan(mesh, hStart, hEnd, fid, vpf, n,
                        positionsList, faceIndicesList, faceVertexCountsList, vertCountBox);
            }
            // vpf < 3 is malformed; ignore.
        }

        int vertCount = vertCountBox[0];
        if (vertCount == 0) {
            ctx.setOutput("geometry", base);
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
            if (c != 4) { allQuads = false; break; }
        }
        if (allQuads) {
            ArrayMesh am = new ArrayMesh(positions, null, faceIdxFlat, 4);
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
     * Emit a Charrot-Gregory-subdivided n-sided fill patch for {@code fid}.
     * Produces {@code N} triangles for the centroid fan at each subdivision
     * ring; with subdivision level {@code n} that's {@code N · n} triangles
     * and {@code 1 + N · n} vertices per face.
     *
     * <p>Sampling pattern:
     * <ul>
     *   <li>1 centroid vertex at canonical domain origin.
     *   <li>N·n boundary-step vertices — {@code n} evenly spaced samples
     *       along each canonical edge (from its start corner, excluding the
     *       end corner which belongs to the next edge).
     *   <li>Fan triangles: {@code (centroid, prev-sample, next-sample)} for
     *       each adjacent pair of boundary samples, wrapping to sample 0 of
     *       the next edge after the last sample of the current edge.
     * </ul>
     */
    private static void emitGregoryFan(MeshTopology mesh, float[] hStart, float[] hEnd,
                                       int fid, int N, int n,
                                       java.util.ArrayList<Float> positionsList,
                                       java.util.ArrayList<Integer> faceIndicesList,
                                       java.util.ArrayList<Integer> faceVertexCountsList,
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
        float twoPi = (float) (2.0 * Math.PI);
        float pi = (float) Math.PI;
        float[] vertsU = new float[N];
        float[] vertsV = new float[N];
        for (int k = 0; k < N; k++) {
            float theta = (k + 0.5f) * twoPi / N + pi;
            vertsU[k] = (float) Math.cos(theta);
            vertsV[k] = (float) Math.sin(theta);
        }

        int baseIdx = vertCountBox[0];
        int centroidIdx = baseIdx;
        Vector3f sampled = new Vector3f();

        // Centroid (domain origin).
        CharrotGregoryPatch.evaluate(curves, 0f, 0f, sampled);
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
                faceVertexCountsList.add(3);
            }
            // Wrap-around: last sample of edge k → first sample of edge (k+1).
            int a = boundarySampleIdx[k * n + (n - 1)];
            int b = boundarySampleIdx[((k + 1) % N) * n + 0];
            faceIndicesList.add(centroidIdx);
            faceIndicesList.add(a);
            faceIndicesList.add(b);
            faceVertexCountsList.add(3);
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
        int cb = mesh.halfEdgeEndVertex(he);
        int o = eid * 3;
        Vector3f p0 = new Vector3f();
        Vector3f p3 = new Vector3f();
        mesh.vertexPosition(startVid, p0);
        mesh.vertexPosition(endVid, p3);

        Vector3f offStart = new Vector3f();
        Vector3f offEnd = new Vector3f();
        if (hStart != null && o + 3 <= hStart.length) {
            offStart.set(hStart[o], hStart[o + 1], hStart[o + 2]);
        }
        if (hEnd != null && o + 3 <= hEnd.length) {
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
     * is at {@code expectedStartVertex} (one of the edge endpoints), matching face
     * winding.
     * <p>
     * Control points are always set up in canonical half-edge direction (ca → cb).
     * When the face traverses the edge in reverse, the parameter is flipped to
     * {@code 1-t} instead of reversing the control points. This guarantees both
     * faces sharing an edge produce bitwise-identical positions, making
     * merge-by-distance reliable.
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

        // Always canonical direction: ca → cb
        p0.set(posCa);
        p1.set(posCa).add(offStart);
        p2.set(posCb).add(offEnd);
        p3.set(posCb);

        // Flip parameter when face winding reverses edge direction
        float evalT = (expectedStartVertex == cb) ? 1f - t : t;
        cubicBezier(p0, p1, p2, p3, evalT, dest);
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
