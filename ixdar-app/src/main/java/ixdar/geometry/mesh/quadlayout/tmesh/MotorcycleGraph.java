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
 * <p><b>PATCH-44 — Lyon 2021 §3 stopping rule.</b> Motorcycles SURVIVE crashes
 * (a regular valence-4 node is recorded, the prior trace is split, and the
 * crashing motorcycle continues). A motorcycle stops only after it has
 * crossed two prior traces t_k and t_l with α_ik ∈ [0, α] AND α_il ∈ [-α, 0],
 * where α_ij is the signed CCW angle of the right-triangle formed by
 * singularity i, intersection node n_ij, and singularity j (see paper §3
 * Stopping Criterion). The classical Eppstein "first-crash-stops" behavior
 * is recoverable by passing {@code alpha = 0}.
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

    /** PATCH-91 H1 diagnostic: per-singularity launch-count histogram (index = #motorcycles launched). */
    public static int[] statSingLaunchCount = new int[16];
    /** Total singularities scanned. */
    public static int statSingTotal;
    /** Singularities where ≥1 face wedge had a cardinal exactly on its boundary
     *  (the under-launch failure mode of cardinalInFaceWedge). */
    public static int statSingBoundaryCardinals;

    /**
     * One crash event: motorcycle {@code crasher} terminated at the crash
     * point, but the prior motorcycle {@code victim} was passing through
     * here too. Both must be split — TMesh.build subdivides {@code victim}'s
     * trace at this point so the resulting T-mesh has an arc ending here.
     *
     * <p>{@code crasherMotorcycleId} and {@code crasherStepIndex} identify
     * the crashing motorcycle and the step on its trace where the crash
     * occurred (post-PATCH-44 each crash also splits the crasher's trace).
     *
     * <p>{@code absAlphaIj} is |α_ij| (radians) — the magnitude of the
     * signed CCW angle of the right-triangle (i, n_ij, j) at singularity i.
     * Used by Lyon §4.3 Eq.(4) layout constraints when |α_ij| &gt; α.
     */
    public record Crash(int victimMotorcycleId,
                        int victimStepIndex,
                        float victimCrashU,
                        float victimCrashV,
                        int intersectionNodeId,
                        int crasherMotorcycleId,
                        int crasherStepIndex,
                        double absAlphaIj) {}

    public record Result(List<Motorcycle> traces,
                         List<TNode> nodes,
                         List<Crash> crashes,
                         /** PATCH-68: vertex id → SINGULARITY-kind TNode id.
                          *  Lets downstream code (TMesh.build) resolve a
                          *  motorcycle's start node without doing the
                          *  per-face uv-match dance, which fails for
                          *  multi-port launches from different faces. */
                         java.util.Map<Integer, Integer> singVertexToNode) {}

    private MotorcycleGraph() {}

    /** Default α-bound (radians) for Lyon §3 stopping criterion when none
     *  is specified. Override at runtime via {@code -Dixdar.lyon.alphaDeg=N}. */
    public static double defaultAlpha() {
        String prop = System.getProperty("ixdar.lyon.alphaDeg");
        double deg = (prop != null) ? Double.parseDouble(prop) : 45.0;
        return Math.toRadians(deg);
    }

    public static Result trace(SeamlessParameterization param,
                               ArrayMesh mesh,
                               FaceRosyField field,
                               CombedField combed,
                               List<Singularity> singularities) {
        return trace(param, mesh, field, combed, singularities, defaultAlpha());
    }

    /**
     * Trace with explicit α-bound (radians). {@code alpha = 0} reverts to
     * Eppstein-classical "first crash stops". Lyon paper uses α ∈ [5°, 45°];
     * smaller α = longer surviving traces = more arcs and patches.
     */
    public static Result trace(SeamlessParameterization param,
                               ArrayMesh mesh,
                               FaceRosyField field,
                               CombedField combed,
                               List<Singularity> singularities,
                               double alpha) {
        Builder b = new Builder(param, mesh, field, combed, singularities, alpha);
        return b.run();
    }

    /** Mutable trace-building scratchpad. */
    private static final class Builder {
        final SeamlessParameterization param;
        final ArrayMesh mesh;
        final FaceRosyField field;
        final CombedField combed;
        final List<Singularity> singularities;
        final double alpha;                 // PATCH-44 stopping bound (radians)
        final List<Motorcycle> motorcycles = new ArrayList<>();
        final List<TNode> nodes = new ArrayList<>();
        final List<Crash> crashes = new ArrayList<>();
        // Per-mesh-face list of trace segments laid down so far. Each entry:
        // {motorcycleId, stepIndex, uA, vA, uB, vB} stored flat.
        final ArrayList<float[]>[] facePaths;
        // Map mesh edgeId -> interior edge index in the FaceRosyField.
        final int[] meshEdgeToInterior;
        // Per-motorcycle cumulative parametric length up to start of step k.
        // motorcycleCumLength[m] is filled out as motorcycle m runs.
        final ArrayList<float[]> motorcycleCumLength = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Builder(SeamlessParameterization param, ArrayMesh mesh, FaceRosyField field,
                CombedField combed, List<Singularity> singularities, double alpha) {
            this.param = param;
            this.mesh = mesh;
            this.field = field;
            this.combed = combed;
            this.singularities = singularities;
            this.alpha = alpha;
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

            // 2) PATCH-60: Multi-port launch. For each singularity vertex,
            //    walk all incident faces; for each face, launch one motorcycle
            //    per cardinal direction that points strictly INTO that face's
            //    parametric wedge at the vertex. Mirrors metriko's gen_ports
            //    in motorcycle.h: 4-ish ports per regular singularity (more
            //    for cone-like singularities), instead of 4 launches all from
            //    a single face which produces only 1-2 valid traces.
            // PATCH-91 H1: reset per-call diagnostic counters.
            java.util.Arrays.fill(statSingLaunchCount, 0);
            statSingTotal = 0;
            statSingBoundaryCardinals = 0;
            for (Singularity s : singularities) {
                Integer startNode = singVertexToNode.get(s.vertexId());
                if (startNode == null) continue;
                int vId = s.vertexId();
                statSingTotal++;
                int launchedFromThisSing = 0;
                boolean boundaryCardinal = false;
                int outCount = mesh.vertexOutgoingHalfEdgeCount(vId);
                for (int oh = 0; oh < outCount; oh++) {
                    int he = mesh.vertexOutgoingHalfEdgeAt(vId, oh);
                    int faceId = mesh.halfEdgeFace(he);
                    if (faceId < 0) continue;
                    int cV = he % 3;                       // corner of v in this face
                    int cN = mesh.halfEdgeNext(he) % 3;
                    int cP = mesh.halfEdgePrev(he) % 3;
                    float vU = param.u(faceId, cV);
                    float vV = param.v(faceId, cV);
                    float nU = param.u(faceId, cN);
                    float nV = param.v(faceId, cN);
                    float pU = param.u(faceId, cP);
                    float pV = param.v(faceId, cP);
                    double e1x = nU - vU, e1y = nV - vV;
                    double e2x = pU - vU, e2y = pV - vV;
                    for (int dir = 0; dir < 4; dir++) {
                        double dx = (dir == 0) ? 1 : (dir == 2 ? -1 : 0);
                        double dy = (dir == 1) ? 1 : (dir == 3 ? -1 : 0);
                        // H1 instrumentation: detect cardinals exactly on a wedge boundary.
                        double c1 = e1x * dy - e1y * dx;
                        double c2 = dx * e2y - dy * e2x;
                        if (Math.abs(c1) < 1e-7 || Math.abs(c2) < 1e-7) {
                            boundaryCardinal = true;
                        }
                        if (!cardinalInFaceWedge(e1x, e1y, e2x, e2y, dx, dy)) continue;
                        Motorcycle m = launch(vId, faceId, cV, dir, startNode);
                        if (m != null && !m.trace().isEmpty()) {
                            motorcycles.add(m);
                            launchedFromThisSing++;
                        }
                    }
                }
                int bucket = Math.min(launchedFromThisSing, statSingLaunchCount.length - 1);
                statSingLaunchCount[bucket]++;
                if (boundaryCardinal) statSingBoundaryCardinals++;
            }
            return new Result(motorcycles, nodes, crashes, singVertexToNode);
        }

        /** Is cardinal {@code (dx, dy)} strictly inside the face wedge bounded
         *  by {@code (e1x, e1y)} and {@code (e2x, e2y)} (CCW from e1 to e2)? */
        private static boolean cardinalInFaceWedge(double e1x, double e1y,
                                                    double e2x, double e2y,
                                                    double dx, double dy) {
            // Wedge total cross > 0 means CCW wedge < 180°.
            double cTotal = e1x * e2y - e1y * e2x;
            double c1 = e1x * dy - e1y * dx;
            double c2 = dx * e2y - dy * e2x;
            double eps = 1e-9;
            if (cTotal > 0) {
                return c1 > eps && c2 > eps;
            } else {
                // Reflex wedge (cone singularity > 180°): inside iff NOT in the
                // complementary acute wedge.
                return !(c1 < -eps && c2 < -eps);
            }
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
            // PATCH-44 Lyon §3: track per-side α-bound hits to know when to stop.
            // leftHit = saw a crash where the prior trace t_j is rotated +90°
            // CCW from this motorcycle's direction (j is on the "right" side
            // of t_i in seamless param) AND |α_ij| ≤ alpha.
            // rightHit is the symmetric flag.
            boolean leftHit = false;
            boolean rightHit = false;
            // Cumulative parametric length up to start of current step.
            float cumLen = 0f;

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
                int bestPriorStep = -1;
                for (float[] seg : facePaths[curFace]) {
                    int priorId = (int) seg[0];
                    if (priorId == motorcycleId) continue;     // own trace; skip
                    float t = raySegmentIntersect(u, v, du, dv,
                            seg[2], seg[3], seg[4], seg[5]);
                    if (t > STEP_EPS && t < bestT) {
                        bestT = t;
                        bestPriorMotorcycle = priorId;
                        bestPriorStep = (int) seg[1];
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

                // PATCH-44: handle crash with Lyon §3 survival rule.
                if (bestPriorMotorcycle >= 0 && bestT <= exitT) {
                    float crashU = u + bestT * du;
                    float crashV = v + bestT * dv;

                    // Classify the crash by α_ij sign + magnitude (Lyon §3).
                    Motorcycle prior = motorcycles.get(bestPriorMotorcycle);
                    Motorcycle.Step priorStep = prior.trace().get(bestPriorStep);
                    double priorDu = priorStep.uOut() - priorStep.uIn();
                    double priorDv = priorStep.vOut() - priorStep.vIn();
                    // cross > 0  ⇒ t_j (prior) is rotated +90° CCW from t_i
                    //              (our motorcycle); j is on our right side.
                    // cross < 0  ⇒ rotated −90°; j is on our left side.
                    double cross = (double) du * priorDv - (double) dv * priorDu;

                    // l_ij = parametric distance from i to crash along t_i.
                    float lIj = cumLen + bestT;
                    // l_ji = parametric distance from j to crash along t_j.
                    float lJi = cumulativeLengthToCrash(bestPriorMotorcycle,
                            bestPriorStep, crashU, crashV);
                    // |α_ij| = atan(l_ji / l_ij). Both sides positive parametric.
                    double absAlphaIj = (lIj < 1e-9f)
                            ? Math.PI / 2
                            : Math.atan((double) lJi / (double) lIj);

                    if (alpha > 1e-9 && absAlphaIj <= alpha + 1e-9) {
                        if (cross < 0) leftHit = true;
                        else if (cross > 0) rightHit = true;
                    }

                    int crashNodeId = recordNode(TNode.NodeKind.INTERSECTION,
                            curFace, crashU, crashV);
                    Motorcycle.Step s = new Motorcycle.Step(curFace, u, v,
                            crashU, crashV, -1);
                    trace.add(s);
                    facePaths[curFace].add(new float[]{
                            motorcycleId, step, u, v, crashU, crashV});
                    // Crash on the prior trace: TMesh.build will split t_j
                    // at the intersection. Record |α_ij| for Lyon §4.3 Eq.(4).
                    crashes.add(new Crash(bestPriorMotorcycle, bestPriorStep,
                            crashU, crashV, crashNodeId,
                            motorcycleId, step, absAlphaIj));

                    boolean stop = (alpha <= 1e-9)            // Eppstein mode
                            || (leftHit && rightHit);          // Lyon both-sides
                    if (stop) {
                        finalNode = crashNodeId;
                        break;
                    }

                    // Survive — but ALSO record a self-crash so TMesh.build
                    // splits OUR trace at this intersection. Without this,
                    // the surviving motorcycle's trace remains a single
                    // TArc traversing the valence-4 node, which breaks
                    // planar face enumeration (the half-arc graph isn't
                    // a true planar DCEL anymore).
                    crashes.add(new Crash(motorcycleId, step,
                            crashU, crashV, crashNodeId,
                            motorcycleId, step, absAlphaIj));

                    // Advance past the crash by a tiny epsilon and continue
                    // the raycast in the same direction. The split step we
                    // just added closes the previous arc-section; the next
                    // step starts fresh from the crash point.
                    cumLen += bestT;
                    u = crashU;
                    v = crashV;
                    continue;
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
                cumLen += exitT;

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

        /** Walk prior motorcycle's trace from its origin, summing per-step
         *  parametric lengths up to and into step {@code crashStepIdx}, where
         *  the crash sits at {@code (crashU, crashV)} within that step. */
        float cumulativeLengthToCrash(int priorMotorcycleId, int crashStepIdx,
                                       float crashU, float crashV) {
            Motorcycle prior = motorcycles.get(priorMotorcycleId);
            float sum = 0f;
            var trace = prior.trace();
            for (int k = 0; k < crashStepIdx && k < trace.size(); k++) {
                Motorcycle.Step s = trace.get(k);
                float du = s.uOut() - s.uIn();
                float dv = s.vOut() - s.vIn();
                sum += (float) Math.hypot(du, dv);
            }
            if (crashStepIdx >= 0 && crashStepIdx < trace.size()) {
                Motorcycle.Step s = trace.get(crashStepIdx);
                float du = s.uOut() - s.uIn();
                float dv = s.vOut() - s.vIn();
                sum += (float) Math.hypot(crashU - s.uIn(), crashV - s.vIn());
                // Cap at full step length (numerical safety).
                float stepLen = (float) Math.hypot(du, dv);
                if (sum > 0 && stepLen > 0
                        && Math.hypot(crashU - s.uIn(), crashV - s.vIn()) > stepLen) {
                    // Crash measured to be past step end — clamp.
                    sum = sum - (float) Math.hypot(crashU - s.uIn(), crashV - s.vIn())
                            + stepLen;
                }
            }
            return sum;
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
