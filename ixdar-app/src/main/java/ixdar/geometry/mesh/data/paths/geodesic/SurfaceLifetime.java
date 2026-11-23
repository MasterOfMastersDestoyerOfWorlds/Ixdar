package ixdar.geometry.mesh.data.paths.geodesic;

import java.util.Arrays;
import java.util.TreeMap;

import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import ixdar.numeric.graph.UnionFind;
import ixdar.numeric.sparse.DirectSolver;
import ixdar.numeric.sparse.DirectSolver.CholeskyHandle;
import ixdar.numeric.sparse.matrix.NormalMatrix;
import ixdar.numeric.sparse.ordering.OrderingMethod;

/**
 * How long a random walker over a surface lasts before it meets an absorbing vertex: the solution
 * of the Poisson problem with a unit source and zero at the absorbing vertices.
 */
public final class SurfaceLifetime {

    public static final int CORNERS = 3;

    public static final int COORDINATES = 3;

    public static final double ROUNDING_SHARE_OF_A_FLOWS_SUPPLY = 1e-12;

    /** Surface the walker moves over. */
    public final MeshTopology mesh;

    /** Corner vertex ids of the surface's triangles, three per triangle. */
    public final int[] triangles;

    /** Face id each triangle came from. */
    public final int[] faceOfTriangle;

    /** Dense index of each vertex id, -1 for an id the surface does not use. */
    public final int[] activeOfVertexId;

    /** Cotangent of each triangle corner's angle, three per triangle. */
    public final double[] cornerCotangent;

    /** Twice each triangle's area. */
    public final double[] doubleArea;

    /** Area each vertex stands for, by vertex id: a third of its triangles'. */
    public final double[] massByVertexId;

    /** The cotangent stiffness over the active vertices. */
    public final NormalMatrix stiffness;

    /**
     * Measures a surface and assembles its stiffness.
     *
     * @param mesh surface of any polygon sizes; its faces are split into triangles by fans
     */
    public SurfaceLifetime(MeshTopology mesh) {
        this.mesh = mesh;
        faceOfTriangle = new int[mesh.triangleCount()];
        triangles = mesh.triangulate(TriangulationRule.FAN, faceOfTriangle);
        PackedPoints positions = SemanticPatchDecomposer.positionsById(mesh);
        int vertexCount = mesh.vertexCount();
        activeOfVertexId = new int[mesh.vertexIdBound()];
        Arrays.fill(activeOfVertexId, -1);
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            activeOfVertexId[mesh.vertexIdAt(activeVertex)] = activeVertex;
        }
        int triangleCount = triangles.length / CORNERS;
        cornerCotangent = new double[triangles.length];
        doubleArea = new double[triangleCount];
        massByVertexId = new double[mesh.vertexIdBound()];
        double[] diagonal = new double[vertexCount];
        TreeMap<Long, Double> upper = new TreeMap<>();
        double[] side = new double[CORNERS * COORDINATES];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            for (int corner = 0; corner < CORNERS; corner++) {
                int from = triangles[CORNERS * triangle + corner];
                int to = triangles[CORNERS * triangle + (corner + 1) % CORNERS];
                for (int axis = 0; axis < COORDINATES; axis++) {
                    side[COORDINATES * corner + axis] = positions.get(to, axis) - positions.get(from, axis);
                }
            }
            double normalX = side[1] * side[COORDINATES + 2] - side[2] * side[COORDINATES + 1];
            double normalY = side[2] * side[COORDINATES] - side[0] * side[COORDINATES + 2];
            double normalZ = side[0] * side[COORDINATES + 1] - side[1] * side[COORDINATES];
            doubleArea[triangle] = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
            if (doubleArea[triangle] <= 0) {
                continue;
            }
            for (int corner = 0; corner < CORNERS; corner++) {
                int arriving = (corner + 2) % CORNERS;
                double along = 0;
                for (int axis = 0; axis < COORDINATES; axis++) {
                    along += side[COORDINATES * corner + axis] * side[COORDINATES * arriving + axis];
                }
                double cotangent = -along / doubleArea[triangle];
                cornerCotangent[CORNERS * triangle + corner] = cotangent;
                int first = activeOfVertexId[triangles[CORNERS * triangle + (corner + 1) % CORNERS]];
                int second = activeOfVertexId[triangles[CORNERS * triangle + arriving]];
                diagonal[first] += cotangent / 2;
                diagonal[second] += cotangent / 2;
                upper.merge((long) Math.min(first, second) << NormalMatrix.KEY_ROW_SHIFT | Math.max(first, second),
                        -cotangent / 2, Double::sum);
                massByVertexId[triangles[CORNERS * triangle + corner]] += doubleArea[triangle] / (2 * CORNERS);
            }
        }
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            diagonal[activeVertex] += massByVertexId[mesh.vertexIdAt(activeVertex)] > 0 ? 0 : 1;
        }
        stiffness = new NormalMatrix(diagonal, upper, new double[vertexCount]);
    }

    /**
     * The walker's expected lifetime from every vertex, in units of area.
     *
     * @param absorbingByVertexId the vertices a walker ends at, by vertex id; every connected
     *                            piece of the surface must hold one
     * @return the lifetime by vertex id, zero at the absorbing vertices
     */
    public double[] lifetime(boolean[] absorbingByVertexId) {
        int vertexCount = mesh.vertexCount();
        boolean[] fixed = new boolean[vertexCount];
        double[] source = new double[vertexCount];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            fixed[activeVertex] = absorbingByVertexId[mesh.vertexIdAt(activeVertex)];
            source[activeVertex] = massByVertexId[mesh.vertexIdAt(activeVertex)];
        }
        CholeskyHandle factor = DirectSolver.factorize(stiffness, fixed, OrderingMethod.AMD);
        double[] solved = new double[vertexCount];
        DirectSolver.solveCompact(factor, stiffness, source, solved, solved, fixed);
        DirectSolver.releaseHandle(factor);
        double[] lifetime = new double[absorbingByVertexId.length];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            lifetime[mesh.vertexIdAt(activeVertex)] = solved[activeVertex];
        }
        return lifetime;
    }

    /**
     * The walker's expected lifetime where some vertices block it: a walker turns back at a
     * blocking vertex and ends only at an absorbing one. A part of the surface the blocking
     * vertices seal off from every absorbing vertex has no lifetime and counts as blocked.
     *
     * @param absorbingByVertexId the vertices a walker ends at, by vertex id
     * @param blockingByVertexId  the vertices no walker passes, by vertex id; made true for the
     *                            sealed parts too
     * @param opennessByVertexId  the share of walkers each vertex lets by, from 0 to 1, by vertex
     *                            id; null where every vertex that does not block lets all by
     * @param startsByVertexId    how many walkers start at each vertex, by vertex id; null where
     *                            they start evenly over the surface, each vertex's area of them
     * @return the lifetime by vertex id, zero at the absorbing and the blocked vertices
     */
    public double[] lifetimeRound(boolean[] absorbingByVertexId, boolean[] blockingByVertexId,
            double[] opennessByVertexId, double[] startsByVertexId) {
        return fadingRound(absorbingByVertexId, blockingByVertexId, opennessByVertexId, startsByVertexId, 0, null);
    }

    /**
     * {@link #lifetimeRound} for walkers that fade as they walk: each loses a share of what is
     * left of it at a steady rate, so one that walks long ends counting for little.
     *
     * @param absorbingByVertexId the vertices a walker ends at, by vertex id
     * @param blockingByVertexId  the vertices no walker passes, by vertex id; made true for the
     *                            sealed parts too
     * @param opennessByVertexId  the share of walkers each vertex lets by, from 0 to 1, by vertex
     *                            id; null where every vertex that does not block lets all by
     * @param startsByVertexId    how many walkers start at each vertex, by vertex id; null where
     *                            they start evenly over the surface, each vertex's area of them
     * @param fadeRate            the share a walker loses in a unit of time; zero for none
     * @param swallowByVertexId   how fast each vertex swallows the walkers standing on it, by
     *                            vertex id, in the stiffness's units; null where none does
     * @return by vertex id, what is left on ending of the walkers passing a vertex, summed over time
     */
    public double[] fadingRound(boolean[] absorbingByVertexId, boolean[] blockingByVertexId,
            double[] opennessByVertexId, double[] startsByVertexId, double fadeRate, double[] swallowByVertexId) {
        int vertexCount = mesh.vertexCount();
        int triangleCount = triangles.length / CORNERS;
        int[] piece = UnionFind.singletons(mesh.vertexIdBound());
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            for (int corner = 1; corner < CORNERS && !blocked(triangle, blockingByVertexId); corner++) {
                UnionFind.union(piece, triangles[CORNERS * triangle], triangles[CORNERS * triangle + corner]);
            }
        }
        boolean[] drains = new boolean[piece.length];
        for (int pass = 0; pass < 2; pass++) {
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                int vertex = mesh.vertexIdAt(activeVertex);
                int root = UnionFind.find(piece, vertex);
                drains[root] |= pass == 0 && !blockingByVertexId[vertex] && (absorbingByVertexId[vertex]
                        || swallowByVertexId != null && swallowByVertexId[vertex] > 0);
                blockingByVertexId[vertex] |= pass == 1 && !drains[root];
            }
        }
        boolean[] fixed = new boolean[vertexCount];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertex = mesh.vertexIdAt(activeVertex);
            fixed[activeVertex] = absorbingByVertexId[vertex] || blockingByVertexId[vertex];
        }
        double[] diagonal = new double[vertexCount];
        double[] source = new double[vertexCount];
        TreeMap<Long, Double> upper = new TreeMap<>();
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            if (blocked(triangle, blockingByVertexId)) {
                continue;
            }
            for (int corner = 0; corner < CORNERS; corner++) {
                int firstId = triangles[CORNERS * triangle + (corner + 1) % CORNERS];
                int secondId = triangles[CORNERS * triangle + (corner + 2) % CORNERS];
                int first = activeOfVertexId[firstId];
                int second = activeOfVertexId[secondId];
                double cotangent = cornerCotangent[CORNERS * triangle + corner] * (opennessByVertexId == null ? 1
                        : Math.min(opennessByVertexId[triangles[CORNERS * triangle + corner]],
                                Math.min(opennessByVertexId[firstId], opennessByVertexId[secondId])));
                diagonal[first] += cotangent / 2;
                diagonal[second] += cotangent / 2;
                upper.merge((long) Math.min(first, second) << NormalMatrix.KEY_ROW_SHIFT | Math.max(first, second),
                        -cotangent / 2, Double::sum);
                source[activeOfVertexId[triangles[CORNERS * triangle + corner]]] += doubleArea[triangle]
                        / (2 * CORNERS);
            }
        }
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            diagonal[activeVertex] += fixed[activeVertex] ? 1 : fadeRate * source[activeVertex]
                    + (swallowByVertexId == null ? 0 : swallowByVertexId[mesh.vertexIdAt(activeVertex)]);
            source[activeVertex] = startsByVertexId == null ? source[activeVertex]
                    : startsByVertexId[mesh.vertexIdAt(activeVertex)];
        }
        NormalMatrix round = new NormalMatrix(diagonal, upper, new double[vertexCount]);
        CholeskyHandle factor = DirectSolver.factorize(round, fixed, OrderingMethod.AMD);
        double[] solved = new double[vertexCount];
        DirectSolver.solveCompact(factor, round, source, solved, solved, fixed);
        DirectSolver.releaseHandle(factor);
        double[] lifetime = new double[absorbingByVertexId.length];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            lifetime[mesh.vertexIdAt(activeVertex)] = solved[activeVertex];
        }
        return lifetime;
    }

    /**
     * The most of a region's air that reaches a stretch of its border when every face supplies its
     * area and an edge lets by at most its length times a width's worth, half of that for each end
     * that is a wall.
     *
     * @param regionOfFace  the region of every face, by face id
     * @param region        the region whose air flows
     * @param borderEdge    the edges no air crosses, by edge id
     * @param drainFaces    the face inside the region beside each border edge of the stretch
     * @param drainLengths  the length of each of those border edges
     * @param wallVertex    the vertices that close half an edge, by vertex id; null for none
     * @param carriedByUnit the air a unit of length lets by
     * @return the air reaching the stretch
     */
    public double reaching(int[] regionOfFace, int region, boolean[] borderEdge, int[] drainFaces,
            double[] drainLengths, boolean[] wallVertex, double carriedByUnit) {
        int[] nodeOfFace = new int[mesh.faceIdBound()];
        Arrays.fill(nodeOfFace, -1);
        int nodes = 0;
        for (int active = 0; active < mesh.faceCount(); active++) {
            if (regionOfFace[mesh.faceIdAt(active)] == region) {
                nodeOfFace[mesh.faceIdAt(active)] = nodes++;
            }
        }
        int source = nodes;
        int sink = nodes + 1;
        double[] supply = new double[nodes];
        for (int triangle = 0; triangle < doubleArea.length; triangle++) {
            if (nodeOfFace[faceOfTriangle[triangle]] >= 0) {
                supply[nodeOfFace[faceOfTriangle[triangle]]] += doubleArea[triangle] / 2;
            }
        }
        int arcBound = nodes + drainFaces.length + mesh.edgeCount();
        int[] head = new int[nodes + 2];
        Arrays.fill(head, -1);
        int[] next = new int[2 * arcBound];
        int[] target = new int[2 * arcBound];
        double[] room = new double[2 * arcBound];
        int arcs = 0;
        double total = 0;
        PackedPoints positions = SemanticPatchDecomposer.positionsById(mesh);
        for (int pass = 0; pass < CORNERS; pass++) {
            int count = pass == 0 ? nodes : pass == 1 ? drainFaces.length : mesh.edgeCount();
            for (int item = 0; item < count; item++) {
                int tail = source;
                int tip = item;
                double forward = 0;
                double back = 0;
                if (pass == 0) {
                    forward = supply[item];
                    total += forward;
                } else if (pass == 1) {
                    tail = nodeOfFace[drainFaces[item]];
                    tip = sink;
                    forward = drainLengths[item] * carriedByUnit;
                } else {
                    int edge = mesh.edgeIdAt(item);
                    int one = mesh.edgeFace(edge, 0);
                    int other = mesh.edgeFace(edge, 1);
                    if (borderEdge[edge] || one < 0 || other < 0 || nodeOfFace[one] < 0 || nodeOfFace[other] < 0) {
                        continue;
                    }
                    int from = mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge));
                    int to = mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge));
                    double length = 0;
                    for (int axis = 0; axis < COORDINATES; axis++) {
                        double span = positions.get(to, axis) - positions.get(from, axis);
                        length += span * span;
                    }
                    int openEnds = (wallVertex != null && wallVertex[from] ? 0 : 1)
                            + (wallVertex != null && wallVertex[to] ? 0 : 1);
                    tail = nodeOfFace[one];
                    tip = nodeOfFace[other];
                    forward = Math.sqrt(length) * carriedByUnit * openEnds / 2;
                    back = forward;
                }
                target[2 * arcs] = tip;
                room[2 * arcs] = forward;
                next[2 * arcs] = head[tail];
                head[tail] = 2 * arcs;
                target[2 * arcs + 1] = tail;
                room[2 * arcs + 1] = back;
                next[2 * arcs + 1] = head[tip];
                head[tip] = 2 * arcs + 1;
                arcs++;
            }
        }
        double flow = 0;
        double none = ROUNDING_SHARE_OF_A_FLOWS_SUPPLY * total;
        int[] level = new int[nodes + 2];
        int[] queue = new int[nodes + 2];
        int[] at = new int[nodes + 2];
        int[] pathArc = new int[nodes + 2];
        while (true) {
            Arrays.fill(level, -1);
            level[source] = 0;
            int queued = 0;
            queue[queued++] = source;
            for (int read = 0; read < queued; read++) {
                for (int arc = head[queue[read]]; arc >= 0; arc = next[arc]) {
                    if (room[arc] > none && level[target[arc]] < 0) {
                        level[target[arc]] = level[queue[read]] + 1;
                        queue[queued++] = target[arc];
                    }
                }
            }
            if (level[sink] < 0) {
                return flow;
            }
            System.arraycopy(head, 0, at, 0, head.length);
            int depth = 0;
            int node = source;
            while (node != source || at[source] >= 0) {
                if (node == sink) {
                    double pushed = Double.POSITIVE_INFINITY;
                    for (int step = 0; step < depth; step++) {
                        pushed = Math.min(pushed, room[pathArc[step]]);
                    }
                    for (int step = 0; step < depth; step++) {
                        room[pathArc[step]] -= pushed;
                        room[pathArc[step] ^ 1] += pushed;
                    }
                    flow += pushed;
                    depth = 0;
                    node = source;
                    continue;
                }
                while (at[node] >= 0 && !(room[at[node]] > none && level[target[at[node]]] == level[node] + 1)) {
                    at[node] = next[at[node]];
                }
                if (at[node] >= 0) {
                    pathArc[depth++] = at[node];
                    node = target[at[node]];
                } else if (depth > 0) {
                    level[node] = -1;
                    depth--;
                    node = target[pathArc[depth] ^ 1];
                }
            }
        }
    }

    /**
     * Whether a triangle has a corner no walker passes.
     *
     * @param triangle           the triangle
     * @param blockingByVertexId the vertices no walker passes, by vertex id
     * @return true when one of its corners blocks
     */
    public boolean blocked(int triangle, boolean[] blockingByVertexId) {
        return blockingByVertexId[triangles[CORNERS * triangle]] || blockingByVertexId[triangles[CORNERS * triangle + 1]]
                || blockingByVertexId[triangles[CORNERS * triangle + 2]];
    }

    /**
     * How steeply a lifetime falls over each face: the mean of its squared gradient. Beside an
     * absorbing wall this is the rate the total lifetime grows as the wall gives the face up.
     *
     * @param lifetimeByVertexId a lifetime from {@link #lifetime}
     * @return the mean squared gradient by face id
     */
    public double[] steepness(double[] lifetimeByVertexId) {
        double[] energy = new double[mesh.faceIdBound()];
        double[] area = new double[mesh.faceIdBound()];
        for (int triangle = 0; triangle < doubleArea.length; triangle++) {
            int face = faceOfTriangle[triangle];
            area[face] += doubleArea[triangle] / 2;
            for (int corner = 0; corner < CORNERS; corner++) {
                double fall = lifetimeByVertexId[triangles[CORNERS * triangle + (corner + 1) % CORNERS]]
                        - lifetimeByVertexId[triangles[CORNERS * triangle + (corner + 2) % CORNERS]];
                energy[face] += cornerCotangent[CORNERS * triangle + corner] * fall * fall / 2;
            }
        }
        for (int face = 0; face < energy.length; face++) {
            energy[face] = area[face] > 0 ? energy[face] / area[face] : 0;
        }
        return energy;
    }

    /**
     * The most crowded face each face's air has come through: the steepest face on the way up the
     * lifetime's slope from it, itself included. A face with a blocking corner has none.
     *
     * @param lifetimeByVertexId a lifetime from {@link #lifetimeRound}
     * @param steepByFaceId      its {@link #steepness}
     * @param borderEdge         the edges no air crosses, by edge id
     * @param blockingByVertexId the vertices no walker passes, by vertex id
     * @return the root of the steepest mean squared gradient upstream, by face id
     */
    public double[] worstUpstream(double[] lifetimeByVertexId, double[] steepByFaceId, boolean[] borderEdge,
            boolean[] blockingByVertexId) {
        int bound = mesh.faceIdBound();
        double[] height = new double[bound];
        int[] corners = new int[bound];
        boolean[] shut = new boolean[bound];
        for (int triangle = 0; triangle < doubleArea.length; triangle++) {
            int face = faceOfTriangle[triangle];
            shut[face] |= blocked(triangle, blockingByVertexId);
            for (int corner = 0; corner < CORNERS; corner++) {
                height[face] += lifetimeByVertexId[triangles[CORNERS * triangle + corner]];
                corners[face]++;
            }
        }
        Integer[] order = new Integer[mesh.faceCount()];
        for (int active = 0; active < order.length; active++) {
            order[active] = mesh.faceIdAt(active);
            height[order[active]] /= Math.max(1, corners[order[active]]);
        }
        Arrays.sort(order, (one, other) -> Double.compare(height[other], height[one]));
        double[] worst = new double[bound];
        // Highest first, so the face upstream of a face is settled before it.
        for (int face : order) {
            if (shut[face]) {
                continue;
            }
            int upstream = -1;
            for (int at = 0; at < mesh.faceEdgeCount(face); at++) {
                int edge = mesh.faceEdgeAt(face, at);
                int other = mesh.edgeFace(edge, 0) == face ? mesh.edgeFace(edge, 1) : mesh.edgeFace(edge, 0);
                if (!borderEdge[edge] && other >= 0 && !shut[other]
                        && height[other] > height[upstream < 0 ? face : upstream]) {
                    upstream = other;
                }
            }
            worst[face] = Math.max(Math.sqrt(steepByFaceId[face]), upstream < 0 ? 0 : worst[upstream]);
        }
        return worst;
    }

    /**
     * How long the walkers passing each face have been walking, for a face beside an absorbing
     * vertex the mean age of those ending there. A face with a blocking corner has none.
     *
     * @param absorbingByVertexId the vertices a walker ends at, by vertex id
     * @param blockingByVertexId  the vertices no walker passes, by vertex id
     * @param opennessByVertexId  the share of walkers each vertex lets by, by vertex id; null for all
     * @param lifetimeByVertexId  the lifetime {@link #lifetimeRound} gives for the same vertices
     * @param steepByFaceId       its {@link #steepness}
     * @return the age by face id
     */
    public double[] lateness(boolean[] absorbingByVertexId, boolean[] blockingByVertexId,
            double[] opennessByVertexId, double[] lifetimeByVertexId, double[] steepByFaceId) {
        // The walkers standing at a vertex are its lifetime's worth; each adds a unit of age a unit of time.
        double[] standing = new double[lifetimeByVertexId.length];
        for (int vertex = 0; vertex < standing.length; vertex++) {
            standing[vertex] = massByVertexId[vertex] * lifetimeByVertexId[vertex];
        }
        double[] aged = steepness(lifetimeRound(absorbingByVertexId, blockingByVertexId, opennessByVertexId, standing));
        boolean[] shut = new boolean[mesh.faceIdBound()];
        for (int triangle = 0; triangle < doubleArea.length; triangle++) {
            shut[faceOfTriangle[triangle]] |= blocked(triangle, blockingByVertexId);
        }
        double[] late = new double[shut.length];
        for (int face = 0; face < late.length; face++) {
            late[face] = shut[face] || steepByFaceId[face] <= 0 ? 0 : Math.sqrt(aged[face] / steepByFaceId[face]);
        }
        return late;
    }

    /**
     * The way the walkers drift over each face: down the lifetime's slope, as long as the slope is
     * steep. A face with a blocking corner has no drift.
     *
     * @param lifetimeByVertexId a lifetime from {@link #lifetimeRound}
     * @param blockingByVertexId the vertices no walker passes, by vertex id
     * @return the drift's three coordinates for every face, by face id
     */
    public float[] drift(double[] lifetimeByVertexId, boolean[] blockingByVertexId) {
        PackedPoints positions = SemanticPatchDecomposer.positionsById(mesh);
        double[] sum = new double[COORDINATES * mesh.faceIdBound()];
        double[] area = new double[mesh.faceIdBound()];
        double[][] side = new double[CORNERS][COORDINATES];
        for (int triangle = 0; triangle < doubleArea.length; triangle++) {
            if (blocked(triangle, blockingByVertexId) || doubleArea[triangle] <= 0) {
                continue;
            }
            // side[corner] is the edge facing the corner; the slope is the sum over corners of the
            // corner's lifetime times that edge turned a quarter round the normal, over twice the area.
            for (int corner = 0; corner < CORNERS; corner++) {
                int from = triangles[CORNERS * triangle + (corner + 1) % CORNERS];
                int to = triangles[CORNERS * triangle + (corner + 2) % CORNERS];
                for (int axis = 0; axis < COORDINATES; axis++) {
                    side[corner][axis] = positions.get(to, axis) - positions.get(from, axis);
                }
            }
            double[] normal = new double[COORDINATES];
            for (int axis = 0; axis < COORDINATES; axis++) {
                int next = (axis + 1) % COORDINATES;
                int last = (axis + 2) % COORDINATES;
                normal[axis] = (side[2][next] * -side[1][last] - side[2][last] * -side[1][next]) / doubleArea[triangle];
            }
            int face = faceOfTriangle[triangle];
            area[face] += doubleArea[triangle] / 2;
            for (int corner = 0; corner < CORNERS; corner++) {
                double life = lifetimeByVertexId[triangles[CORNERS * triangle + corner]];
                for (int axis = 0; axis < COORDINATES; axis++) {
                    int next = (axis + 1) % COORDINATES;
                    int last = (axis + 2) % COORDINATES;
                    sum[COORDINATES * face + axis] -= life
                            * (normal[next] * side[corner][last] - normal[last] * side[corner][next]) / 2;
                }
            }
        }
        float[] drift = new float[sum.length];
        for (int at = 0; at < sum.length; at++) {
            drift[at] = area[at / COORDINATES] > 0 ? (float) (sum[at] / area[at / COORDINATES]) : 0;
        }
        return drift;
    }
}
