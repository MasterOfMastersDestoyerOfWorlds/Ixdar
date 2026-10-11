package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;
import java.util.Locale;

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

    public static final int SHEETS = 2;

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
    private int[] sheetBranch = new int[0];
    private int visit;
    private final Vector3f neighbour = new Vector3f();

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
            sheetBranch = new int[states];
            visit = 0;
        }
        double[] spanCost = new double[SHEETS * distinctCount];
        int[][] spanPath = new int[SHEETS * distinctCount][];
        // The search runs on the geodesic engine's frontier heap, between its own searches.
        SurfaceGeodesics frontier = tracer.geodesics;
        // One anchor's span closes on itself, and swapping the sheets maps the double cover onto
        // itself, so the search from the anchor's other sheet is this one's mirror image. The ring
        // is found where this search meets its own mirror, from both ends at once: it stops at
        // half the ring's cost, the meeting edge leading from closingFrom to the mirror of
        // closingTo.
        boolean closing = distinctCount == 1;
        double closingCost = Double.POSITIVE_INFINITY;
        int closingFrom = -1;
        int closingTo = -1;
        // States are dense metric vertex indices, two per vertex, walked over the metric's flat
        // half-edge arrays rather than the mesh's per-vertex lists.
        SurfaceMetric metric = frontier.metric;
        for (int anchor = 0; anchor < distinctCount; anchor++) {
            int source = SHEETS * metric.vertexIndexByVertexId[distinct[anchor]];
            int target = SHEETS
                    * metric.vertexIndexByVertexId[distinct[(anchor + 1) % distinctCount]];
            visit++;
            frontier.heapSize = 0;
            sheetVisit[source] = visit;
            sheetDistance[source] = 0.0;
            sheetParent[source] = -1;
            frontier.heapPush(0.0, source);
            int settledTargets = 0;
            while (frontier.heapSize > 0 && settledTargets < SHEETS
                    && !(closing && 2.0 * frontier.heapKey[0] >= closingCost)) {
                double key = frontier.heapKey[0];
                int state = frontier.heapPop();
                if (key > sheetDistance[state]) {
                    continue;
                }
                settledTargets += state == target || state == target + 1 ? 1 : 0;
                int activeVertex = state / SHEETS;
                int sheet = state % SHEETS;
                mesh.vertexPosition(metric.sourceVertexId[activeVertex], position);
                double hereAlong = (position.x - centre[0]) * seam[0]
                        + (position.y - centre[1]) * seam[1] + (position.z - centre[2]) * seam[2];
                double hereAcross = (position.x - centre[0]) * across[0]
                        + (position.y - centre[1]) * across[1]
                        + (position.z - centre[2]) * across[2];
                // Counter-clockwise round the fan from the reference half-edge, which a boundary
                // vertex's fan starts at, so its last spoke is the exterior one.
                int reference = metric.vertexReferenceHalfEdge[activeVertex];
                int halfEdge = reference;
                while (halfEdge >= 0) {
                    int spoke = halfEdge;
                    halfEdge = metric.halfEdgeNext[spoke] < 0 ? -1
                            : metric.halfEdgeNext[metric.halfEdgeNext[spoke]] ^ 1;
                    halfEdge = halfEdge == reference ? -1 : halfEdge;
                    int edgeId = metric.sourceEdgeId[spoke >> 1];
                    if (edgeId == MeshTopology.NONE) {
                        continue;
                    }
                    int otherActive = metric.halfEdgeTail[spoke ^ 1];
                    mesh.vertexPosition(metric.sourceVertexId[otherActive], neighbour);
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
                    int next = SHEETS * otherActive + (crosses ? 1 - sheet : sheet);
                    double relaxed = sheetDistance[state] + creases.edgeCost[edgeId];
                    int mirror = next + 1 - 2 * (next % SHEETS);
                    // Both halves of a closing ring come out of this one search tree, so they
                    // must leave the anchor by different first vertices: halves sharing a branch
                    // run out and back along a common stem, a spur that crosses itself.
                    int outwardFirst = state == source ? otherActive
                            : sheetBranch[state] / SHEETS;
                    int inwardFirst = mirror == source ? activeVertex
                            : sheetBranch[mirror] / SHEETS;
                    if (closing && sheetVisit[mirror] == visit && outwardFirst != inwardFirst
                            && relaxed + sheetDistance[mirror] < closingCost) {
                        closingCost = relaxed + sheetDistance[mirror];
                        closingFrom = state;
                        closingTo = mirror;
                    }
                    if (sheetVisit[next] != visit || relaxed < sheetDistance[next]) {
                        sheetVisit[next] = visit;
                        sheetDistance[next] = relaxed;
                        sheetParent[next] = state;
                        sheetBranch[next] = state == source ? next : sheetBranch[state];
                        frontier.heapPush(relaxed, next);
                    }
                }
            }
            for (int sheet = 0; sheet < SHEETS; sheet++) {
                // A closing ring runs out along the tree to closingFrom, then back down the
                // mirror of closingTo's branch to the anchor.
                boolean met = closing && sheet == 1;
                int end = met ? closingFrom : target + sheet;
                boolean reached = met ? closingFrom >= 0 : sheetVisit[end] == visit;
                spanCost[SHEETS * anchor + sheet] = !reached ? Double.POSITIVE_INFINITY
                        : met ? closingCost : sheetDistance[end];
                int outward = 0;
                for (int state = end; reached && state >= 0; state = sheetParent[state]) {
                    outward++;
                }
                int inward = 0;
                for (int state = met ? closingTo : -1; state >= 0; state = sheetParent[state]) {
                    inward++;
                }
                int[] path = new int[outward + inward];
                int slot = outward;
                for (int state = end; reached && state >= 0; state = sheetParent[state]) {
                    path[--slot] = metric.sourceVertexId[state / SHEETS];
                }
                slot = outward;
                for (int state = met ? closingTo : -1; state >= 0; state = sheetParent[state]) {
                    path[slot++] = metric.sourceVertexId[state / SHEETS];
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
        // The paths lie on two sheets, so the walk may come back to a vertex it passed on the
        // other sheet; the loop between the two visits is erased as the walk is laid down, unless
        // an authored anchor lies on it. The searches are done, so each vertex's first sheet
        // state holds its point in the walk under a fresh stamp.
        visit++;
        points = 0;
        int lastAnchorPoint = 0;
        for (int anchor = 0; anchor < distinctCount; anchor++) {
            int[] path = spanPath[SHEETS * anchor + chosen[anchor]];
            fit.authoredPoint[anchor] = points;
            lastAnchorPoint = points;
            for (int step = 0; step + 1 < path.length; step++) {
                int state = SHEETS * metric.vertexIndexByVertexId[path[step]];
                int earlier = sheetVisit[state] == visit ? sheetParent[state] : -1;
                if (step > 0 && earlier >= lastAnchorPoint && earlier < points
                        && referenceVertexId[earlier] == path[step]) {
                    points = earlier + 1;
                    continue;
                }
                sheetVisit[state] = visit;
                sheetParent[state] = points;
                referenceVertexId[points] = path[step];
                mesh.vertexPosition(path[step], position);
                cycleXyz[COORDINATES_PER_POINT * points] = position.x;
                cycleXyz[COORDINATES_PER_POINT * points + 1] = position.y;
                cycleXyz[COORDINATES_PER_POINT * points + 2] = position.z;
                points++;
            }
        }
        Arrays.fill(fit.authoredAllowance, 0.0);
        referencePoints = points;
        return fitAndTrace(mesh, cycleXyz, points, finalDepth);
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
