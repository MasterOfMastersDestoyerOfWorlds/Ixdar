package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.csg.MeshBooleanBackend;
import ixdar.geometry.mesh.csg.QuadTriangulation;
import ixdar.geometry.mesh.data.ops.MeshHoleFiller;
import ixdar.geometry.mesh.data.ops.MeshRepair;
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * Selected ring regions taken out as their own closed mesh, cut along each ring's traced spline
 * rather than its snapped edge loop, then capped.
 *
 * <p>The source mesh, the regions and the splines are only read.
 */
public final class RingRegionExtraction {

    public static final double DEFAULT_SNAP_FRACTION = 0.05;

    public static final double DEGENERATE_AREA_RATIO = 1e-9;

    public static final double MIXED_COMPONENT_SHARE = 0.9;

    public static final int XYZ = 3;

    public static final int TRIANGLE_CORNERS = 3;

    /** The regions whose selection is extracted, built on the surface to cut. */
    public final RingRegions regions;

    /** Traced spline of each ring, parallel to the regions' rings; null cuts along its edges. */
    public final SurfaceSpline[] splineByRing;

    /** Regions to keep, one flag per region. */
    public final boolean[] selectedRegions;

    /** Crossings closer than this fraction of their edge to a vertex or each other merge. */
    public double snapFraction = DEFAULT_SNAP_FRACTION;

    /** Whether the open loops are capped; false leaves {@link #closedMesh} the open cut. */
    public boolean cap = true;

    /** Kernel that judges the capped mesh as a boolean operand, or null to skip the check. */
    public MeshBooleanBackend solidCheck;

    /** Packed xyz of the cut surface: the source vertices in dense order, then the crossings. */
    public float[] cutPositions = new float[0];

    /** Vertices of the cut surface. */
    public int cutVertexCount;

    /** Corners of the cut surface's triangles, three per triangle, indexing cut vertices. */
    public int[] cutTriangles = new int[0];

    /** Triangles of the cut surface. */
    public int cutTriangleCount;

    /** Dense source face each cut triangle came from. */
    public int[] parentActiveFaceByTriangle = new int[0];

    /** Sorted undirected cut-vertex pairs every walling ring's cut runs along. */
    public long[] wallKeys = new long[0];

    /** Cut vertices each ring's spline cut passes through, in ring order; null for an edge cut. */
    public int[][] cutPathByRing = new int[0][];

    /** Edge crossings the splines made. */
    public int crossingCount;

    /** Vertices inserted on source edges for those crossings. */
    public int insertedVertexCount;

    /** Crossings merged into an edge's end vertex or a neighbour rather than cutting a sliver. */
    public int snappedCrossingCount;

    /** Connected pieces of the cut surface between walls. */
    public int componentCount;

    /** Piece of each cut triangle. */
    public int[] componentByTriangle = new int[0];

    /** Region each piece stands for, the one most of its area came from. */
    public int[] regionByComponent = new int[0];

    /** Share of each piece's area that came from that region. */
    public double[] regionShareByComponent = new double[0];

    /** The selected pieces before capping, or null when nothing is selected. */
    public HalfEdgeMesh openMesh;

    /** Boundary edges of {@link #openMesh}. */
    public int openBoundaryEdgeCount;

    /** The extracted mesh: capped, or {@link #openMesh} when {@link #cap} is off. */
    public MeshTopology closedMesh;

    /** The capping repair's report, or null when nothing was capped. */
    public MeshRepairReport capReport;

    /** Whether {@link #closedMesh} has no boundary edge and nothing the repair left torn. */
    public boolean closed;

    /** {@link #solidCheck}'s verdict on {@link #closedMesh}, or empty when not checked. */
    public String solidStatus = "";

    /** Mean source edge length, the unit the distances are quoted in. */
    public double meanEdgeLength;

    /**
     * Largest distance from an open-boundary vertex on a spline cut to that spline on the
     * surface, its {@link SurfaceSpline#surfacePolyline}, the curve the ring tool draws.
     */
    public double boundaryToSplineDistance;

    /** Largest distance from such a vertex to the spline's Euclidean cubic, off the surface. */
    public double boundaryToCubicDistance;

    /** Largest distance from a bounding ring's snapped edge loop to its spline on the surface. */
    public double snappedLoopToSplineDistance;

    /** Problems found, one line each. */
    public final List<String> problems = new ArrayList<>();

    private MeshTopology mesh;

    private int sourceVertexCount;

    private int[] activeVertexByVertexId = new int[0];

    private int[] insertedStartByEdgeId = new int[0];

    private int[] edgeIdOfInserted = new int[0];

    private int wallCount;

    private int[] neighbourByCorner = new int[0];

    /**
     * Stores what {@link #build} extracts.
     *
     * @param regions         regions built on the surface to cut
     * @param splineByRing    each ring's traced spline, parallel to the regions' rings, entries
     *                        null where a ring is cut along its marked edges
     * @param selectedRegions one flag per region, true on the regions to keep
     * @throws IllegalArgumentException when the arrays do not match the regions
     */
    public RingRegionExtraction(RingRegions regions, SurfaceSpline[] splineByRing,
            boolean[] selectedRegions) {
        if (splineByRing.length != regions.ringLabels.length
                || selectedRegions.length != regions.regionCount) {
            throw new IllegalArgumentException(splineByRing.length + " splines and "
                    + selectedRegions.length + " region flags for " + regions.ringLabels.length
                    + " rings and " + regions.regionCount + " regions");
        }
        this.regions = regions;
        this.splineByRing = splineByRing;
        this.selectedRegions = selectedRegions;
    }

    /**
     * Cuts a copy of the surface along the splines, floods it with the cuts as walls, keeps the
     * pieces standing for selected regions, measures their boundary against the splines and caps
     * it. A crossing within {@link #snapFraction} of a vertex or another crossing merges into it.
     *
     * @return this, populated
     */
    public RingRegionExtraction build() {
        mesh = regions.mesh;
        sourceVertexCount = mesh.vertexCount();
        int ringCount = regions.ringLabels.length;
        int vertexIdCeiling = 0;
        for (int activeVertex = 0; activeVertex < sourceVertexCount; activeVertex++) {
            vertexIdCeiling = Math.max(vertexIdCeiling, mesh.vertexIdAt(activeVertex) + 1);
        }
        activeVertexByVertexId = new int[vertexIdCeiling];
        Arrays.fill(activeVertexByVertexId, MeshTopology.NONE);
        for (int activeVertex = 0; activeVertex < sourceVertexCount; activeVertex++) {
            activeVertexByVertexId[mesh.vertexIdAt(activeVertex)] = activeVertex;
        }
        int faceIdCeiling = 0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            faceIdCeiling = Math.max(faceIdCeiling, mesh.faceIdAt(activeFace) + 1);
        }
        int[] activeFaceByFaceId = new int[faceIdCeiling];
        Arrays.fill(activeFaceByFaceId, MeshTopology.NONE);
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            activeFaceByFaceId[mesh.faceIdAt(activeFace)] = activeFace;
        }
        double edgeLengthSum = 0;
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            edgeLengthSum += mesh.edgeLength(mesh.edgeIdAt(activeEdge));
        }
        meanEdgeLength = mesh.edgeCount() == 0 ? 0 : edgeLengthSum / mesh.edgeCount();
        Vector3f tail = new Vector3f();
        Vector3f head = new Vector3f();

        // Gather every spline crossing; the polyline repeats its first point, so skip the last.
        int edgeIdCeiling = RingBundle.edgeIdCeiling(mesh);
        int[] crossingEdge = new int[0];
        double[] crossingFraction = new double[0];
        int[][] crossingOfPoint = new int[ringCount][];
        crossingCount = 0;
        for (int ring = 0; ring < ringCount; ring++) {
            if (!cutsAlongSpline(ring)) {
                continue;
            }
            SurfaceSpline spline = splineByRing[ring];
            int points = spline.pointVertexId.length - 1;
            crossingOfPoint[ring] = new int[points];
            for (int point = 0; point < points; point++) {
                crossingOfPoint[ring][point] = MeshTopology.NONE;
                int edgeId = spline.pointEdgeId[point];
                if (spline.pointVertexId[point] >= 0 || edgeId < 0 || !mesh.hasEdge(edgeId)) {
                    continue;
                }
                if (crossingCount == crossingEdge.length) {
                    crossingEdge = Arrays.copyOf(crossingEdge, Math.max(2, 2 * crossingCount));
                    crossingFraction = Arrays.copyOf(crossingFraction, crossingEdge.length);
                }
                crossingEdge[crossingCount] = edgeId;
                crossingFraction[crossingCount] = spline.pointFraction[point];
                crossingOfPoint[ring][point] = crossingCount++;
            }
        }
        Integer[] order = new Integer[crossingCount];
        for (int crossing = 0; crossing < crossingCount; crossing++) {
            order[crossing] = crossing;
        }
        int[] edges = crossingEdge;
        double[] fractions = crossingFraction;
        Arrays.sort(order, (first, second) -> edges[first] != edges[second]
                ? Integer.compare(edges[first], edges[second])
                : Double.compare(fractions[first], fractions[second]));

        // Merge each edge's crossings into clusters and mint one vertex per cluster, or reuse the
        // end vertex a cluster sits next to, so no cut leaves a sliver.
        int[] crossingVertex = new int[crossingCount];
        cutPositions = new float[XYZ * (sourceVertexCount + crossingCount)];
        for (int activeVertex = 0; activeVertex < sourceVertexCount; activeVertex++) {
            mesh.vertexPosition(mesh.vertexIdAt(activeVertex), tail);
            cutPositions[XYZ * activeVertex] = tail.x;
            cutPositions[XYZ * activeVertex + 1] = tail.y;
            cutPositions[XYZ * activeVertex + 2] = tail.z;
        }
        cutVertexCount = sourceVertexCount;
        snappedCrossingCount = 0;
        edgeIdOfInserted = new int[crossingCount];
        int[] insertedCountByEdgeId = new int[edgeIdCeiling];
        int clusterStart = 0;
        while (clusterStart < crossingCount) {
            int edgeId = crossingEdge[order[clusterStart]];
            double first = crossingFraction[order[clusterStart]];
            int clusterEnd = clusterStart;
            double fractionSum = 0;
            while (clusterEnd < crossingCount && crossingEdge[order[clusterEnd]] == edgeId
                    && crossingFraction[order[clusterEnd]] - first <= snapFraction) {
                fractionSum += crossingFraction[order[clusterEnd]];
                clusterEnd++;
            }
            double fraction = fractionSum / (clusterEnd - clusterStart);
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int vertex;
            if (fraction <= snapFraction) {
                vertex = activeVertexByVertexId[mesh.halfEdgeVertex(halfEdge)];
                snappedCrossingCount += clusterEnd - clusterStart;
            } else if (fraction >= 1 - snapFraction) {
                vertex = activeVertexByVertexId[mesh.halfEdgeEndVertex(halfEdge)];
                snappedCrossingCount += clusterEnd - clusterStart;
            } else {
                vertex = cutVertexCount++;
                mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), tail);
                mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), head);
                tail.lerp(head, (float) fraction);
                cutPositions[XYZ * vertex] = tail.x;
                cutPositions[XYZ * vertex + 1] = tail.y;
                cutPositions[XYZ * vertex + 2] = tail.z;
                edgeIdOfInserted[vertex - sourceVertexCount] = edgeId;
                insertedCountByEdgeId[edgeId]++;
                snappedCrossingCount += clusterEnd - clusterStart - 1;
            }
            for (int sorted = clusterStart; sorted < clusterEnd; sorted++) {
                crossingVertex[order[sorted]] = vertex;
            }
            clusterStart = clusterEnd;
        }
        insertedVertexCount = cutVertexCount - sourceVertexCount;
        cutPositions = Arrays.copyOf(cutPositions, XYZ * cutVertexCount);
        // Vertices were minted in ascending (edge, fraction) order, so each edge's run of inserted
        // vertices is the consecutive range its offsets name.
        insertedStartByEdgeId = new int[edgeIdCeiling + 1];
        for (int edgeId = 0; edgeId < edgeIdCeiling; edgeId++) {
            insertedStartByEdgeId[edgeId + 1] = insertedStartByEdgeId[edgeId]
                    + insertedCountByEdgeId[edgeId];
        }

        // Each ring's spline as a loop of cut vertices.
        cutPathByRing = new int[ringCount][];
        for (int ring = 0; ring < ringCount; ring++) {
            if (crossingOfPoint[ring] == null) {
                continue;
            }
            SurfaceSpline spline = splineByRing[ring];
            int[] path = new int[crossingOfPoint[ring].length];
            int length = 0;
            for (int point = 0; point < path.length; point++) {
                int vertex = spline.pointVertexId[point] >= 0
                        ? activeVertexByVertexId[spline.pointVertexId[point]]
                        : crossingOfPoint[ring][point] >= 0
                                ? crossingVertex[crossingOfPoint[ring][point]]
                                : MeshTopology.NONE;
                if (vertex != MeshTopology.NONE && (length == 0 || path[length - 1] != vertex)) {
                    path[length++] = vertex;
                }
            }
            while (length > 1 && path[length - 1] == path[0]) {
                length--;
            }
            if (length < TRIANGLE_CORNERS) {
                problems.add(regions.ringLabels[ring] + "'s spline collapses to " + length
                        + " cut vertices; it is cut along its marked edges instead");
                continue;
            }
            cutPathByRing[ring] = Arrays.copyOf(path, length);
        }

        // Walls and chords: a span along a source edge walls that edge's pieces, any other span
        // crosses the one face both ends lie on; a ring with no spline walls its marked edges.
        int[] chordHeadByActiveFace = new int[mesh.faceCount()];
        Arrays.fill(chordHeadByActiveFace, MeshTopology.NONE);
        int[] chordNext = new int[0];
        int[] chordFrom = new int[0];
        int[] chordTo = new int[0];
        int chordCount = 0;
        wallKeys = new long[0];
        wallCount = 0;
        int unresolvedSpans = 0;
        for (int ring = 0; ring < ringCount; ring++) {
            if (!regions.ringIsWall[ring]) {
                continue;
            }
            int[] path = cutPathByRing[ring];
            if (path == null) {
                boolean[] marks = regions.ringMarksByEdgeId[ring];
                for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                    int edgeId = mesh.edgeIdAt(activeEdge);
                    if (edgeId < marks.length && marks[edgeId]) {
                        int[] run = edgeRun(edgeId);
                        wallRun(run, 0, run.length - 1);
                    }
                }
                continue;
            }
            for (int index = 0; index < path.length; index++) {
                int from = path[index];
                int to = path[(index + 1) % path.length];
                int sharedEdge;
                if (from >= sourceVertexCount || to >= sourceVertexCount) {
                    int inserted = from >= sourceVertexCount ? from : to;
                    int edgeId = edgeIdOfInserted[inserted - sourceVertexCount];
                    sharedEdge = indexOf(edgeRun(edgeId), inserted == from ? to : from) >= 0
                            ? edgeId : MeshTopology.NONE;
                } else {
                    sharedEdge = mesh.edgeBetween(mesh.vertexIdAt(from), mesh.vertexIdAt(to));
                }
                if (sharedEdge != MeshTopology.NONE) {
                    int[] run = edgeRun(sharedEdge);
                    wallRun(run, indexOf(run, from), indexOf(run, to));
                    continue;
                }
                int sharedFace = MeshTopology.NONE;
                int[] toFaces = facesOf(to);
                for (int faceId : facesOf(from)) {
                    if (faceId != MeshTopology.NONE && indexOf(toFaces, faceId) >= 0) {
                        sharedFace = faceId;
                        break;
                    }
                }
                if (sharedFace == MeshTopology.NONE) {
                    unresolvedSpans++;
                    continue;
                }
                if (chordCount == chordFrom.length) {
                    int grown = Math.max(2, 2 * chordCount);
                    chordFrom = Arrays.copyOf(chordFrom, grown);
                    chordTo = Arrays.copyOf(chordTo, grown);
                    chordNext = Arrays.copyOf(chordNext, grown);
                }
                int activeFace = activeFaceByFaceId[sharedFace];
                chordFrom[chordCount] = from;
                chordTo[chordCount] = to;
                chordNext[chordCount] = chordHeadByActiveFace[activeFace];
                chordHeadByActiveFace[activeFace] = chordCount++;
                addWall(EdgeKey.undirected(from, to));
            }
        }
        wallKeys = Arrays.copyOf(wallKeys, wallCount);
        Arrays.sort(wallKeys);
        int distinctWalls = 0;
        for (int index = 0; index < wallKeys.length; index++) {
            if (index == 0 || wallKeys[index] != wallKeys[index - 1]) {
                wallKeys[distinctWalls++] = wallKeys[index];
            }
        }
        wallKeys = Arrays.copyOf(wallKeys, distinctWalls);
        if (unresolvedSpans > 0) {
            problems.add(unresolvedSpans + " spline span(s) join points that share no face; "
                    + "their cut has a gap the flood can leak through");
        }

        // Split every face along its chords and triangulate the pieces. A piece is convex, being
        // a convex face cut by straight chords, so the interval program below triangulates it;
        // its cost, squared edges over area, keeps collinear crossings out of any one triangle.
        cutTriangles = new int[TRIANGLE_CORNERS * (mesh.faceCount() + 2 * crossingCount + 2)];
        parentActiveFaceByTriangle = new int[cutTriangles.length / TRIANGLE_CORNERS];
        cutTriangleCount = 0;
        int crossingCuts = 0;
        int degeneratePieces = 0;
        int[] boundary = new int[0];
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int length = 0;
            for (int slot = 0; slot < mesh.faceHalfEdgeCount(faceId); slot++) {
                int halfEdge = mesh.faceHalfEdgeAt(faceId, slot);
                int[] run = edgeRun(mesh.halfEdgeEdge(halfEdge));
                boolean forward = run[0] == activeVertexByVertexId[mesh.halfEdgeVertex(halfEdge)];
                if (boundary.length < length + run.length) {
                    boundary = Arrays.copyOf(boundary, 2 * (length + run.length));
                }
                for (int step = 0; step + 1 < run.length; step++) {
                    boundary[length++] = run[forward ? step : run.length - 1 - step];
                }
            }
            List<int[]> pieces = new ArrayList<>();
            pieces.add(Arrays.copyOf(boundary, length));
            for (int chord = chordHeadByActiveFace[activeFace]; chord != MeshTopology.NONE;
                    chord = chordNext[chord]) {
                boolean placed = false;
                for (int piece = 0; piece < pieces.size() && !placed; piece++) {
                    int[] polygon = pieces.get(piece);
                    int from = indexOf(polygon, chordFrom[chord]);
                    int to = indexOf(polygon, chordTo[chord]);
                    if (from < 0 || to < 0) {
                        continue;
                    }
                    placed = true;
                    int gap = Math.abs(from - to);
                    if (gap == 1 || gap == polygon.length - 1) {
                        continue;
                    }
                    int low = Math.min(from, to);
                    int high = Math.max(from, to);
                    int[] outer = new int[polygon.length - (high - low) + 1];
                    System.arraycopy(polygon, 0, outer, 0, low + 1);
                    System.arraycopy(polygon, high, outer, low + 1, polygon.length - high);
                    pieces.set(piece, Arrays.copyOfRange(polygon, low, high + 1));
                    pieces.add(outer);
                }
                crossingCuts += placed ? 0 : 1;
            }
            for (int[] piece : pieces) {
                int corners = piece.length;
                if (corners == TRIANGLE_CORNERS) {
                    addTriangle(piece[0], piece[1], piece[2], activeFace);
                    continue;
                }
                double[][] cost = new double[corners][corners];
                int[][] split = new int[corners][corners];
                for (int span = 2; span < corners; span++) {
                    for (int start = 0; start + span < corners; start++) {
                        int end = start + span;
                        cost[start][end] = Double.POSITIVE_INFINITY;
                        for (int middle = start + 1; middle < end; middle++) {
                            double squaredEdges = distanceSquared(piece[start], piece[middle])
                                    + distanceSquared(piece[middle], piece[end])
                                    + distanceSquared(piece[end], piece[start]);
                            double area = Math.sqrt(Math.max(0,
                                    areaSquared(piece[start], piece[middle], piece[end])));
                            double candidate = cost[start][middle] + cost[middle][end]
                                    + (area <= DEGENERATE_AREA_RATIO * squaredEdges
                                            ? Double.POSITIVE_INFINITY : squaredEdges / area);
                            if (candidate < cost[start][end]) {
                                cost[start][end] = candidate;
                                split[start][end] = middle;
                            }
                        }
                    }
                }
                if (corners < TRIANGLE_CORNERS
                        || cost[0][corners - 1] == Double.POSITIVE_INFINITY) {
                    degeneratePieces += corners < TRIANGLE_CORNERS ? 0 : 1;
                    for (int corner = 1; corner + 1 < corners; corner++) {
                        addTriangle(piece[0], piece[corner], piece[corner + 1], activeFace);
                    }
                    continue;
                }
                int[] stack = new int[2 * corners];
                int depth = 0;
                stack[depth++] = 0;
                stack[depth++] = corners - 1;
                while (depth > 0) {
                    int end = stack[--depth];
                    int start = stack[--depth];
                    if (end - start < 2) {
                        continue;
                    }
                    int middle = split[start][end];
                    addTriangle(piece[start], piece[middle], piece[end], activeFace);
                    stack[depth++] = start;
                    stack[depth++] = middle;
                    stack[depth++] = middle;
                    stack[depth++] = end;
                }
            }
        }
        if (crossingCuts > 0) {
            problems.add(crossingCuts + " cut(s) cross another ring's cut inside a face and "
                    + "were dropped; near-duplicate or crossing rings");
        }
        if (degeneratePieces > 0) {
            problems.add(degeneratePieces + " face piece(s) had no non-degenerate "
                    + "triangulation");
        }

        // Flood the cut triangles across every non-wall edge, and name each piece by the region
        // most of its area came from, which also classifies the band between loop and spline.
        neighbourByCorner = new int[TRIANGLE_CORNERS * cutTriangleCount];
        Arrays.fill(neighbourByCorner, MeshTopology.NONE);
        double[] triangleArea = new double[cutTriangleCount];
        Map<Long, Integer> openCornerByEdge = new HashMap<>();
        for (int triangle = 0; triangle < cutTriangleCount; triangle++) {
            int base = TRIANGLE_CORNERS * triangle;
            triangleArea[triangle] = Math.sqrt(Math.max(0, areaSquared(cutTriangles[base],
                    cutTriangles[base + 1], cutTriangles[base + 2])));
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                long key = EdgeKey.undirected(cutTriangles[base + corner],
                        cutTriangles[base + (corner + 1) % TRIANGLE_CORNERS]);
                Integer other = openCornerByEdge.remove(key);
                if (other == null) {
                    openCornerByEdge.put(key, base + corner);
                } else {
                    neighbourByCorner[base + corner] = other / TRIANGLE_CORNERS;
                    neighbourByCorner[other] = triangle;
                }
            }
        }
        componentByTriangle = new int[cutTriangleCount];
        Arrays.fill(componentByTriangle, MeshTopology.NONE);
        int[] frontier = new int[cutTriangleCount];
        componentCount = 0;
        for (int seed = 0; seed < cutTriangleCount; seed++) {
            if (componentByTriangle[seed] != MeshTopology.NONE) {
                continue;
            }
            int queueHead = 0;
            int queueTail = 0;
            frontier[queueTail++] = seed;
            componentByTriangle[seed] = componentCount;
            while (queueHead < queueTail) {
                int triangle = frontier[queueHead++];
                int base = TRIANGLE_CORNERS * triangle;
                for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                    int neighbour = neighbourByCorner[base + corner];
                    if (neighbour == MeshTopology.NONE
                            || componentByTriangle[neighbour] != MeshTopology.NONE
                            || Arrays.binarySearch(wallKeys, EdgeKey.undirected(
                                    cutTriangles[base + corner],
                                    cutTriangles[base + (corner + 1) % TRIANGLE_CORNERS])) >= 0) {
                        continue;
                    }
                    componentByTriangle[neighbour] = componentCount;
                    frontier[queueTail++] = neighbour;
                }
            }
            componentCount++;
        }
        int regionCount = regions.regionCount;
        double[] areaByComponentRegion = new double[componentCount * regionCount];
        for (int triangle = 0; triangle < cutTriangleCount; triangle++) {
            int region = regions.regionByActiveFace[parentActiveFaceByTriangle[triangle]];
            areaByComponentRegion[componentByTriangle[triangle] * regionCount + region] +=
                    triangleArea[triangle];
        }
        regionByComponent = new int[componentCount];
        regionShareByComponent = new double[componentCount];
        int mixed = 0;
        for (int component = 0; component < componentCount; component++) {
            double total = 0;
            int best = 0;
            for (int region = 0; region < regionCount; region++) {
                double area = areaByComponentRegion[component * regionCount + region];
                total += area;
                if (area > areaByComponentRegion[component * regionCount + best]) {
                    best = region;
                }
            }
            regionByComponent[component] = best;
            regionShareByComponent[component] = total <= 0 ? 1
                    : areaByComponentRegion[component * regionCount + best] / total;
            mixed += regionShareByComponent[component] < MIXED_COMPONENT_SHARE ? 1 : 0;
        }
        if (mixed > 0) {
            problems.add(mixed + " cut piece(s) take less than "
                    + Math.round(100 * MIXED_COMPONENT_SHARE) + "% of their area from one "
                    + "region; a cut leaks");
        }

        // Keep the pieces standing for selected regions.
        int[] keptIndexOfCutVertex = new int[cutVertexCount];
        Arrays.fill(keptIndexOfCutVertex, MeshTopology.NONE);
        boolean[] onBoundary = new boolean[cutVertexCount];
        float[] keptPositions = new float[XYZ * cutVertexCount];
        int[] keptTriangles = new int[TRIANGLE_CORNERS * cutTriangleCount];
        int keptVertices = 0;
        int keptCorners = 0;
        openBoundaryEdgeCount = 0;
        for (int triangle = 0; triangle < cutTriangleCount; triangle++) {
            if (!kept(triangle)) {
                continue;
            }
            int base = TRIANGLE_CORNERS * triangle;
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int vertex = cutTriangles[base + corner];
                if (keptIndexOfCutVertex[vertex] == MeshTopology.NONE) {
                    keptIndexOfCutVertex[vertex] = keptVertices;
                    System.arraycopy(cutPositions, XYZ * vertex, keptPositions,
                            XYZ * keptVertices, XYZ);
                    keptVertices++;
                }
                keptTriangles[keptCorners++] = keptIndexOfCutVertex[vertex];
                int neighbour = neighbourByCorner[base + corner];
                if (neighbour == MeshTopology.NONE || !kept(neighbour)) {
                    openBoundaryEdgeCount++;
                    onBoundary[vertex] = true;
                    onBoundary[cutTriangles[base + (corner + 1) % TRIANGLE_CORNERS]] = true;
                }
            }
        }
        openMesh = null;
        closedMesh = null;
        capReport = null;
        closed = false;
        if (keptCorners == 0) {
            problems.add("no region is selected, so nothing was extracted");
            return this;
        }
        openMesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                Arrays.copyOf(keptPositions, XYZ * keptVertices),
                Arrays.copyOf(keptTriangles, keptCorners));

        // How far the open boundary strays from the splines it was cut along, and how far the
        // snapped loops the selection flooded by stray from them, for comparison.
        boundaryToSplineDistance = 0;
        boundaryToCubicDistance = 0;
        snappedLoopToSplineDistance = 0;
        boolean[][] onRingByCutVertex = new boolean[ringCount][];
        for (int ring = 0; ring < ringCount; ring++) {
            onRingByCutVertex[ring] = new boolean[cutVertexCount];
            for (int vertex : cutPathByRing[ring] == null ? new int[0] : cutPathByRing[ring]) {
                onRingByCutVertex[ring][vertex] = true;
            }
        }
        for (int vertex = 0; vertex < cutVertexCount; vertex++) {
            if (!onBoundary[vertex]) {
                continue;
            }
            double toSpline = Double.POSITIVE_INFINITY;
            double toCubic = Double.POSITIVE_INFINITY;
            float x = cutPositions[XYZ * vertex];
            float y = cutPositions[XYZ * vertex + 1];
            float z = cutPositions[XYZ * vertex + 2];
            for (int ring = 0; ring < ringCount; ring++) {
                if (!onRingByCutVertex[ring][vertex]) {
                    continue;
                }
                SurfaceSpline spline = splineByRing[ring];
                toSpline = Math.min(toSpline, SurfaceSpline.distanceToPolyline(
                        spline.surfacePolyline, spline.surfacePolyline.length / XYZ, x, y, z));
                toCubic = Math.min(toCubic, SurfaceSpline.distanceToPolyline(spline.polyline,
                        spline.polyline.length / XYZ, x, y, z));
            }
            if (toSpline == Double.POSITIVE_INFINITY) {
                continue;
            }
            boundaryToSplineDistance = Math.max(boundaryToSplineDistance, toSpline);
            boundaryToCubicDistance = Math.max(boundaryToCubicDistance, toCubic);
        }
        boolean[] bounding = new boolean[ringCount];
        for (int region = 0; region < regionCount; region++) {
            for (int ring : selectedRegions[region] ? regions.boundingRingsByRegion[region]
                    : new int[0]) {
                bounding[ring] = true;
            }
        }
        for (int ring = 0; ring < ringCount; ring++) {
            if (!bounding[ring] || cutPathByRing[ring] == null) {
                continue;
            }
            SurfaceSpline spline = splineByRing[ring];
            boolean[] marks = regions.ringMarksByEdgeId[ring];
            for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                int edgeId = mesh.edgeIdAt(activeEdge);
                if (edgeId >= marks.length || !marks[edgeId]) {
                    continue;
                }
                mesh.vertexPosition(mesh.halfEdgeVertex(mesh.edgeHalfEdge(edgeId)), tail);
                snappedLoopToSplineDistance = Math.max(snappedLoopToSplineDistance,
                        SurfaceSpline.distanceToPolyline(spline.surfacePolyline,
                                spline.surfacePolyline.length / XYZ, tail.x, tail.y, tail.z));
            }
        }

        // Cap every open loop with the Liepa filler repair_mesh runs, then ask the kernel.
        closedMesh = openMesh;
        if (cap) {
            MeshRepair repair = new MeshRepair(GeometryBundle.ofMesh(openMesh));
            repair.maxHoleEdges = MeshHoleFiller.MAX_LOOP_LENGTH;
            repair.minShellFaces = 0;
            repair.build();
            capReport = repair.report;
            closedMesh = repair.output.mesh();
            closed = capReport.outputBoundaryEdgeCount == 0 && !capReport.hasUnrepaired();
        } else {
            closed = openBoundaryEdgeCount == 0;
        }
        solidStatus = "";
        if (solidCheck != null && closedMesh.faceCount() > 0) {
            solidStatus = solidCheck.solidStatus(new QuadTriangulation(closedMesh).build());
        }
        return this;
    }

    /**
     * Whether a ring is cut along its spline: it walls the regions and carries a spline traced on
     * this surface.
     *
     * @param ring ring index
     * @return true when the cut follows the spline, false when it follows the marked edges
     */
    public boolean cutsAlongSpline(int ring) {
        SurfaceSpline spline = splineByRing[ring];
        return regions.ringIsWall[ring] && spline != null && spline.mesh == regions.mesh
                && spline.pointVertexId.length > TRIANGLE_CORNERS;
    }

    /**
     * The report a reader checks: the cut, the pieces kept, the boundary distances and the cap.
     *
     * @return the report lines
     */
    public List<String> reportLines() {
        List<String> lines = new ArrayList<>();
        int splineRings = 0;
        int edgeRings = 0;
        for (int ring = 0; ring < regions.ringLabels.length; ring++) {
            boolean alongSpline = ring < cutPathByRing.length && cutPathByRing[ring] != null;
            splineRings += alongSpline ? 1 : 0;
            edgeRings += regions.ringIsWall[ring] && !alongSpline ? 1 : 0;
        }
        lines.add(String.format(Locale.ROOT, "cut %d ring(s) along their splines and %d along "
                + "their edges: %d crossing(s), %d vertex(es) inserted, %d snapped; %d triangle(s) "
                + "in %d piece(s)", splineRings, edgeRings, crossingCount, insertedVertexCount,
                snappedCrossingCount, cutTriangleCount, componentCount));
        if (openMesh != null) {
            double unit = Math.max(meanEdgeLength, Double.MIN_VALUE);
            lines.add(String.format(Locale.ROOT, "kept %d face(s), %d open boundary edge(s); "
                    + "boundary to spline max %.4f (%.3f mean edges), the snapped loops %.4f "
                    + "(%.3f mean edges); boundary to the off-surface cubic %.4f",
                    openMesh.faceCount(), openBoundaryEdgeCount, boundaryToSplineDistance,
                    boundaryToSplineDistance / unit, snappedLoopToSplineDistance,
                    snappedLoopToSplineDistance / unit, boundaryToCubicDistance));
        }
        if (capReport != null) {
            lines.add(String.format(Locale.ROOT, "capped %d of %d loop(s) with %d triangle(s): "
                    + "%d face(s), %d boundary edge(s), closed=%b%s", capReport.filledHoleCount,
                    capReport.holeCount, capReport.fillFaceCount, closedMesh.faceCount(),
                    capReport.outputBoundaryEdgeCount, closed,
                    solidStatus.isEmpty() ? "" : ", manifold " + solidStatus));
        }
        for (String problem : problems) {
            lines.add("problem: " + problem);
        }
        return lines;
    }

    private boolean kept(int triangle) {
        return selectedRegions[regionByComponent[componentByTriangle[triangle]]];
    }

    /**
     * The cut vertices along one source edge from its canonical start to its end, crossings
     * inserted on it between them in order.
     *
     * @param edgeId source edge
     * @return start vertex, inserted vertices, end vertex
     */
    private int[] edgeRun(int edgeId) {
        int halfEdge = mesh.edgeHalfEdge(edgeId);
        int from = insertedStartByEdgeId[edgeId];
        int to = insertedStartByEdgeId[edgeId + 1];
        int[] run = new int[to - from + 2];
        run[0] = activeVertexByVertexId[mesh.halfEdgeVertex(halfEdge)];
        for (int inserted = from; inserted < to; inserted++) {
            run[inserted - from + 1] = sourceVertexCount + inserted;
        }
        run[run.length - 1] = activeVertexByVertexId[mesh.halfEdgeEndVertex(halfEdge)];
        return run;
    }

    private void wallRun(int[] run, int fromIndex, int toIndex) {
        for (int index = Math.min(fromIndex, toIndex); index < Math.max(fromIndex, toIndex);
                index++) {
            addWall(EdgeKey.undirected(run[index], run[index + 1]));
        }
    }

    private void addWall(long key) {
        if (wallCount == wallKeys.length) {
            wallKeys = Arrays.copyOf(wallKeys, Math.max(2, 2 * wallCount));
        }
        wallKeys[wallCount++] = key;
    }

    private int[] facesOf(int vertex) {
        if (vertex >= sourceVertexCount) {
            int edgeId = edgeIdOfInserted[vertex - sourceVertexCount];
            return new int[] { mesh.edgeFace(edgeId, 0), mesh.edgeFace(edgeId, 1) };
        }
        int vertexId = mesh.vertexIdAt(vertex);
        int[] faces = new int[mesh.vertexFaceCount(vertexId)];
        for (int index = 0; index < faces.length; index++) {
            faces[index] = mesh.vertexFaceAt(vertexId, index);
        }
        return faces;
    }

    private static int indexOf(int[] values, int value) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == value) {
                return index;
            }
        }
        return -1;
    }

    private double distanceSquared(int first, int second) {
        double dx = cutPositions[XYZ * first] - cutPositions[XYZ * second];
        double dy = cutPositions[XYZ * first + 1] - cutPositions[XYZ * second + 1];
        double dz = cutPositions[XYZ * first + 2] - cutPositions[XYZ * second + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    private double areaSquared(int first, int second, int third) {
        double ax = cutPositions[XYZ * second] - cutPositions[XYZ * first];
        double ay = cutPositions[XYZ * second + 1] - cutPositions[XYZ * first + 1];
        double az = cutPositions[XYZ * second + 2] - cutPositions[XYZ * first + 2];
        double bx = cutPositions[XYZ * third] - cutPositions[XYZ * first];
        double by = cutPositions[XYZ * third + 1] - cutPositions[XYZ * first + 1];
        double bz = cutPositions[XYZ * third + 2] - cutPositions[XYZ * first + 2];
        double cx = ay * bz - az * by;
        double cy = az * bx - ax * bz;
        double cz = ax * by - ay * bx;
        return 0.5 * 0.5 * (cx * cx + cy * cy + cz * cz);
    }

    private void addTriangle(int first, int second, int third, int parent) {
        if (TRIANGLE_CORNERS * (cutTriangleCount + 1) > cutTriangles.length) {
            cutTriangles = Arrays.copyOf(cutTriangles, 2 * cutTriangles.length + TRIANGLE_CORNERS);
            parentActiveFaceByTriangle = Arrays.copyOf(parentActiveFaceByTriangle,
                    cutTriangles.length / TRIANGLE_CORNERS);
        }
        cutTriangles[TRIANGLE_CORNERS * cutTriangleCount] = first;
        cutTriangles[TRIANGLE_CORNERS * cutTriangleCount + 1] = second;
        cutTriangles[TRIANGLE_CORNERS * cutTriangleCount + 2] = third;
        parentActiveFaceByTriangle[cutTriangleCount++] = parent;
    }
}
