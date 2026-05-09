package ixdar.geometry.mesh.quadlayout.tmesh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
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
    public static final String STEP = " step=";
    public static final double NUM_15_0 = 15.0;
    public static final double NUM_1e_20 = 1e-20;

    /** PATCH-91 H1 diagnostic: per-singularity launch-count histogram (index = #motorcycles launched). */
    public static int[] statSingLaunchCount = new int[16];
    /** Total singularities scanned. */
    public static int statSingTotal;
    /**
     * PATCH-110: singularities passed in vs successfully assigned a launch
     *  TNode. statSingsInputCount = singularities.size() (whatever sing finder
     *  produced). statSingsNoNode = sings whose vertex had no incident face
     *  with uvSignedArea > 0 in the parametrization, so we couldn't pick a
     *  launch face → no motorcycles launched from them. Drives understanding
     */
    public static int statSingsInputCount;
    public static int statSingsNoNode;
    /**
     * Singularities where ≥1 face wedge had a cardinal exactly on its boundary.
     */
    public static int statSingBoundaryCardinals;
    /**
     * PATCH-89: number of boundary motorcycles synthesized + boundary nodes.
     */
    public static int statBoundaryMotorcycles;
    public static int statBoundaryNodesCreated;
    /**
     * PATCH-89: synthetic-motorcycle marker. Boundary motorcycles store this
     *  in their {@code singularityVertexId} field so consumers (TMesh.build,
     */
    public static final int BOUNDARY_MOTORCYCLE_VID = -2;

    /**
     * PATCH-92 abort-cause counters: where motorcycles terminated. Lyon's
     *  framework expects traces to stop at α-bound survival or at proper
     *  trace intersections. Aborts at degenerate parametrization triangles
     *  or unmappable seam crossings indicate INPUT QUALITY issues that
     */
    public static int statAbortDegenStartFace;     // line 371: curFace<0 or uvSignedArea≤0
    public static int statAbortBoundaryEdge;       // line 514: isBoundaryEdge or faceMeshEdge<0
    public static int statAbortNoNeighborFace;     // line 520: nbrFace<0
    public static int statAbortNoSharedCorners;    // sharedCorner lookup failed
    public static int statAbortNoExitEdge;         // raycast couldn't find exit
    public static int statTraceProperStop;         // Lyon survival or Eppstein normal stop
    /**
     * PATCH-94 diagnostic: how many trace launches hit MAX_STEPS without
     *  triggering any stop condition (Lyon's both-sides α-hit, Eppstein.
     */
    public static int statAbortMaxSteps;
    /**
     * PATCH-94: count self-intersection events the current code SKIPS
     *  ({@code priorId == motorcycleId}). On genus-positive surfaces a
     *  motorcycle's iso-line can loop back to itself; today we ignore those
     */
    public static int statSelfCrashesSkipped;
    /**
     * PATCH-94 geometric self-crash detector: count steps where the ray
     *  WOULD HIT an own segment (excluding the immediately-prior segment
     *  in the same face) within positive {@code t > STEP_EPS}. Per EGKT08
     *  the canonical Eppstein behavior is to STOP at such events
     *  ("particle meets a vertex that has previously been traversed by
     *  itself"). We currently skip them, which causes traces to run to
     */
    public static int statRealSelfCrashes;
    /**
     * PATCH-105: per-cause classification of MAX_STEPS aborts. After
     *  trace() returns, count how many of the {@code statAbortMaxSteps}
     */
    public static int statMaxStepsZeroCrashes;        // never crashed at all (sanitization / predicate gap)
    public static int statMaxStepsOneSidedAlpha;      // crashed but never both sides of α-cone
    public static int statMaxStepsTwoSidedNeverFired; // had both sides AND in-α — but stop didn't trigger (shouldn't happen)
    public static int statMaxStepsHitFlippedFace;     // entered ≥1 fold-over face along the way
    public static int statMaxStepsUsedNudge;          // PATCH-93 recovery fired ≥1 time
    /**
     * PATCH-107 oscillation probe — uniqueFaces visited per max-stepped motorcycle.
     *  Buckets [≤10, 11-50, 51-200, 201-1000, 1001-5000, 5001+]. If most aborts are
     *  in low buckets, the motorcycle is oscillating (NOT genuinely traversing the
     */
    public static int[] statMaxStepsUniqueFacesHist = new int[6];
    /** PATCH-94: motorcycles that experienced at least one real self-crash. */
    public static int statMotorcyclesWithSelfCrash;
    /**
     * PATCH-95 H1 diagnostic: histogram of |α_ij| at every other-trace crash.
     *  Buckets in degrees: 0-5°, 5-15° (Lyon's α=15° boundary), 15-30°, 30-45°,
     *  45-60°, 60-75°, 75-90°. If most crashes are in 30-60° range, the
     *  α-bound rarely qualifies them as Lyon stop hits, explaining why
     */
    public static int[] statAlphaIjHist = new int[8];
    /**
     * PATCH-94: per-motorcycle final outcome (PROPER_STOP / MAX_STEPS /.
     */
    public static java.util.List<String> motorcycleOutcomes = new java.util.ArrayList<>();
    /** PATCH-93 diagnostic: dump first N no-exit-edge abort events. */
    public static java.util.List<String> abortDumps = new java.util.ArrayList<>();

    /** Numerical tolerance for "ray exits triangle" intersection tests. */
    private static final float EPS = 1e-5f;
    /** How far past an intersection to skip when starting a new step. */
    private static final float STEP_EPS = 1e-4f;
    /** Hard cap on per-motorcycle step count — guards against infinite loops. */
    private static final int MAX_STEPS = 10_000;
    private static final int ABORT_DUMP_LIMIT = 8;

    private MotorcycleGraph() {}

    /**
     * Stub constructor retained for API compatibility; actual tracing is
     * driven through the static {@link #trace} entry points.
     *
     * @param mesh half-edge mesh underlying the parametrization
     * @param seamlessParameterization seamless integer-grid parametrization
     * @param singularities singular vertices to launch motorcycles from
     * @param alpha Lyon §3 stopping bound (radians)
     */
    public MotorcycleGraph(HalfEdgeMesh mesh, SeamlessParameterization seamlessParameterization,
            List<Singularity> singularities, float alpha) {
        //TODO Auto-generated constructor stub
    }

    /**
     * Default α-bound (radians) for Lyon §3 stopping criterion when none
     *  is specified. Lyon Table 1 uses α=15° for ROCKERARM (PATCH-104).
     *  Override at runtime via {@code -Dixdar.lyon.alphaDeg=N}.
     *
     *  <p>Per Lyon §3 (page 308): tighter α forces motorcycles to extend
     *  further before finding a valid stop pair (αik ∈ [0, α], αil ∈ [-α, 0])
     *  → more arcs in the T-mesh. Bigger α = traces stop on any nearby crash
     *  → fewer arcs. With our previous default 45°, ROCKERARM yielded 656
     *  arcs vs Lyon paper's 2742.
     *
     * @return α-bound in radians
     */
    public static double defaultAlpha() {
        String prop = System.getProperty("ixdar.lyon.alphaDeg");
        double deg = (prop != null) ? Double.parseDouble(prop) : NUM_15_0;
        return Math.toRadians(deg);
    }

    /**
     * Trace the motorcycle graph using {@link #defaultAlpha()} as the Lyon §3
     * stopping bound.
     *
     * @param param seamless integer-grid parametrization
     * @param mesh underlying mesh
     * @param field per-face cross field
     * @param combed combed field providing per-edge matching for direction transport
     * @param singularities singularities to launch motorcycles from
     * @return assembled traces, nodes, crashes and singVertex→node map
     */
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
     *
     * @param param seamless integer-grid parametrization
     * @param mesh underlying mesh
     * @param field per-face cross field
     * @param combed combed field providing per-edge matching for direction transport
     * @param singularities singularities to launch motorcycles from
     * @param alpha Lyon §3 stopping bound in radians (0 = Eppstein mode)
     * @return assembled traces, nodes, crashes and singVertex→node map
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

    /**
     * Ray-segment intersection in 2D.  Returns {@code t > 0} for the smallest
     * {@code t} such that {@code (origin + t * dir)} sits on segment AB
     * (within the segment's endpoints), else {@code +Infinity}.
     *
     * @param ox ray origin u
     * @param oy ray origin v
     * @param dx ray direction u
     * @param dy ray direction v
     * @param ax segment endpoint A u
     * @param ay segment endpoint A v
     * @param bx segment endpoint B u
     * @param by segment endpoint B v
     * @return smallest non-negative ray parameter that lands on segment AB, or {@link Float#POSITIVE_INFINITY} if none
     */
    static float raySegmentIntersect(float ox, float oy, float dx, float dy,
                                     float ax, float ay, float bx, float by) {
        // PATCH-107 probe: do the intersection in DOUBLE precision to absorb
        //   per-step transition drift before resorting to QEx §3.2 sanitization.
        //   The inputs are float (per-corner UVs from SeamlessParameterization),
        //   but the cumulative trace position drifts after many seam crossings;
        //   double arithmetic gives us ~7 more decimal digits of headroom and
        //   eliminates the false-no-exit cases that drove PATCH-93 nudge-recovery.
        double exd = (double) bx - ax;
        double eyd = (double) by - ay;
        double det = (double) dx * eyd - (double) dy * exd;
        if (Math.abs(det) < NUM_1e_20) return Float.POSITIVE_INFINITY;
        double rxd = (double) ax - ox;
        double ryd = (double) ay - oy;
        double t = (rxd * eyd - ryd * exd) / det;
        double s = (rxd * dy - ryd * dx) / det;
        if (t < -EPS) return Float.POSITIVE_INFINITY;
        if (s < -EPS || s > 1.0 + EPS) return Float.POSITIVE_INFINITY;
        return (float) t;
    }

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
                         /**
                          * PATCH-68: vertex id → SINGULARITY-kind TNode id.
                          *  Lets downstream code (TMesh.build) resolve a
                          *  motorcycle's start node without doing the
                          *  per-face uv-match dance, which fails for
                          */
                         java.util.Map<Integer, Integer> singVertexToNode) {}

    /**
     * PATCH-95: per-motorcycle state for round-robin (step-locked parallel)
     *  propagation per EGKT08 §5 line 312 ("in a sequence of time steps,
     *  move each particle along the edge"). All motorcycles advance one
     *  triangle-step per round; each motorcycle becomes inactive when it
     *  satisfies a stop condition (Lyon §3 both-side α-bound, EGKT08 self-
     */
    private static final class MotorcycleState {
        final int motorcycleId;
        final int singVid;
        final int direction;
        final int startNode;
        int curFace;
        float u, v;
        int dirInFace;
        int step = 0;
        final ArrayList<Motorcycle.Step> trace = new ArrayList<>();
        int finalNode = -1;
        boolean leftHit = false;
        boolean rightHit = false;
        float cumLen = 0f;
        boolean active = true;

        // PATCH-105 per-motorcycle diagnostics for MAX_STEPS abort triage.
        int crashesRecorded = 0;       // total crashes this trace registered
        int crashesLeftAlpha = 0;      // crashes with cross<0 AND |αij|≤α
        int crashesRightAlpha = 0;     // crashes with cross>0 AND |αij|≤α
        int enteredFlippedFace = 0;    // faces with uvSignedArea ≤ 0 visited
        int nudgeRecoveryFires = 0;    // PATCH-93 recovery firings
        java.util.HashSet<Integer> uniqueFaces = new java.util.HashSet<>();  // PATCH-107 oscillation probe
        /**
         * PATCH-107 cycle-detection: visited (face, dirInFace) tuples. EGKT08
         *  spirit: motorcycle stops when "particle meets a vertex previously
         *  traversed by itself" — generalized here to "re-enters a face in
         *  the same direction as before". Catches oscillation that the
         *  segment-intersection self-stop misses (parallel cardinal traces
         */
        java.util.HashSet<Long> visitedFaceDir = new java.util.HashSet<>();

        MotorcycleState(int motorcycleId, int singVid, int direction, int startNode,
                        int face, float uIn, float vIn) {
            this.motorcycleId = motorcycleId;
            this.singVid = singVid;
            this.direction = direction;
            this.startNode = startNode;
            this.curFace = face;
            this.dirInFace = direction;
            this.u = uIn;
            this.v = vIn;
        }
    }

    /** Mutable trace-building scratchpad. */
    private static final class Builder {
        public static final int NUM_3 = 3;
        public static final int NUM_4 = 4;
        public static final double NUM_1e_7 = 1e-7;
        public static final int NUM_10 = 10;
        public static final int NUM_50 = 50;
        public static final int NUM_200 = 200;
        public static final int NUM_1000 = 1000;
        public static final int NUM_5000 = 5000;
        public static final int NUM_5 = 5;
        public static final double NUM_1e_9 = 1e-9;
        public static final int NUM_0x = 0xF;
        public static final float NUM_1 = 1f;
        public static final float NUM_0 = 0f;
        public static final float NUM_1e_9_2 = 1e-9f;
        public static final int NUM_15 = 15;
        public static final int NUM_30 = 30;
        public static final int NUM_45 = 45;
        public static final int NUM_60 = 60;
        public static final int NUM_75 = 75;
        public static final int NUM_90 = 90;
        public static final int NUM_6 = 6;
        public static final int NUM_7 = 7;
        public static final float NUM_3_2 = 3f;
        public static final float NUM_0_01 = 0.01f;
        public static final float NUM_1e_7_2 = 1e-7f;
        public static final double NUM_1e_30 = 1e-30;
        final SeamlessParameterization param;
        final ArrayMesh mesh;
        final FaceRosyField field;
        final CombedField combed;
        final List<Singularity> singularities;
        final double alpha;                 // PATCH-44 stopping bound (radians)
        final List<Motorcycle> motorcycles = new ArrayList<>();
        final List<TNode> nodes = new ArrayList<>();
        final List<Crash> crashes = new ArrayList<>();
        // PATCH-95: state lookup by motorcycleId during round-robin.
        // Synthetic boundary motorcycles live in `motorcycles` (added before
        // round-robin starts). Regular motorcycles live in `stateById`
        // until they're converted to Motorcycle objects at the end.
        final java.util.HashMap<Integer, MotorcycleState> stateById = new java.util.HashMap<>();
        int numBoundaryMotorcycles = 0;
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
                for (int c = 0; c < NUM_3; c++) {
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

            // 1.5) PATCH-89 — Phase 0: trace mesh boundary edges as α=0
            //      synthetic motorcycles BEFORE singularity launches. Each
            //      boundary half-edge becomes one one-step "motorcycle" whose
            //      Step crosses the boundary edge in its interior face's UV
            //      frame. They are added to {@code facePaths} so regular
            //      motorcycles see them as walls (raySegmentIntersect picks
            //      them up). On crash, the survival rule in launch() force-
            //      stops the crasher (boundary = mesh edge, off-mesh past it).
            //
            //      Lyon §4.4 motivation: "feature edges and boundary edges
            //      should be represented as arcs in the layout" — represented
            //      here as boundary motorcycles whose traces become layout
            //      arcs by the same TMesh.build splitting path.
            statBoundaryMotorcycles = 0;
            statBoundaryNodesCreated = 0;
            HashMap<Integer, Integer> boundaryVertexToNode = new HashMap<>();
            int E = mesh.edgeCount();
            for (int e = 0; e < E; e++) {
                if (!mesh.isBoundaryEdge(e)) continue;
                int he = mesh.edgeHalfEdge(e);
                // Boundary half-edge: twin is NONE; this `he` is the interior
                // side directly.
                int faceB = mesh.halfEdgeFace(he);
                if (faceB < 0 || param.uvSignedArea(faceB) <= 0) continue;
                int startVid = mesh.halfEdgeVertex(he);
                int endVid = mesh.halfEdgeEndVertex(he);
                int cStart = -1, cEnd = -1;
                for (int c = 0; c < NUM_3; c++) {
                    int vid = mesh.faceVertexAt(faceB, c);
                    if (vid == startVid) cStart = c;
                    else if (vid == endVid) cEnd = c;
                }
                if (cStart < 0 || cEnd < 0) continue;
                float uIn = param.u(faceB, cStart);
                float vIn = param.v(faceB, cStart);
                float uOut = param.u(faceB, cEnd);
                float vOut = param.v(faceB, cEnd);

                // Resolve start/end nodes: prefer SINGULARITY if vertex is one,
                // otherwise create/reuse BOUNDARY node at this UV position.
                int startNode = singVertexToNode.containsKey(startVid)
                        ? singVertexToNode.get(startVid)
                        : getOrCreateBoundaryNode(boundaryVertexToNode, startVid,
                                faceB, uIn, vIn);
                int endNode = singVertexToNode.containsKey(endVid)
                        ? singVertexToNode.get(endVid)
                        : getOrCreateBoundaryNode(boundaryVertexToNode, endVid,
                                faceB, uOut, vOut);

                // Closest-cardinal direction for downstream classifyCardinal /
                // arc.directionAtStart fallbacks. Boundary edges may not align
                // perfectly with cardinals — the inferred direction is the
                // best-fit; TArc.directionAtStart/End reads stepUvs deltas.
                double du = uOut - uIn, dv = vOut - vIn;
                int dir;
                if (Math.abs(du) >= Math.abs(dv)) dir = du >= 0 ? 0 : 2;
                else dir = dv >= 0 ? 1 : NUM_3;

                int mId = motorcycles.size();
                ArrayList<Motorcycle.Step> trace = new ArrayList<>();
                trace.add(new Motorcycle.Step(faceB, uIn, vIn, uOut, vOut, -1));
                Motorcycle bm = new Motorcycle(mId, BOUNDARY_MOTORCYCLE_VID,
                        dir, trace, endNode);
                motorcycles.add(bm);
                facePaths[faceB].add(new float[]{mId, 0, uIn, vIn, uOut, vOut});

                // Register boundary node start under its mesh-vertex key so
                // TMesh.build can look it up via the same singVertexToNode map.
                // We use the synthetic motorcycle's record-keeping to drive
                // singularityNode assignment in TMesh.build; concretely, we
                // remember start node by overloading singVertexToNode with a
                // boundary entry only if vertex isn't already a singularity.
                if (!singVertexToNode.containsKey(startVid)) {
                    singVertexToNode.putIfAbsent(startVid, startNode);
                }
                if (!singVertexToNode.containsKey(endVid)) {
                    singVertexToNode.putIfAbsent(endVid, endNode);
                }
                statBoundaryMotorcycles++;
            }
            // PATCH-95: lock in the boundary-motorcycle count. IDs below
            // this are synthetic boundary motorcycles in `motorcycles`;
            // IDs ≥ this are regular round-robin motorcycles in stateById.
            numBoundaryMotorcycles = motorcycles.size();

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
            statAbortDegenStartFace = 0;
            statAbortBoundaryEdge = 0;
            statAbortNoNeighborFace = 0;
            statAbortNoSharedCorners = 0;
            statAbortNoExitEdge = 0;
            statTraceProperStop = 0;
            statAbortMaxSteps = 0;
            statMaxStepsZeroCrashes = 0;       // PATCH-105
            statMaxStepsOneSidedAlpha = 0;
            statMaxStepsTwoSidedNeverFired = 0;
            statMaxStepsHitFlippedFace = 0;
            statMaxStepsUsedNudge = 0;
            for (int i = 0; i < statMaxStepsUniqueFacesHist.length; i++) statMaxStepsUniqueFacesHist[i] = 0;
            statSelfCrashesSkipped = 0;
            statRealSelfCrashes = 0;
            statMotorcyclesWithSelfCrash = 0;
            java.util.Arrays.fill(statAlphaIjHist, 0);
            abortDumps = new java.util.ArrayList<>();
            motorcycleOutcomes = new java.util.ArrayList<>();
            // PATCH-95: collect initial motorcycle states (one per launch),
            // then drive them all forward in step-locked rounds (EGKT08
            // §5 line 312 parallel propagation). Synthetic boundary
            // motorcycles (PATCH-89) are already in `motorcycles` and
            // not part of the active step-driven set.
            List<MotorcycleState> states = new ArrayList<>();
            int nextMotorcycleId = motorcycles.size();
            // Per-singularity launched count for H1 stat (counted at end).
            int[] launchedPerSingIdx = new int[singularities.size()];
            statSingsInputCount = singularities.size();
            statSingsNoNode = 0;
            int sIdx = 0;
            for (Singularity s : singularities) {
                Integer startNode = singVertexToNode.get(s.vertexId());
                if (startNode == null) {
                    statSingsNoNode++;
                    sIdx++;
                    continue;
                }
                int vId = s.vertexId();
                statSingTotal++;
                boolean boundaryCardinal = false;
                int outCount = mesh.vertexOutgoingHalfEdgeCount(vId);
                for (int oh = 0; oh < outCount; oh++) {
                    int he = mesh.vertexOutgoingHalfEdgeAt(vId, oh);
                    int faceId = mesh.halfEdgeFace(he);
                    if (faceId < 0) continue;
                    int cV = he % NUM_3;
                    int cN = mesh.halfEdgeNext(he) % NUM_3;
                    int cP = mesh.halfEdgePrev(he) % NUM_3;
                    float vU = param.u(faceId, cV);
                    float vV = param.v(faceId, cV);
                    float nU = param.u(faceId, cN);
                    float nV = param.v(faceId, cN);
                    float pU = param.u(faceId, cP);
                    float pV = param.v(faceId, cP);
                    double e1x = nU - vU, e1y = nV - vV;
                    double e2x = pU - vU, e2y = pV - vV;
                    for (int dir = 0; dir < NUM_4; dir++) {
                        double dx = (dir == 0) ? 1 : (dir == 2 ? -1 : 0);
                        double dy = (dir == 1) ? 1 : (dir == NUM_3 ? -1 : 0);
                        double c1 = e1x * dy - e1y * dx;
                        double c2 = dx * e2y - dy * e2x;
                        if (Math.abs(c1) < NUM_1e_7 || Math.abs(c2) < NUM_1e_7) {
                            boundaryCardinal = true;
                        }
                        if (!cardinalInFaceWedge(e1x, e1y, e2x, e2y, dx, dy)) continue;
                        if (faceId < 0 || param.uvSignedArea(faceId) <= 0) continue;
                        MotorcycleState ms = new MotorcycleState(
                                nextMotorcycleId++, vId, dir, startNode, faceId,
                                param.u(faceId, cV), param.v(faceId, cV));
                        states.add(ms);
                        stateById.put(ms.motorcycleId, ms);
                        launchedPerSingIdx[sIdx]++;
                    }
                }
                int bucket = Math.min(launchedPerSingIdx[sIdx], statSingLaunchCount.length - 1);
                statSingLaunchCount[bucket]++;
                if (boundaryCardinal) statSingBoundaryCardinals++;
                sIdx++;
            }

            // PATCH-95: round-robin propagation. Each round, every active
            // motorcycle takes one step (one triangle traversal). Stops fire
            // inside stepMotorcycle. Loop ends when no motorcycle is active.
            int safetyRounds = MAX_STEPS;
            boolean anyActive = true;
            while (anyActive && safetyRounds-- > 0) {
                anyActive = false;
                for (MotorcycleState st : states) {
                    if (!st.active) continue;
                    if (st.step >= MAX_STEPS) {
                        // Per-motorcycle step cap reached; mark inactive.
                        st.active = false;
                        continue;
                    }
                    boolean stillActive = stepMotorcycle(st);
                    st.active = stillActive;
                    if (stillActive) anyActive = true;
                }
            }

            // Convert states to Motorcycle objects.
            for (MotorcycleState st : states) {
                if (st.finalNode < 0) {
                    // Round-robin terminated without a stop firing — MAX_STEPS.
                    // PATCH-107: per uniqueFaces probe, ALL such motorcycles
                    //   are stuck oscillating in a small face set, NOT
                    //   genuinely traversing the mesh. Per EGKT08 spirit,
                    //   stop the trace at its current position with a
                    //   self-stop INTERSECTION node so downstream T-mesh
                    //   build splits the trace correctly (rather than
                    //   leaving a trailing BOUNDARY node that downstream
                    //   stages can't consume). This converts our 53
                    //   "abort-max-steps" into proper terminations.
                    statAbortMaxSteps++;
                    motorcycleOutcomes.add("MAX_STEPS id=" + st.motorcycleId);
                    int cycleNodeId = recordNode(TNode.NodeKind.INTERSECTION,
                            st.curFace, st.u, st.v);
                    crashes.add(new Crash(st.motorcycleId, st.step,
                            st.u, st.v, cycleNodeId,
                            st.motorcycleId, st.step, Math.PI / 2));
                    // PATCH-105 per-cause classification.
                    if (st.crashesRecorded == 0) {
                        statMaxStepsZeroCrashes++;
                    } else if (st.crashesLeftAlpha > 0 && st.crashesRightAlpha > 0) {
                        statMaxStepsTwoSidedNeverFired++;
                    } else {
                        statMaxStepsOneSidedAlpha++;
                    }
                    if (st.enteredFlippedFace > 0) statMaxStepsHitFlippedFace++;
                    if (st.nudgeRecoveryFires > 0) statMaxStepsUsedNudge++;
                    int uf = st.uniqueFaces.size();
                    int b = (uf <= NUM_10) ? 0
                          : (uf <= NUM_50) ? 1
                          : (uf <= NUM_200) ? 2
                          : (uf <= NUM_1000) ? NUM_3
                          : (uf <= NUM_5000) ? NUM_4 : NUM_5;
                    statMaxStepsUniqueFacesHist[b]++;
                    // PATCH-107: use the INTERSECTION node we just created
                    //   (not a BOUNDARY node) so T-mesh treats it as a
                    //   genuine endpoint, not a trail-into-boundary failure.
                    st.finalNode = cycleNodeId;
                }
                if (st.trace.isEmpty()) continue;
                motorcycles.add(new Motorcycle(st.motorcycleId, st.singVid,
                        st.direction, st.trace, st.finalNode));
            }
            return new Result(motorcycles, nodes, crashes, singVertexToNode);
        }

        /**
         * Is cardinal {@code (dx, dy)} strictly inside the face wedge bounded
         *  by {@code (e1x, e1y)} and {@code (e2x, e2y)} (CCW from e1 to e2)?.
         *
         * @param e1x first wedge edge u
         * @param e1y first wedge edge v
         * @param e2x second wedge edge u
         * @param e2y second wedge edge v
         * @param dx  cardinal direction u
         * @param dy  cardinal direction v
         * @return true if the cardinal lies strictly inside the CCW wedge (handles reflex cones via complement test)
         */
        private static boolean cardinalInFaceWedge(double e1x, double e1y,
                                                    double e2x, double e2y,
                                                    double dx, double dy) {
            // Wedge total cross > 0 means CCW wedge < 180°.
            double cTotal = e1x * e2y - e1y * e2x;
            double c1 = e1x * dy - e1y * dx;
            double c2 = dx * e2y - dy * e2x;
            double eps = NUM_1e_9;
            if (cTotal > 0) {
                return c1 > eps && c2 > eps;
            } else {
                // Reflex wedge (cone singularity > 180°): inside iff NOT in the
                // complementary acute wedge.
                return !(c1 < -eps && c2 < -eps);
            }
        }

        /**
         * PATCH-95: take one triangle-step on motorcycle {@code st}. Returns
         * {@code true} if the motorcycle is still active (will continue next
         * round); {@code false} if it stopped during this step (finalNode
         * has been set).
         *
         * <p>This is the body of the previous {@code launch()} for-loop,
         * lifted to operate on per-motorcycle {@link MotorcycleState}.
         * Step-locked round-robin (EGKT08 §5 line 312) means each motorcycle
         * advances ONE step per round, so all motorcycles see the cumulative
         * trace state from prior rounds — the parallel propagation Lyon
         * implicitly assumes via the EGKT08 reference.
         *
         * @param st per-motorcycle state advanced in place
         * @return {@code true} if the motorcycle survives this step (still active for next round); {@code false} once it has stopped
         */
        boolean stepMotorcycle(MotorcycleState st) {
            int motorcycleId = st.motorcycleId;
            int curFace = st.curFace;
            float u = st.u;
            float v = st.v;
            int dirInFace = st.dirInFace;
            int step = st.step;
            ArrayList<Motorcycle.Step> trace = st.trace;
            float cumLen = st.cumLen;
            boolean leftHit = st.leftHit;
            boolean rightHit = st.rightHit;
            int finalNode = -1;

            // === BEGIN body of original launch() for-loop body ===
            do {
                if (curFace < 0 || param.uvSignedArea(curFace) <= 0) {
                    // PATCH-105: count flipped-face encounters before abort.
                    if (curFace >= 0) st.enteredFlippedFace++;
                    // Stepped onto a degen / flipped triangle. Abort cleanly.
                    finalNode = recordNode(TNode.NodeKind.BOUNDARY, curFace, u, v);
                    statAbortDegenStartFace++;
                    break;
                }
                st.uniqueFaces.add(curFace);                // PATCH-107 oscillation probe
                // PATCH-107 cycle detection (deferred): track but don't act
                //   on (face, dir) revisits during the trace — cardinal
                //   trace can legitimately re-enter (face, dir) on a
                //   different parametric path. Decision moved to MAX_STEPS
                //   handler at end of run.
                long faceDirKey = ((long) curFace << NUM_4) | (dirInFace & NUM_0x);
                st.visitedFaceDir.add(faceDirKey);
                // Direction unit vector in face's UV frame.
                float du = (dirInFace == 0) ? NUM_1 : (dirInFace == 2 ? -NUM_1 : NUM_0);
                float dv = (dirInFace == 1) ? NUM_1 : (dirInFace == NUM_3 ? -NUM_1 : NUM_0);

                // Triangle corners of curFace.
                float u0 = param.u(curFace, 0), v0 = param.v(curFace, 0);
                float u1 = param.u(curFace, 1), v1 = param.v(curFace, 1);
                float u2 = param.u(curFace, 2), v2 = param.v(curFace, 2);

                // First, check intersection with any pre-existing trace
                // segment in this face — the closest one wins.
                //
                // PATCH-94: own-trace segments now count as self-crashes per
                // EGKT08's canonical motorcycle-graph algorithm: "particle
                // meets a vertex that has previously been traversed by
                // itself, ... it stops". Lyon §3's survival modification
                // applies to OTHER-trace crashes only — self-crashes follow
                // the EGKT08 default. The previous skip-self filter caused
                // 75/128 motorcycles on rocker-arm to run to MAX_STEPS.
                float bestT = Float.POSITIVE_INFINITY;
                int bestPriorMotorcycle = -1;
                int bestPriorStep = -1;
                for (float[] seg : facePaths[curFace]) {
                    int priorId = (int) seg[0];
                    float t = raySegmentIntersect(u, v, du, dv,
                            seg[2], seg[NUM_3], seg[NUM_4], seg[NUM_5]);
                    if (priorId == motorcycleId) {
                        statSelfCrashesSkipped++;
                        // For self-segments, only the IMMEDIATELY-PRIOR step
                        // shares the current position (its endpoint is our
                        // current u, v, t≈0 — filtered by STEP_EPS). Any
                        // earlier own segment with t > STEP_EPS is a real
                        // self-crossing.
                    }
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
                    for (int e = 0; e < NUM_3; e++) {
                        float[] a = cu[e];
                        float[] b = cu[(e + 1) % NUM_3];
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

                    // PATCH-94: self-crash → EGKT08 canonical stop. Lyon's
                    // survival rule applies only to OTHER-trace crashes;
                    // self-crashes follow EGKT08's default (terminate at
                    // the self-intersection, record an INTERSECTION node).
                    if (bestPriorMotorcycle == motorcycleId) {
                        int selfNodeId = recordNode(TNode.NodeKind.INTERSECTION,
                                curFace, crashU, crashV);
                        Motorcycle.Step ss = new Motorcycle.Step(curFace, u, v,
                                crashU, crashV, -1);
                        trace.add(ss);
                        facePaths[curFace].add(new float[]{
                                motorcycleId, step, u, v, crashU, crashV});
                        // Self-crash with α_ij undefined (cross=0 against own
                        // trace). We record it for TMesh.build splitting but
                        // tag absAlphaIj = π/2 so it's NEVER an "offending"
                        // intersection in Lyon §4.3 Eq.(4).
                        crashes.add(new Crash(motorcycleId, bestPriorStep,
                                crashU, crashV, selfNodeId,
                                motorcycleId, step, Math.PI / 2));
                        statRealSelfCrashes++;
                        finalNode = selfNodeId;
                        motorcycleOutcomes.add("SELF_STOP id=" + motorcycleId
                                + STEP + step);
                        break;
                    }

                    // Classify the crash by α_ij sign + magnitude (Lyon §3).
                    // PATCH-95: prior motorcycle may be a synthetic boundary
                    // (in `motorcycles`) or a regular round-robin motorcycle
                    // (in `stateById`). Resolve via lookupPriorStep.
                    Motorcycle.Step priorStep = lookupPriorStep(
                            bestPriorMotorcycle, bestPriorStep);
                    if (priorStep == null) {
                        // Shouldn't happen — bail defensively as a boundary.
                        finalNode = recordNode(TNode.NodeKind.BOUNDARY,
                                curFace, u, v);
                        statAbortNoSharedCorners++;
                        break;
                    }
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
                    double absAlphaIj = (lIj < NUM_1e_9_2)
                            ? Math.PI / 2
                            : Math.atan((double) lJi / (double) lIj);

                    // PATCH-95 H1: bucket |α_ij| (degrees) for diagnostic.
                    {
                        double degs = Math.toDegrees(absAlphaIj);
                        int bucket;
                        if (degs < NUM_5) bucket = 0;
                        else if (degs < NUM_15) bucket = 1;     // Lyon α=15° boundary
                        else if (degs < NUM_30) bucket = 2;
                        else if (degs < NUM_45) bucket = NUM_3;
                        else if (degs < NUM_60) bucket = NUM_4;
                        else if (degs < NUM_75) bucket = NUM_5;
                        else if (degs < NUM_90) bucket = NUM_6;
                        else bucket = NUM_7;
                        statAlphaIjHist[bucket]++;
                    }

                    // PATCH-105: per-motorcycle crash + sided-alpha counters.
                    st.crashesRecorded++;
                    if (alpha > NUM_1e_9 && absAlphaIj <= alpha + NUM_1e_9) {
                        if (cross < 0) {
                            leftHit = true;
                            st.crashesLeftAlpha++;
                        } else if (cross > 0) {
                            rightHit = true;
                            st.crashesRightAlpha++;
                        }
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

                    // PATCH-89: if the prior trace is a synthetic BOUNDARY
                    // motorcycle, force-stop regardless of α-bound. Past the
                    // mesh boundary is off-mesh — no continuation is valid.
                    boolean priorIsBoundary = bestPriorMotorcycle < numBoundaryMotorcycles
                            && motorcycles.get(bestPriorMotorcycle).singularityVertexId() == BOUNDARY_MOTORCYCLE_VID;

                    boolean stop = (alpha <= NUM_1e_9)            // Eppstein mode
                            || (leftHit && rightHit)          // Lyon both-sides
                            || priorIsBoundary;                // PATCH-89
                    if (stop) {
                        finalNode = crashNodeId;
                        statTraceProperStop++;
                        motorcycleOutcomes.add("PROPER_STOP id=" + motorcycleId
                                + STEP + step);
                        break;
                    }

                    // Survive — but ALSO record a self-crash so TMesh.build
                    // splits OUR trace at this intersection.
                    crashes.add(new Crash(motorcycleId, step,
                            crashU, crashV, crashNodeId,
                            motorcycleId, step, absAlphaIj));

                    // PATCH-95: survival advances the motorcycle past the
                    // crash; the NEXT round picks up from the new position.
                    // (Old code did this via for-loop `continue`; we now
                    // write state back and return true to keep motorcycle
                    // active for the next round.)
                    cumLen += bestT;
                    u = crashU;
                    v = crashV;
                    st.curFace = curFace;
                    st.u = u; st.v = v;
                    st.dirInFace = dirInFace;
                    st.step = step + 1;
                    st.cumLen = cumLen;
                    st.leftHit = leftHit;
                    st.rightHit = rightHit;
                    return true;   // still active
                }

                if (exitEdgeIdx < 0 || !Float.isFinite(exitT)) {
                    // PATCH-93: empirically (rocker-arm at α=15°) 65% of trace
                    // launches abort here because precision drift accumulates
                    // across seam crossings, eventually placing the entry on
                    // an edge with the cardinal direction pointing slightly
                    // OUTWARD from the triangle. Recovery: nudge entry toward
                    // the face centroid by a small epsilon and re-raycast.
                    // Lyon's algorithm assumes traces extend until α-bound
                    // stop or a real intersection — dead-ends from precision
                    // are not a Lyon-paper concept and produce the giant
                    // DCEL artifact cycles we've been chasing.
                    float cx = (u0 + u1 + u2) / NUM_3_2;
                    float cy = (v0 + v1 + v2) / NUM_3_2;
                    // Bigger nudge (1% toward centroid) to escape tangent /
                    // on-edge cases; the nudge is geometrically tiny but
                    // larger than STEP_EPS so raycast finds an exit.
                    float nudgeFrac = NUM_0_01;
                    float uN = u + nudgeFrac * (cx - u);
                    float vN = v + nudgeFrac * (cy - v);
                    float[][] cuRetry = {{u0, v0}, {u1, v1}, {u2, v2}};
                    final float STEP_EPS_RECOVERY = NUM_1e_7_2;
                    for (int e = 0; e < NUM_3; e++) {
                        float[] a = cuRetry[e];
                        float[] b = cuRetry[(e + 1) % NUM_3];
                        float t = raySegmentIntersect(uN, vN, du, dv,
                                a[0], a[1], b[0], b[1]);
                        if (t > STEP_EPS_RECOVERY && t < exitT) {
                            exitT = t;
                            exitEdgeIdx = e;
                        }
                    }
                    if (exitEdgeIdx >= 0) {
                        // Recovered. Use nudged position for the step.
                        u = uN;
                        v = vN;
                        st.nudgeRecoveryFires++;   // PATCH-105
                    } else {
                        // Recovery failed too — genuine abort.
                        if (abortDumps.size() < ABORT_DUMP_LIMIT) {
                            float u0d = param.u(curFace, 0), v0d = param.v(curFace, 0);
                            float u1d = param.u(curFace, 1), v1d = param.v(curFace, 1);
                            float u2d = param.u(curFace, 2), v2d = param.v(curFace, 2);
                            abortDumps.add(String.format(
                                    "no-exit: motoId=%d step=%d face=%d entry=(%.4f,%.4f) dir=(%.2f,%.2f) "
                                    + "corners=[(%.4f,%.4f),(%.4f,%.4f),(%.4f,%.4f)] uvArea=%.6f",
                                    motorcycleId, step, curFace, u, v, du, dv,
                                    u0d, v0d, u1d, v1d, u2d, v2d,
                                    param.uvSignedArea(curFace)));
                        }
                        finalNode = recordNode(TNode.NodeKind.BOUNDARY, curFace, u, v);
                        statAbortNoExitEdge++;
                        break;
                    }
                }

                float exitU = u + exitT * du;
                float exitV = v + exitT * dv;
                Motorcycle.Step exitStep = new Motorcycle.Step(curFace, u, v,
                        exitU, exitV, exitEdgeIdx);
                trace.add(exitStep);
                facePaths[curFace].add(new float[]{
                        motorcycleId, step, u, v, exitU, exitV});
                cumLen += exitT;

                // Transition into the neighbor face.
                int faceMeshEdge = mesh.faceEdgeAt(curFace, exitEdgeIdx);
                if (faceMeshEdge < 0 || mesh.isBoundaryEdge(faceMeshEdge)) {
                    finalNode = recordNode(TNode.NodeKind.BOUNDARY, curFace,
                            exitU, exitV);
                    statAbortBoundaryEdge++;
                    break;
                }
                int nbrFace = neighborFace(curFace, faceMeshEdge);
                if (nbrFace < 0) {
                    finalNode = recordNode(TNode.NodeKind.BOUNDARY, curFace,
                            exitU, exitV);
                    statAbortNoNeighborFace++;
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
                    rotForward = (NUM_4 - r) & NUM_3;
                }
                int nbrDir = (dirInFace + rotForward) & NUM_3;

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
                    statAbortNoSharedCorners++;
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
                // PATCH-107 probe (double-precision per-step transition).
                //   Per-corner UVs come in as float, but barycentric
                //   interpolation along the shared edge accumulates roundoff
                //   that would otherwise put the new entry point microscopically
                //   off the edge in the destination face — triggering the
                //   raycast no-exit case and the PATCH-93 nudge-recovery hack.
                double curAu = param.u(curFace, sharedCornerCur[0]);
                double curAv = param.v(curFace, sharedCornerCur[0]);
                double curBu = param.u(curFace, sharedCornerCur[1]);
                double curBv = param.v(curFace, sharedCornerCur[1]);
                double dxU = curBu - curAu, dxV = curBv - curAv;
                double denom = dxU * dxU + dxV * dxV;
                double frac = (denom > NUM_1e_30)
                        ? ((exitU - curAu) * dxU + (exitV - curAv) * dxV) / denom
                        : 0.0;
                if (frac < 0.0) frac = 0.0;
                if (frac > 1.0) frac = 1.0;
                double nbrAu = param.u(nbrFace, nbrA);
                double nbrAv = param.v(nbrFace, nbrA);
                double nbrBu = param.u(nbrFace, nbrB);
                double nbrBv = param.v(nbrFace, nbrB);
                float newU = (float) (nbrAu + frac * (nbrBu - nbrAu));
                float newV = (float) (nbrAv + frac * (nbrBv - nbrAv));

                u = newU;
                v = newV;
                curFace = nbrFace;
                dirInFace = nbrDir;
                // === END normal step path: motorcycle transitioned to nbrFace.
                // Fall through to writeback + return-true below.
            } while (false);
            // === END body of original launch() for-loop body ===

            // PATCH-95: writeback per-step state + return.
            //   - finalNode >= 0  → motorcycle stopped this step; return false.
            //   - finalNode < 0   → motorcycle still active; persist state and
            //                       return true (next round picks up here).
            if (finalNode >= 0) {
                st.finalNode = finalNode;
                st.curFace = curFace;
                st.u = u; st.v = v;
                st.dirInFace = dirInFace;
                st.step = step + 1;
                st.cumLen = cumLen;
                st.leftHit = leftHit;
                st.rightHit = rightHit;
                return false;
            }
            // Active: persist state for next round.
            st.curFace = curFace;
            st.u = u; st.v = v;
            st.dirInFace = dirInFace;
            st.step = step + 1;
            st.cumLen = cumLen;
            st.leftHit = leftHit;
            st.rightHit = rightHit;
            return true;
        }

        int recordNode(TNode.NodeKind kind, int faceId, float u, float v) {
            TNode n = new TNode(nodes.size(), kind, faceId, u, v);
            nodes.add(n);
            return n.id();
        }

        /**
         * PATCH-89: get or create a BOUNDARY-kind TNode at a mesh boundary
         *  vertex's parametric position in {@code face}. Reused across
         *  multiple boundary-edge synthesis calls that share a vertex.
         *
         * @param map  cached vertex-id → boundary-node-id lookup, updated when a new node is created
         * @param vid  mesh boundary vertex id
         * @param face face whose UV frame holds {@code (u, v)}
         * @param u    parametric u of this vertex in {@code face}
         * @param v    parametric v of this vertex in {@code face}
         * @return id of the existing or newly created BOUNDARY-kind {@link TNode}
         */
        int getOrCreateBoundaryNode(HashMap<Integer, Integer> map, int vid,
                                     int face, float u, float v) {
            Integer existing = map.get(vid);
            if (existing != null) return existing;
            TNode bn = new TNode(nodes.size(), TNode.NodeKind.BOUNDARY,
                    face, u, v);
            nodes.add(bn);
            map.put(vid, bn.id());
            statBoundaryNodesCreated++;
            return bn.id();
        }

        /**
         * PATCH-95: resolve a (motorcycleId, stepIndex) to its Step, looking
         *  in either {@code motorcycles} (synthetic boundaries, IDs &lt;
         *  {@code numBoundaryMotorcycles}) or {@code stateById} (regular
         *  round-robin motorcycles, IDs ≥ {@code numBoundaryMotorcycles}).
         *
         * @param priorMotorcycleId id of either a synthetic boundary motorcycle or a regular round-robin one
         * @param stepIdx           zero-based step index into that motorcycle's trace
         * @return the resolved {@link Motorcycle.Step}, or {@code null} if either lookup fails or {@code stepIdx} is out of range
         */
        Motorcycle.Step lookupPriorStep(int priorMotorcycleId, int stepIdx) {
            List<Motorcycle.Step> trace = lookupPriorTrace(priorMotorcycleId);
            if (trace == null || stepIdx < 0 || stepIdx >= trace.size()) return null;
            return trace.get(stepIdx);
        }

        /**
         * PATCH-95: resolve a motorcycleId to its trace list (synthetic or
         *  in-progress round-robin state).
         *
         * @param priorMotorcycleId id of either a synthetic boundary motorcycle or a regular round-robin one
         * @return the matching trace list, or {@code null} if the id is unknown
         */
        List<Motorcycle.Step> lookupPriorTrace(int priorMotorcycleId) {
            if (priorMotorcycleId < numBoundaryMotorcycles) {
                if (priorMotorcycleId < 0 || priorMotorcycleId >= motorcycles.size())
                    return null;
                return motorcycles.get(priorMotorcycleId).trace();
            }
            MotorcycleState s = stateById.get(priorMotorcycleId);
            return s == null ? null : s.trace;
        }

        /**
         * Walk prior motorcycle's trace from its origin, summing per-step
         *  parametric lengths up to and into step {@code crashStepIdx}, where
         *  the crash sits at {@code (crashU, crashV)} within that step.
         *
         * @param priorMotorcycleId id of the prior motorcycle whose trace is being measured
         * @param crashStepIdx      step index where the crash sits
         * @param crashU            parametric u of the crash point
         * @param crashV            parametric v of the crash point
         * @return cumulative parametric length from origin to the crash, clamped to the full step length to absorb numerical drift
         */
        float cumulativeLengthToCrash(int priorMotorcycleId, int crashStepIdx,
                                       float crashU, float crashV) {
            List<Motorcycle.Step> trace = lookupPriorTrace(priorMotorcycleId);
            if (trace == null) return NUM_0;
            float sum = NUM_0;
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
         *
         * @param faceId     face whose corner indices are returned
         * @param meshEdgeId mesh edge id shared with a neighbouring face
         * @return two-element {@code [c, (c+1) % 3]} corner pair, or {@code null} if {@code meshEdgeId} is not on this face
         */
        int[] sharedCornerIndices(int faceId, int meshEdgeId) {
            for (int c = 0; c < NUM_3; c++) {
                if (mesh.faceEdgeAt(faceId, c) == meshEdgeId) {
                    return new int[]{c, (c + 1) % NUM_3};
                }
            }
            return null;
        }
    }

    //public MotorcycleGraph build() {
        // T = (N nodes, A arcs, P patches), each arc has parametric length and
        // axis (u or v). Each trace is recorded with its origin singularity and
        // the ordered list of arcs along it.
    //}
}
