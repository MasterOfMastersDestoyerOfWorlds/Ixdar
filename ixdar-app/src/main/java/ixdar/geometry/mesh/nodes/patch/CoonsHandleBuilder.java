package ixdar.geometry.mesh.nodes.patch;
import java.util.Map;

import java.util.HashMap;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Shared utilities for nodes that preserve bezier handle metadata through topology edits.
 * <p>
 * Collect per-directed-edge handle offsets into a {@link Map} keyed by
 * {@link #dirPack(int, int)}, then call {@link #flushDirectedHandles(MeshTopology, Map)}
 * to realize them as {@code _bezier_handles_start / _bezier_handles_end} float arrays
 * indexed by the output mesh's edge IDs.
 */
public final class CoonsHandleBuilder {
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;
    public static final int NUM_3 = 3;
    public static final float NUM_1e_6 = 1e-6f;
    public static final float NUM_2 = 2f;
    public static final float NUM_1 = 1f;
    public static final float NUM_3_2 = 3f;
    public static final float NUM_6 = 6f;
    public static final float NUM_15 = 15f;
    public static final float NUM_10 = 10f;
    public static final float NUM_1e_3 = 1e-3f;
    public static final float NUM_0 = 0f;
    public static final float NUM_1e_8 = 1e-8f;

    private CoonsHandleBuilder() {
    }

    /**
     * Packs a directed vertex pair {@code (from, to)} into a stable long key
     * for the per-edge handle map.
     *
     * @param from start vertex id
     * @param to end vertex id
     * @return packed {@code long} key suitable for use in a {@link Map}
     */
    public static long dirPack(int from, int to) {
        return ((long) from << NUM_32) | (to & NUM_0xffffffff);
    }

    /**
     * Reads a handle slot (float[3 per edge id]) from a bundle, padding with
     * zeros if the stored array is shorter than needed for {@code mesh}'s edge
     * IDs. Returns zero-filled array when the slot is absent or not a float[].
     *
     * @param base bundle to read from
     * @param slotName slot key, typically {@link AssignBezierHandlesNode#SLOT_HANDLES_START} or {@code _END}
     * @param mesh mesh whose edge IDs determine the required array length
     * @return float array of length {@code (maxEdgeId + 1) * 3}, zero-padded as needed
     */
    public static float[] readHandleSlot(GeometryBundle base, String slotName, MeshTopology mesh) {
        Object o = base.slots().get(slotName);
        int maxEid = maxEdgeId(mesh);
        int need = (maxEid + 1) * NUM_3;
        if (!(o instanceof float[] arr)) {
            return new float[need];
        }
        if (arr.length < need) {
            float[] padded = new float[need];
            System.arraycopy(arr, 0, padded, 0, Math.min(arr.length, need));
            return padded;
        }
        return arr;
    }

    /**
     * True if the bundle has a non-null, non-empty {@code _bezier_handles_start}
     * slot — a cheap check for whether a node should take its curve-preserving
     * path vs its straight-math fallback.
     *
     * @param base bundle to inspect
     * @return {@code true} if start-handle data is present and non-empty
     */
    public static boolean hasHandles(GeometryBundle base) {
        Object o = base.slots().get(AssignBezierHandlesNode.SLOT_HANDLES_START);
        return o instanceof float[] arr && arr.length > 0;
    }

    /**
     * Returns {@code max(mesh.edgeIdAt(i))} across all edges in {@code mesh}, or
     * 0 for an empty mesh.
     *
     * @param mesh mesh to scan
     * @return largest active edge id, or 0 if no edges exist
     */
    public static int maxEdgeId(MeshTopology mesh) {
        int max = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            max = Math.max(max, mesh.edgeIdAt(i));
        }
        return max;
    }

    /**
     * Returns the four cubic bezier control points of the curve on edge
     * {@code eid}, oriented so that {@code P0} is at {@code fromVid}. When
     * {@code fromVid} is the canonical half-edge's end vertex, the points and
     * their handle offsets are reversed to match the requested direction.
     *
     * @param mesh source topology
     * @param hStart start-handle slot data ({@code 3 * eid} indexed)
     * @param hEnd end-handle slot data ({@code 3 * eid} indexed)
     * @param eid edge id
     * @param fromVid endpoint of {@code eid} that should map to {@code P0}
     * @return four-element {@code [P0, P1, P2, P3]} array
     */
    public static Vector3f[] getEdgeControlPoints(MeshTopology mesh, float[] hStart, float[] hEnd,
            int eid, int fromVid) {
        int he = mesh.edgeHalfEdge(eid);
        int ca = mesh.halfEdgeVertex(he);
        int cb = mesh.halfEdgeEndVertex(he);
        int o = eid * NUM_3;
        Vector3f posCa = mesh.vertexPosition(ca, new Vector3f());
        Vector3f posCb = mesh.vertexPosition(cb, new Vector3f());
        Vector3f offS = new Vector3f(hStart[o], hStart[o + 1], hStart[o + 2]);
        Vector3f offE = new Vector3f(hEnd[o], hEnd[o + 1], hEnd[o + 2]);
        if (fromVid == ca) {
            return new Vector3f[]{
                    new Vector3f(posCa),
                    new Vector3f(posCa).add(offS),
                    new Vector3f(posCb).add(offE),
                    new Vector3f(posCb)};
        }
        return new Vector3f[]{
                new Vector3f(posCb),
                new Vector3f(posCb).add(offE),
                new Vector3f(posCa).add(offS),
                new Vector3f(posCa)};
    }

    /**
     * de Casteljau split of the cubic bezier {@code (p0, p1, p2, p3)} at
     * parameter {@code t ∈ [0, 1]}. Does not mutate the inputs.
     *
     * @param p0 first control point
     * @param p1 second control point
     * @param p2 third control point
     * @param p3 fourth control point
     * @param t split parameter in {@code [0, 1]}
     * @return {@link SplitResult} with the split point and the four interior control points
     */
    public static SplitResult split(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t) {
        Vector3f a = new Vector3f(p0).lerp(p1, t);
        Vector3f b = new Vector3f(p1).lerp(p2, t);
        Vector3f c = new Vector3f(p2).lerp(p3, t);
        Vector3f d = new Vector3f(a).lerp(b, t);
        Vector3f e = new Vector3f(b).lerp(c, t);
        Vector3f f = new Vector3f(d).lerp(e, t);
        return new SplitResult(f, a, d, e, c);
    }

    /**
     * Rebuilds bezier handle slots after a vertex-welding operation such as
     * {@code merge_by_distance}, pairing input to output edges by nearest
     * welded endpoint within {@code 2 * weldDist}.
     * <p>
     * Edges that collapse into a single vertex are skipped, and when two input
     * edges map onto the same output edge the first one wins.
     *
     * @param inHStart start-handle data on the input mesh, indexed by input edge id
     * @param inHEnd end-handle data on the input mesh, indexed by input edge id
     * @param inMesh pre-weld mesh
     * @param outMesh post-weld mesh
     * @param weldDist weld tolerance used to match input vertices to output vertices (matched within {@code 2 * weldDist})
     * @return the resulting {@code [hStart, hEnd]} arrays indexed by output
     *         edge ID, or {@code null} if either input mesh or output mesh is
     *         null or empty.
     */
    public static float[][] rebuildHandlesAfterWeld(
            float[] inHStart, float[] inHEnd,
            MeshTopology inMesh, MeshTopology outMesh, float weldDist) {
        if (inMesh == null || outMesh == null
                || inMesh.vertexCount() == 0 || outMesh.vertexCount() == 0) {
            return null;
        }
        int outVc = outMesh.vertexCount();
        float[] outPos = new float[outVc * NUM_3];
        int[] outVids = new int[outVc];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < outVc; i++) {
            int vid = outMesh.vertexIdAt(i);
            outVids[i] = vid;
            outMesh.vertexPosition(vid, tmp);
            outPos[i * NUM_3] = tmp.x;
            outPos[i * NUM_3 + 1] = tmp.y;
            outPos[i * NUM_3 + 2] = tmp.z;
        }
        float tol = Math.max(weldDist, NUM_1e_6) * NUM_2;
        float tol2 = tol * tol;
        Map<Integer, Integer> inToOut = new HashMap<>();
        int inVc = inMesh.vertexCount();
        for (int i = 0; i < inVc; i++) {
            int ivid = inMesh.vertexIdAt(i);
            inMesh.vertexPosition(ivid, tmp);
            int best = -1;
            float bestD = Float.POSITIVE_INFINITY;
            for (int k = 0; k < outVc; k++) {
                float dx = tmp.x - outPos[k * NUM_3];
                float dy = tmp.y - outPos[k * NUM_3 + 1];
                float dz = tmp.z - outPos[k * NUM_3 + 2];
                float d2 = dx * dx + dy * dy + dz * dz;
                if (d2 < bestD) {
                    bestD = d2;
                    best = k;
                }
            }
            if (best >= 0 && bestD <= tol2) {
                inToOut.put(ivid, outVids[best]);
            }
        }
        Map<Long, float[]> dh = new HashMap<>();
        for (int ei = 0; ei < inMesh.edgeCount(); ei++) {
            int eid = inMesh.edgeIdAt(ei);
            int he = inMesh.edgeHalfEdge(eid);
            int va = inMesh.halfEdgeVertex(he);
            int vb = inMesh.halfEdgeEndVertex(he);
            Integer oa = inToOut.get(va);
            Integer ob = inToOut.get(vb);
            if (oa == null || ob == null || oa.intValue() == ob.intValue()) {
                continue;
            }
            int o = eid * NUM_3;
            dh.putIfAbsent(dirPack(oa, ob),
                    new float[]{inHStart[o], inHStart[o + 1], inHStart[o + 2]});
            dh.putIfAbsent(dirPack(ob, oa),
                    new float[]{inHEnd[o], inHEnd[o + 1], inHEnd[o + 2]});
        }
        return flushDirectedHandles(outMesh, dh);
    }

    /**
     * Realizes a directed-handle map into {@code _bezier_handles_start} and
     * {@code _bezier_handles_end} arrays keyed by {@code outMesh}'s edge IDs.
     * <p>
     * For each output edge {@code eid}, looks up:
     * <ul>
     *   <li>{@code dh.get(dirPack(canonicalStart, canonicalEnd))} → start handle</li>
     *   <li>{@code dh.get(dirPack(canonicalEnd, canonicalStart))} → end handle</li>
     * </ul>
     * Missing entries default to zero.
     *
     * @param outMesh output mesh providing canonical edge directions
     * @param dh directed-handle map keyed by {@link #dirPack(int, int)}
     * @return float[] array of length 2: {@code [0] = hStart, [1] = hEnd}, both
     *         indexed by {@code eid * 3}.
     */
    public static float[][] flushDirectedHandles(MeshTopology outMesh, Map<Long, float[]> dh) {
        int outMaxEid = maxEdgeId(outMesh);
        float[] hs = new float[(outMaxEid + 1) * NUM_3];
        float[] he = new float[(outMaxEid + 1) * NUM_3];
        for (int i = 0; i < outMesh.edgeCount(); i++) {
            int eid = outMesh.edgeIdAt(i);
            int ohe = outMesh.edgeHalfEdge(eid);
            int ca = outMesh.halfEdgeVertex(ohe);
            int cb = outMesh.halfEdgeEndVertex(ohe);
            int o = eid * NUM_3;
            float[] s = dh.get(dirPack(ca, cb));
            if (s != null) {
                hs[o] = s[0]; hs[o + 1] = s[1]; hs[o + 2] = s[2];
            }
            float[] e = dh.get(dirPack(cb, ca));
            if (e != null) {
                he[o] = e[0]; he[o + 1] = e[1]; he[o + 2] = e[2];
            }
        }
        return new float[][]{hs, he};
    }

    // ------------------------------------------------------------------
    // Coons-surface evaluation (shared with CoonsPatchNode)
    // ------------------------------------------------------------------

    /**
     * Evaluates a cubic Bezier at parameter {@code t}. Allocating version —
     * fine outside hot loops. Use {@link #cubicBezier(Vector3f, Vector3f,
     * Vector3f, Vector3f, float, Vector3f)} when you have a destination to
     * reuse.
     *
     * @param p0 first control point
     * @param p1 second control point
     * @param p2 third control point
     * @param p3 fourth control point
     * @param t parameter in {@code [0, 1]}
     * @return newly-allocated {@link Vector3f} with the curve sample
     */
    public static Vector3f cubicBezier(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t) {
        Vector3f dest = new Vector3f();
        cubicBezier(p0, p1, p2, p3, t, dest);
        return dest;
    }

    /**
     * Evaluates a cubic Bezier at {@code t}, writing into {@code dest}.
     *
     * @param p0 first control point
     * @param p1 second control point
     * @param p2 third control point
     * @param p3 fourth control point
     * @param t parameter in {@code [0, 1]}
     * @param dest output vector overwritten with the curve sample
     */
    public static void cubicBezier(
            Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t, Vector3f dest) {
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

    /**
     * Samples the cubic Bezier on undirected edge {@code eid} so that {@code t=0}
     * is at {@code expectedStartVid}. When the canonical half-edge direction
     * disagrees the parameter is flipped rather than the control points, so the
     * two faces sharing the edge get bitwise-identical samples.
     *
     * @param mesh source topology
     * @param hStart start-handle slot data
     * @param hEnd end-handle slot data
     * @param eid edge id of the curve to sample
     * @param expectedStartVid endpoint of {@code eid} that {@code t = 0} should align with
     * @param t parameter in {@code [0, 1]}
     * @return curve sample
     */
    public static Vector3f evalFaceEdgeAt(
            MeshTopology mesh, float[] hStart, float[] hEnd,
            int eid, int expectedStartVid, float t) {
        int he = mesh.edgeHalfEdge(eid);
        int ca = mesh.halfEdgeVertex(he);
        int cb = mesh.halfEdgeEndVertex(he);
        int o = eid * NUM_3;
        Vector3f posCa = mesh.vertexPosition(ca, new Vector3f());
        Vector3f posCb = mesh.vertexPosition(cb, new Vector3f());
        Vector3f p1 = new Vector3f(posCa).add(hStart[o], hStart[o + 1], hStart[o + 2]);
        Vector3f p2 = new Vector3f(posCb).add(hEnd[o], hEnd[o + 1], hEnd[o + 2]);
        float evalT = (expectedStartVid == cb) ? NUM_1 - t : t;
        return cubicBezier(posCa, p1, p2, posCb, evalT);
    }

    /**
     * Smootherstep — the same easing {@link CoonsPatchNode} uses for its
     * bilinear blend, so surface samples match.
     *
     * @param t parameter, expected in {@code [0, 1]}
     * @return eased value
     */
    public static float smootherStep(float t) {
        return t * t * t * (t * (t * NUM_6 - NUM_15) + NUM_10);
    }

    /**
     * Coons surface normal at parameter {@code (u, v)} on a quad face, by
     * symmetric finite difference on {@link #evalCoonsSurface}, one-sided at the
     * parameter-square boundary. Signed to match the face's flat-polygon normal;
     * a zero-length result signals a degenerate face.
     *
     * @param mesh source topology
     * @param hStart start-handle slot data
     * @param hEnd end-handle slot data
     * @param v0 corner at {@code (u=0, v=0)}
     * @param v1 corner at {@code (u=1, v=0)}
     * @param v3 corner at {@code (u=0, v=1)}
     * @param e0 v0 to v1 edge id
     * @param e1 v1 to v2 edge id
     * @param e2 v3 to v2 edge id
     * @param e3 v0 to v3 edge id
     * @param u parameter in {@code [0, 1]}
     * @param v parameter in {@code [0, 1]}
     * @return unit normal at {@code (u, v)}; zero-length if the surface degenerates
     */
    public static Vector3f coonsSurfaceNormal(
            MeshTopology mesh, float[] hStart, float[] hEnd,
            int v0, int v1, int v3,
            int e0, int e1, int e2, int e3,
            float u, float v) {
        float eps = NUM_1e_3;
        float uPlus = Math.min(NUM_1, u + eps);
        float uMinus = Math.max(NUM_0, u - eps);
        float vPlus = Math.min(NUM_1, v + eps);
        float vMinus = Math.max(NUM_0, v - eps);

        Vector3f sUPlus = evalCoonsSurface(mesh, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, uPlus, v);
        Vector3f sUMinus = evalCoonsSurface(mesh, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, uMinus, v);
        Vector3f sVPlus = evalCoonsSurface(mesh, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, u, vPlus);
        Vector3f sVMinus = evalCoonsSurface(mesh, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, u, vMinus);

        Vector3f dS_du = new Vector3f(sUPlus).sub(sUMinus);
        Vector3f dS_dv = new Vector3f(sVPlus).sub(sVMinus);
        Vector3f n = new Vector3f();
        dS_du.cross(dS_dv, n);

        // Sign check against flat-polygon normal of v0→v1 × v0→v3 at the face corner.
        // Ixdar's face convention has normals pointing outward from closed meshes, so
        // we flip our finite-difference normal to match if it came out inverted.
        Vector3f p0 = mesh.vertexPosition(v0, new Vector3f());
        Vector3f p1 = mesh.vertexPosition(v1, new Vector3f());
        Vector3f p3 = mesh.vertexPosition(v3, new Vector3f());
        Vector3f flatN = new Vector3f(p1).sub(p0).cross(new Vector3f(p3).sub(p0));
        if (n.dot(flatN) < NUM_0) n.negate();

        float len = n.length();
        if (len > NUM_1e_8) n.mul(NUM_1 / len);
        return n;
    }

    /**
     * Evaluates the Coons-patch surface S(u, v) for a quad face with
     * bezier-handled boundary edges. Corners v0..v3 and edges {@code e0..e3} are
     * in face-winding order with {@code e0} the v0→v1 edge, so {@code (0, 0)} is
     * at v0, {@code (1, 0)} at v1, {@code (1, 1)} at v2 and {@code (0, 1)} at v3.
     *
     * @param mesh source topology
     * @param hStart start-handle slot data
     * @param hEnd end-handle slot data
     * @param v0 corner at {@code (u=0, v=0)}
     * @param v1 corner at {@code (u=1, v=0)}
     * @param v3 corner at {@code (u=0, v=1)}
     * @param e0 v0 to v1 edge id
     * @param e1 v1 to v2 edge id
     * @param e2 v3 to v2 edge id
     * @param e3 v0 to v3 edge id
     * @param u parameter in {@code [0, 1]}
     * @param v parameter in {@code [0, 1]}
     * @return surface position at {@code (u, v)}
     */
    public static Vector3f evalCoonsSurface(
            MeshTopology mesh, float[] hStart, float[] hEnd,
            int v0, int v1, int v3,
            int e0, int e1, int e2, int e3,
            float u, float v) {
        float uS = smootherStep(u);
        float vS = smootherStep(v);

        Vector3f p00 = evalFaceEdgeAt(mesh, hStart, hEnd, e0, v0, NUM_0);
        Vector3f p10 = evalFaceEdgeAt(mesh, hStart, hEnd, e0, v0, NUM_1);
        Vector3f p01 = evalFaceEdgeAt(mesh, hStart, hEnd, e2, v3, NUM_0);
        Vector3f p11 = evalFaceEdgeAt(mesh, hStart, hEnd, e2, v3, NUM_1);

        Vector3f bottom = evalFaceEdgeAt(mesh, hStart, hEnd, e0, v0, u);
        Vector3f top = evalFaceEdgeAt(mesh, hStart, hEnd, e2, v3, u);
        Vector3f left = evalFaceEdgeAt(mesh, hStart, hEnd, e3, v0, v);
        Vector3f right = evalFaceEdgeAt(mesh, hStart, hEnd, e1, v1, v);

        // loftU = lerp(bottom, top, vS); loftV = lerp(left, right, uS)
        Vector3f loftU = new Vector3f(bottom).lerp(top, vS);
        Vector3f loftV = new Vector3f(left).lerp(right, uS);
        // bilinear = lerp( lerp(p00,p10,uS), lerp(p01,p11,uS), vS )
        Vector3f mix1 = new Vector3f(p00).lerp(p10, uS);
        Vector3f mix2 = new Vector3f(p01).lerp(p11, uS);
        Vector3f bilinear = new Vector3f(mix1).lerp(mix2, vS);

        return new Vector3f(loftU).add(loftV).sub(bilinear);
    }

    /**
     * Result of splitting a cubic bezier {@code P0,P1,P2,P3} at parameter
     * {@code t} via de Casteljau construction.
     * <p>
     * The split point is {@link #splitPoint}. The left sub-curve's control
     * polygon is {@code (P0, leftCp1, leftCp2, splitPoint)} and the right
     * sub-curve's is {@code (splitPoint, rightCp1, rightCp2, P3)}.
     */
    public static final class SplitResult {
        public final Vector3f splitPoint;
        public final Vector3f leftCp1;
        public final Vector3f leftCp2;
        public final Vector3f rightCp1;
        public final Vector3f rightCp2;

        SplitResult(Vector3f splitPoint, Vector3f leftCp1, Vector3f leftCp2,
                Vector3f rightCp1, Vector3f rightCp2) {
            this.splitPoint = splitPoint;
            this.leftCp1 = leftCp1;
            this.leftCp2 = leftCp2;
            this.rightCp1 = rightCp1;
            this.rightCp2 = rightCp2;
        }
    }
}
