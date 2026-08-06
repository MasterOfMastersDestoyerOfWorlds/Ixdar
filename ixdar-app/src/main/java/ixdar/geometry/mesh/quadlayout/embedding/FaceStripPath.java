package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * One arc's traced route refined onto the constraint mesh. No face of that mesh holds a
 * vertex inside it, so a chord across a passage cannot leave it.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class FaceStripPath {

    /** Corners (and edges) of a triangle. */
    public static final int CORNERS = 3;

    public final EmbeddedMeshTopology topology;

    /** Arc the route belongs to. */
    public final int arcId;

    /** Constraint face of each passage, in travel order. */
    public final List<Integer> passageFaces = new ArrayList<>();

    /** Source active face each passage lies in, which is its exact barycentric frame. */
    public final List<Integer> passageSourceFaces = new ArrayList<>();

    /**
     * Constraint edge crossed between consecutive passages, as an endpoint vertex pair, or
     * {@code null} where the route runs exactly through a vertex instead.
     */
    public final List<int[]> crossedEdges = new ArrayList<>();

    /**
     * Constraint vertex the route runs exactly through at each crossing, or
     * {@link EmbeddedMeshTopology#UNCLAIMED} where it crosses an edge. Such a crossing needs no
     * lane: the vertex it wants is already there.
     */
    public final List<Integer> crossedVertices = new ArrayList<>();

    /** Traced position along each crossed edge, measured from its first recorded endpoint. */
    public final List<Double> crossingParameters = new ArrayList<>();

    /**
     * Stores the arc a route is being refined for.
     *
     * @param topology working copy, standing as the constraint mesh
     * @param arcId    arc the route belongs to
     */
    public FaceStripPath(EmbeddedMeshTopology topology, int arcId) {
        this.topology = topology;
        this.arcId = arcId;
    }

    /**
     * Refines one passage across a source face into the constraint faces it crosses, recording
     * each crossed edge. A passage starting where the last ended continues it, so a bend inside
     * one face records nothing.
     *
     * @param sourceFace source active face the passage runs in
     * @param from       barycentric the passage enters at
     * @param to         barycentric the passage leaves at
     * @throws IllegalStateException when the passage leaves the source face or runs longer
     *                               than the face has children
     */
    public void addPassage(int sourceFace, double[] from, double[] to) {
        int face = enteredChild(sourceFace, from, to);
        int entryFirst = EmbeddedMeshTopology.UNCLAIMED;
        int entrySecond = EmbeddedMeshTopology.UNCLAIMED;
        if (passageFaces.isEmpty() || passageFaces.get(passageFaces.size() - 1) != face) {
            if (!passageFaces.isEmpty()) {
                recordEntry(sourceFace, passageFaces.get(passageFaces.size() - 1), face, from);
            }
            append(face, sourceFace);
        }
        int bound = topology.copyFacesBySourceFace.get(sourceFace).size();
        for (int step = 0; step <= bound; step++) {
            if (holdsClosed(sourceFace, face, to)) {
                return;
            }
            int through = cornerOnSegment(sourceFace, face, from, to, entryFirst);
            if (through != EmbeddedMeshTopology.UNCLAIMED) {
                crossedEdges.add(null);
                crossedVertices.add(through);
                crossingParameters.add(0.0);
                entryFirst = through;
                entrySecond = EmbeddedMeshTopology.UNCLAIMED;
                face = enteredChild(sourceFace, topology.barycentricOf(sourceFace, through), to);
                append(face, sourceFace);
                continue;
            }
            int exit = exitCorner(sourceFace, face, from, to, entryFirst, entrySecond);
            entryFirst = topology.copy.faceVertexAt(face, exit);
            entrySecond = topology.copy.faceVertexAt(face, (exit + 1) % CORNERS);
            recordCrossing(sourceFace, entryFirst, entrySecond, from, to);
            face = across(face, entryFirst, entrySecond, sourceFace);
            append(face, sourceFace);
        }
        throw new IllegalStateException("the passage of arc " + arcId + " across source face "
                + sourceFace + " crossed more of its children than it has");
    }

    /**
     * The corner the arc would rather pass through at one crossing: the vertex the next
     * crossed edge shares with this one.
     *
     * @param crossing index into {@link #crossedEdges}
     * @return that corner's copy vertex, or {@link EmbeddedMeshTopology#UNCLAIMED} at the
     *         last crossing, where there is no next edge to share one with
     */
    public int sharedCornerAt(int crossing) {
        if (crossing + 1 >= crossedEdges.size()) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        int[] here = crossedEdges.get(crossing);
        int[] next = crossedEdges.get(crossing + 1);
        if (here == null || next == null) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        for (int end : here) {
            if (end == next[0] || end == next[1]) {
                return end;
            }
        }
        return EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * Drops the route's first crossing along with the passage before it, for a crossing on an
     * edge the route's own start node already sits on.
     *
     * @throws IllegalStateException when there is no crossing to drop
     */
    public void removeFirstCrossing() {
        requireCrossingToRemove();
        crossedEdges.remove(0);
        crossedVertices.remove(0);
        crossingParameters.remove(0);
        passageFaces.remove(0);
        passageSourceFaces.remove(0);
    }

    /**
     * Drops the route's last crossing along with the passage after it, for a crossing on an
     * edge the route's own end node already sits on.
     *
     * @throws IllegalStateException when there is no crossing to drop
     */
    public void removeLastCrossing() {
        requireCrossingToRemove();
        crossedEdges.remove(crossedEdges.size() - 1);
        crossedVertices.remove(crossedVertices.size() - 1);
        crossingParameters.remove(crossingParameters.size() - 1);
        passageFaces.remove(passageFaces.size() - 1);
        passageSourceFaces.remove(passageSourceFaces.size() - 1);
    }

    /**
     * Checks the route still has a crossing, so dropping one keeps a passage behind.
     *
     * @throws IllegalStateException when the route has no crossing
     */
    private void requireCrossingToRemove() {
        if (crossedEdges.isEmpty()) {
            throw new IllegalStateException("arc " + arcId + " has no crossing to drop");
        }
    }

    /**
     * Whether one crossing sits on an edge that already has a vertex as an endpoint, or runs
     * exactly through it.
     *
     * @param crossing index into {@link #crossedEdges}
     * @param vertexId copy vertex to test against
     * @return true when the crossing touches that vertex
     */
    public boolean crossingTouches(int crossing, int vertexId) {
        int[] edge = crossedEdges.get(crossing);
        if (edge == null) {
            return crossedVertices.get(crossing) == vertexId;
        }
        return edge[0] == vertexId || edge[1] == vertexId;
    }

    /**
     * Records one passage, so that {@link #passageFaces} always runs one longer than
     * {@link #crossedEdges} and a crossing's index is also its passage's.
     *
     * @param face       constraint face the passage crosses
     * @param sourceFace source active face it lies in
     */
    private void append(int face, int sourceFace) {
        passageFaces.add(face);
        passageSourceFaces.add(sourceFace);
    }

    /**
     * Records the crossing where the route leaves one source face for the next. The two
     * passages either side of it meet on a constraint edge lying along the source edge
     * between them, and the traced entry point is already on that edge.
     *
     * @param sourceFace source active face being entered
     * @param leaving    constraint face the route is leaving
     * @param entering   constraint face it is entering
     * @param at         barycentric of the traced crossing, in {@code sourceFace}
     * @throws IllegalStateException when the two faces share no edge
     */
    private void recordEntry(int sourceFace, int leaving, int entering, double[] at) {
        for (int corner = 0; corner < CORNERS; corner++) {
            int first = topology.copy.faceVertexAt(entering, corner);
            int second = topology.copy.faceVertexAt(entering, (corner + 1) % CORNERS);
            if (!holdsCorner(leaving, first) || !holdsCorner(leaving, second)) {
                continue;
            }
            int low = Math.min(first, second);
            int high = Math.max(first, second);
            crossedEdges.add(new int[] { low, high });
            crossedVertices.add(EmbeddedMeshTopology.UNCLAIMED);
            crossingParameters.add(alongEdge(sourceFace, low, high, at));
            return;
        }
        for (int corner = 0; corner < CORNERS; corner++) {
            int shared = topology.copy.faceVertexAt(entering, corner);
            if (holdsCorner(leaving, shared)) {
                crossedEdges.add(null);
                crossedVertices.add(shared);
                crossingParameters.add(0.0);
                return;
            }
        }
        throw new IllegalStateException("arc " + arcId + " passes from constraint face "
                + leaving + " to " + entering + ", which share neither an edge nor a corner");
    }

    /**
     * Whether a constraint face has a copy vertex as one of its corners.
     *
     * @param face     constraint face to search
     * @param vertexId copy vertex to find
     * @return true when it is a corner of that face
     */
    private boolean holdsCorner(int face, int vertexId) {
        for (int corner = 0; corner < CORNERS; corner++) {
            if (topology.copy.faceVertexAt(face, corner) == vertexId) {
                return true;
            }
        }
        return false;
    }

    /**
     * How far along an edge a point on it sits, read off whichever barycentric component
     * separates the two endpoints.
     *
     * @param sourceFace source active face the coordinates are in
     * @param low        endpoint the position is measured from
     * @param high       endpoint it is measured to
     * @param at         barycentric of the point, which lies on that edge
     * @throws IllegalStateException when the endpoints share every component
     * @return the position in {@code [0, 1]}
     */
    private double alongEdge(int sourceFace, int low, int high, double[] at) {
        double[] fromLow = topology.barycentricOf(sourceFace, low);
        double[] fromHigh = topology.barycentricOf(sourceFace, high);
        int widest = EmbeddedMeshTopology.UNCLAIMED;
        for (int index = 0; index < CORNERS; index++) {
            if (widest == EmbeddedMeshTopology.UNCLAIMED
                    || Math.abs(fromHigh[index] - fromLow[index])
                            > Math.abs(fromHigh[widest] - fromLow[widest])) {
                widest = index;
            }
        }
        if (fromHigh[widest] == fromLow[widest]) {
            throw new IllegalStateException("copy vertices " + low + " and " + high
                    + " share every barycentric in source face " + sourceFace);
        }
        return (at[widest] - fromLow[widest]) / (fromHigh[widest] - fromLow[widest]);
    }

    /**
     * The constraint face a passage starts in: the one holding its entry point whose
     * interior the passage then runs into.
     *
     * @param sourceFace source active face the passage runs in
     * @param from       barycentric the passage enters at
     * @param to         barycentric the passage leaves at
     * @throws IllegalStateException when no child holds the entry point on the way in
     * @return that child face
     */
    private int enteredChild(int sourceFace, double[] from, double[] to) {
        for (int face : topology.copyFacesBySourceFace.get(sourceFace)) {
            if (holdsClosed(sourceFace, face, from) && opensToward(sourceFace, face, from, to)) {
                return face;
            }
        }
        throw new IllegalStateException("no child of source face " + sourceFace + " holds the"
                + " entry point of arc " + arcId + "'s passage on the way in");
    }

    /**
     * Whether a constraint face holds a point, its edges included.
     *
     * @param sourceFace  source active face the coordinates are in
     * @param face        constraint face to test
     * @param barycentric the point
     * @return true when the point is inside it or on its boundary
     */
    private boolean holdsClosed(int sourceFace, int face, double[] barycentric) {
        for (int corner = 0; corner < CORNERS; corner++) {
            if (ExactBarycentricOrient.sign(cornerOf(sourceFace, face, corner),
                    cornerOf(sourceFace, face, (corner + 1) % CORNERS), barycentric) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a face the entry point sits on the boundary of is the one the passage runs
     * into. A target lying on an edge through the entry point is accepted: the passage then
     * runs along that edge, which bounds this face as much as the next.
     *
     * @param sourceFace source active face the coordinates are in
     * @param face       constraint face to test
     * @param from       barycentric the passage enters at
     * @param to         barycentric the passage leaves at
     * @return true when the passage runs into this face
     */
    private boolean opensToward(int sourceFace, int face, double[] from, double[] to) {
        for (int corner = 0; corner < CORNERS; corner++) {
            double[] first = cornerOf(sourceFace, face, corner);
            double[] second = cornerOf(sourceFace, face, (corner + 1) % CORNERS);
            if (ExactBarycentricOrient.sign(first, second, from) == 0
                    && ExactBarycentricOrient.sign(first, second, to) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * A corner of a constraint face the route runs exactly through, strictly between where it
     * entered and where it is headed. It leaves the face through a vertex, not an edge.
     *
     * @param sourceFace source active face the coordinates are in
     * @param face       constraint face being crossed
     * @param from       barycentric the passage enters at
     * @param to         barycentric the passage leaves at
     * @param entered    corner the route already came through, excluded, or
     *                   {@link EmbeddedMeshTopology#UNCLAIMED}
     * @return that corner's copy vertex, or {@link EmbeddedMeshTopology#UNCLAIMED} for none
     */
    private int cornerOnSegment(int sourceFace, int face, double[] from, double[] to,
            int entered) {
        for (int corner = 0; corner < CORNERS; corner++) {
            int vertexId = topology.copy.faceVertexAt(face, corner);
            double[] at = topology.barycentricOf(sourceFace, vertexId);
            if (vertexId == entered || at == null
                    || ExactBarycentricOrient.sign(from, to, at) != 0
                    || !strictlyBetween(from, at, to)) {
                continue;
            }
            return vertexId;
        }
        return EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * Whether a point collinear with two others lies strictly between them.
     *
     * @param from   segment start barycentric
     * @param middle point tested
     * @param to     segment end barycentric
     * @return true when it is strictly inside the open segment
     */
    private static boolean strictlyBetween(double[] from, double[] middle, double[] to) {
        double fromSide = 0.0;
        double toSide = 0.0;
        for (int index = 0; index < CORNERS; index++) {
            fromSide += (middle[index] - from[index]) * (to[index] - from[index]);
            toSide += (middle[index] - to[index]) * (from[index] - to[index]);
        }
        return fromSide > 0.0 && toSide > 0.0;
    }

    /**
     * The local edge a passage leaves a constraint face through: the one whose endpoints
     * the traced segment separates, other than the edge it came in on.
     *
     * @param sourceFace  source active face the coordinates are in
     * @param face        constraint face being crossed
     * @param from        barycentric the passage enters at
     * @param to          barycentric the passage leaves at
     * @param entryFirst  first endpoint of the edge it entered through
     * @param entrySecond second endpoint of that edge
     * @throws IllegalStateException when no edge carries the passage onward
     * @return the local index of the leaving edge
     */
    private int exitCorner(int sourceFace, int face, double[] from, double[] to, int entryFirst,
            int entrySecond) {
        for (int corner = 0; corner < CORNERS; corner++) {
            int first = topology.copy.faceVertexAt(face, corner);
            int second = topology.copy.faceVertexAt(face, (corner + 1) % CORNERS);
            if (first == entryFirst && second == entrySecond
                    || first == entrySecond && second == entryFirst) {
                continue;
            }
            int firstSide = ExactBarycentricOrient.sign(from, to,
                    topology.barycentricOf(sourceFace, first));
            int secondSide = ExactBarycentricOrient.sign(from, to,
                    topology.barycentricOf(sourceFace, second));
            if (firstSide * secondSide < 0 && ExactBarycentricOrient.sign(
                    cornerOf(sourceFace, face, corner),
                    cornerOf(sourceFace, face, (corner + 1) % CORNERS), to) < 0) {
                return corner;
            }
        }
        throw new IllegalStateException("arc " + arcId + " enters constraint face " + face
                + " of source face " + sourceFace + " and no edge carries it onward");
    }

    /**
     * Records a crossed constraint edge and where the traced segment met it, in a fixed
     * endpoint order so both faces sharing the edge agree on the position.
     *
     * @param sourceFace source active face the coordinates are in
     * @param first      one endpoint of the crossed edge
     * @param second     the other endpoint
     * @param from       barycentric the passage enters at
     * @param to         barycentric the passage leaves at
     */
    private void recordCrossing(int sourceFace, int first, int second, double[] from,
            double[] to) {
        int low = Math.min(first, second);
        int high = Math.max(first, second);
        double atLow = ExactBarycentricOrient.area(from, to, topology.barycentricOf(sourceFace,
                low));
        double atHigh = ExactBarycentricOrient.area(from, to, topology.barycentricOf(sourceFace,
                high));
        crossedEdges.add(new int[] { low, high });
        crossedVertices.add(EmbeddedMeshTopology.UNCLAIMED);
        crossingParameters.add(atLow / (atLow - atHigh));
    }

    /**
     * The constraint face across one of a face's edges.
     *
     * @param face       constraint face being left
     * @param first      one endpoint of the crossed edge
     * @param second     the other endpoint
     * @param sourceFace source active face the passage runs in
     * @throws IllegalStateException when the passage would leave the source face
     * @return the constraint face on the far side
     */
    private int across(int face, int first, int second, int sourceFace) {
        int edgeId = topology.edgeBetween(first, second);
        int halfEdge = topology.copy.edgeHalfEdge(edgeId);
        int nearSide = topology.copy.halfEdgeFace(halfEdge);
        int farSide = nearSide == face
                ? topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge))
                : nearSide;
        if (farSide == EmbeddedMeshTopology.UNCLAIMED
                || topology.sourceFaceByCopyFace[farSide] != sourceFace) {
            throw new IllegalStateException("arc " + arcId + " leaves source face " + sourceFace
                    + " across copy edge " + edgeId + " in the middle of a passage");
        }
        return farSide;
    }

    /**
     * One corner of a constraint face, in its source face's frame.
     *
     * @param sourceFace source active face the coordinates are in
     * @param face       constraint face
     * @param corner     local corner index
     * @return that corner's barycentric
     */
    private double[] cornerOf(int sourceFace, int face, int corner) {
        return topology.barycentricOf(sourceFace, topology.copy.faceVertexAt(face, corner));
    }
}
