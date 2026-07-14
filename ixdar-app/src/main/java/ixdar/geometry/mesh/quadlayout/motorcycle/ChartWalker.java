package ixdar.geometry.mesh.quadlayout.motorcycle;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceAxis;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * QEx-style chart walker: advance a parametric iso-line one triangle at a time,
 * composing cut transitions on seam edges.
 */
public final class ChartWalker {

    /** Doubles per face corner UV buffer {@code [u0,v0,u1,v1,u2,v2]}. */
    public static final int CORNER_UV_FLOATS = 6;
    public static final int CORNERS = SeamlessParameterization.CORNERS_PER_FACE;

    public final SeamlessParameterization seamless;
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
         * Exact parameter of the hit along {@link #localEdgeIndex}, running from
         * corner {@code localEdgeIndex} to corner {@code (localEdgeIndex + 1) % 3}.
         * Zero for a corner hit. The LCBK19 §6.1 embedding carve splits the crossed
         * mesh edge at exactly this parameter, so it must not be re-derived from the
         * lifted 3D position.
         */
        public final double edgeParameter;

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
         * @param edgeParameter    exact parameter of the hit along the local edge
         */
        public EdgeHit(double parametricDelta, double exitU, double exitV, int localEdgeIndex, boolean boundary,
                int cornerLocalIndex, double edgeParameter) {
            this.parametricDelta = parametricDelta;
            this.exitU = exitU;
            this.exitV = exitV;
            this.localEdgeIndex = localEdgeIndex;
            this.boundary = boundary;
            this.cornerLocalIndex = cornerLocalIndex;
            this.edgeParameter = edgeParameter;
        }
    }

    /**
     * Find the next edge crossing along the trace's iso-line from {@code state}.
     *
     * <p>
     * QEx-style sign-predicate walk: the trace is the level set {@code held ==
     * level} with {@code level} read exactly from the state's held coordinate, and
     * each corner's signed offset {@code cornerHeld - level} determines the
     * topology. An edge is crossed iff its endpoint offsets strictly straddle zero;
     * a corner with an exactly-zero offset is the Lyon §3 vertex case, reported via
     * {@code cornerLocalIndex} so the caller routes through {@link #crossVertex}.
     * No tolerance decides anything: the constructed exit coordinate only feeds
     * span bookkeeping, never the choice of exit. When the trace entered through a
     * known edge the exit is the unique other candidate (purely combinatorial); the
     * strictly-forward filter is only needed at spawn/meeting states where the
     * incoming edge is unknown.
     *
     * @param state current position and direction; unchanged by this call
     * @return edge hit data, or {@code null} when the level line leaves the face
     *         only behind the current position (corner-tangent or inconsistent
     *         state)
     */
    public EdgeHit nextEdgeHit(State state) {
        double[] cornerUv = new double[CORNER_UV_FLOATS];
        seamless.faceCornerUv(state.activeFace, cornerUv);
        boolean holdsU = state.axis.holdsUConstant();
        double level = holdsU ? state.u : state.v;
        double currentAlong = holdsU ? state.v : state.u;

        double[] heldDelta = new double[CORNERS];
        for (int corner = 0; corner < CORNERS; corner++) {
            double held = holdsU ? cornerUv[corner * 2] : cornerUv[corner * 2 + 1];
            heldDelta[corner] = held - level;
        }

        int candidateCount = 0;
        int[] candidateEdge = new int[CORNERS];
        int[] candidateCorner = new int[CORNERS];
        double[] candidateAlong = new double[CORNERS];
        double[] candidateParam = new double[CORNERS];
        for (int corner = 0; corner < CORNERS; corner++) {
            if (heldDelta[corner] != 0.0) {
                continue;
            }
            candidateEdge[candidateCount] = corner;
            candidateCorner[candidateCount] = corner;
            candidateAlong[candidateCount] = holdsU ? cornerUv[corner * 2 + 1] : cornerUv[corner * 2];
            candidateParam[candidateCount] = 0.0;
            candidateCount++;
        }
        for (int edge = 0; edge < CORNERS; edge++) {
            if (edge == state.incomingLocalEdgeIndex) {
                continue;
            }
            int next = (edge + 1) % CORNERS;
            if (!(heldDelta[edge] * heldDelta[next] < 0.0)) {
                continue;
            }
            double alongA = holdsU ? cornerUv[edge * 2 + 1] : cornerUv[edge * 2];
            double alongB = holdsU ? cornerUv[next * 2 + 1] : cornerUv[next * 2];
            double tEdge = heldDelta[edge] / (heldDelta[edge] - heldDelta[next]);
            candidateEdge[candidateCount] = edge;
            candidateCorner[candidateCount] = -1;
            candidateAlong[candidateCount] = alongA + tEdge * (alongB - alongA);
            candidateParam[candidateCount] = tEdge;
            candidateCount++;
        }

        int best = -1;
        if (state.incomingLocalEdgeIndex >= 0 && candidateCount == 1) {
            best = 0;
        } else {
            double bestAdvance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < candidateCount; i++) {
                double advance = (candidateAlong[i] - currentAlong) * state.sign;
                if (advance > 0.0 && advance < bestAdvance) {
                    bestAdvance = advance;
                    best = i;
                }
            }
        }
        if (best < 0) {
            return null;
        }
        double parametricDelta = Math.abs(candidateAlong[best] - currentAlong);
        if (candidateCorner[best] >= 0) {
            int corner = candidateCorner[best];
            return new EdgeHit(parametricDelta, cornerUv[corner * 2], cornerUv[corner * 2 + 1],
                    corner, false, corner, 0.0);
        }
        double exitU = holdsU ? level : candidateAlong[best];
        double exitV = holdsU ? candidateAlong[best] : level;
        int faceId = mesh.faceIdAt(state.activeFace);
        int edgeId = mesh.faceEdgeAt(faceId, candidateEdge[best]);
        return new EdgeHit(parametricDelta, exitU, exitV, candidateEdge[best],
                mesh.isBoundaryEdge(edgeId), -1, candidateParam[best]);
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
        boolean isSingularity = false;
        for (var s : seamless.crossField.singularities) {
            if (s.vertexId() == vertexId) {
                isSingularity = true;
                break;
            }
        }
        if (isSingularity) {
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
            EdgeHit synthetic = new EdgeHit(0.0, current.u, current.v, crossEdge, false, -1, 0.0);
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
            double[] uv = new double[CORNER_UV_FLOATS];
            seamless.faceCornerUv(next.activeFace, uv);
            int aIdx = (cornerInNext + 1) % CORNERS;
            int bIdx = (cornerInNext + CORNERS - 1) % CORNERS;
            double aDirX = uv[aIdx * 2] - next.u;
            double aDirY = uv[aIdx * 2 + 1] - next.v;
            double bDirX = uv[bIdx * 2] - next.u;
            double bDirY = uv[bIdx * 2 + 1] - next.v;
            double[] dir = next.axis.direction(next.sign);
            double wedgeSign = aDirX * bDirY - aDirY * bDirX;
            double crossA = aDirX * dir[1] - aDirY * dir[0];
            double crossB = dir[0] * bDirY - dir[1] * bDirX;
            if (wedgeSign * crossA >= 0.0 && wedgeSign * crossB >= 0.0) {
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

    private int cornerOfVertex(int faceId, int vertexId) {
        for (int corner = 0; corner < CORNERS; corner++) {
            if (mesh.faceVertexAt(faceId, corner) == vertexId) {
                return corner;
            }
        }
        return -1;
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
        seamless.faceCornerUv(state.activeFace, oldUv);
        seamless.faceCornerUv(nextActiveFace, newUv);
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
        double snappedCos = rotCos > 0.5 ? 1.0 : (rotCos < -0.5 ? -1.0 : 0.0);
        double snappedSin = rotSin > 0.5 ? 1.0 : (rotSin < -0.5 ? -1.0 : 0.0);
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
        return true;
    }

}
