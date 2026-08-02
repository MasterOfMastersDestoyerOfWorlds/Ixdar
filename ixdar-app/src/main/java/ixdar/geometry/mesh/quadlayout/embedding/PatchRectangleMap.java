package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.HashMap;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.OrderingMethod;

/**
 * Harmonic (cotangent-weighted) embedding of one patch's triangulated region
 * onto an axis-aligned rectangle, with the four sides pinned to the four
 * rectangle edges.
 *
 * <p>
 * The interior weights must stay positive for the map to remain fold-free.
 *
 * <p>
 * See also: Tutte 1963; MPZ14 Section 7.3; LCK21a Section 6
 */
public final class PatchRectangleMap {

    /**
     * Floor on a harmonic edge weight. Cotangents turn negative on obtuse triangles
     * and Tutte's fold-free guarantee needs every weight positive; they are
     * scale-invariant, so this is absolute.
     */
    public static final double MINIMUM_HARMONIC_WEIGHT = 1.0e-3;

    /**
     * Share of each arc's boundary spacing taken uniformly rather than by chord
     * length. Tutte's theorem needs the boundary strictly ordered around a convex
     * polygon, and two copy vertices at the same position tie on chord length
     * alone, pinning a triangle to zero area.
     */
    public static final double UNIFORM_SPACING_SHARE = 1.0;

    private static final int KEY_ROW_SHIFT = 32;

    public final Vector3f[] positions;
    public final int[][] triangles;
    public final int[] boundaryLoop;

    /**
     * Per side, the {@code boundaryLoop} indices where the side's arcs begin and
     * end, from the side's own corner to the next one, so entry {@code 0} is the
     * corner and the last entry is the next corner.
     */
    public final int[][] sideBreakLoopIndex;

    /**
     * Per side, the quantized offset of each entry of {@link #sideBreakLoopIndex}
     * from the side's start, so the first is {@code 0} and the last is the side's
     * quantized length.
     */
    public final int[][] sideBreakOffset;

    public final double width;
    public final double height;

    /**
     * Caller's label for each dense vertex — the copy vertex id it came from, for
     * relating a rectangle coordinate back to the mesh. Identity
     * {@code {0, 1, 2, ...}} when the caller works in dense indices directly.
     */
    public final int[] vertexLabel;

    /**
     * Rectangle x-coordinate of each vertex, by dense index; filled by
     * {@link #build()}.
     */
    public final double[] rectangleU;

    /**
     * Rectangle y-coordinate of each vertex, by dense index; filled by
     * {@link #build()}.
     */
    public final double[] rectangleV;

    /** Whether each vertex is a pinned boundary vertex, by dense index. */
    public final boolean[] onBoundary;

    /**
     * Prepares a map over primitive geometry. Call {@link #build()} to solve it.
     *
     * @param positions          3D position of each vertex, indexed by dense vertex
     *                           index
     * @param triangles          each triangle as three dense vertex indices in
     *                           winding order
     * @param boundaryLoop       dense vertex indices around the region boundary,
     *                           one consistent direction, each boundary vertex
     *                           once, not repeating the first
     * @param sideBreakLoopIndex per side, the loop indices of its arc endpoints,
     *                           corner first
     * @param sideBreakOffset    per side, the quantized offset of each of those
     *                           endpoints
     * @param width              rectangle width, the extent of sides 0 and 2; must
     *                           be positive
     * @param height             rectangle height, the extent of sides 1 and 3; must
     *                           be positive
     * @param vertexLabel        caller's label per dense vertex (a copy vertex id),
     *                           or {@code null} for identity labels
     *                           {@code {0, 1, 2, ...}}
     * @param boundaryStepLength length of the boundary step arriving at each loop
     *                           entry, or {@code null} to space the boundary by 3D
     *                           chord length
     */
    public PatchRectangleMap(Vector3f[] positions, int[][] triangles, int[] boundaryLoop,
            int[][] sideBreakLoopIndex, int[][] sideBreakOffset, double width, double height,
            int[] vertexLabel) {
        this.positions = positions;
        this.triangles = triangles;
        this.boundaryLoop = boundaryLoop;
        this.sideBreakLoopIndex = sideBreakLoopIndex;
        this.sideBreakOffset = sideBreakOffset;
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
     * @throws IllegalStateException when a side has no geometric extent (an
     *                               un-contracted patch) or the Tutte system is not
     *                               positive definite
     * @return this, solved
     */
    public PatchRectangleMap build() {
        placeBoundary();
        solveInterior();
        return this;
    }

    /**
     * Pins each boundary vertex to a rectangle edge one arc at a time, so adjacent
     * patches place the integers identically along a shared arc.
     *
     * <p>
     * See also: LCBK19 Section 6.2
     *
     * @throws IllegalStateException when an arc has no quantized length or no
     *                               geometric extent, so its vertices cannot be
     *                               distributed
     */
    private void placeBoundary() {
        double[] cornerX = { 0.0, width, width, 0.0 };
        double[] cornerY = { 0.0, 0.0, height, height };
        int loopLength = boundaryLoop.length;
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            int[] breakLoopIndex = sideBreakLoopIndex[side];
            int[] breakOffset = sideBreakOffset[side];
            int sideLength = breakOffset[breakOffset.length - 1];
            if (sideLength <= 0) {
                throw new IllegalStateException("patch side " + side + " has quantized length "
                        + sideLength + "; a rectangle side must be at least one quantum");
            }
            int nextSide = (side + 1) % EmbeddedPatch.SIDES;
            for (int arcIndex = 0; arcIndex < breakLoopIndex.length - 1; arcIndex++) {
                int startIndex = breakLoopIndex[arcIndex];
                int endIndex = breakLoopIndex[arcIndex + 1];
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
                    throw new IllegalStateException("arc " + arcIndex + " of patch side " + side
                            + " has no extent; its boundary vertices cannot be spaced along the"
                            + " rectangle side");
                }
                double arcStart = breakOffset[arcIndex] / (double) sideLength;
                double arcEnd = breakOffset[arcIndex + 1] / (double) sideLength;
                index = startIndex;
                for (int step = 0; step < count; step++) {
                    double fraction = (1.0 - UNIFORM_SPACING_SHARE) * cumulative[step] / total
                            + UNIFORM_SPACING_SHARE * step / (count - 1.0);
                    double along = arcStart + fraction * (arcEnd - arcStart);
                    int dense = boundaryLoop[index];
                    rectangleU[dense] = cornerX[side] + along * (cornerX[nextSide] - cornerX[side]);
                    rectangleV[dense] = cornerY[side] + along * (cornerY[nextSide] - cornerY[side]);
                    onBoundary[dense] = true;
                    index = (index + 1) % loopLength;
                }
            }
        }
    }

    /**
     * The number of vertices on a side, walking the loop forward from one corner
     * index to the next, both inclusive.
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
     * Solves the harmonic system for the interior vertices, holding the boundary
     * fixed, once for the x-coordinate and once for the y.
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
        for (Map.Entry<Long, Double> edge : cotangentEdgeWeights().entrySet()) {
            long key = edge.getKey();
            double weight = edge.getValue();
            diagonal[(int) (key >>> KEY_ROW_SHIFT)] += weight;
            diagonal[(int) key] += weight;
            upper.put(key, -weight);
        }
        double[] rightHandSide = new double[n];
        NormalMatrix matrix = new NormalMatrix(diagonal, upper, rightHandSide);
        DirectSolver.CholeskyHandle handle = DirectSolver.factorize(matrix, onBoundary, OrderingMethod.AMD);
        double[] solvedU = rectangleU.clone();
        DirectSolver.solveCompact(handle, matrix, rightHandSide, solvedU, rectangleU, onBoundary);
        double[] solvedV = rectangleV.clone();
        DirectSolver.solveCompact(handle, matrix, rightHandSide, solvedV, rectangleV, onBoundary);
        DirectSolver.releaseHandle(handle);
        System.arraycopy(solvedU, 0, rectangleU, 0, n);
        System.arraycopy(solvedV, 0, rectangleV, 0, n);
    }

    /**
     * The harmonic weight of every graph edge: the cotangent of each opposite
     * angle, summed over the triangles on both sides, floored at
     * {@link #MINIMUM_HARMONIC_WEIGHT}.
     *
     * <p>
     * The customary factor of one half is dropped — the interior system is
     * homogeneous, so scaling every weight alike leaves the solution unchanged.
     *
     * @return weight per undirected edge, keyed {@code (min << 32) | max}
     */
    private Map<Long, Double> cotangentEdgeWeights() {
        Map<Long, Double> weightByEdge = new HashMap<>();
        Vector3f toFirst = new Vector3f();
        Vector3f toSecond = new Vector3f();
        Vector3f perpendicular = new Vector3f();
        for (int[] triangle : triangles) {
            for (int corner = 0; corner < triangle.length; corner++) {
                int first = triangle[(corner + 1) % triangle.length];
                int second = triangle[(corner + 2) % triangle.length];
                Vector3f apex = positions[triangle[corner]];
                toFirst.set(positions[first]).sub(apex);
                toSecond.set(positions[second]).sub(apex);
                double twiceArea = toFirst.cross(toSecond, perpendicular).length();
                double cotangent = twiceArea == 0.0 ? 0.0 : toFirst.dot(toSecond) / twiceArea;
                int low = Math.min(first, second);
                int high = Math.max(first, second);
                weightByEdge.merge(((long) low << KEY_ROW_SHIFT) | high, cotangent, Double::sum);
            }
        }
        weightByEdge.replaceAll((key, weight) -> Math.max(MINIMUM_HARMONIC_WEIGHT, weight));
        return weightByEdge;
    }

    // in solveInterior(), replace cotangentEdgeWeights() with:
    private Map<Long, Double> uniformEdgeWeights() {
        Map<Long, Double> weightByEdge = new HashMap<>();
        for (int[] triangle : triangles) {
            for (int corner = 0; corner < triangle.length; corner++) {
                int low = Math.min(triangle[(corner + 1) % 3], triangle[(corner + 2) % 3]);
                int high = Math.max(triangle[(corner + 1) % 3], triangle[(corner + 2) % 3]);
                weightByEdge.put(((long) low << KEY_ROW_SHIFT) | high, 1.0);
            }
        }
        return weightByEdge;
    }

    /**
     * The number of triangles that fold over in the map — zero area, or the
     * opposite winding from the majority. Tutte's theorem promises this is zero for
     * a convex boundary and positive weights, so a non-zero count is a broken map,
     * not a tolerance to accept.
     *
     * @return count of folded or degenerate triangles
     */
    public int flippedTriangleCount() {
        double floor = 1.0e-12 * width * height;
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
            if (Math.abs(area) <= floor || (area > 0.0) != referencePositive) {
                flipped++;
            }
        }
        return flipped;
    }

    /**
     * Asserts the map is bijective — no folded or degenerate triangles — the
     * property every downstream client relies on.
     *
     * @throws IllegalStateException when any triangle folds over
     */
    public void assertFoldFree() {
        int flipped = flippedTriangleCount();
        if (flipped == 0) {
            return;
        }
        throw new IllegalStateException("Tutte map is not bijective: " + flipped + " of "
                + triangles.length + " triangles fold over on the " + width + "x" + height
                + " rectangle; first is " + describeFirstFold());
    }

    /**
     * The first folded triangle as its dense indices, rectangle coordinates and
     * boundary flags — a zero-area triangle with three boundary corners is an
     * un-subdivided chord, while a reversed one with an interior corner is a broken
     * solve.
     *
     * @return a one-line description, or {@code "none"} when no triangle folds
     */
    private String describeFirstFold() {
        double referenceArea = 0.0;
        for (int[] triangle : triangles) {
            if (Math.abs(signedArea(triangle)) > Math.abs(referenceArea)) {
                referenceArea = signedArea(triangle);
            }
        }
        double floor = 1.0e-12 * width * height;

        boolean referencePositive = referenceArea > 0.0;
        for (int[] triangle : triangles) {
            double area = signedArea(triangle);
            if (Math.abs(area) > floor && (area > 0.0) == referencePositive) {
                continue;
            }
            StringBuilder description = new StringBuilder(area == 0.0 ? "degenerate" : "reversed");
            description.append(" area=").append(area);
            for (int corner = 0; corner < triangle.length; corner++) {
                int dense = triangle[corner];
                description.append(" v").append(vertexLabel[dense])
                        .append(onBoundary[dense] ? "(boundary)" : "(interior)")
                        .append("=(").append(rectangleU[dense]).append(", ")
                        .append(rectangleV[dense]).append(')');
            }
            return description.toString();
        }
        return "none";
    }

    /**
     * Twice the signed area of a triangle in the rectangle, positive for
     * counter-clockwise winding.
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
