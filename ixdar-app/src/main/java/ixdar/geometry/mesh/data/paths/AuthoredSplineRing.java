package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * The ring authored anchors decide: the plane fitted through them, its loop, and the spline
 * through them that supporting anchors keep on that loop. A pure function of anchors and base
 * normal.
 */
public final class AuthoredSplineRing {

    public static final int COORDINATES_PER_POINT = 3;

    // One bisection below the confirmed depth, so a hover or a drag fits inside a frame; a
    // confirmed ring is re-traced at the full depth after.
    public static final int SUPPORTING_FIT_DEPTH = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH - 1;

    // Three clicks rarely lie in a girdling plane, so the plane of three or more anchors leans on
    // the base normal by this fraction of their spread and the spline bends through them off it.
    public static final double BASE_NORMAL_PULL_OF_SPREAD = 0.25;

    public static final int JACOBI_ROTATION_SWEEPS = 24;

    public static final int PLANE_FIT_MATRIX_ENTRIES = 9;

    /** Tracer holding the ring after a successful {@link #trace}. */
    public final SurfaceSplineTracer tracer;

    /** Fit that placed the supporting anchors, carrying its tolerance and deviation. */
    public final SplineAnchorFit fit;

    /** The loop the fitted plane cut, the reference the supporting anchors sit on. */
    public final PlaneSurfaceLoop cut = new PlaneSurfaceLoop();

    /** Unit normal of the plane the last trace cut with, packed xyz. */
    public final float[] planeNormal = new float[COORDINATES_PER_POINT];

    /**
     * The authored anchors the last trace placed, in ring order and led by the one given first;
     * empty after a failed trace.
     */
    public int[] authoredVertexId = new int[0];

    /** Why the last trace failed, or empty when it did not. */
    public String failure = "";

    private final float[] planePoint = new float[COORDINATES_PER_POINT];
    private final Vector3f position = new Vector3f();
    private final double[] spread = new double[PLANE_FIT_MATRIX_ENTRIES];
    private final double[] eigenvectors = new double[PLANE_FIT_MATRIX_ENTRIES];
    private int[] referenceVertexId = new int[0];

    /**
     * Binds a ring to the surface engine its geodesics run on.
     *
     * @param geodesics engine holding the surface's cached triangulation
     */
    public AuthoredSplineRing(SurfaceGeodesics geodesics) {
        this.tracer = new SurfaceSplineTracer(geodesics);
        this.fit = new SplineAnchorFit(tracer);
    }

    /**
     * Trace the ring through authored anchors, leaving it in {@link #tracer} with the anchors
     * reordered around the ring in {@link #authoredVertexId}.
     *
     * @param anchorVertexIds mesh vertices the user placed; the first leads the ring, repeats drop
     * @param count           anchors to read from the front of {@code anchorVertexIds}
     * @param baseNormal      normal the plane leans toward, packed xyz; required under three
     *                        anchors, {@code null} lets three or more decide alone
     * @param finalDepth      bisections the finished ring is traced to
     * @return true when a ring was traced; {@link #failure} says why not otherwise
     */
    public boolean trace(int[] anchorVertexIds, int count, float[] baseNormal, int finalDepth) {
        failure = "";
        authoredVertexId = new int[0];
        int[] distinct = new int[count];
        int distinctCount = 0;
        for (int anchor = 0; anchor < count; anchor++) {
            boolean repeated = false;
            for (int held = 0; held < distinctCount; held++) {
                repeated |= distinct[held] == anchorVertexIds[anchor];
            }
            if (!repeated && anchorVertexIds[anchor] >= 0) {
                distinct[distinctCount++] = anchorVertexIds[anchor];
            }
        }
        if (distinctCount == 0
                || (baseNormal == null && distinctCount < SurfaceSplineTracer.MINIMUM_ANCHORS)) {
            failure = "a ring needs one anchor and a base normal, or three anchors";
            return false;
        }
        MeshTopology mesh = tracer.geodesics.mesh;
        fitPlane(mesh, distinct, distinctCount, baseNormal);
        mesh.vertexPosition(distinct[0], position);
        planePoint[0] = position.x;
        planePoint[1] = position.y;
        planePoint[2] = position.z;
        int startEdgeId = -1;
        for (int face = 0; face < mesh.vertexFaceCount(distinct[0]) && startEdgeId < 0; face++) {
            startEdgeId = cut.crossingEdgeOn(mesh, planePoint, planeNormal,
                    mesh.vertexFaceAt(distinct[0], face));
        }
        if (startEdgeId < 0) {
            startEdgeId = cut.nearestCrossingEdge(mesh, planePoint, planeNormal);
        }
        cut.maximumLength = Double.POSITIVE_INFINITY;
        if (startEdgeId < 0 || !cut.walk(mesh, planePoint, planeNormal, startEdgeId)) {
            failure = "the plane through the anchors cuts no closed loop";
            return false;
        }
        int points = cut.stepCount;
        if (referenceVertexId.length < points) {
            referenceVertexId = new int[points];
        }
        for (int step = 0; step < points; step++) {
            int halfEdge = mesh.edgeHalfEdge(cut.edgeId[step]);
            referenceVertexId[step] = cut.crossingFraction[step] <= 0.5
                    ? mesh.halfEdgeVertex(halfEdge)
                    : mesh.halfEdgeEndVertex(halfEdge);
        }
        fit.authoredVertexId = Arrays.copyOf(distinct, distinctCount);
        fit.authoredPoint = new int[distinctCount];
        fit.authoredAllowance = new double[distinctCount];
        for (int anchor = 0; anchor < distinctCount; anchor++) {
            mesh.vertexPosition(distinct[anchor], position);
            double nearest = Double.POSITIVE_INFINITY;
            for (int step = 0; step < points; step++) {
                double dx = cut.polyline[COORDINATES_PER_POINT * step] - position.x;
                double dy = cut.polyline[COORDINATES_PER_POINT * step + 1] - position.y;
                double dz = cut.polyline[COORDINATES_PER_POINT * step + 2] - position.z;
                double squared = dx * dx + dy * dy + dz * dz;
                if (squared < nearest) {
                    nearest = squared;
                    fit.authoredPoint[anchor] = step;
                }
            }
            fit.authoredAllowance[anchor] = SurfaceSpline.distanceToPolyline(cut.polyline, points,
                    position.x, position.y, position.z);
        }
        tracer.maximumDepth = SUPPORTING_FIT_DEPTH;
        if (!fit.fit(cut.polyline, cut.stepCount, referenceVertexId, fit.authoredPoint[0])) {
            failure = "the fit traced no spline on a " + cut.stepCount + "-crossing loop";
            return false;
        }
        if (finalDepth != SUPPORTING_FIT_DEPTH) {
            tracer.maximumDepth = finalDepth;
            tracer.retraceAll();
        }
        int[] placed = new int[distinctCount];
        int held = 0;
        for (int anchor = 0; anchor < tracer.anchorCount; anchor++) {
            if (tracer.anchorAuthored[anchor] && held < distinctCount) {
                placed[held++] = tracer.anchorVertexId[anchor];
            }
        }
        authoredVertexId = Arrays.copyOf(placed, held);
        return true;
    }

    /**
     * Set {@link #planeNormal} to the ring's plane: the base normal for one anchor, the plane
     * through two nearest it, and for more the least-squares plane leaning on it, found by cyclic
     * Jacobi rotations of the anchors' spread. Order-independent to the bit.
     *
     * @param mesh          surface the anchors sit on
     * @param distinct      the anchors' mesh vertices, without repeats
     * @param distinctCount anchors held in the front of {@code distinct}
     * @param baseNormal    normal the plane leans toward, or {@code null}
     */
    public void fitPlane(MeshTopology mesh, int[] distinct, int distinctCount,
            float[] baseNormal) {
        double baseX = 0.0;
        double baseY = 0.0;
        double baseZ = 0.0;
        if (baseNormal != null) {
            double length = Math.sqrt(baseNormal[0] * (double) baseNormal[0]
                    + baseNormal[1] * (double) baseNormal[1] + baseNormal[2] * (double) baseNormal[2]);
            baseX = length > 0.0 ? baseNormal[0] / length : 0.0;
            baseY = length > 0.0 ? baseNormal[1] / length : 0.0;
            baseZ = length > 0.0 ? baseNormal[2] / length : 0.0;
        }
        if (distinctCount == 1) {
            planeNormal[0] = (float) baseX;
            planeNormal[1] = (float) baseY;
            planeNormal[2] = (float) baseZ;
            return;
        }
        if (distinctCount == 2 && baseNormal != null) {
            mesh.vertexPosition(distinct[1], position);
            double chordX = position.x;
            double chordY = position.y;
            double chordZ = position.z;
            mesh.vertexPosition(distinct[0], position);
            chordX -= position.x;
            chordY -= position.y;
            chordZ -= position.z;
            double chordSquared = chordX * chordX + chordY * chordY + chordZ * chordZ;
            double along = (baseX * chordX + baseY * chordY + baseZ * chordZ) / chordSquared;
            double normalX = baseX - along * chordX;
            double normalY = baseY - along * chordY;
            double normalZ = baseZ - along * chordZ;
            double length = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
            if (length > Math.sqrt(Double.MIN_NORMAL)) {
                planeNormal[0] = (float) (normalX / length);
                planeNormal[1] = (float) (normalY / length);
                planeNormal[2] = (float) (normalZ / length);
                return;
            }
        }
        int[] sorted = Arrays.copyOf(distinct, distinctCount);
        Arrays.sort(sorted);
        double[] xyz = new double[COORDINATES_PER_POINT * distinctCount];
        double[] centre = new double[COORDINATES_PER_POINT];
        for (int anchor = 0; anchor < distinctCount; anchor++) {
            mesh.vertexPosition(sorted[anchor], position);
            xyz[COORDINATES_PER_POINT * anchor] = position.x;
            xyz[COORDINATES_PER_POINT * anchor + 1] = position.y;
            xyz[COORDINATES_PER_POINT * anchor + 2] = position.z;
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                centre[axis] += xyz[COORDINATES_PER_POINT * anchor + axis] / distinctCount;
            }
        }
        Arrays.fill(spread, 0.0);
        for (int anchor = 0; anchor < distinctCount; anchor++) {
            for (int row = 0; row < COORDINATES_PER_POINT; row++) {
                for (int column = 0; column < COORDINATES_PER_POINT; column++) {
                    spread[COORDINATES_PER_POINT * row + column] +=
                            (xyz[COORDINATES_PER_POINT * anchor + row] - centre[row])
                                    * (xyz[COORDINATES_PER_POINT * anchor + column]
                                            - centre[column]);
                }
            }
        }
        double[] base = { baseX, baseY, baseZ };
        double pull = BASE_NORMAL_PULL_OF_SPREAD * (spread[0] + spread[COORDINATES_PER_POINT + 1]
                + spread[2 * COORDINATES_PER_POINT + 2]);
        for (int row = 0; baseNormal != null && row < COORDINATES_PER_POINT; row++) {
            for (int column = 0; column < COORDINATES_PER_POINT; column++) {
                spread[COORDINATES_PER_POINT * row + column] +=
                        pull * ((row == column ? 1.0 : 0.0) - base[row] * base[column]);
            }
        }
        Arrays.fill(eigenvectors, 0.0);
        for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
            eigenvectors[COORDINATES_PER_POINT * axis + axis] = 1.0;
        }
        for (int sweep = 0; sweep < JACOBI_ROTATION_SWEEPS; sweep++) {
            for (int p = 0; p < COORDINATES_PER_POINT - 1; p++) {
                for (int q = p + 1; q < COORDINATES_PER_POINT; q++) {
                    double offDiagonal = spread[COORDINATES_PER_POINT * p + q];
                    if (Math.abs(offDiagonal) < Double.MIN_NORMAL) {
                        continue;
                    }
                    double theta = (spread[COORDINATES_PER_POINT * q + q]
                            - spread[COORDINATES_PER_POINT * p + p]) / (2.0 * offDiagonal);
                    double tangent = Math.signum(theta == 0.0 ? 1.0 : theta)
                            / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
                    double cosine = 1.0 / Math.sqrt(tangent * tangent + 1.0);
                    double sine = tangent * cosine;
                    for (int k = 0; k < COORDINATES_PER_POINT; k++) {
                        double kp = spread[COORDINATES_PER_POINT * k + p];
                        double kq = spread[COORDINATES_PER_POINT * k + q];
                        spread[COORDINATES_PER_POINT * k + p] = cosine * kp - sine * kq;
                        spread[COORDINATES_PER_POINT * k + q] = sine * kp + cosine * kq;
                    }
                    for (int k = 0; k < COORDINATES_PER_POINT; k++) {
                        double pk = spread[COORDINATES_PER_POINT * p + k];
                        double qk = spread[COORDINATES_PER_POINT * q + k];
                        spread[COORDINATES_PER_POINT * p + k] = cosine * pk - sine * qk;
                        spread[COORDINATES_PER_POINT * q + k] = sine * pk + cosine * qk;
                    }
                    for (int k = 0; k < COORDINATES_PER_POINT; k++) {
                        double kp = eigenvectors[COORDINATES_PER_POINT * k + p];
                        double kq = eigenvectors[COORDINATES_PER_POINT * k + q];
                        eigenvectors[COORDINATES_PER_POINT * k + p] = cosine * kp - sine * kq;
                        eigenvectors[COORDINATES_PER_POINT * k + q] = sine * kp + cosine * kq;
                    }
                }
            }
        }
        int least = 0;
        for (int axis = 1; axis < COORDINATES_PER_POINT; axis++) {
            if (spread[COORDINATES_PER_POINT * axis + axis]
                    < spread[COORDINATES_PER_POINT * least + least]) {
                least = axis;
            }
        }
        double[] normal = {
            eigenvectors[least], eigenvectors[COORDINATES_PER_POINT + least],
            eigenvectors[2 * COORDINATES_PER_POINT + least] };
        int largestAxis = 0;
        for (int axis = 1; axis < COORDINATES_PER_POINT; axis++) {
            if (Math.abs(normal[axis]) > Math.abs(normal[largestAxis])) {
                largestAxis = axis;
            }
        }
        double sign = baseNormal != null
                ? Math.signum(normal[0] * baseX + normal[1] * baseY + normal[2] * baseZ)
                : Math.signum(normal[largestAxis]);
        double length = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1]
                + normal[2] * normal[2]);
        for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
            planeNormal[axis] = (float) ((sign < 0.0 ? -1.0 : 1.0) * normal[axis] / length);
        }
    }
}
