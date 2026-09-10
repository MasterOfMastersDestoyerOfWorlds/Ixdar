package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.UnionFind;

/**
 * Element counts, edge-connected shells and boundary loops of a mesh, as parallel arrays indexed
 * by shell or by loop, largest first. Read from face corners, since a {@code HalfEdgeMesh} cannot
 * hold the non-manifold edges this counts.
 */
public final class MeshTopologyAnalysis {

    /** Sort-key shift putting the size in the high bits and the discovery index in the low bits. */
    private static final int KEY_SHIFT = 32;

    /** Mask recovering the discovery index from a sort key. */
    private static final long KEY_MASK = 0xffffffffL;

    /** Smallest grid cell the duplicate-position scan uses, however small the tolerance is. */
    private static final float SMALLEST_CELL = 1e-8f;

    /** Distance below which two vertices count as sharing a position; not positive skips the scan. */
    public double duplicateTolerance = 1e-6;

    /** Live vertices of the analysed mesh. */
    public int vertexCount;

    /** Distinct undirected edges the faces run along. */
    public int edgeCount;

    /** Live faces of the analysed mesh. */
    public int faceCount;

    /** Face sides, i.e. corners summed over every face. */
    public int faceSideCount;

    /** Triangles the faces fan into, {@code corners - 2} per face. */
    public int triangleCount;

    /** Edges with exactly one incident face. */
    public int boundaryEdgeCount;

    /** Edges with three or more incident face sides. */
    public int nonManifoldEdgeCount;

    /** Edges two sides of the same shell run the same way along, which no orientation can fix. */
    public int unorientedEdgeCount;

    /** Vertices no face touches. */
    public int isolatedVertexCount;

    /** Boundary vertices with more than one outgoing boundary edge, which no loop walk can pass. */
    public int nonManifoldBoundaryVertexCount;

    /** Boundary edges the loop walk could not place on a closed loop. */
    public int unwalkedBoundaryEdgeCount;

    /** Vertices sharing a position with another vertex, at {@link #duplicateTolerance}. */
    public int duplicatePositionVertexCount;

    /** Position clusters the duplicate scan found, i.e. the vertex count a weld would leave. */
    public int distinctPositionCount;

    /** Edge-connected face components. */
    public int shellCount;

    /** Closed boundary loops across every shell. */
    public int boundaryLoopCount;

    /** Whole-mesh Euler characteristic, vertices minus edges plus faces. */
    public int eulerCharacteristic;

    /** Faces of each shell, largest shell first. */
    public int[] shellFaceCounts = new int[0];

    /** Vertices of each shell; a vertex two shells share is counted in both. */
    public int[] shellVertexCounts = new int[0];

    /** Edges of each shell. */
    public int[] shellEdgeCounts = new int[0];

    /** Boundary edges of each shell. */
    public int[] shellBoundaryEdgeCounts = new int[0];

    /** Closed boundary loops of each shell. */
    public int[] shellBoundaryLoopCounts = new int[0];

    /** Euler characteristic of each shell, from that shell's own vertex, edge and face counts. */
    public int[] shellEulerCharacteristics = new int[0];

    /** Edges of each boundary loop, longest loop first. */
    public int[] loopEdgeCounts = new int[0];

    /** Summed edge length of each boundary loop. */
    public double[] loopPerimeters = new double[0];

    /** Vector-area estimate of each boundary loop, exact when the loop is planar. */
    public double[] loopAreas = new double[0];

    /** Shell index of each boundary loop, into the {@code shell*} arrays. */
    public int[] loopShells = new int[0];

    /** Dense vertex index of every vertex id, or -1 where the id is not live. */
    private int[] denseVertexOfId = new int[0];

    /** Position of each dense vertex, split by axis. */
    private float[] positionX = new float[0];

    /** Position of each dense vertex, split by axis. */
    private float[] positionY = new float[0];

    /** Position of each dense vertex, split by axis. */
    private float[] positionZ = new float[0];

    /** Offset of each face's corners into {@link #faceCorners}, with a trailing total. */
    private int[] faceCornerStart = new int[0];

    /** Dense corner vertices of every face, in winding order. */
    private int[] faceCorners = new int[0];

    /** Edge index of each face side, parallel to {@link #faceCorners}. */
    private int[] edgeOfFaceSide = new int[0];

    /** Incident face sides of each edge. */
    private int[] edgeFaceUses = new int[0];

    /** One incident face per edge. */
    private int[] edgeFirstFace = new int[0];

    /** Face sides that run their edge from its lower dense vertex to its higher one. */
    private int[] edgeForwardUses = new int[0];

    /** Next vertex around the hole from each dense vertex, or -1 when it is not on a boundary. */
    private int[] boundaryNextVertex = new int[0];

    /** Face bounding the boundary edge that leaves each dense vertex, or -1. */
    private int[] boundaryFaceOfVertex = new int[0];

    /** Dense vertices with a second outgoing boundary edge, which stops the loop walk. */
    private boolean[] ambiguousBoundaryVertex = new boolean[0];

    /** Shell index of each face, in largest-first shell order. */
    private int[] shellOfFace = new int[0];

    /** Shell that last counted each dense vertex, offset by one so zero means none. */
    private int[] vertexShellStamp = new int[0];

    /**
     * Fills every count and table from {@code mesh}. Safe to call again on another mesh; each run
     * rebuilds its own scratch.
     *
     * @param mesh mesh to analyse; treated read-only
     */
    public void analyze(MeshTopology mesh) {
        readVertices(mesh);
        readFaces(mesh);
        buildEdges();
        findShells();
        walkBoundaryLoops();
        countDuplicatePositions();
        eulerCharacteristic = vertexCount - edgeCount + faceCount;
    }

    /**
     * Reads positions into dense arrays and records where each vertex id landed.
     *
     * @param mesh mesh being analysed
     */
    private void readVertices(MeshTopology mesh) {
        vertexCount = mesh.vertexCount();
        positionX = new float[vertexCount];
        positionY = new float[vertexCount];
        positionZ = new float[vertexCount];
        int maxVertexId = -1;
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            maxVertexId = Math.max(maxVertexId, mesh.vertexIdAt(activeVertex));
        }
        denseVertexOfId = new int[maxVertexId + 1];
        Arrays.fill(denseVertexOfId, -1);
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            denseVertexOfId[vertexId] = activeVertex;
            mesh.vertexPosition(vertexId, position);
            positionX[activeVertex] = position.x;
            positionY[activeVertex] = position.y;
            positionZ[activeVertex] = position.z;
        }
    }

    /**
     * Flattens every face's corner vertices into one dense array in winding order.
     *
     * @param mesh mesh being analysed
     */
    private void readFaces(MeshTopology mesh) {
        faceCount = mesh.faceCount();
        faceCornerStart = new int[faceCount + 1];
        triangleCount = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int corners = mesh.faceVertexCount(mesh.faceIdAt(activeFace));
            faceCornerStart[activeFace + 1] = faceCornerStart[activeFace] + corners;
            triangleCount += Math.max(0, corners - 2);
        }
        faceSideCount = faceCornerStart[faceCount];
        faceCorners = new int[faceSideCount];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int corners = faceCornerStart[activeFace + 1] - faceCornerStart[activeFace];
            for (int corner = 0; corner < corners; corner++) {
                faceCorners[faceCornerStart[activeFace] + corner] =
                        denseVertexOfId[mesh.faceVertexAt(faceId, corner)];
            }
        }
    }

    /**
     * Assigns an edge index to every distinct vertex pair the faces run along, counting the sides
     * on each edge and recording the directed boundary edge leaving each vertex.
     */
    private void buildEdges() {
        int capacity = 1;
        while (capacity < Math.max(1, faceSideCount) * 2) {
            capacity <<= 1;
        }
        int mask = capacity - 1;
        long[] slotKeys = new long[capacity];
        int[] slotEdge = new int[capacity];
        Arrays.fill(slotKeys, Long.MIN_VALUE);
        edgeOfFaceSide = new int[faceSideCount];
        edgeFaceUses = new int[faceSideCount];
        edgeFirstFace = new int[faceSideCount];
        edgeForwardUses = new int[faceSideCount];
        Arrays.fill(edgeFirstFace, -1);
        edgeCount = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int start = faceCornerStart[activeFace];
            int corners = faceCornerStart[activeFace + 1] - start;
            for (int corner = 0; corner < corners; corner++) {
                int from = faceCorners[start + corner];
                int to = faceCorners[start + (corner + 1) % corners];
                long key = from < to
                        ? ((long) from << KEY_SHIFT) | to
                        : ((long) to << KEY_SHIFT) | from;
                int slot = (int) (MeshMergeByDistance.mixKey(key) & mask);
                while (slotKeys[slot] != Long.MIN_VALUE && slotKeys[slot] != key) {
                    slot = (slot + 1) & mask;
                }
                if (slotKeys[slot] == Long.MIN_VALUE) {
                    slotKeys[slot] = key;
                    slotEdge[slot] = edgeCount++;
                }
                int edge = slotEdge[slot];
                edgeOfFaceSide[start + corner] = edge;
                edgeFaceUses[edge]++;
                if (from < to) {
                    edgeForwardUses[edge]++;
                }
                if (edgeFirstFace[edge] < 0) {
                    edgeFirstFace[edge] = activeFace;
                }
            }
        }
        boundaryEdgeCount = 0;
        nonManifoldEdgeCount = 0;
        unorientedEdgeCount = 0;
        for (int edge = 0; edge < edgeCount; edge++) {
            int uses = edgeFaceUses[edge];
            if (uses == 1) {
                boundaryEdgeCount++;
            } else if (uses > 2) {
                nonManifoldEdgeCount++;
            } else if (edgeForwardUses[edge] != 1) {
                unorientedEdgeCount++;
            }
        }
        markBoundaryTraversal();
    }

    /** Records, per vertex, the boundary edge that leaves it, and where two of them collide. */
    private void markBoundaryTraversal() {
        boundaryNextVertex = new int[vertexCount];
        boundaryFaceOfVertex = new int[vertexCount];
        ambiguousBoundaryVertex = new boolean[vertexCount];
        Arrays.fill(boundaryNextVertex, -1);
        Arrays.fill(boundaryFaceOfVertex, -1);
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int start = faceCornerStart[activeFace];
            int corners = faceCornerStart[activeFace + 1] - start;
            for (int corner = 0; corner < corners; corner++) {
                if (edgeFaceUses[edgeOfFaceSide[start + corner]] != 1) {
                    continue;
                }
                int from = faceCorners[start + corner];
                int to = faceCorners[start + (corner + 1) % corners];
                if (boundaryNextVertex[to] >= 0) {
                    ambiguousBoundaryVertex[to] = true;
                } else {
                    boundaryNextVertex[to] = from;
                    boundaryFaceOfVertex[to] = activeFace;
                }
            }
        }
        nonManifoldBoundaryVertexCount = 0;
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            if (ambiguousBoundaryVertex[activeVertex]) {
                nonManifoldBoundaryVertexCount++;
            }
        }
    }

    /**
     * Unions faces across every shared edge, orders the resulting shells largest first and fills
     * the per-shell vertex, edge and Euler tables.
     */
    private void findShells() {
        int[] parent = UnionFind.singletons(Math.max(1, faceCount));
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int start = faceCornerStart[activeFace];
            int corners = faceCornerStart[activeFace + 1] - start;
            for (int corner = 0; corner < corners; corner++) {
                int edge = edgeOfFaceSide[start + corner];
                if (edgeFaceUses[edge] > 1) {
                    UnionFind.union(parent, activeFace, edgeFirstFace[edge]);
                }
            }
        }
        int[] shellOfRoot = new int[Math.max(1, faceCount)];
        Arrays.fill(shellOfRoot, -1);
        shellOfFace = new int[faceCount];
        int discovered = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int root = UnionFind.find(parent, activeFace);
            if (shellOfRoot[root] < 0) {
                shellOfRoot[root] = discovered++;
            }
            shellOfFace[activeFace] = shellOfRoot[root];
        }
        shellCount = discovered;
        int[] facesPerDiscovered = new int[discovered];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            facesPerDiscovered[shellOfFace[activeFace]]++;
        }
        long[] order = new long[discovered];
        for (int shell = 0; shell < discovered; shell++) {
            order[shell] =
                    ((long) (Integer.MAX_VALUE - facesPerDiscovered[shell]) << KEY_SHIFT) | shell;
        }
        Arrays.sort(order);
        int[] rankOfDiscovered = new int[discovered];
        for (int rank = 0; rank < discovered; rank++) {
            rankOfDiscovered[(int) (order[rank] & KEY_MASK)] = rank;
        }
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            shellOfFace[activeFace] = rankOfDiscovered[shellOfFace[activeFace]];
        }
        shellFaceCounts = new int[discovered];
        shellVertexCounts = new int[discovered];
        shellEdgeCounts = new int[discovered];
        shellBoundaryEdgeCounts = new int[discovered];
        shellBoundaryLoopCounts = new int[discovered];
        shellEulerCharacteristics = new int[discovered];
        vertexShellStamp = new int[vertexCount];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int shell = shellOfFace[activeFace];
            shellFaceCounts[shell]++;
            int start = faceCornerStart[activeFace];
            int corners = faceCornerStart[activeFace + 1] - start;
            for (int corner = 0; corner < corners; corner++) {
                int activeVertex = faceCorners[start + corner];
                if (vertexShellStamp[activeVertex] != shell + 1) {
                    vertexShellStamp[activeVertex] = shell + 1;
                    shellVertexCounts[shell]++;
                }
            }
        }
        isolatedVertexCount = 0;
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            if (vertexShellStamp[activeVertex] == 0) {
                isolatedVertexCount++;
            }
        }
        for (int edge = 0; edge < edgeCount; edge++) {
            int shell = shellOfFace[edgeFirstFace[edge]];
            shellEdgeCounts[shell]++;
            if (edgeFaceUses[edge] == 1) {
                shellBoundaryEdgeCounts[shell]++;
            }
        }
        for (int shell = 0; shell < discovered; shell++) {
            shellEulerCharacteristics[shell] =
                    shellVertexCounts[shell] - shellEdgeCounts[shell] + shellFaceCounts[shell];
        }
    }

    /**
     * Follows the boundary traversal into closed loops, measuring each loop's perimeter and
     * vector area, and orders the loops longest first.
     */
    private void walkBoundaryLoops() {
        boolean[] walked = new boolean[vertexCount];
        int[] loop = new int[vertexCount];
        int[] discoveredEdgeCounts = new int[vertexCount];
        double[] discoveredPerimeters = new double[vertexCount];
        double[] discoveredAreas = new double[vertexCount];
        int[] discoveredShells = new int[vertexCount];
        int discovered = 0;
        int loopEdgeTotal = 0;
        for (int start = 0; start < vertexCount; start++) {
            if (boundaryNextVertex[start] < 0 || walked[start]) {
                continue;
            }
            int length = 0;
            boolean closed = true;
            int activeVertex = start;
            do {
                if (walked[activeVertex] || ambiguousBoundaryVertex[activeVertex]
                        || length >= vertexCount) {
                    closed = false;
                    break;
                }
                walked[activeVertex] = true;
                loop[length++] = activeVertex;
                activeVertex = boundaryNextVertex[activeVertex];
            } while (activeVertex != start && activeVertex >= 0);
            if (!closed || activeVertex != start) {
                continue;
            }
            loopEdgeTotal += length;
            double perimeter = 0;
            double areaX = 0;
            double areaY = 0;
            double areaZ = 0;
            for (int index = 0; index < length; index++) {
                int here = loop[index];
                int next = loop[(index + 1) % length];
                double deltaX = positionX[next] - positionX[here];
                double deltaY = positionY[next] - positionY[here];
                double deltaZ = positionZ[next] - positionZ[here];
                perimeter += Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
                areaX += (double) positionY[here] * positionZ[next]
                        - (double) positionZ[here] * positionY[next];
                areaY += (double) positionZ[here] * positionX[next]
                        - (double) positionX[here] * positionZ[next];
                areaZ += (double) positionX[here] * positionY[next]
                        - (double) positionY[here] * positionX[next];
            }
            discoveredEdgeCounts[discovered] = length;
            discoveredPerimeters[discovered] = perimeter;
            discoveredAreas[discovered] =
                    0.5 * Math.sqrt(areaX * areaX + areaY * areaY + areaZ * areaZ);
            discoveredShells[discovered] = shellOfFace[boundaryFaceOfVertex[loop[0]]];
            discovered++;
        }
        boundaryLoopCount = discovered;
        unwalkedBoundaryEdgeCount = boundaryEdgeCount - loopEdgeTotal;
        long[] order = new long[discovered];
        for (int hole = 0; hole < discovered; hole++) {
            order[hole] =
                    ((long) (Integer.MAX_VALUE - discoveredEdgeCounts[hole]) << KEY_SHIFT) | hole;
        }
        Arrays.sort(order);
        loopEdgeCounts = new int[discovered];
        loopPerimeters = new double[discovered];
        loopAreas = new double[discovered];
        loopShells = new int[discovered];
        for (int rank = 0; rank < discovered; rank++) {
            int hole = (int) (order[rank] & KEY_MASK);
            loopEdgeCounts[rank] = discoveredEdgeCounts[hole];
            loopPerimeters[rank] = discoveredPerimeters[hole];
            loopAreas[rank] = discoveredAreas[hole];
            loopShells[rank] = discoveredShells[hole];
            shellBoundaryLoopCounts[discoveredShells[hole]]++;
        }
    }

    /**
     * Clusters vertices closer than {@link #duplicateTolerance} through a cell grid, leaving the
     * cluster count and the surplus vertices a weld at that distance would remove.
     */
    private void countDuplicatePositions() {
        distinctPositionCount = vertexCount;
        duplicatePositionVertexCount = 0;
        if (duplicateTolerance <= 0 || vertexCount == 0) {
            return;
        }
        float cell = Math.max((float) duplicateTolerance, SMALLEST_CELL);
        int[] cellX = new int[vertexCount];
        int[] cellY = new int[vertexCount];
        int[] cellZ = new int[vertexCount];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            cellX[activeVertex] = (int) Math.floor(positionX[activeVertex] / cell);
            cellY[activeVertex] = (int) Math.floor(positionY[activeVertex] / cell);
            cellZ[activeVertex] = (int) Math.floor(positionZ[activeVertex] / cell);
        }
        int capacity = 1;
        while (capacity < vertexCount * 2) {
            capacity <<= 1;
        }
        int mask = capacity - 1;
        long[] gridKeys = new long[capacity];
        int[] gridHead = new int[capacity];
        Arrays.fill(gridKeys, Long.MIN_VALUE);
        Arrays.fill(gridHead, -1);
        int[] nextInCell = new int[vertexCount];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            long key = MeshMergeByDistance.packCell(cellX[activeVertex], cellY[activeVertex],
                    cellZ[activeVertex]);
            int slot = (int) (MeshMergeByDistance.mixKey(key) & mask);
            while (gridKeys[slot] != Long.MIN_VALUE && gridKeys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            gridKeys[slot] = key;
            nextInCell[activeVertex] = gridHead[slot];
            gridHead[slot] = activeVertex;
        }
        int[] parent = UnionFind.singletons(vertexCount);
        double toleranceSquared = duplicateTolerance * duplicateTolerance;
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            for (int stepX = -1; stepX <= 1; stepX++) {
                for (int stepY = -1; stepY <= 1; stepY++) {
                    for (int stepZ = -1; stepZ <= 1; stepZ++) {
                        long key = MeshMergeByDistance.packCell(cellX[activeVertex] + stepX,
                                cellY[activeVertex] + stepY, cellZ[activeVertex] + stepZ);
                        int slot = (int) (MeshMergeByDistance.mixKey(key) & mask);
                        while (gridKeys[slot] != Long.MIN_VALUE && gridKeys[slot] != key) {
                            slot = (slot + 1) & mask;
                        }
                        if (gridKeys[slot] != key) {
                            continue;
                        }
                        for (int other = gridHead[slot]; other >= 0; other = nextInCell[other]) {
                            if (other <= activeVertex) {
                                continue;
                            }
                            double deltaX = positionX[activeVertex] - positionX[other];
                            double deltaY = positionY[activeVertex] - positionY[other];
                            double deltaZ = positionZ[activeVertex] - positionZ[other];
                            if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                                    < toleranceSquared) {
                                UnionFind.union(parent, activeVertex, other);
                            }
                        }
                    }
                }
            }
        }
        int clusters = 0;
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            if (UnionFind.find(parent, activeVertex) == activeVertex) {
                clusters++;
            }
        }
        distinctPositionCount = clusters;
        duplicatePositionVertexCount = vertexCount - clusters;
    }

    /**
     * The whole analysis as text, counts first and then the shell and loop tables. Byte-stable
     * across runs over the same mesh.
     *
     * @return multi-line report ending in a newline
     */
    public String toText() {
        StringBuilder text = new StringBuilder();
        text.append("mesh topology\n");
        appendCount(text, "vertexCount", vertexCount);
        appendCount(text, "edgeCount", edgeCount);
        appendCount(text, "faceCount", faceCount);
        appendCount(text, "faceSideCount", faceSideCount);
        appendCount(text, "triangleCount", triangleCount);
        appendCount(text, "eulerCharacteristic", eulerCharacteristic);
        appendCount(text, "boundaryEdgeCount", boundaryEdgeCount);
        appendCount(text, "nonManifoldEdgeCount", nonManifoldEdgeCount);
        appendCount(text, "unorientedEdgeCount", unorientedEdgeCount);
        appendCount(text, "isolatedVertexCount", isolatedVertexCount);
        appendCount(text, "nonManifoldBoundaryVertexCount", nonManifoldBoundaryVertexCount);
        appendCount(text, "unwalkedBoundaryEdgeCount", unwalkedBoundaryEdgeCount);
        appendCount(text, "duplicatePositionVertexCount", duplicatePositionVertexCount);
        appendCount(text, "distinctPositionCount", distinctPositionCount);
        appendCount(text, "shellCount", shellCount);
        appendCount(text, "boundaryLoopCount", boundaryLoopCount);
        for (int shell = 0; shell < shellFaceCounts.length; shell++) {
            text.append("  shell ").append(shell)
                    .append(" faces=").append(shellFaceCounts[shell])
                    .append(" vertices=").append(shellVertexCounts[shell])
                    .append(" edges=").append(shellEdgeCounts[shell])
                    .append(" boundaryEdges=").append(shellBoundaryEdgeCounts[shell])
                    .append(" loops=").append(shellBoundaryLoopCounts[shell])
                    .append(" euler=").append(shellEulerCharacteristics[shell])
                    .append('\n');
        }
        for (int loop = 0; loop < loopEdgeCounts.length; loop++) {
            text.append("  loop ").append(loop)
                    .append(" edges=").append(loopEdgeCounts[loop])
                    .append(" perimeter=").append((float) loopPerimeters[loop])
                    .append(" area=").append((float) loopAreas[loop])
                    .append(" shell=").append(loopShells[loop])
                    .append('\n');
        }
        return text.toString();
    }

    /**
     * Appends one {@code name=value} line at the report's indent.
     *
     * @param text builder to append to
     * @param name field name to print
     * @param value value to print
     */
    private static void appendCount(StringBuilder text, String name, int value) {
        text.append("  ").append(name).append('=').append(value).append('\n');
    }
}
