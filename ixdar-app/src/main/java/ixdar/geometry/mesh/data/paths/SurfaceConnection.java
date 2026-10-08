package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * How two faces of a surface are joined: their shortest path and the neck it passes, its narrowest
 * cross-section between them, snapped to mesh edges so it can be confirmed as a ring.
 */
public final class SurfaceConnection {

    public static final int GIRTH_SAMPLES = 48;

    public static final int COORDINATES_PER_POINT = 3;

    public static final double NANOS_PER_MILLI = 1e6;

    public static final double NECK_WIDENING = 0.1;

    /** Surface the connection runs over. */
    public final MeshTopology mesh;

    /** Faces of {@link #mesh}, the graph's nodes, numbered by active face index. */
    public final int faceCount;

    /** Active face index of each face id, -1 for a dead id. */
    public final int[] activeFaceByFaceId;

    /** First arc of each face plus a closing total; a face has one arc per interior edge. */
    public final int[] arcStart;

    /** Face each arc leads to. */
    public final int[] arcFace;

    /** Mesh edge each arc crosses. */
    public final int[] arcEdgeId;

    /** The arc crossing the same edge the other way. */
    public final int[] arcReverse;

    /** Surface length of each arc: face centroid to the crossed edge's midpoint to centroid. */
    public final double[] arcLength;

    /** Centroid of each face, packed xyz. */
    public final float[] centroidXyz;

    /** Edges no path and no cross-section may cross, by edge id; empty for none. */
    public boolean[] wallByEdgeId = new boolean[0];

    /** Faces repair_mesh added, by active face index; empty when unknown. */
    public boolean[] filledByActiveFace = new boolean[0];

    /** Whether the last connect found the two faces joined. */
    public boolean connected;

    /** Why the last connect found no connection or no cross-section, or empty. */
    public String failure = "";

    /** Surface distance of every face from the start face, infinite where unreachable. */
    public double[] distanceFromStart = new double[0];

    /** Faces of the shortest path, start to end, by active face index. */
    public int[] pathActiveFace = new int[0];

    /** The path through face centroids and crossed-edge midpoints, packed xyz. */
    public float[] pathPolyline = new float[0];

    /** Surface length of the path. */
    public double pathLength;

    /** Path faces repair_mesh added. */
    public int pathFilledFaceCount;

    /**
     * Girth of the neck: the narrowest cross-section the path crosses that the path widens away
     * from on both sides, so never one at a pick. Infinite when there is none.
     */
    public double narrowestGirth = Double.POSITIVE_INFINITY;

    /** Girth of the widest cross-section sampled, which puts the neck in proportion. */
    public double widestGirth;

    /** Position along {@link #pathActiveFace} of the neck, or -1. */
    public int narrowestPathIndex = -1;

    /** Where the neck crosses the path, packed xyz. */
    public final float[] narrowestPoint = new float[COORDINATES_PER_POINT];

    /** The neck snapped to the mesh edges nearest it, by edge id. */
    public boolean[] narrowestMarkedByEdgeId = new boolean[0];

    /** Cross-sections sampled along the path that crossed a wall and were passed over. */
    public int girthsCrossingWalls;

    /** Milliseconds the distance field and the path took. */
    public double pathMillis;

    /** Milliseconds the cross-section sampling and the snap took. */
    public double girthMillis;

    private final GirdlingPlane girdle = new GirdlingPlane();
    private final ConformingLoopSnap snap = new ConformingLoopSnap();
    private final Vector3f scratch = new Vector3f();
    private final int[] loopFaceStamp;
    private int loopStamp;
    private int[] arcTowardStart = new int[0];
    private double[] frontierKey = new double[0];
    private int[] frontierFace = new int[0];
    private int frontierSize;

    /**
     * Build the face graph of a surface once; every connect reuses it.
     *
     * @param mesh the surface, every face joined to its neighbours across interior edges
     */
    public SurfaceConnection(MeshTopology mesh) {
        this.mesh = mesh;
        faceCount = mesh.faceCount();
        int faceBound = 0;
        int edgeBound = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            faceBound = Math.max(faceBound, mesh.faceIdAt(activeFace) + 1);
        }
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            edgeBound = Math.max(edgeBound, mesh.edgeIdAt(activeEdge) + 1);
        }
        activeFaceByFaceId = new int[faceBound];
        Arrays.fill(activeFaceByFaceId, -1);
        centroidXyz = new float[COORDINATES_PER_POINT * faceCount];
        loopFaceStamp = new int[faceCount];
        arcStart = new int[faceCount + 1];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            activeFaceByFaceId[faceId] = activeFace;
            int corners = mesh.faceVertexCount(faceId);
            for (int corner = 0; corner < corners; corner++) {
                mesh.vertexPosition(mesh.faceVertexAt(faceId, corner), scratch);
                centroidXyz[COORDINATES_PER_POINT * activeFace] += scratch.x / corners;
                centroidXyz[COORDINATES_PER_POINT * activeFace + 1] += scratch.y / corners;
                centroidXyz[COORDINATES_PER_POINT * activeFace + 2] += scratch.z / corners;
            }
            int interior = 0;
            for (int side = 0; side < mesh.faceEdgeCount(faceId); side++) {
                interior += faceAcross(mesh.faceEdgeAt(faceId, side), faceId) >= 0 ? 1 : 0;
            }
            arcStart[activeFace + 1] = arcStart[activeFace] + interior;
        }
        int arcs = arcStart[faceCount];
        arcFace = new int[arcs];
        arcEdgeId = new int[arcs];
        arcReverse = new int[arcs];
        arcLength = new double[arcs];
        int[] firstArcByEdgeId = new int[edgeBound];
        Arrays.fill(firstArcByEdgeId, -1);
        Vector3f midpoint = new Vector3f();
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int arc = arcStart[activeFace];
            for (int side = 0; side < mesh.faceEdgeCount(faceId); side++) {
                int edgeId = mesh.faceEdgeAt(faceId, side);
                int otherFaceId = faceAcross(edgeId, faceId);
                if (otherFaceId < 0) {
                    continue;
                }
                int other = activeFaceByFaceId[otherFaceId];
                mesh.edgeMidpoint(edgeId, midpoint);
                arcFace[arc] = other;
                arcEdgeId[arc] = edgeId;
                arcLength[arc] = centroidDistance(activeFace, midpoint)
                        + centroidDistance(other, midpoint);
                if (firstArcByEdgeId[edgeId] < 0) {
                    firstArcByEdgeId[edgeId] = arc;
                } else {
                    arcReverse[arc] = firstArcByEdgeId[edgeId];
                    arcReverse[firstArcByEdgeId[edgeId]] = arc;
                }
                arc++;
            }
        }
    }

    /**
     * Join two faces: the shortest path, then the neck along it, a cross-section crossing no wall
     * that the path widens away from on both sides, snapped to mesh edges. Walls bound both.
     *
     * @param startFace active index of the first face
     * @param endFace   active index of the second face
     * @return true when the faces are joined and a neck was found and snapped
     */
    public boolean connect(int startFace, int endFace) {
        connected = false;
        failure = "";
        pathActiveFace = new int[0];
        pathPolyline = new float[0];
        pathLength = 0.0;
        pathFilledFaceCount = 0;
        narrowestGirth = Double.POSITIVE_INFINITY;
        widestGirth = 0.0;
        narrowestPathIndex = -1;
        narrowestMarkedByEdgeId = new boolean[0];
        girthsCrossingWalls = 0;
        if (startFace < 0 || endFace < 0 || startFace >= faceCount || endFace >= faceCount) {
            failure = "both ends must lie on the surface";
            return false;
        }
        if (startFace == endFace) {
            failure = "the two ends are the same face";
            return false;
        }
        // Surface distance of every face from the start over arcs that cross no wall, each
        // reached face keeping its arc back toward the start.
        long start = System.nanoTime();
        distanceFromStart = new double[faceCount];
        Arrays.fill(distanceFromStart, Double.POSITIVE_INFINITY);
        if (arcTowardStart.length != faceCount) {
            arcTowardStart = new int[faceCount];
        }
        Arrays.fill(arcTowardStart, -1);
        distanceFromStart[startFace] = 0.0;
        frontierSize = 0;
        pushFrontier(0.0, startFace);
        while (frontierSize > 0) {
            double key = frontierKey[0];
            int settled = frontierFace[0];
            frontierSize--;
            double movedKey = frontierKey[frontierSize];
            int movedFace = frontierFace[frontierSize];
            int hole = 0;
            while (true) {
                int child = 2 * hole + 1;
                if (child >= frontierSize) {
                    break;
                }
                if (child + 1 < frontierSize && frontierKey[child + 1] < frontierKey[child]) {
                    child++;
                }
                if (frontierKey[child] >= movedKey) {
                    break;
                }
                frontierKey[hole] = frontierKey[child];
                frontierFace[hole] = frontierFace[child];
                hole = child;
            }
            frontierKey[hole] = movedKey;
            frontierFace[hole] = movedFace;
            if (key > distanceFromStart[settled]) {
                continue;
            }
            for (int arc = arcStart[settled]; arc < arcStart[settled + 1]; arc++) {
                if (wall(arcEdgeId[arc])) {
                    continue;
                }
                int neighbour = arcFace[arc];
                double relaxed = key + arcLength[arc];
                if (relaxed < distanceFromStart[neighbour]) {
                    distanceFromStart[neighbour] = relaxed;
                    arcTowardStart[neighbour] = arcReverse[arc];
                    pushFrontier(relaxed, neighbour);
                }
            }
        }
        if (distanceFromStart[endFace] == Double.POSITIVE_INFINITY) {
            failure = "the two ends are not joined: no path between them crosses no wall";
            pathMillis = (System.nanoTime() - start) / NANOS_PER_MILLI;
            return false;
        }
        connected = true;
        pathLength = distanceFromStart[endFace];
        int steps = 1;
        for (int face = endFace; face != startFace; face = arcFace[arcTowardStart[face]]) {
            steps++;
        }
        pathActiveFace = new int[steps];
        pathPolyline = new float[COORDINATES_PER_POINT * (2 * steps - 1)];
        int face = endFace;
        Vector3f midpoint = new Vector3f();
        for (int index = steps - 1; index >= 0; index--) {
            pathActiveFace[index] = face;
            System.arraycopy(centroidXyz, COORDINATES_PER_POINT * face, pathPolyline,
                    2 * COORDINATES_PER_POINT * index, COORDINATES_PER_POINT);
            pathFilledFaceCount += face < filledByActiveFace.length && filledByActiveFace[face]
                    ? 1 : 0;
            if (index > 0) {
                int arc = arcTowardStart[face];
                mesh.edgeMidpoint(arcEdgeId[arc], midpoint);
                int base = COORDINATES_PER_POINT * (2 * index - 1);
                pathPolyline[base] = midpoint.x;
                pathPolyline[base + 1] = midpoint.y;
                pathPolyline[base + 2] = midpoint.z;
                face = arcFace[arc];
            }
        }
        pathMillis = (System.nanoTime() - start) / NANOS_PER_MILLI;

        // At evenly spaced path faces, the shortest loop a plane through the face cuts from the
        // surface, the scan starting across the path's direction; a loop crossing a wall leaves
        // the region the walls bound, so it is passed over.
        start = System.nanoTime();
        int samples = Math.min(GIRTH_SAMPLES, steps);
        int reach = Math.max(1, steps / (2 * GIRTH_SAMPLES));
        double[] sampleGirth = new double[samples];
        int[] samplePathIndex = new int[samples];
        float[] samplePoint = new float[COORDINATES_PER_POINT * samples];
        float[] sampleAlong = new float[COORDINATES_PER_POINT * samples];
        int kept = 0;
        for (int sample = 0; sample < samples; sample++) {
            int index = samples == 1 ? 0 : Math.round(sample * (steps - 1f) / (samples - 1f));
            int ahead = pathActiveFace[Math.min(steps - 1, index + reach)];
            int behind = pathActiveFace[Math.max(0, index - reach)];
            int base = COORDINATES_PER_POINT * kept;
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                samplePoint[base + axis] =
                        centroidXyz[COORDINATES_PER_POINT * pathActiveFace[index] + axis];
                sampleAlong[base + axis] = centroidXyz[COORDINATES_PER_POINT * ahead + axis]
                        - centroidXyz[COORDINATES_PER_POINT * behind + axis];
            }
            if (!girdle.find(mesh, mesh.faceIdAt(pathActiveFace[index]),
                    Arrays.copyOfRange(samplePoint, base, base + COORDINATES_PER_POINT),
                    Arrays.copyOfRange(sampleAlong, base, base + COORDINATES_PER_POINT))) {
                continue;
            }
            boolean crossesWall = false;
            for (int step = 0; step < girdle.cut.stepCount && !crossesWall; step++) {
                crossesWall = wall(girdle.cut.edgeId[step]);
            }
            if (crossesWall) {
                girthsCrossingWalls++;
                continue;
            }
            // The path must cross the loop an odd number of times, or the loop does not cut it
            // (one around a spine beside the path). Within a face the loop is the plane's only
            // chord, so a path span changing side inside a loop face crosses the loop.
            loopStamp++;
            for (int step = 0; step < girdle.cut.stepCount; step++) {
                for (int side = 0; side < 2; side++) {
                    int faceId = mesh.edgeFace(girdle.cut.edgeId[step], side);
                    if (faceId >= 0) {
                        loopFaceStamp[activeFaceByFaceId[faceId]] = loopStamp;
                    }
                }
            }
            int crossings = 0;
            double previousSide = 0.0;
            for (int point = 0; point < 2 * steps - 1; point++) {
                double signed = 0.0;
                for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                    signed += (pathPolyline[COORDINATES_PER_POINT * point + axis]
                            - samplePoint[base + axis]) * girdle.normal[axis];
                }
                if (point > 0 && signed >= 0.0 != previousSide >= 0.0
                        && loopFaceStamp[pathActiveFace[point / 2]] == loopStamp) {
                    crossings++;
                }
                previousSide = signed;
            }
            if (crossings % 2 == 0) {
                continue;
            }
            widestGirth = Math.max(widestGirth, girdle.length);
            sampleGirth[kept] = girdle.length;
            samplePathIndex[kept++] = index;
        }
        // The neck is the narrowest cross-section that the path widens away from on both sides
        // by NECK_WIDENING: a path that only narrows toward a pick, or ripples over spines, has
        // no neck there.
        double[] widestAfter = new double[kept + 1];
        for (int sample = kept - 1; sample >= 0; sample--) {
            widestAfter[sample] = Math.max(widestAfter[sample + 1], sampleGirth[sample]);
        }
        int neck = -1;
        double widestBefore = 0.0;
        for (int sample = 0; sample + 1 < kept; sample++) {
            double bound = Math.min(widestBefore, widestAfter[sample + 1]) / (1.0 + NECK_WIDENING);
            if (sampleGirth[sample] <= bound && sampleGirth[sample] < narrowestGirth) {
                narrowestGirth = sampleGirth[sample];
                neck = sample;
            }
            widestBefore = Math.max(widestBefore, sampleGirth[sample]);
        }
        float[] neckAlong = new float[COORDINATES_PER_POINT];
        if (neck >= 0) {
            narrowestPathIndex = samplePathIndex[neck];
            System.arraycopy(samplePoint, COORDINATES_PER_POINT * neck, narrowestPoint, 0,
                    COORDINATES_PER_POINT);
            System.arraycopy(sampleAlong, COORDINATES_PER_POINT * neck, neckAlong, 0,
                    COORDINATES_PER_POINT);
        }
        // The neck is cut again rather than kept per sample, so only one loop is snapped.
        boolean snapped = neck >= 0
                && girdle.find(mesh, mesh.faceIdAt(pathActiveFace[narrowestPathIndex]),
                        narrowestPoint, neckAlong);
        if (snapped) {
            narrowestMarkedByEdgeId = snap.snap(mesh, girdle.cut.asTracedPath(mesh));
            snapped = snap.unresolvedGaps == 0;
        }
        girthMillis = (System.nanoTime() - start) / NANOS_PER_MILLI;
        if (neck < 0) {
            failure = kept == 0 ? "no cross-section along the path closes inside the walls"
                    : "no neck between the picks: the path widens away from no cross-section "
                            + "on both sides";
            return false;
        }
        if (!snapped) {
            failure = "the neck did not snap to mesh edges";
            narrowestMarkedByEdgeId = new boolean[0];
            return false;
        }
        return true;
    }

    /**
     * The face whose centroid lies nearest a point, for callers holding a position, not a pick.
     *
     * @param x point x
     * @param y point y
     * @param z point z
     * @return its active face index, or -1 on an empty surface
     */
    public int nearestActiveFace(float x, float y, float z) {
        int nearest = -1;
        double nearestSquared = Double.POSITIVE_INFINITY;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            double dx = centroidXyz[COORDINATES_PER_POINT * activeFace] - x;
            double dy = centroidXyz[COORDINATES_PER_POINT * activeFace + 1] - y;
            double dz = centroidXyz[COORDINATES_PER_POINT * activeFace + 2] - z;
            double squared = dx * dx + dy * dy + dz * dz;
            if (squared < nearestSquared) {
                nearestSquared = squared;
                nearest = activeFace;
            }
        }
        return nearest;
    }

    /**
     * Add one entry to the binary min-heap the distance search pops from; stale entries stay
     * and are skipped when popped.
     *
     * @param key  the face's tentative distance
     * @param face active face index
     */
    private void pushFrontier(double key, int face) {
        if (frontierSize == frontierKey.length) {
            int grown = Math.max(2 * frontierKey.length, faceCount);
            frontierKey = Arrays.copyOf(frontierKey, grown);
            frontierFace = Arrays.copyOf(frontierFace, grown);
        }
        int hole = frontierSize++;
        while (hole > 0) {
            int parent = (hole - 1) / 2;
            if (frontierKey[parent] <= key) {
                break;
            }
            frontierKey[hole] = frontierKey[parent];
            frontierFace[hole] = frontierFace[parent];
            hole = parent;
        }
        frontierKey[hole] = key;
        frontierFace[hole] = face;
    }

    /**
     * The face across an edge from a face, or -1 across the mesh boundary.
     *
     * @param edgeId edge of {@code faceId}
     * @param faceId the face on this side
     * @return the face id on the other side, or -1
     */
    private int faceAcross(int edgeId, int faceId) {
        int first = mesh.edgeFace(edgeId, 0);
        int other = first == faceId ? mesh.edgeFace(edgeId, 1) : first;
        return other == faceId ? -1 : other;
    }

    /**
     * Distance from a face's centroid to a point.
     *
     * @param activeFace active face index
     * @param point      the point
     * @return the Euclidean distance
     */
    private double centroidDistance(int activeFace, Vector3f point) {
        return point.distance(centroidXyz[COORDINATES_PER_POINT * activeFace],
                centroidXyz[COORDINATES_PER_POINT * activeFace + 1],
                centroidXyz[COORDINATES_PER_POINT * activeFace + 2]);
    }

    /**
     * Whether an edge is a wall.
     *
     * @param edgeId edge id
     * @return true when {@link #wallByEdgeId} marks it
     */
    private boolean wall(int edgeId) {
        return edgeId < wallByEdgeId.length && wallByEdgeId[edgeId];
    }
}
