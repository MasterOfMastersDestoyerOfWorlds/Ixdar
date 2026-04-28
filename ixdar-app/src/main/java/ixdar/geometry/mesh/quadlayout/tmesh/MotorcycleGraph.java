package ixdar.geometry.mesh.quadlayout.tmesh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * Classical motorcycle graph (Eppstein-Goodrich-Kim-Tamstorf 2008) on the
 * seamless integer-grid parametrization (PATCH-48).
 *
 * <p>Each singularity emits four motorcycles along the cardinal directions of
 * its parametric frame.  Motorcycles travel along iso-lines of the (u, v)
 * field; when one crashes into a previously-laid trace it stops and a node is
 * recorded.
 *
 * <p>v1 ships the simple "first motorcycle to a cell wins" variant: traces are
 * processed sequentially, and each new motorcycle treats already-laid traces
 * as walls.  The Lyon 2021 angular-bound modification (motorcycles surviving
 * crashes) is deferred to PATCH-44.
 *
 * <p>Defensive: faces with {@code uvSignedArea <= 0} are skipped — PATCH-48
 * leaves a handful of degenerate or flipped triangles on the cube which we
 * cannot trace through reliably.
 */
public final class MotorcycleGraph {

    /** Numerical tolerance for "ray exits triangle" intersection tests. */
    private static final float EPS = 1e-5f;
    /** How far past an intersection to skip when starting a new step. */
    private static final float STEP_EPS = 1e-4f;
    /** Hard cap on per-motorcycle step count — guards against infinite loops. */
    private static final int MAX_STEPS = 10_000;

    public record Result(List<Motorcycle> traces, List<TNode> nodes) {}

    private MotorcycleGraph() {}

    public static Result trace(SeamlessParameterization param,
                               ArrayMesh mesh,
                               FaceRosyField field,
                               CombedField combed,
                               List<Singularity> singularities) {
        Builder b = new Builder(param, mesh, field, combed, singularities);
        return b.run();
    }

    /** Mutable trace-building scratchpad. */
    private static final class Builder {
        final SeamlessParameterization param;
        final ArrayMesh mesh;
        final FaceRosyField field;
        final CombedField combed;
        final List<Singularity> singularities;
        final List<Motorcycle> motorcycles = new ArrayList<>();
        final List<TNode> nodes = new ArrayList<>();
        // Per-mesh-face list of trace segments laid down so far. Each entry:
        // {motorcycleId, stepIndex, uA, vA, uB, vB} stored flat.
        final ArrayList<float[]>[] facePaths;
        // Map mesh edgeId -> interior edge index in the FaceRosyField.
        final int[] meshEdgeToInterior;

        @SuppressWarnings("unchecked")
        Builder(SeamlessParameterization param, ArrayMesh mesh, FaceRosyField field,
                CombedField combed, List<Singularity> singularities) {
            this.param = param;
            this.mesh = mesh;
            this.field = field;
            this.combed = combed;
            this.singularities = singularities;
            int F = mesh.faceCount();
            this.facePaths = (ArrayList<float[]>[]) new ArrayList<?>[F];
            for (int i = 0; i < F; i++) facePaths[i] = new ArrayList<>();
            this.meshEdgeToInterior = new int[mesh.edgeCount()];
            java.util.Arrays.fill(meshEdgeToInterior, -1);
            for (int e = 0; e < field.interiorEdgeCount(); e++) {
                meshEdgeToInterior[field.edgeMeshId(e)] = e;
            }
        }

        Result run() {
            // 1) One SINGULARITY node per distinct singularity vertex. Pick a
            //    face/corner that holds this vertex (and is non-degenerate)
            //    as the launch site.
            HashMap<Integer, Integer> singVertexToNode = new HashMap<>();
            HashMap<Integer, int[]> singVertexToFaceCorner = new HashMap<>();
            HashSet<Integer> singVerts = new HashSet<>();
            for (Singularity s : singularities) singVerts.add(s.vertexId());

            int F = mesh.faceCount();
            for (int f = 0; f < F; f++) {
                if (param.uvSignedArea(f) <= 0) continue;
                for (int c = 0; c < 3; c++) {
                    int vid = mesh.faceVertexAt(f, c);
                    if (singVerts.contains(vid) && !singVertexToFaceCorner.containsKey(vid)) {
                        singVertexToFaceCorner.put(vid, new int[]{f, c});
                        TNode node = new TNode(nodes.size(), TNode.NodeKind.SINGULARITY,
                                f, param.u(f, c), param.v(f, c));
                        singVertexToNode.put(vid, node.id());
                        nodes.add(node);
                    }
                }
            }

            // 2) Launch four motorcycles per singularity. Sequential simulation:
            //    each motorcycle treats already-laid traces as walls.
            for (Singularity s : singularities) {
                int[] fc = singVertexToFaceCorner.get(s.vertexId());
                if (fc == null) continue;          // sing vertex on a degen face only
                int startNode = singVertexToNode.get(s.vertexId());
                for (int dir = 0; dir < 4; dir++) {
                    Motorcycle m = launch(s.vertexId(), fc[0], fc[1], dir, startNode);
                    if (m != null) motorcycles.add(m);
                }
            }
            return new Result(motorcycles, nodes);
        }

        /**
         * Trace one motorcycle from singularity vertex {@code singVid} starting
         * at corner {@code (face, corner)} heading along cardinal {@code dir}.
         */
        Motorcycle launch(int singVid, int face, int corner, int dir, int startNode) {
            float u = param.u(face, corner);
            float v = param.v(face, corner);
            int dirInFace = dir;
            int curFace = face;
            ArrayList<Motorcycle.Step> trace = new ArrayList<>();
            int finalNode = -1;
            int motorcycleId = motorcycles.size();

            for (int step = 0; step < MAX_STEPS; step++) {
                if (curFace < 0 || param.uvSignedArea(curFace) <= 0) {
                    // Stepped onto a degen / flipped triangle. Abort cleanly.
                    finalNode = recordNode(TNode.NodeKind.BOUNDARY, curFace, u, v);
                    break;
                }
                // Direction unit vector in face's UV frame.
                float du = (dirInFace == 0) ? 1f : (dirInFace == 2 ? -1f : 0f);
                float dv = (dirInFace == 1) ? 1f : (dirInFace == 3 ? -1f : 0f);

                // Triangle corners of curFace.
                float u0 = param.u(curFace, 0), v0 = param.v(curFace, 0);
                float u1 = param.u(curFace, 1), v1 = param.v(curFace, 1);
                float u2 = param.u(curFace, 2), v2 = param.v(curFace, 2);

                // First, check intersection with any pre-existing trace
                // segment in this face — the closest one wins.
                float bestT = Float.POSITIVE_INFINITY;
                int bestPriorMotorcycle = -1;
                for (float[] seg : facePaths[curFace]) {
                    int priorId = (int) seg[0];
                    if (priorId == motorcycleId) continue;     // own trace; skip
                    float t = raySegmentIntersect(u, v, du, dv,
                            seg[2], seg[3], seg[4], seg[5]);
                    if (t > STEP_EPS && t < bestT) {
                        bestT = t;
                        bestPriorMotorcycle = priorId;
                    }
                }

                // Then find which face edge the ray exits.
                int exitEdgeIdx = -1;          // 0,1,2 — corner index opposite the edge
                float exitT = Float.POSITIVE_INFINITY;
                {
                    // Edges: edge i is between corner i and corner (i+1)%3.
                    // Pre-package corner UV.
                    float[][] cu = {{u0, v0}, {u1, v1}, {u2, v2}};
                    for (int e = 0; e < 3; e++) {
                        float[] a = cu[e];
                        float[] b = cu[(e + 1) % 3];
                        float t = raySegmentIntersect(u, v, du, dv,
                                a[0], a[1], b[0], b[1]);
                        if (t > STEP_EPS && t < exitT) {
                            exitT = t;
                            exitEdgeIdx = e;
                        }
                    }
                }

                // If a prior motorcycle's trace is closer than the face edge,
                // crash here.
                if (bestPriorMotorcycle >= 0 && bestT <= exitT) {
                    float crashU = u + bestT * du;
                    float crashV = v + bestT * dv;
                    finalNode = recordNode(TNode.NodeKind.INTERSECTION, curFace,
                            crashU, crashV);
                    Motorcycle.Step s = new Motorcycle.Step(curFace, u, v,
                            crashU, crashV, -1);
                    trace.add(s);
                    facePaths[curFace].add(new float[]{
                            motorcycleId, step, u, v, crashU, crashV});
                    break;
                }

                if (exitEdgeIdx < 0 || !Float.isFinite(exitT)) {
                    // Could not find an exit edge — degenerate face. Abort.
                    finalNode = recordNode(TNode.NodeKind.BOUNDARY, curFace, u, v);
                    break;
                }

                float exitU = u + exitT * du;
                float exitV = v + exitT * dv;
                Motorcycle.Step st = new Motorcycle.Step(curFace, u, v,
                        exitU, exitV, exitEdgeIdx);
                trace.add(st);
                facePaths[curFace].add(new float[]{
                        motorcycleId, step, u, v, exitU, exitV});

                // Transition into the neighbor face.
                int faceMeshEdge = mesh.faceEdgeAt(curFace, exitEdgeIdx);
                if (faceMeshEdge < 0 || mesh.isBoundaryEdge(faceMeshEdge)) {
                    finalNode = recordNode(TNode.NodeKind.BOUNDARY, curFace,
                            exitU, exitV);
                    break;
                }
                int nbrFace = neighborFace(curFace, faceMeshEdge);
                if (nbrFace < 0) {
                    finalNode = recordNode(TNode.NodeKind.BOUNDARY, curFace,
                            exitU, exitV);
                    break;
                }
                int interiorEdge = meshEdgeToInterior[faceMeshEdge];
                int r = (interiorEdge >= 0) ? combed.matching(interiorEdge) : 0;
                // Direction r is signed for A->B. If we're going from B->A,
                // invert the rotation.
                int rotForward;
                if (interiorEdge >= 0 && field.edgeFaceA(interiorEdge) == curFace) {
                    rotForward = r;
                } else {
                    rotForward = (4 - r) & 3;
                }
                int nbrDir = (dirInFace + rotForward) & 3;

                // Map exit point in curFace UV to entry point in nbrFace UV.
                // The two faces share an edge (mesh edge id `faceMeshEdge`)
                // with two endpoints. We find the parametric position of those
                // two endpoints in BOTH faces' local frames and barycentrically
                // interpolate by the same fraction.
                int[] sharedCornerCur = sharedCornerIndices(curFace, faceMeshEdge);
                int[] sharedCornerNbr = sharedCornerIndices(nbrFace, faceMeshEdge);
                if (sharedCornerCur == null || sharedCornerNbr == null) {
                    finalNode = recordNode(TNode.NodeKind.BOUNDARY, curFace,
                            exitU, exitV);
                    break;
                }
                int va = mesh.faceVertexAt(curFace, sharedCornerCur[0]);
                int vb = mesh.faceVertexAt(curFace, sharedCornerCur[1]);
                // Identify which corner in nbr corresponds to va vs vb.
                int nbrA, nbrB;
                if (mesh.faceVertexAt(nbrFace, sharedCornerNbr[0]) == va) {
                    nbrA = sharedCornerNbr[0];
                    nbrB = sharedCornerNbr[1];
                } else {
                    nbrA = sharedCornerNbr[1];
                    nbrB = sharedCornerNbr[0];
                }
                float curAu = param.u(curFace, sharedCornerCur[0]);
                float curAv = param.v(curFace, sharedCornerCur[0]);
                float curBu = param.u(curFace, sharedCornerCur[1]);
                float curBv = param.v(curFace, sharedCornerCur[1]);
                float dxU = curBu - curAu, dxV = curBv - curAv;
                float denom = dxU * dxU + dxV * dxV;
                float frac = (denom > 1e-30f)
                        ? ((exitU - curAu) * dxU + (exitV - curAv) * dxV) / denom
                        : 0f;
                if (frac < 0f) frac = 0f;
                if (frac > 1f) frac = 1f;
                float nbrAu = param.u(nbrFace, nbrA);
                float nbrAv = param.v(nbrFace, nbrA);
                float nbrBu = param.u(nbrFace, nbrB);
                float nbrBv = param.v(nbrFace, nbrB);
                float newU = nbrAu + frac * (nbrBu - nbrAu);
                float newV = nbrAv + frac * (nbrBv - nbrAv);

                u = newU;
                v = newV;
                curFace = nbrFace;
                dirInFace = nbrDir;
            }

            if (finalNode < 0) {
                finalNode = recordNode(TNode.NodeKind.BOUNDARY, curFace, u, v);
            }
            // Drop motorcycles that never made it past the first face — they
            // either started on a degenerate triangle or hit an immediate seam
            // we cannot transition. v1 records a node but no arc.
            if (trace.isEmpty()) return null;
            return new Motorcycle(motorcycleId, singVid, dir, trace, finalNode);
        }

        int recordNode(TNode.NodeKind kind, int faceId, float u, float v) {
            TNode n = new TNode(nodes.size(), kind, faceId, u, v);
            nodes.add(n);
            return n.id();
        }

        int neighborFace(int faceId, int meshEdgeId) {
            int he = mesh.edgeHalfEdge(meshEdgeId);
            if (mesh.halfEdgeFace(he) == faceId) {
                int twin = mesh.halfEdgeTwin(he);
                return twin >= 0 ? mesh.halfEdgeFace(twin) : -1;
            }
            int twin = mesh.halfEdgeTwin(he);
            if (twin >= 0 && mesh.halfEdgeFace(twin) == faceId) {
                return mesh.halfEdgeFace(he);
            }
            return -1;
        }

        /**
         * Return {cornerIdxA, cornerIdxB} — the two face-corner indices of the
         * shared mesh edge {@code meshEdgeId}, in that face's vertex order.
         */
        int[] sharedCornerIndices(int faceId, int meshEdgeId) {
            for (int c = 0; c < 3; c++) {
                if (mesh.faceEdgeAt(faceId, c) == meshEdgeId) {
                    return new int[]{c, (c + 1) % 3};
                }
            }
            return null;
        }
    }

    /**
     * Ray-segment intersection in 2D.  Returns {@code t > 0} for the smallest
     * {@code t} such that {@code (origin + t * dir)} sits on segment AB
     * (within the segment's endpoints), else {@code +Infinity}.
     */
    static float raySegmentIntersect(float ox, float oy, float dx, float dy,
                                     float ax, float ay, float bx, float by) {
        float ex = bx - ax;
        float ey = by - ay;
        float det = dx * ey - dy * ex;
        if (Math.abs(det) < 1e-12f) return Float.POSITIVE_INFINITY;
        float rx = ax - ox;
        float ry = ay - oy;
        float t = (rx * ey - ry * ex) / det;
        float s = (rx * dy - ry * dx) / det;
        if (t < -EPS) return Float.POSITIVE_INFINITY;
        if (s < -EPS || s > 1f + EPS) return Float.POSITIVE_INFINITY;
        return t;
    }
}
