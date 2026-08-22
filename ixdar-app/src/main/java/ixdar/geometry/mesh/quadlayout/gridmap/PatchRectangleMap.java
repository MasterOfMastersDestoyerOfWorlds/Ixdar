package ixdar.geometry.mesh.quadlayout.gridmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.EdgeKey;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
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
     * Bit per rectangle side each boundary vertex was pinned to, by dense index;
     * zero for an interior vertex. A corner carries both of its sides, so two
     * vertices share a side exactly when their masks intersect.
     */
    public final int[] sideMaskByVertex;

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
        this.sideMaskByVertex = new int[positions.length];
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
     * Places the boundary on the rectangle and solves for the interior vertices
     * with cotangent weights, falling back to uniform weights when obtuse
     * cotangents leave the system indefinite (RPP17 §6).
     *
     * @throws IllegalStateException when a side has no quantized length
     * @return this, solved
     */
    public PatchRectangleMap build() {
        placeBoundary();
        try {
            solveInterior(cotangentEdgeWeights());
        } catch (IllegalStateException notPositiveDefinite) {
            solveUniform();
        }
        return this;
    }

    /**
     * Re-solves the interior with uniform weights, the RPP17 §6 fallback when the
     * cotangent solve folds: uniform weights are a true convex combination, so
     * Tutte's theorem makes the result fold-free unconditionally.
     */
    public void solveUniform() {
        solveInterior(uniformEdgeWeights());
    }

    /**
     * Pins each boundary vertex to a rectangle edge one arc at a time: arc endpoints
     * at their integer offsets, the vertices between them spaced evenly, so adjacent
     * patches place a shared arc identically.
     *
     * <p>
     * See also: LCBK19 Section 6.2, which leaves the intra-arc distribution open
     *
     * @throws IllegalStateException when a side has no length, so its vertices
     *                               cannot be distributed
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
                throw new IllegalStateException("patch side " + side + " spans " + sideLength
                        + " quads; a rectangle side must be at least one quad");
            }
            int nextSide = (side + 1) % EmbeddedPatch.SIDES;
            for (int arcIndex = 0; arcIndex < breakLoopIndex.length - 1; arcIndex++) {
                int startIndex = breakLoopIndex[arcIndex];
                int endIndex = breakLoopIndex[arcIndex + 1];
                int count = walkLength(startIndex, endIndex, loopLength);
                double arcStart = breakOffset[arcIndex] / (double) sideLength;
                double arcEnd = breakOffset[arcIndex + 1] / (double) sideLength;
                int index = startIndex;
                for (int step = 0; step < count; step++) {
                    double fraction = step / (count - 1.0);
                    double along = arcStart + fraction * (arcEnd - arcStart);
                    int dense = boundaryLoop[index];
                    rectangleU[dense] = cornerX[side] + along * (cornerX[nextSide] - cornerX[side]);
                    rectangleV[dense] = cornerY[side] + along * (cornerY[nextSide] - cornerY[side]);
                    onBoundary[dense] = true;
                    sideMaskByVertex[dense] |= 1 << side;
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
     * @param weightByEdge weight per undirected edge, keyed
     *                     {@code (min << 32) | max}
     * @throws IllegalStateException when the system is not positive definite
     */
    private void solveInterior(Map<Long, Double> weightByEdge) {
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
        for (Map.Entry<Long, Double> edge : weightByEdge.entrySet()) {
            long key = edge.getKey();
            double weight = edge.getValue();
            diagonal[EdgeKey.minVertex(key)] += weight;
            diagonal[EdgeKey.maxVertex(key)] += weight;
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
     * angle, summed over both incident triangles. Can go negative on obtuse
     * configurations, the case {@link #uniformEdgeWeights()} covers.
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
                weightByEdge.merge(EdgeKey.undirected(first, second), cotangent, Double::sum);
            }
        }
        return weightByEdge;
    }

    /**
     * Weight one on every graph edge, the convex combination Tutte's theorem
     * guarantees fold-free on a 3-connected region with a convex boundary.
     *
     * @return weight per undirected edge, keyed {@code (min << 32) | max}
     */
    private Map<Long, Double> uniformEdgeWeights() {
        Map<Long, Double> weightByEdge = new HashMap<>();
        for (int[] triangle : triangles) {
            for (int corner = 0; corner < triangle.length; corner++) {
                int first = triangle[(corner + 1) % triangle.length];
                int second = triangle[(corner + 2) % triangle.length];
                weightByEdge.put(EdgeKey.undirected(first, second), 1.0);
            }
        }
        return weightByEdge;
    }

    /**
     * The signed area of the map's largest triangle, whose sign is the majority
     * orientation.
     *
     * @return the reference signed area measure
     */
    private double referenceArea() {
        double reference = 0.0;
        for (int[] triangle : triangles) {
            double area = signedArea(triangle);
            if (Math.abs(area) > Math.abs(reference)) {
                reference = area;
            }
        }
        return reference;
    }

    /**
     * Whether the solved map winds counter-clockwise, taken from its largest
     * triangle. {@link #assertFoldFree()} makes this one winding shared by every
     * triangle, so it is a property of the patch rather than of any triangle — and
     * unlike a per-triangle test it survives a sliver being translated into global
     * grid coordinates.
     *
     * @return true when the map preserves orientation
     */
    public boolean isCounterClockwise() {
        return referenceArea() > 0.0;
    }

    /**
     * The number of triangles that fold over in the map: exactly zero area, or the
     * opposite winding from the majority. A thin triangle is not a fold — Tutte's
     * theorem bounds no area from below, and a region whose modulus does not match
     * its rectangle compresses without inverting anything.
     *
     * @return count of inverted or exactly degenerate triangles
     */
    public int flippedTriangleCount() {
        boolean referencePositive = referenceArea() > 0.0;
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
     * Interior vertices whose every neighbour is a boundary vertex of one rectangle
     * side. Such a vertex is a convex combination of points sharing a coordinate, so
     * no weights can lift it off that side's line.
     *
     * @return count of interior vertices pinned onto a side by their neighbourhood
     */
    public int collinearNeighbourhoodCount() {
        List<Set<Integer>> neighbours = new ArrayList<>(positions.length);
        for (int dense = 0; dense < positions.length; dense++) {
            neighbours.add(new HashSet<>());
        }
        for (int[] triangle : triangles) {
            for (int corner = 0; corner < triangle.length; corner++) {
                int from = triangle[corner];
                int to = triangle[(corner + 1) % triangle.length];
                neighbours.get(from).add(to);
                neighbours.get(to).add(from);
            }
        }
        int collapsed = 0;
        for (int dense = 0; dense < positions.length; dense++) {
            if (onBoundary[dense] || neighbours.get(dense).isEmpty()) {
                continue;
            }
            int shared = (1 << EmbeddedPatch.SIDES) - 1;
            for (int neighbour : neighbours.get(dense)) {
                shared &= sideMaskByVertex[neighbour];
            }
            collapsed += shared != 0 ? 1 : 0;
        }
        return collapsed;
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
        boolean referencePositive = referenceArea() > 0.0;
        int degenerate = 0;
        int inverted = 0;
        for (int[] triangle : triangles) {
            double area = signedArea(triangle);
            if (area == 0.0) {
                degenerate++;
            } else if ((area > 0.0) != referencePositive) {
                inverted++;
            }
        }
        throw new IllegalStateException("Tutte map is not bijective: " + flipped + " of "
                + triangles.length + " triangles fold over on the " + width + "x" + height
                + " rectangle — " + inverted + " wound against the majority, " + degenerate
                + " of exactly zero area, " + collinearNeighbourhoodCount()
                + " interior vertices pinned onto a side by their whole neighbourhood;"
                + " majority winding is "
                + (referencePositive ? "counter-clockwise" : "clockwise") + "; first is "
                + describeFirstFold());
    }

    /**
     * The first folded triangle, labelled by which test caught it, with its
     * rectangle coordinates and boundary flags. A degenerate triangle with three
     * boundary corners is an un-subdivided chord; an inverted one with an interior
     * corner is a broken solve.
     *
     * @return a one-line description, or {@code "none"} when no triangle folds
     */
    private String describeFirstFold() {
        boolean referencePositive = referenceArea() > 0.0;
        for (int[] triangle : triangles) {
            double area = signedArea(triangle);
            boolean degenerate = area == 0.0;
            if (!degenerate && (area > 0.0) == referencePositive) {
                continue;
            }
            StringBuilder description = new StringBuilder(degenerate ? "degenerate" : "inverted");
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
