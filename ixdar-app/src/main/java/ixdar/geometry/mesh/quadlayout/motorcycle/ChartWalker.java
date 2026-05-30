package ixdar.geometry.mesh.quadlayout.motorcycle;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * QEx-style chart walker: advance a parametric iso-line one triangle at a time,
 * composing cut transitions on seam edges.
 */
public final class ChartWalker {

    public static final double RAY_MIN_T = 1.0e-9;
    public static final double ORIENT_COLLINEAR_EPSILON = 1.0e-12;
    /** Doubles per face corner UV buffer {@code [u0,v0,u1,v1,u2,v2]}. */
    public static final int CORNER_UV_FLOATS = 6;
    /** Index of third triangle corner. */
    public static final int CORNER_TWO = 2;
    /** Cut rotation value for 90-degree seam twist. */
    public static final int CUT_ROTATION_90 = 1;
    /** Cut rotation value for 180-degree seam twist. */
    public static final int CUT_ROTATION_180 = 2;
    /** Cut rotation value for 270-degree seam twist. */
    public static final int CUT_ROTATION_270 = 3;
    private static final int CORNERS = SeamlessParameterization.CORNERS_PER_FACE;

    private final SeamlessParameterization seamless;
    private final HalfEdgeMesh mesh;

    /**
     * Binds the walker to a built seamless parametrization.
     *
     * @param seamless built seamless parametrization with UV corners and cut graph
     */
    public ChartWalker(SeamlessParameterization seamless) {
        this.seamless = seamless;
        this.mesh = seamless.mesh;
    }

    /**
     * Mutable chart position and axis-aligned direction of one trace.
     */
    public static final class State {
        public int activeFace;
        public double u;
        public double v;
        public TraceAxis axis;
        public int sign;
        /** Local edge index entered through on {@link #activeFace}, or -1 at spawn. */
        public int incomingLocalEdgeIndex = -1;

        /**
         * Captures a chart position and axis-aligned direction.
         *
         * @param activeFace active face index
         * @param u          current u
         * @param v          current v
         * @param axis       parametric axis
         * @param sign       +1 or -1 along axis
         */
        public State(int activeFace, double u, double v, TraceAxis axis, int sign) {
            this.activeFace = activeFace;
            this.u = u;
            this.v = v;
            this.axis = axis;
            this.sign = sign;
        }

        /**
         * Copy constructor.
         *
         * @param other state to copy
         */
        public State(State other) {
            this.activeFace = other.activeFace;
            this.u = other.u;
            this.v = other.v;
            this.axis = other.axis;
            this.sign = other.sign;
            this.incomingLocalEdgeIndex = other.incomingLocalEdgeIndex;
        }

        /**
         * Parametric distance from {@code (u0, v0)} to current point along the trace
         * axis.
         *
         * @param u0 start u
         * @param v0 start v
         * @return arc length in chart space
         */
        public double parametricDistanceFrom(double u0, double v0) {
            if (axis == TraceAxis.U) {
                return Math.abs(u - u0);
            }
            return Math.abs(v - v0);
        }
    }

    /**
     * Result of advancing until the next triangle-edge or boundary hit.
     */
    public static final class EdgeHit {
        public final double parametricDelta;
        public final double exitU;
        public final double exitV;
        public final int localEdgeIndex;
        public final boolean boundary;
        /**
         * Local corner index (0/1/2) if the hit coincides with a triangle corner, else
         * {@code -1}. Used by {@link MotorcycleGraph} to route through
         * {@link #crossVertex} instead of {@link #crossEdge} so vertex-degenerate
         * iso-lines do not stall.
         */
        public final int cornerLocalIndex;

        /**
         * Records one ray exit hit on a triangle edge.
         *
         * @param parametricDelta  distance from current point to hit
         * @param exitU            u at intersection
         * @param exitV            v at intersection
         * @param localEdgeIndex   edge index on current face, or -1 at boundary
         * @param boundary         true when the hit is on a mesh boundary
         * @param cornerLocalIndex local corner index 0/1/2 if the hit lies on a
         *                         triangle corner (within face-relative epsilon), else
         *                         -1
         */
        public EdgeHit(double parametricDelta, double exitU, double exitV, int localEdgeIndex, boolean boundary,
                int cornerLocalIndex) {
            this.parametricDelta = parametricDelta;
            this.exitU = exitU;
            this.exitV = exitV;
            this.localEdgeIndex = localEdgeIndex;
            this.boundary = boundary;
            this.cornerLocalIndex = cornerLocalIndex;
        }
    }

    /**
     * Corner UV coordinates for an active face.
     *
     * @param activeFace active face index
     * @param out        length-6 buffer receiving {@code [u0,v0,u1,v1,u2,v2]}
     */
    public void faceCornerUv(int activeFace, double[] out) {
        int base = activeFace * CORNERS;
        out[0] = seamless.uCorner[base];
        out[1] = seamless.vCorner[base];
        out[2] = seamless.uCorner[base + 1];
        out[3] = seamless.vCorner[base + 1];
        out[4] = seamless.uCorner[base + 2];
        out[5] = seamless.vCorner[base + 2];
    }

    /**
     * Find the next edge crossing along the trace's iso-line from {@code state}.
     *
     * @param state current position and direction; unchanged by this call
     * @return edge hit data, or {@code null} when no forward hit exists
     */
    public EdgeHit nextEdgeHit(State state) {
        double[] cornerUv = new double[CORNER_UV_FLOATS];
        faceCornerUv(state.activeFace, cornerUv);
        double[] dir = state.axis.direction(state.sign);
        double bestT = Double.POSITIVE_INFINITY;
        double bestU = state.u;
        double bestV = state.v;
        int bestEdge = -1;
        boolean bestBoundary = false;
        for (int edge = 0; edge < CORNERS; edge++) {
            if (edge == state.incomingLocalEdgeIndex) {
                continue;
            }
            int next = (edge + 1) % CORNERS;
            double ax = cornerUv[edge * 2];
            double ay = cornerUv[edge * 2 + 1];
            double bx = cornerUv[next * 2];
            double by = cornerUv[next * 2 + 1];

            double[] hit = raySegmentIntersection(
                    state.u, state.v, dir[0], dir[1], ax, ay, bx, by, RAY_MIN_T);
            if (hit == null || hit[0] >= bestT) {
                continue;
            }
            bestT = hit[0];
            bestU = hit[1];
            bestV = hit[2];
            bestEdge = edge;
            int faceId = mesh.faceIdAt(state.activeFace);
            int edgeId = mesh.faceEdgeAt(faceId, edge);
            bestBoundary = mesh.isBoundaryEdge(edgeId);
        }
        if (bestEdge < 0 || !Double.isFinite(bestT)) {
            return null;
        }
        int cornerLocalIndex = detectCornerHit(cornerUv, bestEdge, bestU, bestV);
        return new EdgeHit(bestT, bestU, bestV, bestEdge, bestBoundary, cornerLocalIndex);
    }

    /**
     * Intersect a ray {@code origin + t * direction} with segment {@code a→b},
     * requiring {@code t > minT}.
     *
     * @param ox   ray origin x
     * @param oy   ray origin y
     * @param dx   ray direction x
     * @param dy   ray direction y
     * @param ax   segment start x
     * @param ay   segment start y
     * @param bx   segment end x
     * @param by   segment end y
     * @param minT minimum ray parameter (exclusive)
     * @return ray parameter {@code t} and intersection point {@code [t, ix, iy]} or
     *         {@code null}
     */
    public static double[] raySegmentIntersection(
            double ox, double oy, double dx, double dy,
            double ax, double ay, double bx, double by, double minT) {
        double segDx = bx - ax;
        double segDy = by - ay;
        double denom = dx * segDy - dy * segDx;
        if (Math.abs(denom) < ORIENT_COLLINEAR_EPSILON) {
            return null;
        }
        double t = ((ax - ox) * segDy - (ay - oy) * segDx) / denom;
        double u = ((ax - ox) * dy - (ay - oy) * dx) / denom;
        if (t <= minT + ORIENT_COLLINEAR_EPSILON) {
            return null;
        }
        if (u < -ORIENT_COLLINEAR_EPSILON || u > 1.0 + ORIENT_COLLINEAR_EPSILON) {
            return null;
        }
        return new double[] { t, ox + t * dx, oy + t * dy };
    }

    /**
     * Identify whether {@code (exitU, exitV)} sits at one of the two corners of
     * face-local edge {@code edge}. Uses a face-relative epsilon scaled to the
     * triangle's edge magnitudes so detection is stable across UV chart sizes.
     *
     * @param cornerUv flattened face corner UV buffer
     * @param edge     local edge index
     * @param exitU    u-coordinate of the exit point
     * @param exitV    v-coordinate of the exit point
     * @return local corner index 0/1/2 the hit coincides with, or -1
     */
    private static int detectCornerHit(double[] cornerUv, int edge, double exitU, double exitV) {
        int next = (edge + 1) % CORNERS;
        double tolSq = cornerEpsilonSquared(cornerUv);
        double d0Sq = squaredDist(cornerUv[edge * 2], cornerUv[edge * 2 + 1], exitU, exitV);
        if (d0Sq <= tolSq) {
            return edge;
        }
        double d1Sq = squaredDist(cornerUv[next * 2], cornerUv[next * 2 + 1], exitU, exitV);
        if (d1Sq <= tolSq) {
            return next;
        }
        return -1;
    }

    private static double cornerEpsilonSquared(double[] cornerUv) {
        // Scale to the face's longest edge so chart magnitude doesn't matter; pick
        // 1e-5 of edge length as the proximity threshold for "this is a corner".
        double e01 = squaredDist(cornerUv[0], cornerUv[1], cornerUv[2], cornerUv[3]);
        double e12 = squaredDist(cornerUv[2], cornerUv[3], cornerUv[4], cornerUv[5]);
        double e20 = squaredDist(cornerUv[4], cornerUv[5], cornerUv[0], cornerUv[1]);
        double maxEdgeSq = Math.max(e01, Math.max(e12, e20));
        return 1.0e-10 * maxEdgeSq;
    }

    private static double squaredDist(double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        return dx * dx + dy * dy;
    }

    /**
     * Cross the given local edge, applying a cut transition when needed, and write
     * the resulting state into {@code out}.
     *
     * @param state   current state before crossing
     * @param edgeHit edge hit from {@link #nextEdgeHit(State)}
     * @param out     destination state after crossing
     * @return {@code false} when crossing hits boundary or no adjacent face exists
     */
    /**
     * Outcome of {@link #crossVertex(State, EdgeHit, State)}.
     */
    public enum CrossVertexResult {
        /**
         * Trace continued into a fan-neighbour face; {@code out} holds the new state.
         */
        FAN_TRANSITION,
        /**
         * Trace reached a singularity; it should terminate as a separatrix endpoint.
         */
        HIT_SINGULARITY,
        /** Trace walked off the mesh boundary while traversing the fan. */
        HIT_BOUNDARY,
        /**
         * Trace walked all the way around the fan without finding a wedge that contains
         * the continuation direction (singularity defect cone).
         */
        HIT_SINGULARITY_GAP
    }

    /**
     * Lyon §3 vertex-aware traversal. When an iso-line exits a face exactly at one
     * of its corners, the next face's two non-incoming edges both share that corner
     * and {@link #nextEdgeHit} returns {@code null}. This method walks the vertex
     * fan around the corner (composing cut transitions per crossed seam) until it
     * finds a face whose interior wedge at {@code V} contains the transported
     * continuation direction.
     *
     * @param state   state at the corner exit (chart position == V in {@code
     *                 state.activeFace})
     * @param edgeHit hit that reported the corner ({@code cornerLocalIndex >= 0})
     * @param out     state filled in on successful transition
     * @return outcome of the fan walk
     */
    public CrossVertexResult crossVertex(State state, EdgeHit edgeHit, State out) {
        int faceId = mesh.faceIdAt(state.activeFace);
        int vertexId = mesh.faceVertexAt(faceId, edgeHit.cornerLocalIndex);
        if (isSingularity(vertexId)) {
            out.activeFace = state.activeFace;
            out.u = edgeHit.exitU;
            out.v = edgeHit.exitV;
            out.axis = state.axis;
            out.sign = state.sign;
            out.incomingLocalEdgeIndex = state.incomingLocalEdgeIndex;
            return CrossVertexResult.HIT_SINGULARITY;
        }
        State probe = new State(state);
        probe.u = edgeHit.exitU;
        probe.v = edgeHit.exitV;
        probe.incomingLocalEdgeIndex = -1;
        int startFace = state.activeFace;
        int firstCcw = edgeHit.cornerLocalIndex;
        int firstCw = (edgeHit.cornerLocalIndex + CORNERS - 1) % CORNERS;
        CrossVertexResult ccw = walkFan(probe, vertexId, firstCcw, startFace, out);
        if (ccw == CrossVertexResult.FAN_TRANSITION) {
            return ccw;
        }
        CrossVertexResult cw = walkFan(probe, vertexId, firstCw, startFace, out);
        if (cw == CrossVertexResult.FAN_TRANSITION) {
            return cw;
        }
        if (ccw == CrossVertexResult.HIT_BOUNDARY && cw == CrossVertexResult.HIT_BOUNDARY) {
            return CrossVertexResult.HIT_BOUNDARY;
        }
        return CrossVertexResult.HIT_SINGULARITY_GAP;
    }

    /**
     * Walk one direction around the vertex fan looking for a wedge that contains
     * the continuation direction. Reuses {@link #crossEdge} so cut transitions
     * accumulate naturally.
     *
     * @param state      initial state at {@code V}
     * @param vertexId   mesh vertex id of {@code V}
     * @param firstCross local edge of the initial face to cross first
     * @param startFace  initial face's active index (for loop detection)
     * @param out        filled with the resulting state on FAN_TRANSITION
     * @return walk outcome (FAN_TRANSITION, HIT_BOUNDARY, or HIT_SINGULARITY_GAP)
     */
    private CrossVertexResult walkFan(State state, int vertexId, int firstCross, int startFace, State out) {
        State current = new State(state);
        int crossEdge = firstCross;
        int maxSteps = mesh.vertexFaceCount(vertexId) + 1;
        for (int step = 0; step < maxSteps; step++) {
            int faceId = mesh.faceIdAt(current.activeFace);
            int crossEdgeId = mesh.faceEdgeAt(faceId, crossEdge);
            if (mesh.isBoundaryEdge(crossEdgeId)) {
                return CrossVertexResult.HIT_BOUNDARY;
            }
            EdgeHit synthetic = new EdgeHit(0.0, current.u, current.v, crossEdge, false, -1);
            State next = new State(current);
            if (!crossEdge(current, synthetic, next)) {
                return CrossVertexResult.HIT_BOUNDARY;
            }
            if (step > 0 && next.activeFace == startFace) {
                return CrossVertexResult.HIT_SINGULARITY_GAP;
            }
            int nextFaceId = mesh.faceIdAt(next.activeFace);
            int cornerInNext = cornerOfVertex(nextFaceId, vertexId);
            if (cornerInNext < 0) {
                return CrossVertexResult.HIT_BOUNDARY;
            }
            if (wedgeContainsDirection(next, cornerInNext)) {
                out.activeFace = next.activeFace;
                out.u = next.u;
                out.v = next.v;
                out.axis = next.axis;
                out.sign = next.sign;
                out.incomingLocalEdgeIndex = -1;
                return CrossVertexResult.FAN_TRANSITION;
            }
            int incomingInNext = next.incomingLocalEdgeIndex;
            int edgeA = cornerInNext;
            int edgeB = (cornerInNext + CORNERS - 1) % CORNERS;
            int nextCross = incomingInNext == edgeA ? edgeB : edgeA;
            current = next;
            crossEdge = nextCross;
        }
        return CrossVertexResult.HIT_SINGULARITY_GAP;
    }

    private boolean wedgeContainsDirection(State state, int cornerInFace) {
        double[] uv = new double[CORNER_UV_FLOATS];
        faceCornerUv(state.activeFace, uv);
        int aIdx = (cornerInFace + 1) % CORNERS;
        int bIdx = (cornerInFace + CORNERS - 1) % CORNERS;
        double aDirX = uv[aIdx * 2] - state.u;
        double aDirY = uv[aIdx * 2 + 1] - state.v;
        double bDirX = uv[bIdx * 2] - state.u;
        double bDirY = uv[bIdx * 2 + 1] - state.v;
        double[] dir = state.axis.direction(state.sign);
        double wedgeSign = aDirX * bDirY - aDirY * bDirX;
        double crossA = aDirX * dir[1] - aDirY * dir[0];
        double crossB = dir[0] * bDirY - dir[1] * bDirX;
        return wedgeSign * crossA >= 0.0 && wedgeSign * crossB >= 0.0;
    }

    private int cornerOfVertex(int faceId, int vertexId) {
        for (int corner = 0; corner < CORNERS; corner++) {
            if (mesh.faceVertexAt(faceId, corner) == vertexId) {
                return corner;
            }
        }
        return -1;
    }

    private boolean isSingularity(int vertexId) {
        for (var s : seamless.crossField.singularities) {
            if (s.vertexId() == vertexId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cross the given local edge, applying a cut transition when needed, and write
     * the resulting state into {@code out}.
     *
     * @param state   current state before crossing
     * @param edgeHit edge hit from {@link #nextEdgeHit(State)}
     * @param out     destination state after crossing
     * @return {@code false} when crossing hits boundary or no adjacent face exists
     */
    public boolean crossEdge(State state, EdgeHit edgeHit, State out) {
        out.axis = state.axis;
        out.sign = state.sign;
        if (edgeHit.boundary) {
            out.u = edgeHit.exitU;
            out.v = edgeHit.exitV;
            out.activeFace = state.activeFace;
            return false;
        }
        int faceId = mesh.faceIdAt(state.activeFace);
        int edgeId = mesh.faceEdgeAt(faceId, edgeHit.localEdgeIndex);
        int activeEdge = seamless.crossField.edgeIdToActive.get(edgeId);
        HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(activeEdge);
        int nextFaceId = edgeFaces.faceA == faceId ? edgeFaces.faceB : edgeFaces.faceA;
        if (nextFaceId < 0) {
            out.u = edgeHit.exitU;
            out.v = edgeHit.exitV;
            out.activeFace = state.activeFace;
            return false;
        }
        int nextActiveFace = seamless.crossField.faceIdToActive.get(nextFaceId);
        int incomingInNext = -1;
        for (int edge = 0; edge < CORNERS; edge++) {
            if (mesh.faceEdgeAt(nextFaceId, edge) == edgeId) {
                incomingInNext = edge;
                break;
            }
        }
        applyChartTransition(state, edgeHit, faceId, nextFaceId,
                nextActiveFace, incomingInNext, out);
        return true;
    }

    /**
     * Derive the affine chart-to-chart transition for the shared edge directly from
     * both faces' stored corner UVs and apply it to the exit point and the trace
     * direction. Avoids relying on {@code cutGraph.isCutEdge} or the
     * {@code cutTranslationS/T} table — which on this codebase's seamless builds
     * can be incomplete or inconsistent, leaving traces in a chart frame that does
     * not match their {@code activeFace}.
     *
     * @param state          current state in the old face
     * @param edgeHit        exit hit on the shared edge
     * @param faceId         old mesh face id
     * @param nextFaceId     new mesh face id
     * @param nextActiveFace new active face index
     * @param incomingInNext local edge index in the new face for the shared edge
     * @param out            destination state to fill in
     */
    private void applyChartTransition(State state, EdgeHit edgeHit, int faceId, int nextFaceId,
            int nextActiveFace, int incomingInNext, State out) {
        int oldCornerA = edgeHit.localEdgeIndex;
        int oldCornerB = (oldCornerA + 1) % CORNERS;
        int newCornerP = incomingInNext;
        int newCornerQ = (incomingInNext + 1) % CORNERS;
        int vertexA = mesh.faceVertexAt(faceId, oldCornerA);
        int newVertexP = mesh.faceVertexAt(nextFaceId, newCornerP);
        int newCornerA = vertexA == newVertexP ? newCornerP : newCornerQ;
        int newCornerB = newCornerA == newCornerP ? newCornerQ : newCornerP;

        double[] oldUv = new double[CORNER_UV_FLOATS];
        double[] newUv = new double[CORNER_UV_FLOATS];
        faceCornerUv(state.activeFace, oldUv);
        faceCornerUv(nextActiveFace, newUv);
        double oldAx = oldUv[oldCornerA * 2];
        double oldAy = oldUv[oldCornerA * 2 + 1];
        double oldBx = oldUv[oldCornerB * 2];
        double oldBy = oldUv[oldCornerB * 2 + 1];
        double newAx = newUv[newCornerA * 2];
        double newAy = newUv[newCornerA * 2 + 1];
        double newBx = newUv[newCornerB * 2];
        double newBy = newUv[newCornerB * 2 + 1];

        double oldDx = oldBx - oldAx;
        double oldDy = oldBy - oldAy;
        double newDx = newBx - newAx;
        double newDy = newBy - newAy;
        double oldLenSq = oldDx * oldDx + oldDy * oldDy;
        double oldNewDot = oldDx * newDx + oldDy * newDy;
        double oldNewCross = oldDx * newDy - oldDy * newDx;
        double rotCos = oldNewDot / oldLenSq;
        double rotSin = oldNewCross / oldLenSq;
        double snappedCos = snapToAxisAlignedRotation(rotCos);
        double snappedSin = snapToAxisAlignedRotation(rotSin);
        double tx = newAx - (snappedCos * oldAx - snappedSin * oldAy);
        double ty = newAy - (snappedSin * oldAx + snappedCos * oldAy);

        double newExitU = snappedCos * edgeHit.exitU - snappedSin * edgeHit.exitV + tx;
        double newExitV = snappedSin * edgeHit.exitU + snappedCos * edgeHit.exitV + ty;
        double[] dirOld = state.axis.direction(state.sign);
        double newDirX = snappedCos * dirOld[0] - snappedSin * dirOld[1];
        double newDirY = snappedSin * dirOld[0] + snappedCos * dirOld[1];

        out.u = newExitU;
        out.v = newExitV;
        out.activeFace = nextActiveFace;
        out.incomingLocalEdgeIndex = incomingInNext;
        out.axis = TraceAxis.fromDirection(newDirX, newDirY);
        out.sign = TraceAxis.signFor(out.axis, newDirX, newDirY);
    }

    private static double snapToAxisAlignedRotation(double value) {
        if (value > 0.5) {
            return 1.0;
        }
        if (value < -0.5) {
            return -1.0;
        }
        return 0.0;
    }

    /**
     * Iso-value held constant on this face for the trace (u when axis is V, v when
     * axis is U).
     *
     * @param state trace state
     * @return iso coordinate value
     */
    public static double isoValue(State state) {
        return state.axis.holdsUConstant() ? state.u : state.v;
    }

    /**
     * Span coordinate at the current point along the varying axis.
     *
     * @param state trace state
     * @return varying coordinate
     */
    public static double spanCoordinate(State state) {
        return state.axis.holdsUConstant() ? state.v : state.u;
    }
}
