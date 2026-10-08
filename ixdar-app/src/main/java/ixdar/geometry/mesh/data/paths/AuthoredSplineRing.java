package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;
import java.util.Locale;
import java.util.PriorityQueue;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * The ring authored anchors decide, a pure function of their cycle and base normal: up to three
 * follow the loop of a plane fitted through them, more keep their order with every span following
 * the geodesic to the next.
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

    // Up to this many anchors have one cyclic order whatever is clicked, so the plane's loop can
    // shape the ring; past it the order is the ring's own and geodesics shape each span.
    public static final int PLANE_STARTED_ANCHORS = 3;

    public static final double FOLD_ARC_OF_SPAN = 0.25;

    // The fold test's arc never goes under a few edges, so snapping its ends to vertices cannot
    // bring them together, and it calls a fold only when they come back within half the arc.
    public static final double FOLD_ARC_EDGES = 3.0;

    public static final double FOLD_GAP_OF_ARC = 0.5;

    // A crease ring's search keeps to a slab this many plane-loop radii either side of the plane,
    // so it stays on the part the anchors girdle.
    public static final double CREASE_BAND_OF_RADIUS = 1.0;

    // A groove ring starts from the nearest groove within this fraction of the plane loop's
    // radius of the click.
    public static final double GROOVE_REACH_OF_RADIUS = 0.15;

    // Valley strength at which a vertex counts as lying in a groove.
    public static final double IN_GROOVE_STRENGTH = 0.5;

    // A groove ring that runs in a groove for less than this fraction of its length found none.
    public static final double MINIMUM_GROOVE_FRACTION = 0.5;

    public static final int SHEETS = 2;

    public static final double PERCENT = 100.0;

    /** Tracer holding the ring after a successful {@link #trace}. */
    public final SurfaceSplineTracer tracer;

    /** Fit that placed the supporting anchors, carrying its tolerance and deviation. */
    public final SplineAnchorFit fit;

    /** The loop the fitted plane cut on the last plane-started trace, its supporting reference. */
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

    /**
     * Surface length each slot of the last {@link #slotsByAddedLength} call would add, by slot.
     */
    public double[] addedLength = new double[0];

    /** Whether the last traced ring is a simple closed curve on the surface. */
    public boolean simple;

    /** Where the last traced ring crosses or touches itself, when it is not simple. */
    public final SurfacePathCrossings crossings = new SurfacePathCrossings();

    /** How the ring runs between authored anchors; the crease mode needs {@link #creases}. */
    public RingSegmentMode mode = RingSegmentMode.GEODESIC;

    /** Crease cost of the surface the crease mode follows, or {@code null} for none. */
    public SurfaceCreases creases;

    /** Fraction of the last crease reference's length lying in a groove; zero in geodesic mode. */
    public double grooveFraction;

    /**
     * Base normal the last successful {@link #traceGroove} traced with, as a statement writes it:
     * the groove's own plane, or the girdling plane given when that rang no groove.
     */
    public final float[] grooveNormal = new float[COORDINATES_PER_POINT];

    private final float[] planePoint = new float[COORDINATES_PER_POINT];
    private final Vector3f position = new Vector3f();
    private final double[] spread = new double[PLANE_FIT_MATRIX_ENTRIES];
    private final double[] eigenvectors = new double[PLANE_FIT_MATRIX_ENTRIES];
    private int[] referenceVertexId = new int[0];
    private float[] cycleXyz = new float[0];
    private int[] spanLabel = new int[0];
    private boolean cycleDoublesBack;
    private int referencePoints;
    private double[] sheetDistance = new double[0];
    private int[] sheetParent = new int[0];
    private int[] sheetVisit = new int[0];
    private int visit;
    private final Vector3f neighbour = new Vector3f();
    private final Vector3f clickPosition = new Vector3f();

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
     * Trace the ring through authored anchors into {@link #tracer}. Up to
     * {@link #PLANE_STARTED_ANCHORS} follow the plane's loop, more the geodesics between them; in
     * the crease {@link #mode} every span follows the cheapest crease path once round the part.
     *
     * @param anchorVertexIds mesh vertices the user placed, in ring order; the first leads the
     *                        ring, repeats drop
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
        grooveFraction = 0.0;
        if (mode == RingSegmentMode.CREASE && (creases == null || creases.sourceMesh != mesh)) {
            failure = "a crease ring needs the creases of the surface it rings";
            return false;
        }
        fitPlane(mesh, distinct, distinctCount, baseNormal);
        fit.authoredVertexId = Arrays.copyOf(distinct, distinctCount);
        fit.authoredPoint = new int[distinctCount];
        fit.authoredAllowance = new double[distinctCount];
        if (mode == RingSegmentMode.GEODESIC) {
            if (distinctCount <= PLANE_STARTED_ANCHORS) {
                int loopPoints = planeLoopReference(mesh);
                return loopPoints > 0 && fitAndTrace(mesh, cut.polyline, loopPoints, finalDepth);
            }
            // Geodesic spans when they make a simple ring that passes through every anchor.
            // Where the anchors leave a stretch of the girdle uncovered a geodesic cuts back the
            // short way, so the plane's loop shapes the ring instead, provided it keeps the
            // anchors' order; the geodesic ring stands otherwise.
            int cyclePoints = geodesicCycleReference(mesh);
            if (cyclePoints < 0 || !fitAndTrace(mesh, cycleXyz, cyclePoints, finalDepth)) {
                return false;
            }
            if (simple && !cycleDoublesBack) {
                return true;
            }
            int loopPoints = planeLoopReference(mesh);
            if (loopPoints > 0 && fitAndTrace(mesh, cut.polyline, loopPoints, finalDepth)
                    && simple && authoredVertexId.length == distinctCount) {
                int start = 0;
                while (authoredVertexId[start] != distinct[0]) {
                    start++;
                }
                boolean forward = true;
                boolean backward = true;
                for (int anchor = 0; anchor < distinctCount; anchor++) {
                    forward &= authoredVertexId[(start + anchor) % distinctCount]
                            == distinct[anchor];
                    backward &= authoredVertexId[Math.floorMod(start - anchor, distinctCount)]
                            == distinct[anchor];
                }
                if (forward || backward) {
                    return true;
                }
            }
            geodesicCycleReference(mesh);
            return fitAndTrace(mesh, cycleXyz, cyclePoints, finalDepth);
        }
        // The crease ring: the cheapest closed crease path through the anchors in their order,
        // once round the part, is the reference the spline fits. Once round is tested on a double
        // cover of a slab about the plane: a half-plane from the plane loop's centre through the
        // first anchor is a seam, crossing it swaps sheets, and the closed path must end on the
        // other sheet. Each span is one Dijkstra from its anchor over both sheets and takes its
        // cheaper sheet; when the swaps come out even, the span losing least by its other sheet
        // takes that one.
        int loopPoints = planeLoopReference(mesh);
        if (loopPoints < 0) {
            return false;
        }
        double[] centre = new double[COORDINATES_PER_POINT];
        for (int point = 0; point < loopPoints; point++) {
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                centre[axis] += cut.polyline[COORDINATES_PER_POINT * point + axis] / loopPoints;
            }
        }
        double band = CREASE_BAND_OF_RADIUS * SplineAnchorFit.meanRadiusOf(cut.polyline,
                loopPoints);
        for (int anchor = 0; anchor < distinctCount; anchor++) {
            mesh.vertexPosition(distinct[anchor], position);
            band = Math.max(band, Math.abs(planeNormal[0] * (position.x - centre[0])
                    + planeNormal[1] * (position.y - centre[1])
                    + planeNormal[2] * (position.z - centre[2])));
        }
        // The seam runs from the centre along the first anchor's direction in the plane, and the
        // across axis completes the plane's frame.
        mesh.vertexPosition(distinct[0], position);
        double[] seam = { position.x - centre[0], position.y - centre[1], position.z - centre[2] };
        double off = seam[0] * planeNormal[0] + seam[1] * planeNormal[1] + seam[2] * planeNormal[2];
        double seamLength = 0.0;
        for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
            seam[axis] -= off * planeNormal[axis];
            seamLength += seam[axis] * seam[axis];
        }
        seamLength = Math.sqrt(seamLength);
        if (seamLength <= 0.0) {
            failure = "the first anchor sits on the axis of the plane's loop";
            return false;
        }
        for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
            seam[axis] /= seamLength;
        }
        double[] across = {
            planeNormal[1] * seam[2] - planeNormal[2] * seam[1],
            planeNormal[2] * seam[0] - planeNormal[0] * seam[2],
            planeNormal[0] * seam[1] - planeNormal[1] * seam[0] };
        int states = SHEETS * tracer.geodesics.metric.vertexIdBound;
        if (sheetVisit.length < states) {
            sheetDistance = new double[states];
            sheetParent = new int[states];
            sheetVisit = new int[states];
            visit = 0;
        }
        double[] spanCost = new double[SHEETS * distinctCount];
        int[][] spanPath = new int[SHEETS * distinctCount][];
        PriorityQueue<double[]> frontier = new PriorityQueue<>(
                (left, right) -> Double.compare(left[0], right[0]));
        for (int anchor = 0; anchor < distinctCount; anchor++) {
            int source = SHEETS * distinct[anchor];
            int target = SHEETS * distinct[(anchor + 1) % distinctCount];
            visit++;
            frontier.clear();
            sheetVisit[source] = visit;
            sheetDistance[source] = 0.0;
            sheetParent[source] = -1;
            frontier.add(new double[] { 0.0, source });
            int settledTargets = 0;
            while (!frontier.isEmpty() && settledTargets < SHEETS) {
                double[] entry = frontier.poll();
                int state = (int) entry[1];
                if (entry[0] > sheetDistance[state]) {
                    continue;
                }
                settledTargets += state == target || state == target + 1 ? 1 : 0;
                int vertexId = state / SHEETS;
                int sheet = state % SHEETS;
                mesh.vertexPosition(vertexId, position);
                double hereAlong = (position.x - centre[0]) * seam[0]
                        + (position.y - centre[1]) * seam[1] + (position.z - centre[2]) * seam[2];
                double hereAcross = (position.x - centre[0]) * across[0]
                        + (position.y - centre[1]) * across[1]
                        + (position.z - centre[2]) * across[2];
                int spokes = mesh.vertexEdgeCount(vertexId);
                for (int spoke = 0; spoke < spokes; spoke++) {
                    int edgeId = mesh.vertexEdgeAt(vertexId, spoke);
                    int otherId = mesh.edgeOtherVertex(edgeId, vertexId);
                    if (otherId < 0) {
                        continue;
                    }
                    mesh.vertexPosition(otherId, neighbour);
                    double dx = neighbour.x - centre[0];
                    double dy = neighbour.y - centre[1];
                    double dz = neighbour.z - centre[2];
                    if (Math.abs(dx * planeNormal[0] + dy * planeNormal[1] + dz * planeNormal[2])
                            > band) {
                        continue;
                    }
                    double thereAlong = dx * seam[0] + dy * seam[1] + dz * seam[2];
                    double thereAcross = dx * across[0] + dy * across[1] + dz * across[2];
                    // The edge crosses the seam where it changes side of the across axis on the
                    // seam's own half, not on the half behind the centre.
                    boolean crosses = (hereAcross < 0.0) != (thereAcross < 0.0)
                            && hereAlong + (thereAlong - hereAlong) * hereAcross
                                    / (hereAcross - thereAcross) > 0.0;
                    int next = SHEETS * otherId + (crosses ? 1 - sheet : sheet);
                    double relaxed = sheetDistance[state] + creases.edgeCost[edgeId];
                    if (sheetVisit[next] != visit || relaxed < sheetDistance[next]) {
                        sheetVisit[next] = visit;
                        sheetDistance[next] = relaxed;
                        sheetParent[next] = state;
                        frontier.add(new double[] { relaxed, next });
                    }
                }
            }
            for (int sheet = 0; sheet < SHEETS; sheet++) {
                int end = target + sheet;
                boolean reached = sheetVisit[end] == visit;
                spanCost[SHEETS * anchor + sheet] = reached ? sheetDistance[end]
                        : Double.POSITIVE_INFINITY;
                int length = 0;
                for (int state = end; reached && state >= 0; state = sheetParent[state]) {
                    length++;
                }
                int[] path = new int[length];
                for (int state = end; reached && state >= 0; state = sheetParent[state]) {
                    path[--length] = state / SHEETS;
                }
                spanPath[SHEETS * anchor + sheet] = path;
            }
        }
        int[] chosen = new int[distinctCount];
        int parity = 0;
        int flip = -1;
        double cheapestFlip = Double.POSITIVE_INFINITY;
        for (int anchor = 0; anchor < distinctCount; anchor++) {
            double first = spanCost[SHEETS * anchor];
            double second = spanCost[SHEETS * anchor + 1];
            chosen[anchor] = first <= second ? 0 : 1;
            parity ^= chosen[anchor];
            double loss = Math.abs(second - first);
            if (loss < cheapestFlip) {
                cheapestFlip = loss;
                flip = anchor;
            }
        }
        if (parity == 0 && flip >= 0) {
            chosen[flip] ^= 1;
        }
        int points = 0;
        for (int anchor = 0; anchor < distinctCount; anchor++) {
            if (spanCost[SHEETS * anchor + chosen[anchor]] == Double.POSITIVE_INFINITY) {
                failure = "no crease path joins authored anchors " + anchor + " and "
                        + (anchor + 1) % distinctCount + " once round the part";
                return false;
            }
            points += spanPath[SHEETS * anchor + chosen[anchor]].length - 1;
        }
        if (referenceVertexId.length < points) {
            referenceVertexId = new int[points];
        }
        if (cycleXyz.length < COORDINATES_PER_POINT * points) {
            cycleXyz = new float[COORDINATES_PER_POINT * points];
        }
        points = 0;
        for (int anchor = 0; anchor < distinctCount; anchor++) {
            int[] path = spanPath[SHEETS * anchor + chosen[anchor]];
            fit.authoredPoint[anchor] = points;
            for (int step = 0; step + 1 < path.length; step++) {
                referenceVertexId[points] = path[step];
                mesh.vertexPosition(path[step], position);
                cycleXyz[COORDINATES_PER_POINT * points] = position.x;
                cycleXyz[COORDINATES_PER_POINT * points + 1] = position.y;
                cycleXyz[COORDINATES_PER_POINT * points + 2] = position.z;
                points++;
            }
        }
        Arrays.fill(fit.authoredAllowance, 0.0);
        double inGroove = 0.0;
        double total = 0.0;
        for (int point = 0; point < points; point++) {
            int next = (point + 1) % points;
            double squared = 0.0;
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                double gap = cycleXyz[COORDINATES_PER_POINT * next + axis]
                        - cycleXyz[COORDINATES_PER_POINT * point + axis];
                squared += gap * gap;
            }
            double step = Math.sqrt(squared);
            total += step;
            inGroove += creases.valleyStrength[referenceVertexId[point]]
                    + creases.valleyStrength[referenceVertexId[next]]
                    >= 2.0 * IN_GROOVE_STRENGTH ? step : 0.0;
        }
        grooveFraction = total > 0.0 ? inGroove / total : 0.0;
        referencePoints = points;
        return fitAndTrace(mesh, cycleXyz, points, finalDepth);
    }

    /**
     * Trace the crease ring along the groove nearest a click: its one authored anchor is the
     * bottom of the nearest groove within {@link #GROOVE_REACH_OF_RADIUS} of the plane loop's
     * radius, and its plane the groove's own, normal to the across-groove line there.
     *
     * @param clickedVertexId mesh vertex under the click
     * @param baseNormal      normal of the plane girdling the part at the click, packed xyz
     * @param finalDepth      bisections the finished ring is traced to
     * @return true when a ring was traced that runs in a groove for at least
     *         {@link #MINIMUM_GROOVE_FRACTION} of its length; {@link #failure} says why not
     */
    public boolean traceGroove(int clickedVertexId, float[] baseNormal, int finalDepth) {
        mode = RingSegmentMode.CREASE;
        failure = "";
        authoredVertexId = new int[0];
        MeshTopology mesh = tracer.geodesics.mesh;
        if (creases == null || creases.sourceMesh != mesh) {
            failure = "a groove ring needs the creases of the surface it rings";
            return false;
        }
        int[] clicked = { clickedVertexId };
        fitPlane(mesh, clicked, 1, baseNormal);
        fit.authoredVertexId = clicked;
        fit.authoredPoint = new int[1];
        fit.authoredAllowance = new double[1];
        int loopPoints = planeLoopReference(mesh);
        if (loopPoints < 0) {
            return false;
        }
        double reach = GROOVE_REACH_OF_RADIUS * SplineAnchorFit.meanRadiusOf(cut.polyline,
                loopPoints);
        // Breadth-first over the vertices within reach of the click, keeping the groove vertex
        // nearest it.
        int bound = tracer.geodesics.metric.vertexIdBound;
        if (sheetVisit.length < SHEETS * bound) {
            sheetDistance = new double[SHEETS * bound];
            sheetParent = new int[SHEETS * bound];
            sheetVisit = new int[SHEETS * bound];
            visit = 0;
        }
        visit++;
        mesh.vertexPosition(clickedVertexId, clickPosition);
        int[] queue = new int[Math.min(bound, mesh.vertexCount())];
        int head = 0;
        int tail = 0;
        queue[tail++] = clickedVertexId;
        sheetVisit[SHEETS * clickedVertexId] = visit;
        int groove = -1;
        double grooveDistance = Double.POSITIVE_INFINITY;
        while (head < tail) {
            int vertexId = queue[head++];
            mesh.vertexPosition(vertexId, neighbour);
            double distance = neighbour.distance(clickPosition);
            if (creases.valleyStrength[vertexId] >= IN_GROOVE_STRENGTH
                    && distance < grooveDistance) {
                groove = vertexId;
                grooveDistance = distance;
            }
            for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertexId); spoke++) {
                int otherId = mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, spoke), vertexId);
                if (otherId < 0 || sheetVisit[SHEETS * otherId] == visit) {
                    continue;
                }
                sheetVisit[SHEETS * otherId] = visit;
                mesh.vertexPosition(otherId, neighbour);
                if (neighbour.distance(clickPosition) <= reach) {
                    queue[tail++] = otherId;
                }
            }
        }
        if (groove < 0) {
            failure = String.format(Locale.ROOT, "no groove within %.5f of the click", reach);
            return false;
        }
        // From the groove's edge nearest the click, up to the bottom of that groove.
        for (boolean climbing = true; climbing;) {
            climbing = false;
            for (int spoke = 0; spoke < mesh.vertexEdgeCount(groove) && !climbing; spoke++) {
                int otherId = mesh.edgeOtherVertex(mesh.vertexEdgeAt(groove, spoke), groove);
                if (otherId >= 0
                        && creases.valleyStrength[otherId] > creases.valleyStrength[groove]) {
                    groove = otherId;
                    climbing = true;
                }
            }
        }
        // The groove's own plane first, the girdling plane when that rings no groove; each normal
        // goes through the statement's text format, so a reload traces from the same numbers.
        float[][] normals = {
            SurfaceWaypoints.parse(SurfaceWaypoints.format(Arrays.copyOfRange(
                    creases.acrossGroove, COORDINATES_PER_POINT * groove,
                    COORDINATES_PER_POINT * (groove + 1)), 1)),
            baseNormal };
        String refusal = "";
        int[] anchor = { groove };
        for (float[] normal : normals) {
            if (trace(anchor, 1, normal, finalDepth)
                    && grooveFraction >= MINIMUM_GROOVE_FRACTION) {
                System.arraycopy(normal, 0, grooveNormal, 0, COORDINATES_PER_POINT);
                return true;
            }
            refusal = !failure.isEmpty() ? failure : refusal.isEmpty() ? String.format(Locale.ROOT,
                    "no closed groove: the cheapest ring round the part from the groove at the "
                            + "click runs in a groove for only %.0f%% of its length",
                    PERCENT * grooveFraction) : refusal;
        }
        failure = refusal;
        authoredVertexId = new int[0];
        return false;
    }

    /**
     * Lay the closed path of geodesics joining the authored anchors in the order given into the
     * cycle reference, each anchor at the start of its own span.
     *
     * @return the reference's point count, or -1 when no geodesic joins two neighbours
     */
    private int geodesicCycleReference(MeshTopology mesh) {
        SurfaceGeodesics geodesics = tracer.geodesics;
        int[] distinct = fit.authoredVertexId;
        int points = 0;
        Arrays.fill(fit.authoredAllowance, 0.0);
        for (int anchor = 0; anchor < distinct.length; anchor++) {
            fit.authoredPoint[anchor] = points;
            int next = (anchor + 1) % distinct.length;
            if (!geodesics.geodesic(distinct[anchor], distinct[next])) {
                failure = "no surface path joins authored anchors " + anchor + " and " + next;
                return -1;
            }
            int span = geodesics.tracedPointCount - 1;
            if (referenceVertexId.length < points + span) {
                referenceVertexId = Arrays.copyOf(referenceVertexId, 2 * (points + span));
            }
            if (cycleXyz.length < COORDINATES_PER_POINT * (points + span)) {
                cycleXyz = Arrays.copyOf(cycleXyz, 2 * COORDINATES_PER_POINT * (points + span));
            }
            for (int step = 0; step < span; step++) {
                int edgeId = geodesics.tracedEdgeId[step];
                int faceId = geodesics.tracedFaceId[step];
                int halfEdge = edgeId >= 0 ? mesh.edgeHalfEdge(edgeId) : -1;
                referenceVertexId[points] = geodesics.tracedVertexId[step] >= 0
                        ? geodesics.tracedVertexId[step]
                        : halfEdge < 0 ? mesh.faceVertexAt(faceId, 0)
                        : geodesics.tracedFraction[step] <= 0.5 ? mesh.halfEdgeVertex(halfEdge)
                        : mesh.halfEdgeEndVertex(halfEdge);
                for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                    cycleXyz[COORDINATES_PER_POINT * points + axis] =
                            (float) geodesics.tracedXyz[COORDINATES_PER_POINT * step + axis];
                }
                points++;
            }
        }
        // The path doubles back at an anchor when the points a short arc before and after it come
        // within half that arc over the surface: a path running straight through puts them twice
        // the arc apart, one that turned back on itself puts them together. The arc is a quarter
        // of the shorter neighbouring span, and the surface distance is what tells going round a
        // thin edge, straight on the surface, from turning back.
        cycleDoublesBack = false;
        referencePoints = points;
        for (int anchor = 0; anchor < distinct.length && !cycleDoublesBack; anchor++) {
            int at = fit.authoredPoint[anchor];
            int previousSpan = Math.floorMod(at
                    - fit.authoredPoint[Math.floorMod(anchor - 1, distinct.length)], points);
            int nextSpan = Math.floorMod(fit.authoredPoint[(anchor + 1) % distinct.length] - at,
                    points);
            double arc = Math.max(FOLD_ARC_EDGES * geodesics.metric.meanEdgeLength,
                    Math.min(arcLength(at, -1, previousSpan), arcLength(at, 1, nextSpan))
                            * FOLD_ARC_OF_SPAN);
            int before = at;
            int after = at;
            for (int step = 1; step < previousSpan && arcLength(at, -1, step) < arc; step++) {
                before = Math.floorMod(at - step - 1, points);
            }
            for (int step = 1; step < nextSpan && arcLength(at, 1, step) < arc; step++) {
                after = (at + step + 1) % points;
            }
            boolean reached = arcLength(at, -1, Math.floorMod(at - before, points)) >= arc
                    && arcLength(at, 1, Math.floorMod(after - at, points)) >= arc;
            cycleDoublesBack = reached
                    && (referenceVertexId[before] == referenceVertexId[after]
                            || geodesics.geodesic(referenceVertexId[before],
                                    referenceVertexId[after])
                                    && geodesics.pathLength < FOLD_GAP_OF_ARC * arc);
        }
        return points;
    }

    /** Length along the cycle reference from one point over a number of steps either way. */
    private double arcLength(int from, int direction, int steps) {
        double length = 0.0;
        for (int step = 0; step < steps; step++) {
            int here = COORDINATES_PER_POINT
                    * Math.floorMod(from + direction * step, referencePoints);
            int there = COORDINATES_PER_POINT
                    * Math.floorMod(from + direction * (step + 1), referencePoints);
            double squared = 0.0;
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                double gap = cycleXyz[there + axis] - cycleXyz[here + axis];
                squared += gap * gap;
            }
            length += Math.sqrt(squared);
        }
        return length;
    }

    /**
     * Walk the loop the fitted plane cuts from the first anchor's crossing into {@link #cut}, and
     * place each authored anchor at its nearest loop point.
     *
     * @return the loop's point count, or -1 when the plane cuts no closed loop
     */
    private int planeLoopReference(MeshTopology mesh) {
        int[] distinct = fit.authoredVertexId;
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
            return -1;
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
        for (int anchor = 0; anchor < distinct.length; anchor++) {
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
            fit.authoredAllowance[anchor] = SurfaceSpline.distanceToPolyline(cut.polyline,
                    points, position.x, position.y, position.z);
        }
        return points;
    }

    /**
     * Fit supporting anchors to a reference, re-trace at the final depth, read the authored
     * anchors back in ring order and test whether the ring is a simple curve.
     *
     * @return true when a spline was traced
     */
    private boolean fitAndTrace(MeshTopology mesh, float[] reference, int points,
            int finalDepth) {
        simple = false;
        authoredVertexId = new int[0];
        tracer.maximumDepth = SUPPORTING_FIT_DEPTH;
        if (!fit.fit(reference, points, referenceVertexId, fit.authoredPoint[0])) {
            failure = "the fit traced no spline on a " + points + "-point reference loop";
            return false;
        }
        if (finalDepth != SUPPORTING_FIT_DEPTH) {
            tracer.maximumDepth = finalDepth;
            tracer.retraceAll();
        }
        int authored = fit.authoredVertexId.length;
        int[] placed = new int[authored];
        int held = 0;
        for (int anchor = 0; anchor < tracer.anchorCount; anchor++) {
            if (tracer.anchorAuthored[anchor] && held < authored) {
                placed[held++] = tracer.anchorVertexId[anchor];
            }
        }
        authoredVertexId = Arrays.copyOf(placed, held);
        simple = crossings.isSimple(mesh, tracer.tracedRing());
        return true;
    }

    /**
     * Add an authored anchor to the last traced ring where it adds the least surface length (see
     * {@link #slotsByAddedLength}), the held anchors keeping their order; a slot that makes a
     * simple ring cross itself is passed over for the next cheapest.
     *
     * @param vertexId   mesh vertex of the new anchor
     * @param baseNormal the ring's base normal, as {@link #trace} takes it
     * @param finalDepth bisections the finished ring is traced to
     * @return true when the anchor was placed; otherwise the ring is traced back as it was and
     *         {@link #failure} says why
     */
    public boolean insert(int vertexId, float[] baseNormal, int finalDepth) {
        int[] before = authoredVertexId;
        boolean wasSimple = simple;
        int[] slots = before.length < PLANE_STARTED_ANCHORS ? new int[] { before.length - 1 }
                : slotsByAddedLength(vertexId);
        String refusal = "";
        for (int slot : slots) {
            int[] grown = new int[before.length + 1];
            System.arraycopy(before, 0, grown, 0, slot + 1);
            grown[slot + 1] = vertexId;
            System.arraycopy(before, slot + 1, grown, slot + 2, before.length - slot - 1);
            if (trace(grown, grown.length, baseNormal, finalDepth) && (simple || !wasSimple)) {
                return true;
            }
            if (refusal.isEmpty()) {
                refusal = !failure.isEmpty() ? failure
                        : String.format(Locale.ROOT, "the ring would cross itself near %.4f,%.4f,%.4f",
                                crossings.firstCrossingXyz[0], crossings.firstCrossingXyz[1],
                                crossings.firstCrossingXyz[2]);
            }
        }
        trace(before, before.length, baseNormal, finalDepth);
        failure = (slots.length > 1 ? "no place for the anchor keeps the ring simple: " : "")
                + refusal;
        return false;
    }

    /**
     * The slots a new anchor can take in the last traced ring, cheapest first by the surface
     * length it adds: the detour out to it from the nearest point of each span, which is
     * cheapest insertion into the ring's own traced path.
     *
     * @param vertexId mesh vertex of the new anchor
     * @return slot indices, slot {@code s} putting the anchor after {@code authoredVertexId[s]};
     *         a span the search never reached sorts last
     */
    public int[] slotsByAddedLength(int vertexId) {
        SurfaceGeodesics geodesics = tracer.geodesics;
        MeshTopology mesh = geodesics.mesh;
        int count = authoredVertexId.length;
        if (spanLabel.length < geodesics.metric.vertexIdBound) {
            spanLabel = new int[geodesics.metric.vertexIdBound];
            Arrays.fill(spanLabel, -1);
        }
        // Label every vertex the ring's path runs through or beside with the span it lies on,
        // from just past one authored anchor to the next; the anchors themselves stay unlabelled
        // so a click beside one is placed by the side it is on.
        int[] labelled = new int[0];
        int labels = 0;
        int span = -1;
        for (int segment = 0; segment < tracer.anchorCount; segment++) {
            span += tracer.anchorAuthored[segment] ? 1 : 0;
            int[] vertices = tracer.segmentVertexId[segment];
            int[] edges = tracer.segmentEdgeId[segment];
            for (int point = tracer.anchorAuthored[segment] ? 1 : 0; span >= 0
                    && point < vertices.length; point++) {
                int halfEdge = edges[point] >= 0 ? mesh.edgeHalfEdge(edges[point]) : -1;
                int[] ends = vertices[point] >= 0 ? new int[] { vertices[point] }
                        : halfEdge >= 0 ? new int[] { mesh.halfEdgeVertex(halfEdge),
                            mesh.halfEdgeEndVertex(halfEdge) } : new int[0];
                for (int end : ends) {
                    if (labels == labelled.length) {
                        labelled = Arrays.copyOf(labelled, Math.max(64, 2 * labels));
                    }
                    labelled[labels++] = end;
                    spanLabel[end] = span;
                }
            }
        }
        for (int anchor : authoredVertexId) {
            spanLabel[anchor] = -1;
        }
        double[] distance = geodesics.distanceToLabels(vertexId, spanLabel, count);
        for (int label = 0; label < labels; label++) {
            spanLabel[labelled[label]] = -1;
        }
        addedLength = new double[count];
        Integer[] slots = new Integer[count];
        for (int slot = 0; slot < count; slot++) {
            addedLength[slot] = 2.0 * distance[slot];
            slots[slot] = slot;
        }
        Arrays.sort(slots, (first, second) -> Double.compare(addedLength[first],
                addedLength[second]));
        int[] ordered = new int[count];
        for (int slot = 0; slot < count; slot++) {
            ordered[slot] = slots[slot];
        }
        return ordered;
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
