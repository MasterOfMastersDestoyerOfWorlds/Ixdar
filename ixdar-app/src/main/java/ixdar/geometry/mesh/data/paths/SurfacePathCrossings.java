package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Finds where a closed path traced on a mesh crosses or touches itself: two spans crossing inside
 * a face they share, or the path passing through one mesh vertex twice.
 *
 * <p>
 * A ring is a simple closed curve exactly when neither happens.
 */
public final class SurfacePathCrossings {

    public static final int COORDINATES_PER_POINT = 3;

    public static final int MOST_SHARED_FACES = 2;

    public static final int FACE_SHIFT = 32;

    public static final long SPAN_MASK = 0xFFFFFFFFL;

    /** Pairs of spans the last search found crossing inside a face. */
    public int crossings;

    /** Mesh vertices the last search found the path passing through more than once. */
    public int revisitedVertices;

    /** Surface point of the first crossing or revisit found, packed xyz. */
    public final float[] firstCrossingXyz = new float[COORDINATES_PER_POINT];

    private final int[] sharedFaces = new int[MOST_SHARED_FACES];
    private long[] faceSpans = new long[0];

    /**
     * Whether a closed path is a simple curve on its surface, counting its crossings and revisited
     * vertices. A stretch run out and straight back over the same vertices has no width and is
     * read as not taken; a path that cancels down to nothing is not a ring.
     *
     * @param mesh surface the path's vertex, edge and face ids index
     * @param path closed path, its points each on a vertex, across an edge or inside a face
     * @return true when the path neither crosses nor touches itself
     */
    public boolean isSimple(MeshTopology mesh, TracedSurfacePath path) {
        crossings = 0;
        revisitedVertices = 0;
        // Cancel every stretch the path runs out along and straight back over the same vertices,
        // across the seam too; what is left is the curve the path draws.
        int[] order = new int[path.pointCount];
        int points = 0;
        for (int point = 0; point < path.pointCount; point++) {
            int vertexId = path.vertexId[point];
            if (vertexId >= 0 && points >= 1 && path.vertexId[order[points - 1]] == vertexId) {
                continue;
            }
            if (vertexId >= 0 && points >= 2 && path.vertexId[order[points - 2]] == vertexId) {
                points--;
                continue;
            }
            order[points++] = point;
        }
        int start = 0;
        while (points - start >= COORDINATES_PER_POINT && path.vertexId[order[points - 1]] >= 0
                && (path.vertexId[order[points - 1]] == path.vertexId[order[start]]
                        || path.vertexId[order[points - 1]] == path.vertexId[order[start + 1]])) {
            start += path.vertexId[order[points - 1]] == path.vertexId[order[start]] ? 0 : 1;
            points--;
        }
        order = Arrays.copyOfRange(order, start, points);
        points -= start;
        if (points < COORDINATES_PER_POINT) {
            revisitedVertices = path.pointCount < COORDINATES_PER_POINT ? 0 : 1;
            if (revisitedVertices > 0) {
                record(path, 0, -1, 0.0);
            }
            return revisitedVertices == 0;
        }
        long[] byVertex = new long[points];
        int visits = 0;
        for (int point = 0; point < points; point++) {
            int vertexId = path.vertexId[order[point]];
            if (vertexId >= 0 && vertexId != path.vertexId[order[Math.floorMod(point - 1,
                    points)]]) {
                byVertex[visits++] = ((long) vertexId << FACE_SHIFT) | order[point];
            }
        }
        Arrays.sort(byVertex, 0, visits);
        for (int visit = 1; visit < visits; visit++) {
            if (byVertex[visit] >>> FACE_SHIFT == byVertex[visit - 1] >>> FACE_SHIFT) {
                if (crossings + revisitedVertices == 0) {
                    record(path, (int) (byVertex[visit] & SPAN_MASK), -1, 0.0);
                }
                revisitedVertices++;
            }
        }

        int entries = 0;
        if (faceSpans.length < MOST_SHARED_FACES * points) {
            faceSpans = new long[MOST_SHARED_FACES * points];
        }
        // A span lies in every face its two ends share: one across a face, two along an edge,
        // none across a gap in the path.
        for (int span = 0; span < points; span++) {
            int from = order[span];
            int to = order[(span + 1) % points];
            int shared = 0;
            int carriers = carrierCount(mesh, path, from);
            for (int carrier = 0; carrier < carriers && shared < MOST_SHARED_FACES; carrier++) {
                int faceId = carrierAt(mesh, path, from, carrier);
                if (faceId < 0 || (shared > 0 && sharedFaces[0] == faceId)) {
                    continue;
                }
                int others = carrierCount(mesh, path, to);
                for (int other = 0; other < others; other++) {
                    if (carrierAt(mesh, path, to, other) == faceId) {
                        sharedFaces[shared++] = faceId;
                        break;
                    }
                }
            }
            for (int face = 0; face < shared; face++) {
                faceSpans[entries++] = ((long) sharedFaces[face] << FACE_SHIFT) | span;
            }
        }
        Arrays.sort(faceSpans, 0, entries);
        for (int first = 0; first < entries;) {
            int faceId = (int) (faceSpans[first] >>> FACE_SHIFT);
            int last = first + 1;
            while (last < entries && (int) (faceSpans[last] >>> FACE_SHIFT) == faceId) {
                last++;
            }
            // Two spans properly cross in the plane that drops the face's dominant normal axis;
            // spans that only touch at an end do not count.
            int dominantAxis = mesh.faceDominantAxis(faceId);
            int firstAxis = (dominantAxis + 1) % COORDINATES_PER_POINT;
            int secondAxis = (dominantAxis + 2) % COORDINATES_PER_POINT;
            double[] xyz = path.surfacePositions;
            for (int one = first; one < last; one++) {
                for (int other = one + 1; other < last; other++) {
                    int spanA = (int) (faceSpans[one] & SPAN_MASK);
                    int spanB = (int) (faceSpans[other] & SPAN_MASK);
                    int apart = Math.floorMod(spanB - spanA, points);
                    if (apart <= 1 || apart >= points - 1) {
                        continue;
                    }
                    int a0 = COORDINATES_PER_POINT * order[spanA];
                    int a1 = COORDINATES_PER_POINT * order[(spanA + 1) % points];
                    int b0 = COORDINATES_PER_POINT * order[spanB];
                    int b1 = COORDINATES_PER_POINT * order[(spanB + 1) % points];
                    if (orientation(xyz, a0, a1, b0, firstAxis, secondAxis)
                            * orientation(xyz, a0, a1, b1, firstAxis, secondAxis) < 0.0
                            && orientation(xyz, b0, b1, a0, firstAxis, secondAxis)
                                    * orientation(xyz, b0, b1, a1, firstAxis, secondAxis) < 0.0) {
                        if (crossings + revisitedVertices == 0) {
                            record(path, order[spanA], order[(spanA + 1) % points], 0.5);
                        }
                        crossings++;
                    }
                }
            }
            first = last;
        }
        return crossings == 0 && revisitedVertices == 0;
    }

    private static int carrierCount(MeshTopology mesh, TracedSurfacePath path, int point) {
        if (path.vertexId[point] >= 0) {
            return mesh.vertexFaceCount(path.vertexId[point]);
        }
        return path.edgeId[point] >= 0 ? MOST_SHARED_FACES : path.faceId[point] >= 0 ? 1 : 0;
    }

    private static int carrierAt(MeshTopology mesh, TracedSurfacePath path, int point,
            int carrier) {
        if (path.vertexId[point] >= 0) {
            return mesh.vertexFaceAt(path.vertexId[point], carrier);
        }
        return path.edgeId[point] >= 0 ? mesh.edgeFace(path.edgeId[point], carrier)
                : path.faceId[point];
    }

    private static double orientation(double[] xyz, int from, int to, int at, int first,
            int second) {
        return (xyz[to + first] - xyz[from + first]) * (xyz[at + second] - xyz[from + second])
                - (xyz[to + second] - xyz[from + second]) * (xyz[at + first] - xyz[from + first]);
    }

    private void record(TracedSurfacePath path, int point, int otherPoint, double blend) {
        for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
            double here = path.surfacePositions[COORDINATES_PER_POINT * point + axis];
            double there = otherPoint < 0 ? here
                    : path.surfacePositions[COORDINATES_PER_POINT * otherPoint + axis];
            firstCrossingXyz[axis] = (float) (here + blend * (there - here));
        }
    }
}
