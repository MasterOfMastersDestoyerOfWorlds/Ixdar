package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.EdgeKey;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;
import ixdar.numeric.sparse.AdaptiveSolver;

/**
 * Fills one boundary loop after Liepa 2003 (Filling holes in meshes, SGP): minimum-area
 * triangulation, density refinement, biharmonic fairing.
 *
 * <p>Local indices run loop, then the surrounding ring the fairing holds fixed with it, then the
 * vertices minted here. The caller maps them back.
 */
public final class MeshHoleFiller {

    public static final int TRIANGLE_CORNERS = 3;

    public static final int MAX_LOOP_LENGTH = 4096;

    private static final double REFINE_ALPHA = 1.4142135623730951;

    private static final int DEFAULT_REFINE_ROUNDS = 8;

    private static final int DEFAULT_FAIRING_ITERATIONS = 4000;

    private static final double FAIRING_TOLERANCE = 1e-12;

    private static final double FAIRING_NORM_FLOOR = 1e-300;

    private static final int RELAX_PASSES = 8;

    private static final int GROWTH = 2;

    private static final int PARALLEL_LOOP_LENGTH = 256;

    private static final int SPLIT_LOOP_LENGTH = 256;

    private static final int CUT_SHARE = 4;

    private static final int PACKED_VERTEX_BITS = 20;

    private static final int PACKED_POSITION_BITS = 23;

    /** Loop vertex positions in traversal order. */
    public PackedPoints loopPositions = new PackedPoints(0);

    /** Mean incident mesh edge length per loop vertex, the density the refinement matches. */
    public double[] loopSigma = new double[0];

    /** Surrounding-ring vertex positions; held fixed with the loop so the patch meets the
     * surface's tangent rather than flattening against it. */
    public PackedPoints ringPositions = new PackedPoints(0);

    /** Vertices in the surrounding ring; local indices {@code [loopLength, loopLength + ringCount)}. */
    public int ringCount;

    /**
     * Mesh edges among the loop and the surrounding ring, as packed local index pairs. They give
     * the loop vertices their true surface valence in the fairing operator.
     */
    public long[] surfaceEdges = new long[0];

    /** Vertices in the loop. */
    public int loopLength;

    /**
     * Packed {@code (min, max)} local index pairs the triangulation may not use as a chord because
     * the mesh already carries that edge elsewhere. Sorted ascending.
     */
    public long[] forbiddenChords = new long[0];

    /** Cap on the fairing solve's conjugate-gradient iterations; zero skips the solve. */
    public int fairingIterations = DEFAULT_FAIRING_ITERATIONS;

    /** Refinement rounds; zero fills with the bare minimum-area triangulation. */
    public int refineRounds = DEFAULT_REFINE_ROUNDS;

    /** Whether {@link #build()} produced a triangulation. */
    public boolean filled;

    /** Local vertex indices, three per triangle. */
    public int[] triangles = new int[0];

    /** Triangles in {@link #triangles}. */
    public int triangleCount;

    /** Local vertex positions, loop vertices first, then the ring, then minted ones. */
    public PackedPoints vertexPositions = new PackedPoints(0);

    /** Density scale per local vertex, parallel to {@link #vertexPositions}. */
    public double[] vertexSigma = new double[0];

    /** Local vertices, {@link #loopLength} of which are the loop's own. */
    public int vertexCount;

    /** Neighbour list offsets used by the fairing pass, {@code vertexCount + 1} long. */
    private int[] neighbourStart = new int[0];

    /** Neighbour vertex indices used by the fairing pass. */
    private int[] neighbours = new int[0];

    /** The minted vertices and their neighbours, whose umbrellas the fairing operator forms. */
    private int[] umbrellaRows = new int[0];

    /** Umbrella per local vertex, written for {@link #umbrellaRows} only. */
    private double[] umbrellaScratch = new double[0];

    /**
     * Triangulate, refine and fair the loop. Leaves {@link #filled} false when no triangulation
     * avoids the chords the mesh already carries.
     */
    public void build() {
        filled = false;
        triangleCount = 0;
        vertexCount = loopLength;
        if (loopLength < TRIANGLE_CORNERS || loopLength > MAX_LOOP_LENGTH) {
            return;
        }
        int fixedCount = loopLength + ringCount;
        vertexPositions = PackedPoints.concat(loopPositions.slice(0, loopLength), ringPositions.slice(0, ringCount));
        vertexSigma = Arrays.copyOf(loopSigma, fixedCount);
        vertexCount = fixedCount;
        if (!triangulate()) {
            return;
        }
        filled = true;
        refine();
        fair();
    }

    /**
     * Minimum-area triangulation of the loop by the standard interval dynamic program, skipping
     * any chord {@link #forbiddenChords} rules out and any triangle of zero area, which has no
     * normal for Liepa's dihedral weight and no gradient for a parametrization.
     *
     * @return whether a triangulation exists under those restrictions
     */
    private boolean triangulate() {
        triangles = new int[(loopLength - 2) * TRIANGLE_CORNERS];
        triangleCount = 0;
        return triangulate(IntStream.range(0, loopLength).toArray());
    }

    /**
     * Appends a minimum-area triangulation of one polygon over loop vertices, cutting a polygon
     * longer than {@link #SPLIT_LOOP_LENGTH} along a short chord first.
     *
     * @param polygon loop indices in loop order; its last vertex joins back to its first
     * @return whether a triangulation exists under the chord restrictions
     */
    private boolean triangulate(int[] polygon) {
        int n = polygon.length;
        if (n > SPLIT_LOOP_LENGTH) {
            int cutFrom = -1;
            int cutTo = -1;
            double shortest = Double.POSITIVE_INFINITY;
            for (int from = 0; from < n; from++) {
                for (int to = from + n / CUT_SHARE; to <= from + n - n / CUT_SHARE && to < n; to++) {
                    double length = loopPositions.distanceSquared(polygon[to], polygon[from]);
                    if (length < shortest && Arrays.binarySearch(forbiddenChords,
                            EdgeKey.undirected(polygon[from], polygon[to])) < 0) {
                        shortest = length;
                        cutFrom = from;
                        cutTo = to;
                    }
                }
            }
            if (cutFrom >= 0) {
                int before = triangleCount;
                int[] other = new int[n - (cutTo - cutFrom) + 1];
                System.arraycopy(polygon, cutTo, other, 0, n - cutTo);
                System.arraycopy(polygon, 0, other, n - cutTo, cutFrom + 1);
                if (triangulate(Arrays.copyOfRange(polygon, cutFrom, cutTo + 1)) && triangulate(other)) {
                    return true;
                }
                triangleCount = before;
            }
        }
        double[][] cost = new double[n][n];
        double[][] costToEnd = new double[n][n];
        int[][] split = new int[n][n];
        boolean[][] feasible = new boolean[n][n];
        boolean[][] feasibleToEnd = new boolean[n][n];
        for (int start = 0; start + 1 < n; start++) {
            feasible[start][start + 1] = true;
            feasibleToEnd[start + 1][start] = true;
        }
        for (int span = 2; span < n; span++) {
            int spanLength = span;
            IntStream starts = IntStream.range(0, n - span);
            (n >= PARALLEL_LOOP_LENGTH ? starts.parallel() : starts).forEach(start -> {
                int end = start + spanLength;
                if (!(start == 0 && end == n - 1) && Arrays.binarySearch(forbiddenChords,
                        EdgeKey.undirected(polygon[start], polygon[end])) >= 0) {
                    return;
                }
                double best = Double.POSITIVE_INFINITY;
                int bestSplit = -1;
                double[] fromStart = cost[start];
                double[] toEnd = costToEnd[end];
                boolean[] feasibleFromStart = feasible[start];
                boolean[] feasibleToEndRow = feasibleToEnd[end];
                for (int middle = start + 1; middle < end; middle++) {
                    if (!feasibleFromStart[middle] || !feasibleToEndRow[middle]) {
                        continue;
                    }
                    double halves = fromStart[middle] + toEnd[middle];
                    if (halves >= best) {
                        continue;
                    }
                    double area = TriangleGeometry.area(loopPositions, polygon[start], polygon[middle], polygon[end]);
                    double candidate = halves + area;
                    if (area > 0 && candidate < best) {
                        best = candidate;
                        bestSplit = middle;
                    }
                }
                if (bestSplit >= 0) {
                    feasible[start][end] = true;
                    feasibleToEnd[end][start] = true;
                    cost[start][end] = best;
                    costToEnd[end][start] = best;
                    split[start][end] = bestSplit;
                }
            });
        }
        if (!feasible[0][n - 1]) {
            return false;
        }
        emit(polygon, split, 0, n - 1);
        return true;
    }

    /**
     * Walks the dynamic program's split table, appending one triangle per interval.
     *
     * @param polygon loop index per polygon position
     * @param split best split position per interval
     * @param start interval start
     * @param end interval end
     */
    private void emit(int[] polygon, int[][] split, int start, int end) {
        if (end <= start + 1) {
            return;
        }
        int middle = split[start][end];
        emit(polygon, split, start, middle);
        emit(polygon, split, middle, end);
        triangles[triangleCount * TRIANGLE_CORNERS] = polygon[start];
        triangles[triangleCount * TRIANGLE_CORNERS + 1] = polygon[middle];
        triangles[triangleCount * TRIANGLE_CORNERS + 2] = polygon[end];
        triangleCount++;
    }

    /**
     * Liepa's density refinement: split a patch triangle at its centroid whenever the centroid
     * stands further from every corner than the local edge scale allows, then relax the patch's
     * interior edges toward Delaunay and repeat.
     */
    private void refine() {
        for (int round = 0; round < refineRounds; round++) {
            boolean split = false;
            int existing = triangleCount;
            for (int triangle = 0; triangle < existing; triangle++) {
                if (splitAtCentroid(triangle)) {
                    split = true;
                }
            }
            if (!split) {
                return;
            }
            relax();
        }
    }

    /**
     * Splits one patch triangle into three at its centroid when Liepa's density test asks for it.
     *
     * @param triangle patch triangle index
     * @return whether the triangle was split
     */
    private boolean splitAtCentroid(int triangle) {
        int cornerA = triangles[triangle * TRIANGLE_CORNERS];
        int cornerB = triangles[triangle * TRIANGLE_CORNERS + 1];
        int cornerC = triangles[triangle * TRIANGLE_CORNERS + 2];
        Vector3f centre = vertexPositions.gather(Arrays.copyOfRange(triangles, triangle * TRIANGLE_CORNERS,
                (triangle + 1) * TRIANGLE_CORNERS)).centroid();
        double centroidSigma = (vertexSigma[cornerA] + vertexSigma[cornerB] + vertexSigma[cornerC])
                / TRIANGLE_CORNERS;
        if (!denser(cornerA, centre, centroidSigma) || !denser(cornerB, centre, centroidSigma)
                || !denser(cornerC, centre, centroidSigma)) {
            return false;
        }
        if (vertexCount == vertexSigma.length) {
            vertexSigma = Arrays.copyOf(vertexSigma, Math.max(vertexCount * GROWTH, vertexCount + 1));
        }
        int centroid = vertexPositions.append(centre);
        vertexSigma[vertexCount++] = centroidSigma;
        triangles[triangle * TRIANGLE_CORNERS + 2] = centroid;
        addTriangle(cornerB, cornerC, centroid);
        addTriangle(cornerC, cornerA, centroid);
        return true;
    }

    /**
     * Liepa's per-corner density test for a candidate centroid.
     *
     * @param corner patch vertex the centroid is measured against
     * @param centroid candidate position
     * @param centroidSigma density scale the candidate would take
     * @return true when the scaled distance beats both density scales
     */
    private boolean denser(int corner, Vector3f centroid, double centroidSigma) {
        double scaled = REFINE_ALPHA * Math.sqrt(vertexPositions.distanceSquared(corner, centroid));
        return scaled > vertexSigma[corner] && scaled > centroidSigma;
    }

    /**
     * Flips patch edges toward the Delaunay criterion, leaving loop edges and any flip that would
     * duplicate an existing edge alone.
     */
    private void relax() {
        for (int pass = 0; pass < RELAX_PASSES; pass++) {
            long[] edgeKeys = new long[triangleCount * TRIANGLE_CORNERS];
            int[] cornerOfEdge = new int[edgeKeys.length];
            for (int triangle = 0; triangle < triangleCount; triangle++) {
                for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                    int slot = triangle * TRIANGLE_CORNERS + corner;
                    int from = triangles[slot];
                    int to = triangles[triangle * TRIANGLE_CORNERS + (corner + 1) % TRIANGLE_CORNERS];
                    edgeKeys[slot] = EdgeKey.undirected(from, to);
                    cornerOfEdge[slot] = slot;
                }
            }
            sortByKey(edgeKeys, cornerOfEdge);
            boolean[] touched = new boolean[triangleCount];
            Set<Long> created = new HashSet<>();
            boolean flipped = false;
            int index = 0;
            while (index < edgeKeys.length) {
                int runEnd = index;
                while (runEnd < edgeKeys.length && edgeKeys[runEnd] == edgeKeys[index]) {
                    runEnd++;
                }
                if (runEnd - index == 2) {
                    int firstTriangle = cornerOfEdge[index] / TRIANGLE_CORNERS;
                    int secondTriangle = cornerOfEdge[index + 1] / TRIANGLE_CORNERS;
                    if (!touched[firstTriangle] && !touched[secondTriangle]
                            && flipEdge(cornerOfEdge[index], cornerOfEdge[index + 1], edgeKeys,
                                    created)) {
                        touched[firstTriangle] = true;
                        touched[secondTriangle] = true;
                        flipped = true;
                    }
                }
                index = runEnd;
            }
            if (!flipped) {
                return;
            }
        }
    }

    /**
     * Flips the patch edge shared by the two corner slots when the opposite angles exceed a
     * straight angle, the replacement edge does not already exist and both new triangles have area.
     *
     * @param firstSlot corner slot of one incident triangle
     * @param secondSlot corner slot of the other incident triangle
     * @param edgeKeys sorted patch edge keys, used to reject a duplicate replacement
     * @param created replacement edges this pass already minted
     * @return whether the flip happened
     */
    private boolean flipEdge(int firstSlot, int secondSlot, long[] edgeKeys, Set<Long> created) {
        int firstTriangle = firstSlot / TRIANGLE_CORNERS;
        int secondTriangle = secondSlot / TRIANGLE_CORNERS;
        int firstCorner = firstSlot % TRIANGLE_CORNERS;
        int secondCorner = secondSlot % TRIANGLE_CORNERS;
        int startVertex = triangles[firstSlot];
        int endVertex = triangles[firstTriangle * TRIANGLE_CORNERS + (firstCorner + 1) % TRIANGLE_CORNERS];
        int firstApex = triangles[firstTriangle * TRIANGLE_CORNERS
                + (firstCorner + 2) % TRIANGLE_CORNERS];
        int secondApex = triangles[secondTriangle * TRIANGLE_CORNERS
                + (secondCorner + 2) % TRIANGLE_CORNERS];
        if (firstApex == secondApex) {
            return false;
        }
        if (TriangleGeometry.cornerAngle(vertexPositions, firstApex, startVertex, endVertex)
                + TriangleGeometry.cornerAngle(vertexPositions, secondApex, startVertex, endVertex) <= Math.PI) {
            return false;
        }
        long replacement = EdgeKey.undirected(firstApex, secondApex);
        if (TriangleGeometry.area(vertexPositions, startVertex, secondApex, firstApex) == 0
                || TriangleGeometry.area(vertexPositions, secondApex, endVertex, firstApex) == 0
                || Arrays.binarySearch(edgeKeys, replacement) >= 0
                || Arrays.binarySearch(forbiddenChords, replacement) >= 0
                || !created.add(replacement)) {
            return false;
        }
        triangles[firstTriangle * TRIANGLE_CORNERS] = startVertex;
        triangles[firstTriangle * TRIANGLE_CORNERS + 1] = secondApex;
        triangles[firstTriangle * TRIANGLE_CORNERS + 2] = firstApex;
        triangles[secondTriangle * TRIANGLE_CORNERS] = secondApex;
        triangles[secondTriangle * TRIANGLE_CORNERS + 1] = endVertex;
        triangles[secondTriangle * TRIANGLE_CORNERS + 2] = firstApex;
        return true;
    }

    /**
     * Solves the thin-plate system {@code U(U(x)) = 0} over the minted vertices, holding the loop
     * and the surrounding ring fixed so the patch leaves the surface along its tangent.
     *
     * <p>Matrix-free conjugate gradient on the normal equations of {@code min ||U x||^2}.
     */
    private void fair() {
        int fixedCount = loopLength + ringCount;
        if (vertexCount == fixedCount || fairingIterations <= 0) {
            return;
        }
        buildNeighbours();
        boolean[] needed = new boolean[vertexCount];
        for (int vertex = fixedCount; vertex < vertexCount; vertex++) {
            needed[vertex] = true;
            for (int index = neighbourStart[vertex]; index < neighbourStart[vertex + 1]; index++) {
                needed[neighbours[index]] = true;
            }
        }
        umbrellaRows = IntStream.range(0, vertexCount)
                .filter(vertex -> needed[vertex]).toArray();
        umbrellaScratch = new double[vertexCount];
        for (int axis = 0; axis < PackedPoints.XYZ; axis++) {
            solveCoordinate(axis, fixedCount);
        }
    }

    /**
     * Runs the fairing solve for one coordinate of {@link #vertexPositions} in double, writing the
     * result back over the minted vertices.
     *
     * @param axis the coordinate solved: 0 for x, 1 for y, 2 for z; vertices below
     *     {@code fixedCount} are the Dirichlet data and are not written
     * @param fixedCount local vertices held fixed: the loop and the surrounding ring
     */
    private void solveCoordinate(int axis, int fixedCount) {
        int unknowns = vertexCount - fixedCount;
        double[] boundaryOnly = new double[vertexCount];
        for (int vertex = 0; vertex < fixedCount; vertex++) {
            boundaryOnly[vertex] = vertexPositions.get(vertex, axis);
        }
        double[] rightHandSide = new double[unknowns];
        double[] scratch = new double[vertexCount];
        applyNormalOperator(boundaryOnly, scratch);
        for (int unknown = 0; unknown < unknowns; unknown++) {
            rightHandSide[unknown] = -scratch[fixedCount + unknown];
        }

        double[] solution = new double[unknowns];
        for (int unknown = 0; unknown < unknowns; unknown++) {
            solution[unknown] = vertexPositions.get(fixedCount + unknown, axis);
        }
        double[] full = new double[vertexCount];
        AdaptiveSolver.preconditionedConjugateGradient(
                (source, destination) -> applyToUnknowns(source, full, scratch, fixedCount,
                        destination),
                rightHandSide, solution, null,
                (residual, output) -> System.arraycopy(residual, 0, output, 0, unknowns),
                fairingIterations, FAIRING_TOLERANCE, FAIRING_NORM_FLOOR);
        for (int unknown = 0; unknown < unknowns; unknown++) {
            vertexPositions.set(fixedCount + unknown, axis, (float) solution[unknown]);
        }
    }

    /**
     * Applies the normal operator to a vector supported on the unknowns alone.
     *
     * @param unknownValues one value per unknown
     * @param full scratch of {@link #vertexCount} entries, overwritten with the embedded vector
     * @param scratch scratch of {@link #vertexCount} entries, overwritten with the operator's output
     * @param fixedCount local vertices held fixed
     * @param destination filled with the unknown rows of the result
     */
    private void applyToUnknowns(double[] unknownValues, double[] full, double[] scratch,
            int fixedCount, double[] destination) {
        System.arraycopy(unknownValues, 0, full, fixedCount, unknownValues.length);
        applyNormalOperator(full, scratch);
        System.arraycopy(scratch, fixedCount, destination, 0, unknownValues.length);
    }

    /**
     * Applies {@code U^T U} for the uniform umbrella {@code U = D^-1 A - I}.
     *
     * @param source one value per local vertex
     * @param destination filled with the operator's output, one value per local vertex
     */
    private void applyNormalOperator(double[] source, double[] destination) {
        int fixedCount = loopLength + ringCount;
        double[] umbrella = umbrellaScratch;
        for (int vertex : umbrellaRows) {
            int start = neighbourStart[vertex];
            int end = neighbourStart[vertex + 1];
            if (end == start) {
                umbrella[vertex] = 0;
                continue;
            }
            double sum = 0;
            for (int index = start; index < end; index++) {
                sum += source[neighbours[index]];
            }
            umbrella[vertex] = sum / (end - start) - source[vertex];
        }
        for (int vertex = fixedCount; vertex < vertexCount; vertex++) {
            int start = neighbourStart[vertex];
            int end = neighbourStart[vertex + 1];
            double sum = 0;
            for (int index = start; index < end; index++) {
                int neighbour = neighbours[index];
                int degree = neighbourStart[neighbour + 1] - neighbourStart[neighbour];
                if (degree > 0) {
                    sum += umbrella[neighbour] / degree;
                }
            }
            destination[vertex] = sum - umbrella[vertex];
        }
    }

    /** Builds the patch's vertex adjacency in compressed row form, each neighbour listed once. */
    private void buildNeighbours() {
        int[] degree = new int[vertexCount + 1];
        for (int slot = 0; slot < triangleCount * TRIANGLE_CORNERS; slot++) {
            degree[triangles[slot]] += 2;
        }
        for (long edge : surfaceEdges) {
            degree[EdgeKey.minVertex(edge)]++;
            degree[EdgeKey.maxVertex(edge)]++;
        }
        neighbourStart = new int[vertexCount + 1];
        int running = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            neighbourStart[vertex] = running;
            running += degree[vertex];
        }
        neighbourStart[vertexCount] = running;
        int[] cursor = Arrays.copyOf(neighbourStart, vertexCount);
        int[] raw = new int[running];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int vertex = triangles[triangle * TRIANGLE_CORNERS + corner];
                raw[cursor[vertex]++] = triangles[triangle * TRIANGLE_CORNERS
                        + (corner + 1) % TRIANGLE_CORNERS];
                raw[cursor[vertex]++] = triangles[triangle * TRIANGLE_CORNERS
                        + (corner + 2) % TRIANGLE_CORNERS];
            }
        }
        for (long edge : surfaceEdges) {
            int low = EdgeKey.minVertex(edge);
            int high = EdgeKey.maxVertex(edge);
            raw[cursor[low]++] = high;
            raw[cursor[high]++] = low;
        }
        neighbours = new int[running];
        int packed = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int start = neighbourStart[vertex];
            int end = neighbourStart[vertex + 1];
            Arrays.sort(raw, start, end);
            neighbourStart[vertex] = packed;
            for (int index = start; index < end; index++) {
                if (index == start || raw[index] != raw[index - 1]) {
                    neighbours[packed++] = raw[index];
                }
            }
        }
        neighbourStart[vertexCount] = packed;
    }

    /**
     * Appends a patch triangle.
     *
     * @param cornerA first corner
     * @param cornerB second corner
     * @param cornerC third corner
     */
    private void addTriangle(int cornerA, int cornerB, int cornerC) {
        if ((triangleCount + 1) * TRIANGLE_CORNERS > triangles.length) {
            triangles = Arrays.copyOf(triangles,
                    Math.max(triangles.length * GROWTH, (triangleCount + 1) * TRIANGLE_CORNERS));
        }
        triangles[triangleCount * TRIANGLE_CORNERS] = cornerA;
        triangles[triangleCount * TRIANGLE_CORNERS + 1] = cornerB;
        triangles[triangleCount * TRIANGLE_CORNERS + 2] = cornerC;
        triangleCount++;
    }

    /**
     * Sorts a key array ascending, carrying a payload array along, by insertion order over a
     * permutation so the payload stays paired with its key.
     *
     * @param keys keys to sort in place
     * @param payload values to permute the same way
     */
    private static void sortByKey(long[] keys, int[] payload) {
        long vertexLimit = 1L << PACKED_VERTEX_BITS;
        boolean packable = keys.length < 1 << PACKED_POSITION_BITS;
        for (int index = 0; index < keys.length && packable; index++) {
            packable = EdgeKey.maxVertex(keys[index]) < vertexLimit;
        }
        if (packable) {
            long[] packed = new long[keys.length];
            for (int index = 0; index < keys.length; index++) {
                packed[index] = (long) EdgeKey.minVertex(keys[index]) << (PACKED_VERTEX_BITS + PACKED_POSITION_BITS)
                        | (long) EdgeKey.maxVertex(keys[index]) << PACKED_POSITION_BITS | index;
            }
            Arrays.sort(packed);
            long[] originalKeys = keys.clone();
            int[] originalPayload = payload.clone();
            for (int index = 0; index < keys.length; index++) {
                int from = (int) (packed[index] & ((1L << PACKED_POSITION_BITS) - 1));
                keys[index] = originalKeys[from];
                payload[index] = originalPayload[from];
            }
            return;
        }
        long[] originalKeys = keys.clone();
        Integer[] order = new Integer[keys.length];
        for (int index = 0; index < keys.length; index++) {
            order[index] = index;
        }
        Arrays.sort(order, (left, right) -> Long.compare(originalKeys[left], originalKeys[right]));
        int[] originalPayload = payload.clone();
        for (int index = 0; index < keys.length; index++) {
            keys[index] = originalKeys[order[index]];
            payload[index] = originalPayload[order[index]];
        }
    }
}
