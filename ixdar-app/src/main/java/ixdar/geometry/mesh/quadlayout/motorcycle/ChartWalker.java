package ixdar.geometry.mesh.quadlayout.motorcycle;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * QEx-style chart walker: advance a parametric iso-line one triangle at a time,
 * composing cut transitions on seam edges.
 */
public final class ChartWalker {

    public static final double RAY_MIN_T = 1.0e-9;
    /** Floats per face corner UV buffer {@code [u0,v0,u1,v1,u2,v2]}. */
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
        public float u;
        public float v;
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
        public State(int activeFace, float u, float v, TraceAxis axis, int sign) {
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
        public double parametricDistanceFrom(float u0, float v0) {
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
        public final float exitU;
        public final float exitV;
        public final int localEdgeIndex;
        public final boolean boundary;

        /**
         * Records one ray exit hit on a triangle edge.
         *
         * @param parametricDelta distance from current point to hit
         * @param exitU           u at intersection
         * @param exitV           v at intersection
         * @param localEdgeIndex  edge index on current face, or -1 at boundary
         * @param boundary        true when the hit is on a mesh boundary
         */
        public EdgeHit(double parametricDelta, float exitU, float exitV, int localEdgeIndex, boolean boundary) {
            this.parametricDelta = parametricDelta;
            this.exitU = exitU;
            this.exitV = exitV;
            this.localEdgeIndex = localEdgeIndex;
            this.boundary = boundary;
        }
    }

    /**
     * Corner UV coordinates for an active face.
     *
     * @param activeFace active face index
     * @param out        length-6 buffer receiving {@code [u0,v0,u1,v1,u2,v2]}
     */
    public void faceCornerUv(int activeFace, float[] out) {
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
        float[] cornerUv = new float[6];
        faceCornerUv(state.activeFace, cornerUv);
        double[] dir = state.axis.direction(state.sign);
        double bestT = Double.POSITIVE_INFINITY;
        float bestU = state.u;
        float bestV = state.v;
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
            double[] hit = UvPredicates.raySegmentIntersection(
                    state.u, state.v, dir[0], dir[1], ax, ay, bx, by, RAY_MIN_T);
            if (hit == null || hit[0] >= bestT) {
                continue;
            }
            bestT = hit[0];
            bestU = (float) hit[1];
            bestV = (float) hit[2];
            bestEdge = edge;
            int faceId = mesh.faceIdAt(state.activeFace);
            int edgeId = mesh.faceEdgeAt(faceId, edge);
            bestBoundary = mesh.isBoundaryEdge(edgeId);
        }
        if (bestEdge < 0 || !Double.isFinite(bestT)) {
            return null;
        }
        return new EdgeHit(bestT, bestU, bestV, bestEdge, bestBoundary);
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
        out.u = edgeHit.exitU;
        out.v = edgeHit.exitV;
        out.axis = state.axis;
        out.sign = state.sign;
        if (edgeHit.boundary) {
            out.activeFace = state.activeFace;
            return false;
        }
        int faceId = mesh.faceIdAt(state.activeFace);
        int edgeId = mesh.faceEdgeAt(faceId, edgeHit.localEdgeIndex);
        int activeEdge = seamless.crossField.edgeIdToActive.get(edgeId);
        HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(activeEdge);
        int nextFaceId = edgeFaces.faceA == faceId ? edgeFaces.faceB : edgeFaces.faceA;
        if (nextFaceId < 0) {
            out.activeFace = state.activeFace;
            return false;
        }
        out.activeFace = seamless.crossField.faceIdToActive.get(nextFaceId);
        out.incomingLocalEdgeIndex = -1;
        for (int edge = 0; edge < CORNERS; edge++) {
            if (mesh.faceEdgeAt(nextFaceId, edge) == edgeId) {
                out.incomingLocalEdgeIndex = edge;
                break;
            }
        }
        if (!seamless.cutGraph.isCutEdge[activeEdge]) {
            return true;
        }
        applyCutTransition(activeEdge, out);
        return true;
    }

    private void applyCutTransition(int activeEdge, State state) {
        int rotation = seamless.cutGraph.cutRotation[activeEdge];
        float s = seamless.cutTranslationS[activeEdge];
        float t = seamless.cutTranslationT[activeEdge];
        float uIn = state.u;
        float vIn = state.v;
        float uOut;
        float vOut;
        switch (rotation) {
        case 1 -> {
            uOut = -vIn + s;
            vOut = uIn + t;
        }
        case 2 -> {
            uOut = -uIn + s;
            vOut = -vIn + t;
        }
        case 3 -> {
            uOut = vIn + s;
            vOut = -uIn + t;
        }
        default -> {
            uOut = uIn + s;
            vOut = vIn + t;
        }
        }
        state.u = uOut;
        state.v = vOut;
        double[] dirIn = state.axis.direction(state.sign);
        double du = dirIn[0];
        double dv = dirIn[1];
        double duOut;
        double dvOut;
        switch (rotation) {
        case 1 -> {
            duOut = -dv;
            dvOut = du;
        }
        case 2 -> {
            duOut = -du;
            dvOut = -dv;
        }
        case 3 -> {
            duOut = dv;
            dvOut = -du;
        }
        default -> {
            duOut = du;
            dvOut = dv;
        }
        }
        state.axis = TraceAxis.fromDirection(duOut, dvOut);
        state.sign = TraceAxis.signFor(state.axis, duOut, dvOut);
    }

    /**
     * Iso-value held constant on this face for the trace (u when axis is V, v when
     * axis is U).
     *
     * @param state trace state
     * @return iso coordinate value
     */
    public static float isoValue(State state) {
        return state.axis.holdsUConstant() ? state.u : state.v;
    }

    /**
     * Span coordinate at the current point along the varying axis.
     *
     * @param state trace state
     * @return varying coordinate
     */
    public static float spanCoordinate(State state) {
        return state.axis.holdsUConstant() ? state.v : state.u;
    }
}
