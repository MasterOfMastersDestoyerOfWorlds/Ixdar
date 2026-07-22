package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.OrderingMethod;

/**
 * Tutte's barycentric embedding of one patch's triangulated region onto an axis-aligned
 * rectangle, with the four sides pinned to the four rectangle edges.
 *
 * <p>The interior weights must stay positive for the map to remain fold-free.
 *
 * <p>See also: Tutte 1963; MPZ14 Section 7.3
 */
public final class PatchRectangleMap {

    /** Uniform (barycentric) edge weight — positive, so Tutte's fold-free guarantee is unconditional. */
    public static final double UNIFORM_WEIGHT = 1.0;

    private static final int KEY_ROW_SHIFT = 32;

    public final Vector3f[] positions;
    public final int[][] triangles;
    public final int[] boundaryLoop;
    public final int[] boundaryCornerAt;
    public final double width;
    public final double height;

    /**
     * Caller's label for each dense vertex — the copy vertex id it came from, for relating a
     * rectangle coordinate back to the mesh. Identity {@code {0, 1, 2, ...}} when the caller
     * works in dense indices directly.
     */
    public final int[] vertexLabel;

    /** Rectangle x-coordinate of each vertex, by dense index; filled by {@link #build()}. */
    public final double[] rectangleU;

    /** Rectangle y-coordinate of each vertex, by dense index; filled by {@link #build()}. */
    public final double[] rectangleV;

    /** Whether each vertex is a pinned boundary vertex, by dense index. */
    public final boolean[] onBoundary;

    /**
     * Prepares a map over primitive geometry. Call {@link #build()} to solve it.
     *
     * @param positions        3D position of each vertex, indexed by dense vertex index
     * @param triangles        each triangle as three dense vertex indices in winding order
     * @param boundaryLoop      dense vertex indices around the region boundary, one consistent
     *                          direction, each boundary vertex once, not repeating the first
     * @param boundaryCornerAt  four indices into {@code boundaryLoop} marking where sides 0..3
     *                          start; side {@code s} runs from {@code boundaryCornerAt[s]} to
     *                          {@code boundaryCornerAt[(s + 1) % 4]}
     * @param width             rectangle width, the extent of sides 0 and 2; must be positive
     * @param height            rectangle height, the extent of sides 1 and 3; must be positive
     * @param vertexLabel       caller's label per dense vertex (a copy vertex id), or {@code null}
     *                          for identity labels {@code {0, 1, 2, ...}}
     */
    public PatchRectangleMap(Vector3f[] positions, int[][] triangles, int[] boundaryLoop,
            int[] boundaryCornerAt, double width, double height, int[] vertexLabel) {
        this.positions = positions;
        this.triangles = triangles;
        this.boundaryLoop = boundaryLoop;
        this.boundaryCornerAt = boundaryCornerAt;
        this.width = width;
        this.height = height;
        this.rectangleU = new double[positions.length];
        this.rectangleV = new double[positions.length];
        this.onBoundary = new boolean[positions.length];
        this.vertexLabel = vertexLabel != null ? vertexLabel : identity(positions.length);
    }

    /**
     * Identity labels {@code {0, 1, ..., count - 1}}.
     *
     * @param count number of labels
     * @return an array where entry {@code i} is {@code i}
     */
    private static int[] identity(int count) {
        int[] labels = new int[count];
        for (int index = 0; index < count; index++) {
            labels[index] = index;
        }
        return labels;
    }

    /**
     * Places the boundary on the rectangle and solves for the interior vertices.
     *
     * @return this, solved
     * @throws IllegalStateException when a side has no geometric extent (an un-contracted patch)
     *                               or the Tutte system is not positive definite
     */
    public PatchRectangleMap build() {
        placeBoundary();
        solveInterior();
        return this;
    }

    /**
     * Pins each boundary vertex to a rectangle edge, spaced by cumulative chord length within
     * its side, with the four corners at the rectangle's corners.
     *
     * @throws IllegalStateException when a side collapses to a point, so its vertices cannot be
     *                               distributed by chord-length ratio
     */
    private void placeBoundary() {
        double[] cornerX = {0.0, width, width, 0.0};
        double[] cornerY = {0.0, 0.0, height, height};
        int loopLength = boundaryLoop.length;
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            int startIndex = boundaryCornerAt[side];
            int endIndex = boundaryCornerAt[(side + 1) % EmbeddedPatch.SIDES];
            int count = walkLength(startIndex, endIndex, loopLength);
            double total = 0.0;
            double[] cumulative = new double[count];
            int previous = boundaryLoop[startIndex];
            int index = startIndex;
            for (int step = 1; step < count; step++) {
                index = (index + 1) % loopLength;
                int here = boundaryLoop[index];
                total += positions[here].distance(positions[previous]);
                cumulative[step] = total;
                previous = here;
            }
            if (total == 0.0) {
                throw new IllegalStateException("patch side " + side + " has no geometric extent;"
                        + " its boundary vertices cannot be spaced by chord length");
            }
            int nextSide = (side + 1) % EmbeddedPatch.SIDES;
            index = startIndex;
            for (int step = 0; step < count; step++) {
                double fraction = cumulative[step] / total;
                int dense = boundaryLoop[index];
                rectangleU[dense] = cornerX[side] + fraction * (cornerX[nextSide] - cornerX[side]);
                rectangleV[dense] = cornerY[side] + fraction * (cornerY[nextSide] - cornerY[side]);
                onBoundary[dense] = true;
                index = (index + 1) % loopLength;
            }
        }
    }

    /**
     * The number of vertices on a side, walking the loop forward from one corner index to the
     * next, both inclusive.
     *
     * @param startIndex loop index of the side's first corner
     * @param endIndex   loop index of the side's last corner
     * @param loopLength number of vertices in the boundary loop
     * @return the count of vertices from start to end inclusive
     */
    private int walkLength(int startIndex, int endIndex, int loopLength) {
        int span = endIndex - startIndex;
        if (span <= 0) {
            span += loopLength;
        }
        return span + 1;
    }

    /**
     * Solves the Tutte system for the interior vertices — each the uniform average of its
     * neighbours — holding the boundary fixed, once for the x-coordinate and once for the y.
     *
     * @throws IllegalStateException when the system is not positive definite
     */
    private void solveInterior() {
        boolean anyFree = false;
        for (boolean pinned : onBoundary) {
            if (!pinned) {
                anyFree = true;
                break;
            }
        }
        if (!anyFree) {
            return;
        }
        int n = positions.length;
        double[] diagonal = new double[n];
        Map<Long, Double> upper = new HashMap<>();
        Set<Long> seenEdges = new HashSet<>();
        for (int[] triangle : triangles) {
            accumulateEdge(triangle[0], triangle[1], diagonal, upper, seenEdges);
            accumulateEdge(triangle[1], triangle[2], diagonal, upper, seenEdges);
            accumulateEdge(triangle[2], triangle[0], diagonal, upper, seenEdges);
        }
        NormalMatrix matrix = new NormalMatrix(diagonal, upper, new double[n]);
        double[] solvedU = DirectSolver.solve(matrix, rectangleU, onBoundary, OrderingMethod.RCM);
        double[] solvedV = DirectSolver.solve(matrix, rectangleV, onBoundary, OrderingMethod.RCM);
        System.arraycopy(solvedU, 0, rectangleU, 0, n);
        System.arraycopy(solvedV, 0, rectangleV, 0, n);
    }

    /**
     * Adds one graph edge's uniform weight to the Laplacian, once per undirected edge.
     *
     * @param first     one dense vertex index of the edge
     * @param second    the other dense vertex index
     * @param diagonal  diagonal of the Laplacian, accumulated in place
     * @param upper     off-diagonal upper-triangle entries, keyed {@code (min << 32) | max}
     * @param seenEdges undirected edges already added, so a shared edge counts once
     */
    private void accumulateEdge(int first, int second, double[] diagonal, Map<Long, Double> upper,
            Set<Long> seenEdges) {
        int low = Math.min(first, second);
        int high = Math.max(first, second);
        long key = ((long) low << KEY_ROW_SHIFT) | high;
        if (!seenEdges.add(key)) {
            return;
        }
        diagonal[low] += UNIFORM_WEIGHT;
        diagonal[high] += UNIFORM_WEIGHT;
        upper.merge(key, -UNIFORM_WEIGHT, Double::sum);
    }

    /**
     * The number of triangles that fold over in the map — zero area, or the opposite winding
     * from the majority. Tutte's theorem promises this is zero for a convex boundary and
     * positive weights, so a non-zero count is a broken map, not a tolerance to accept.
     *
     * @return count of folded or degenerate triangles
     */
    public int flippedTriangleCount() {
        double referenceArea = 0.0;
        for (int[] triangle : triangles) {
            double area = signedArea(triangle);
            if (Math.abs(area) > Math.abs(referenceArea)) {
                referenceArea = area;
            }
        }
        boolean referencePositive = referenceArea > 0.0;
        int flipped = 0;
        for (int[] triangle : triangles) {
            double area = signedArea(triangle);
            if (area == 0.0 || (area > 0.0) != referencePositive) {
                flipped++;
            }
        }
        return flipped;
    }

    /**
     * Asserts the map is bijective — no folded or degenerate triangles — the property every
     * downstream client relies on.
     *
     * @throws IllegalStateException when any triangle folds over
     */
    public void assertFoldFree() {
        int flipped = flippedTriangleCount();
        if (flipped > 0) {
            throw new IllegalStateException("Tutte map is not bijective: " + flipped + " of "
                    + triangles.length + " triangles fold over");
        }
    }

    /**
     * Twice the signed area of a triangle in the rectangle, positive for counter-clockwise
     * winding.
     *
     * @param triangle three dense vertex indices in winding order
     * @return the signed area measure (sign is what matters)
     */
    private double signedArea(int[] triangle) {
        double ux = rectangleU[triangle[0]];
        double uy = rectangleV[triangle[0]];
        double vx = rectangleU[triangle[1]];
        double vy = rectangleV[triangle[1]];
        double wx = rectangleU[triangle[2]];
        double wy = rectangleV[triangle[2]];
        return (vx - ux) * (wy - uy) - (wx - ux) * (vy - uy);
    }
}
