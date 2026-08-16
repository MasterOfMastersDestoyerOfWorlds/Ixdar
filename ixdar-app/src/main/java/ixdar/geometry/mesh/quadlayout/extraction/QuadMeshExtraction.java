package ixdar.geometry.mesh.quadlayout.extraction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ExactBarycentricOrient;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapVerification;
import ixdar.geometry.mesh.quadlayout.gridmap.IntegerGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutPatchMaps;
import ixdar.platform.Platforms;

/**
 * Extracts the quad mesh of the relaxed integer grid map: vertices at the
 * preimages of integer grid points, connectivity by tracing unit iso-segments
 * through the patch charts, faces by clockwise port cycling.
 *
 * <p>
 * See also: EBC13 Algorithms 3-6
 */
public final class QuadMeshExtraction {

    /** Iso-line directions leaving a regular grid point. */
    private static final int AXIS_DIRECTIONS = 4;

    /** Grid u step of each direction, indexed by quarter turns from {@code +u}. */
    private static final int[] DIRECTION_U = { 1, 0, -1, 0 };

    /** Grid v step of each direction, indexed by quarter turns from {@code +u}. */
    private static final int[] DIRECTION_V = { 0, 1, 0, -1 };

    /** Chart corner to face vertex index when the patch chart winds clockwise. */
    private static final int[] CLOCKWISE_CORNER_PERMUTATION = { 0, 2, 1 };

    public final GlobalGridMap gridMap;
    public final GridMapVerification verification;
    public final EmbeddedTMesh tmesh;
    public final LayoutPatchMaps patchMaps;
    public final HalfEdgeMesh copy;

    /** Preimages found on copy vertices. */
    public int vertexPreimageCount;

    /** Preimages found strictly inside copy edges. */
    public int edgePreimageCount;

    /** Preimages found strictly inside copy faces. */
    public int facePreimageCount;

    /**
     * Expected quad count from the pre-relaxation extraction; NONE skips the check.
     */
    public int expectedQuadCount = ExtractedQuadMesh.NONE;

    /** Traces that connected to an opposite port found across a collinear edge. */
    public int fanFallbackConnectionCount;

    /** Patch of every region copy face, from the patch regions. */
    private final Map<Integer, Integer> patchByCopyFace;

    /** Chart triangle index of every region copy face within its patch's map. */
    private final Map<Integer, Integer> triangleIndexByCopyFace = new HashMap<>();

    /** Quad vertex id by copy vertex id, for vertices holding a preimage. */
    private final Map<Integer, Integer> quadVertexByCopyVertex = new HashMap<>();

    /** Quad vertex ids strictly inside each copy edge. */
    private final Map<Integer, List<Integer>> quadVerticesByCopyEdge = new HashMap<>();

    /** Quad vertex ids strictly inside each copy face. */
    private final Map<Integer, List<Integer>> quadVerticesByCopyFace = new HashMap<>();

    /** Quad vertices emitted so far; the growth arrays are valid up to here. */
    private int quadVertexCount;

    private int[] vertexKind = new int[16];
    private int[] anchorEntityId = new int[16];
    private int[] chartPatchId = new int[16];
    private double[] chartU = new double[16];
    private double[] chartV = new double[16];
    private float[] positionX = new float[16];
    private float[] positionY = new float[16];
    private float[] positionZ = new float[16];

    /**
     * Ports over all quad vertices, clockwise per vertex; valid up to portCount.
     */
    private int portCount;

    private int[] portStart;
    private int[] portOwner = new int[16];
    private int[] portFace = new int[16];
    private int[] portDirectionTurns = new int[16];
    private double[] portChartU = new double[16];
    private double[] portChartV = new double[16];
    private int[] portConnection;
    private int[] connectionTurns;
    private int[] connectionTranslationU;
    private int[] connectionTranslationV;

    /** Extracted quads; four corner quad vertex ids each, valid up to quadCount. */
    private int quadCount;

    private int[] quadCorner = new int[16];

    /** Unique undirected quad edges, one per traced connection pair. */
    private int quadEdgeCount;

    private int[] quadEdgeVertexA = new int[16];
    private int[] quadEdgeVertexB = new int[16];

    /**
     * Stores the verified relaxed map to extract from.
     *
     * @param gridMap      the patch maps carried into one common grid, relaxed
     * @param verification the map's canonicalization, holding resolved transitions
     */
    public QuadMeshExtraction(GlobalGridMap gridMap, GridMapVerification verification) {
        this.gridMap = gridMap;
        this.verification = verification;
        this.tmesh = gridMap.tmesh;
        this.patchMaps = gridMap.patchMaps;
        this.copy = tmesh.topology.copy;
        this.patchByCopyFace = patchMaps.regions.patchIdByCopyFace;
    }

    /**
     * Runs the extraction and packs the result.
     *
     * @throws IllegalStateException when any extraction invariant fails
     * @return the extracted quad mesh
     */
    public ExtractedQuadMesh build() {
        indexFaces();
        generateQuadVertices();
        enumeratePorts();
        traceAllPorts();
        extractQuads();
        requireInvariants();
        Platforms.log("[quad-extract] preimages: vertex=%d edge=%d face=%d total=%d"
                + " | ports=%d fanFallbacks=%d quads=%d euler=%d%n",
                vertexPreimageCount, edgePreimageCount, facePreimageCount, quadVertexCount,
                portCount, fanFallbackConnectionCount, quadCount,
                quadVertexCount - portCount / 2 + quadCount);
        return pack();
    }

    /**
     * Records each region copy face's chart triangle index, which is its position
     * in the patch's region face list.
     */
    private void indexFaces() {
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            List<Integer> regionFaces = patchMaps.regions.copyFacesByPatch.get(patch.patchId);
            for (int faceIndex = 0; faceIndex < regionFaces.size(); faceIndex++) {
                triangleIndexByCopyFace.put(regionFaces.get(faceIndex), faceIndex);
            }
        }
    }

    /**
     * EBC13 Algorithm 3: finds every integer grid point preimage, classified onto
     * the copy vertex, edge interior, or face interior it sits on, each entity
     * enumerated once in the chart of the first patch reaching it.
     */
    private void generateQuadVertices() {
        Set<Integer> seenVertices = new HashSet<>();
        Set<Integer> seenEdges = new HashSet<>();
        double[] cornerU = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] cornerV = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            Map<Integer, Integer> denseByCopyVertex = gridMap.denseByCopyVertexByPatchId[patch.patchId];
            double[] uv = gridMap.uvByPatchId[patch.patchId];
            for (int faceId : patchMaps.regions.copyFacesByPatch.get(patch.patchId)) {
                for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
                    int copyVertex = copy.faceVertexAt(faceId, corner);
                    int dense = denseByCopyVertex.get(copyVertex);
                    cornerU[corner] = uv[dense * GlobalGridMap.GRID_COORDINATES];
                    cornerV[corner] = uv[dense * GlobalGridMap.GRID_COORDINATES + 1];
                    if (seenVertices.add(copyVertex)
                            && cornerU[corner] == Math.rint(cornerU[corner])
                            && cornerV[corner] == Math.rint(cornerV[corner])) {
                        Vector3f position = copy.vertexPosition(copyVertex);
                        int quadVertex = emitQuadVertex(ExtractedQuadMesh.KIND_MESH_VERTEX,
                                copyVertex, patch.patchId, cornerU[corner], cornerV[corner],
                                position.x, position.y, position.z);
                        quadVertexByCopyVertex.put(copyVertex, quadVertex);
                        vertexPreimageCount++;
                    }
                }
                for (int edgeIndex = 0; edgeIndex < HalfEdgeMesh.TRIANGLE_CORNERS; edgeIndex++) {
                    int edgeId = copy.faceEdgeAt(faceId, edgeIndex);
                    if (seenEdges.add(edgeId)) {
                        generateEdgePreimages(patch.patchId, faceId, edgeIndex, edgeId,
                                cornerU, cornerV);
                    }
                }
                generateFacePreimages(patch.patchId, faceId, cornerU, cornerV);
            }
        }
    }

    /**
     * Finds the integer grid points strictly inside one copy edge's chart image by
     * scanning the dominant axis and confirming each candidate exactly collinear.
     *
     * @param patchId   patch whose chart the edge is read in
     * @param faceId    region face providing the corner coordinates
     * @param edgeIndex edge position within the face, endpoints at
     *                  {@code edgeIndex} and the next corner
     * @param edgeId    the copy edge the preimages anchor to
     * @param cornerU   the face's corner grid u values
     * @param cornerV   the face's corner grid v values
     */
    private void generateEdgePreimages(int patchId, int faceId, int edgeIndex, int edgeId,
            double[] cornerU, double[] cornerV) {
        int nextCorner = (edgeIndex + 1) % HalfEdgeMesh.TRIANGLE_CORNERS;
        double startU = cornerU[edgeIndex];
        double startV = cornerV[edgeIndex];
        double endU = cornerU[nextCorner];
        double endV = cornerV[nextCorner];
        boolean dominantIsU = Math.abs(endU - startU) >= Math.abs(endV - startV);
        double startDominant = dominantIsU ? startU : startV;
        double endDominant = dominantIsU ? endU : endV;
        double startOther = dominantIsU ? startV : startU;
        double endOther = dominantIsU ? endV : endU;
        double low = Math.min(startDominant, endDominant);
        double high = Math.max(startDominant, endDominant);
        double[] first = { startU, startV, 1.0 };
        double[] second = { endU, endV, 1.0 };
        double[] candidate = { 0.0, 0.0, 1.0 };
        Vector3f startPosition = copy.vertexPosition(copy.faceVertexAt(faceId, edgeIndex));
        Vector3f endPosition = copy.vertexPosition(copy.faceVertexAt(faceId, nextCorner));
        for (double dominant = Math.floor(low) + 1.0; dominant < high; dominant += 1.0) {
            if (dominant <= low) {
                continue;
            }
            double fraction = (dominant - startDominant) / (endDominant - startDominant);
            double other = startOther + fraction * (endOther - startOther);
            double floorOther = Math.floor(other);
            for (double candidateOther = floorOther; candidateOther <= floorOther + 1.0; candidateOther += 1.0) {
                candidate[0] = dominantIsU ? dominant : candidateOther;
                candidate[1] = dominantIsU ? candidateOther : dominant;
                if (ExactBarycentricOrient.sign(first, second, candidate) != 0) {
                    continue;
                }
                boolean atStart = candidate[0] == startU && candidate[1] == startV;
                boolean atEnd = candidate[0] == endU && candidate[1] == endV;
                if (atStart || atEnd) {
                    continue;
                }
                float lerp = (float) ((dominant - startDominant) / (endDominant - startDominant));
                int quadVertex = emitQuadVertex(ExtractedQuadMesh.KIND_EDGE_INTERIOR, edgeId,
                        patchId, candidate[0], candidate[1],
                        startPosition.x + lerp * (endPosition.x - startPosition.x),
                        startPosition.y + lerp * (endPosition.y - startPosition.y),
                        startPosition.z + lerp * (endPosition.z - startPosition.z));
                quadVerticesByCopyEdge.computeIfAbsent(edgeId, key -> new ArrayList<>())
                        .add(quadVertex);
                edgePreimageCount++;
            }
        }
    }

    /**
     * Finds the integer grid points strictly inside one chart triangle by an
     * integer bounding-box scan with three exact strict side tests.
     *
     * @param patchId patch whose chart the face is read in
     * @param faceId  the copy face the preimages anchor to
     * @param cornerU the face's corner grid u values
     * @param cornerV the face's corner grid v values
     */
    private void generateFacePreimages(int patchId, int faceId, double[] cornerU,
            double[] cornerV) {
        int patchSign = verification.counterClockwiseByPatch[patchId] ? 1 : -1;
        double lowU = Math.ceil(Math.min(cornerU[0], Math.min(cornerU[1], cornerU[2])));
        double highU = Math.floor(Math.max(cornerU[0], Math.max(cornerU[1], cornerU[2])));
        double lowV = Math.ceil(Math.min(cornerV[0], Math.min(cornerV[1], cornerV[2])));
        double highV = Math.floor(Math.max(cornerV[0], Math.max(cornerV[1], cornerV[2])));
        double[] first = { 0.0, 0.0, 1.0 };
        double[] second = { 0.0, 0.0, 1.0 };
        double[] candidate = { 0.0, 0.0, 1.0 };
        Vector3f cornerPosition = new Vector3f();
        for (double u = lowU; u <= highU; u += 1.0) {
            for (double v = lowV; v <= highV; v += 1.0) {
                candidate[0] = u;
                candidate[1] = v;
                boolean inside = true;
                for (int side = 0; side < HalfEdgeMesh.TRIANGLE_CORNERS && inside; side++) {
                    int next = (side + 1) % HalfEdgeMesh.TRIANGLE_CORNERS;
                    first[0] = cornerU[side];
                    first[1] = cornerV[side];
                    second[0] = cornerU[next];
                    second[1] = cornerV[next];
                    inside = ExactBarycentricOrient.sign(first, second, candidate) == patchSign;
                }
                if (!inside) {
                    continue;
                }
                double area01 = triangleDoubleArea(cornerU[0], cornerV[0], cornerU[1],
                        cornerV[1], u, v);
                double area12 = triangleDoubleArea(cornerU[1], cornerV[1], cornerU[2],
                        cornerV[2], u, v);
                double area20 = triangleDoubleArea(cornerU[2], cornerV[2], cornerU[0],
                        cornerV[0], u, v);
                double total = area01 + area12 + area20;
                float x = 0f;
                float y = 0f;
                float z = 0f;
                for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
                    double opposite = corner == 0 ? area12 : corner == 1 ? area20 : area01;
                    float weight = (float) (opposite / total);
                    copy.vertexPosition(copy.faceVertexAt(faceId, corner), cornerPosition);
                    x += weight * cornerPosition.x;
                    y += weight * cornerPosition.y;
                    z += weight * cornerPosition.z;
                }
                int quadVertex = emitQuadVertex(ExtractedQuadMesh.KIND_FACE_INTERIOR, faceId,
                        patchId, u, v, x, y, z);
                quadVerticesByCopyFace.computeIfAbsent(faceId, key -> new ArrayList<>())
                        .add(quadVertex);
                facePreimageCount++;
            }
        }
    }

    /**
     * Twice the signed area of a chart triangle, in plain floating point for
     * barycentric interpolation only.
     *
     * @param firstU  first point grid u
     * @param firstV  first point grid v
     * @param secondU second point grid u
     * @param secondV second point grid v
     * @param thirdU  third point grid u
     * @param thirdV  third point grid v
     * @return the doubled signed area
     */
    private static double triangleDoubleArea(double firstU, double firstV, double secondU,
            double secondV, double thirdU, double thirdV) {
        return (secondU - firstU) * (thirdV - firstV) - (thirdU - firstU) * (secondV - firstV);
    }

    /**
     * Appends one quad vertex to the growth arrays.
     *
     * @param kind     one of the {@link ExtractedQuadMesh} kind constants
     * @param anchorId copy vertex, edge, or face id carrying the preimage
     * @param patchId  patch whose chart the coordinates are stored in
     * @param gridU    integer grid u in that chart
     * @param gridV    integer grid v in that chart
     * @param x        surface x
     * @param y        surface y
     * @param z        surface z
     * @return the new quad vertex id
     */
    private int emitQuadVertex(int kind, int anchorId, int patchId, double gridU, double gridV,
            float x, float y, float z) {
        if (quadVertexCount == vertexKind.length) {
            int grown = vertexKind.length * 2;
            vertexKind = Arrays.copyOf(vertexKind, grown);
            anchorEntityId = Arrays.copyOf(anchorEntityId, grown);
            chartPatchId = Arrays.copyOf(chartPatchId, grown);
            chartU = Arrays.copyOf(chartU, grown);
            chartV = Arrays.copyOf(chartV, grown);
            positionX = Arrays.copyOf(positionX, grown);
            positionY = Arrays.copyOf(positionY, grown);
            positionZ = Arrays.copyOf(positionZ, grown);
        }
        vertexKind[quadVertexCount] = kind;
        anchorEntityId[quadVertexCount] = anchorId;
        chartPatchId[quadVertexCount] = patchId;
        chartU[quadVertexCount] = gridU;
        chartV[quadVertexCount] = gridV;
        positionX[quadVertexCount] = x;
        positionY[quadVertexCount] = y;
        positionZ[quadVertexCount] = z;
        return quadVertexCount++;
    }

    /**
     * The face vertex index sitting at one chart corner, undoing the corner swap
     * that presents a clockwise chart as counter-clockwise.
     *
     * @param chartCorner      corner in normalized chart order
     * @param counterClockwise whether the patch chart winds counter-clockwise
     * @return the face vertex adjacency index
     */
    private static int faceCornerIndex(int chartCorner, boolean counterClockwise) {
        return counterClockwise ? chartCorner : CLOCKWISE_CORNER_PERMUTATION[chartCorner];
    }

    /**
     * The copy edge under one normalized chart edge, which runs from the chart
     * corner to its successor.
     *
     * @param faceId           copy face holding the edge
     * @param chartEdge        edge index in normalized chart order
     * @param counterClockwise whether the patch chart winds counter-clockwise
     * @return the copy edge id
     */
    private int chartEdgeCopyEdge(int faceId, int chartEdge, boolean counterClockwise) {
        return copy.faceEdgeAt(faceId, counterClockwise ? chartEdge : 2 - chartEdge);
    }

    /**
     * Reads one face's corner grid coordinates in normalized chart order, so the
     * corners always wind counter-clockwise.
     *
     * @param patchId patch whose chart is read
     * @param faceId  copy face to read
     * @param cornerU receives the corner grid u values
     * @param cornerV receives the corner grid v values
     */
    private void readChartCorners(int patchId, int faceId, double[] cornerU, double[] cornerV) {
        boolean counterClockwise = verification.counterClockwiseByPatch[patchId];
        Map<Integer, Integer> denseByCopyVertex = gridMap.denseByCopyVertexByPatchId[patchId];
        double[] uv = gridMap.uvByPatchId[patchId];
        for (int chartCorner = 0; chartCorner < HalfEdgeMesh.TRIANGLE_CORNERS; chartCorner++) {
            int copyVertex = copy.faceVertexAt(faceId,
                    faceCornerIndex(chartCorner, counterClockwise));
            int dense = denseByCopyVertex.get(copyVertex);
            cornerU[chartCorner] = uv[dense * GlobalGridMap.GRID_COORDINATES];
            cornerV[chartCorner] = uv[dense * GlobalGridMap.GRID_COORDINATES + 1];
        }
    }

    /**
     * The patch on the other side of an arc.
     *
     * @param arcId     arc crossed
     * @param fromPatch patch being left
     * @return the opposite patch id
     */
    private int otherPatchAcross(int arcId, int fromPatch) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        if (arc.leftPatchId == fromPatch) {
            return arc.rightPatchId;
        }
        if (arc.rightPatchId == fromPatch) {
            return arc.leftPatchId;
        }
        throw new IllegalStateException("arc " + arcId + " does not bound patch " + fromPatch);
    }

    /**
     * Maps a chart point across an arc's transition, exactly, in place.
     *
     * @param arcId     arc crossed
     * @param fromPatch patch the point is currently expressed in
     * @param pointUv   the point, replaced by its image in the opposite chart
     */
    private void mapPointAcrossArc(int arcId, int fromPatch, double[] pointUv) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        int turns = verification.transitionTurnsByArcId[arcId];
        int translationU = verification.transitionTranslationUByArcId[arcId];
        int translationV = verification.transitionTranslationVByArcId[arcId];
        if (arc.rightPatchId == fromPatch) {
            IntegerGridMap.rotate(turns, pointUv[0], pointUv[1], pointUv);
            pointUv[0] += translationU;
            pointUv[1] += translationV;
        } else {
            IntegerGridMap.rotate((IntegerGridMap.QUARTER_TURNS - turns)
                    % IntegerGridMap.QUARTER_TURNS,
                    pointUv[0] - translationU, pointUv[1] - translationV, pointUv);
        }
    }

    /**
     * Maps a direction's quarter turns across an arc's transition.
     *
     * @param arcId     arc crossed
     * @param fromPatch patch the direction is currently expressed in
     * @param turns     direction as quarter turns
     * @return the direction's quarter turns in the opposite chart
     */
    private int mapTurnsAcrossArc(int arcId, int fromPatch, int turns) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        int transition = verification.transitionTurnsByArcId[arcId];
        if (arc.rightPatchId == fromPatch) {
            return (turns + transition) % IntegerGridMap.QUARTER_TURNS;
        }
        return (turns + IntegerGridMap.QUARTER_TURNS - transition)
                % IntegerGridMap.QUARTER_TURNS;
    }

    /**
     * EBC13 Algorithm 4: enumerates each quad vertex's outgoing iso-line directions
     * clockwise, checking the count against the T-mesh valence.
     *
     * @throws IllegalStateException when a vertex's port count is wrong
     */
    private void enumeratePorts() {
        portStart = new int[quadVertexCount + 1];
        for (int quadVertex = 0; quadVertex < quadVertexCount; quadVertex++) {
            portStart[quadVertex] = portCount;
            if (vertexKind[quadVertex] == ExtractedQuadMesh.KIND_FACE_INTERIOR) {
                int faceId = anchorEntityId[quadVertex];
                emitPort(quadVertex, faceId, 0, chartU[quadVertex], chartV[quadVertex]);
                emitPort(quadVertex, faceId, AXIS_DIRECTIONS - 1, chartU[quadVertex],
                        chartV[quadVertex]);
                emitPort(quadVertex, faceId, 2, chartU[quadVertex], chartV[quadVertex]);
                emitPort(quadVertex, faceId, 1, chartU[quadVertex], chartV[quadVertex]);
            } else if (vertexKind[quadVertex] == ExtractedQuadMesh.KIND_EDGE_INTERIOR) {
                edgePorts(quadVertex);
            } else {
                vertexPorts(quadVertex);
            }
            int emitted = portCount - portStart[quadVertex];
            int expected = expectedPortCount(quadVertex);
            if (emitted != expected) {
                throw new IllegalStateException("quad vertex " + quadVertex + " of kind "
                        + vertexKind[quadVertex] + " on entity " + anchorEntityId[quadVertex]
                        + " has " + emitted + " ports, expected " + expected);
            }
        }
        portStart[quadVertexCount] = portCount;
        portConnection = new int[portCount];
        connectionTurns = new int[portCount];
        connectionTranslationU = new int[portCount];
        connectionTranslationV = new int[portCount];
        Arrays.fill(portConnection, ExtractedQuadMesh.NONE);
    }

    /**
     * How many iso-line directions must leave a quad vertex: the T-mesh node
     * valence on a layout node, four everywhere else.
     *
     * @param quadVertex quad vertex to size
     * @return the expected port count
     */
    private int expectedPortCount(int quadVertex) {
        if (vertexKind[quadVertex] == ExtractedQuadMesh.KIND_MESH_VERTEX) {
            int nodeId = tmesh.topology.ownerNodeByCopyVertex[anchorEntityId[quadVertex]];
            if (nodeId != EmbeddedMeshTopology.UNCLAIMED && tmesh.nodes.get(nodeId).alive) {
                return tmesh.degree(nodeId);
            }
        }
        return AXIS_DIRECTIONS;
    }

    /**
     * Emits the ports of an edge-interior quad vertex: each incident face's
     * half-plane wedge claims its directions, the two wedges concatenated
     * clockwise.
     *
     * @param quadVertex edge-interior quad vertex
     */
    private void edgePorts(int quadVertex) {
        int edgeId = anchorEntityId[quadVertex];
        int halfEdge = copy.edgeHalfEdge(edgeId);
        double[] apex = new double[GlobalGridMap.GRID_COORDINATES];
        double[] cornerU = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] cornerV = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        for (int side = 0; side < 2; side++) {
            int faceId = copy.halfEdgeFace(side == 0 ? halfEdge : copy.halfEdgeTwin(halfEdge));
            if (faceId < 0) {
                continue;
            }
            int patchId = patchByCopyFace.get(faceId);
            apex[0] = chartU[quadVertex];
            apex[1] = chartV[quadVertex];
            if (patchId != chartPatchId[quadVertex]) {
                mapPointAcrossArc(tmesh.topology.ownerArcByCopyEdge[edgeId],
                        chartPatchId[quadVertex], apex);
            }
            boolean counterClockwise = verification.counterClockwiseByPatch[patchId];
            readChartCorners(patchId, faceId, cornerU, cornerV);
            int chartEdge = 0;
            while (chartEdge < HalfEdgeMesh.TRIANGLE_CORNERS
                    && chartEdgeCopyEdge(faceId, chartEdge, counterClockwise) != edgeId) {
                chartEdge++;
            }
            int nextCorner = (chartEdge + 1) % HalfEdgeMesh.TRIANGLE_CORNERS;
            wedgePorts(quadVertex, faceId, apex[0], apex[1], cornerU[nextCorner],
                    cornerV[nextCorner], cornerU[chartEdge], cornerV[chartEdge]);
        }
    }

    /**
     * Emits the ports of a mesh-vertex quad vertex by walking its corner fan
     * clockwise, each corner wedge claiming its directions.
     *
     * @param quadVertex mesh-vertex quad vertex
     * @throws IllegalStateException when the fan hits a mesh boundary or fails to
     *                               close
     */
    private void vertexPorts(int quadVertex) {
        int copyVertex = anchorEntityId[quadVertex];
        int startFace = copy.vertexFaceAt(copyVertex, 0);
        double[] cornerU = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] cornerV = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        int faceId = startFace;
        int guard = copy.vertexFaceCount(copyVertex) + 1;
        do {
            int patchId = patchByCopyFace.get(faceId);
            boolean counterClockwise = verification.counterClockwiseByPatch[patchId];
            readChartCorners(patchId, faceId, cornerU, cornerV);
            int chartCorner = 0;
            while (chartCorner < HalfEdgeMesh.TRIANGLE_CORNERS && copy.faceVertexAt(faceId,
                    faceCornerIndex(chartCorner, counterClockwise)) != copyVertex) {
                chartCorner++;
            }
            int clockwiseCorner = (chartCorner + 1) % HalfEdgeMesh.TRIANGLE_CORNERS;
            int counterClockwiseCorner = (chartCorner + 2) % HalfEdgeMesh.TRIANGLE_CORNERS;
            wedgePorts(quadVertex, faceId, cornerU[chartCorner], cornerV[chartCorner],
                    cornerU[clockwiseCorner], cornerV[clockwiseCorner],
                    cornerU[counterClockwiseCorner], cornerV[counterClockwiseCorner]);
            int crossEdge = chartEdgeCopyEdge(faceId, chartCorner, counterClockwise);
            faceId = neighborFace(faceId, crossEdge);
            if (faceId < 0) {
                throw new IllegalStateException("copy vertex " + copyVertex + " sits on a mesh"
                        + " boundary, which the extraction does not support");
            }
            guard--;
        } while (faceId != startFace && guard > 0);
        if (faceId != startFace) {
            throw new IllegalStateException("the corner fan of copy vertex " + copyVertex
                    + " did not close");
        }
    }

    /**
     * Emits the directions one wedge claims, clockwise: strictly inside it, or
     * collinear with its clockwise boundary, found by stepping clockwise from a
     * direction outside the wedge (EBC13 Algorithm 4).
     *
     * @param quadVertex owning quad vertex
     * @param faceId     copy face whose chart the directions live in
     * @param apexU      the vertex's grid u in that chart
     * @param apexV      the vertex's grid v in that chart
     * @param clockwiseU grid u of the clockwise wedge boundary point
     * @param clockwiseV grid v of the clockwise wedge boundary point
     * @param counterUcw grid u of the counter-clockwise wedge boundary point
     * @param counterVcw grid v of the counter-clockwise wedge boundary point
     * @throws IllegalStateException when every direction claims the wedge
     */
    private void wedgePorts(int quadVertex, int faceId, double apexU, double apexV,
            double clockwiseU, double clockwiseV, double counterUcw, double counterVcw) {
        int outside = ExtractedQuadMesh.NONE;
        for (int turns = 0; turns < AXIS_DIRECTIONS; turns++) {
            if (!wedgeClaims(turns, apexU, apexV, clockwiseU, clockwiseV, counterUcw,
                    counterVcw)) {
                outside = turns;
                break;
            }
        }
        if (outside == ExtractedQuadMesh.NONE) {
            throw new IllegalStateException("every direction claims the wedge of quad vertex "
                    + quadVertex + " in face " + faceId + ", which a triangle corner forbids");
        }
        for (int step = 1; step < AXIS_DIRECTIONS; step++) {
            int turns = ((outside - step) % AXIS_DIRECTIONS + AXIS_DIRECTIONS)
                    % AXIS_DIRECTIONS;
            if (wedgeClaims(turns, apexU, apexV, clockwiseU, clockwiseV, counterUcw,
                    counterVcw)) {
                emitPort(quadVertex, faceId, turns, apexU, apexV);
            }
        }
    }

    /**
     * Whether a wedge claims a direction: strictly between its boundaries, or
     * collinear with the clockwise boundary and pointing the same way. The
     * counter-clockwise boundary belongs to the neighbouring wedge.
     *
     * @param turns      direction as quarter turns
     * @param apexU      wedge apex grid u
     * @param apexV      wedge apex grid v
     * @param clockwiseU clockwise boundary point grid u
     * @param clockwiseV clockwise boundary point grid v
     * @param counterU   counter-clockwise boundary point grid u
     * @param counterV   counter-clockwise boundary point grid v
     * @return whether the direction belongs to this wedge
     */
    private static boolean wedgeClaims(int turns, double apexU, double apexV, double clockwiseU,
            double clockwiseV, double counterU, double counterV) {
        double[] apex = { apexU, apexV, 1.0 };
        double[] tip = { apexU + DIRECTION_U[turns], apexV + DIRECTION_V[turns], 1.0 };
        double[] clockwiseBoundary = { clockwiseU, clockwiseV, 1.0 };
        double[] counterBoundary = { counterU, counterV, 1.0 };
        int sideClockwise = ExactBarycentricOrient.sign(apex, clockwiseBoundary, tip);
        int sideCounter = ExactBarycentricOrient.sign(apex, tip, counterBoundary);
        if (sideClockwise > 0 && sideCounter > 0) {
            return true;
        }
        if (sideClockwise != 0) {
            return false;
        }
        double along = DIRECTION_U[turns] != 0
                ? (clockwiseU - apexU) * DIRECTION_U[turns]
                : (clockwiseV - apexV) * DIRECTION_V[turns];
        return along > 0.0;
    }

    /**
     * Appends one port to the growth arrays.
     *
     * @param owner  owning quad vertex
     * @param faceId copy face whose chart holds the direction
     * @param turns  direction as quarter turns in that chart
     * @param apexU  the owner's grid u in that chart
     * @param apexV  the owner's grid v in that chart
     */
    private void emitPort(int owner, int faceId, int turns, double apexU, double apexV) {
        if (portCount == portOwner.length) {
            int grown = portOwner.length * 2;
            portOwner = Arrays.copyOf(portOwner, grown);
            portFace = Arrays.copyOf(portFace, grown);
            portDirectionTurns = Arrays.copyOf(portDirectionTurns, grown);
            portChartU = Arrays.copyOf(portChartU, grown);
            portChartV = Arrays.copyOf(portChartV, grown);
        }
        portOwner[portCount] = owner;
        portFace[portCount] = faceId;
        portDirectionTurns[portCount] = turns;
        portChartU[portCount] = apexU;
        portChartV[portCount] = apexV;
        portCount++;
    }

    /**
     * The face across a copy edge, or a negative id at a mesh boundary.
     *
     * @param faceId face being left
     * @param edgeId copy edge crossed
     * @return the neighbouring face id
     */
    private int neighborFace(int faceId, int edgeId) {
        int halfEdge = copy.edgeHalfEdge(edgeId);
        int face = copy.halfEdgeFace(halfEdge);
        return face != faceId ? face : copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge));
    }

    /**
     * EBC13 Algorithm 5: traces every untraced port's unit iso-segment to the
     * opposite port and connects the pair.
     */
    private void traceAllPorts() {
        for (int port = 0; port < portCount; port++) {
            if (portConnection[port] == ExtractedQuadMesh.NONE) {
                tracePort(port);
            }
        }
    }

    /**
     * Traces one port's unit segment through the charts: cross triangle edges by
     * exact side tests, apply arc transitions, and connect to the opposite port at
     * the segment's end.
     *
     * @param port port to trace
     * @throws IllegalStateException when the segment strands, hits a boundary, or
     *                               its endpoint has no matching quad vertex
     */
    private void tracePort(int port) {
        int patchId = patchByCopyFace.get(portFace[port]);
        int faceId = portFace[port];
        int turns = portDirectionTurns[port];
        double[] segmentStart = { portChartU[port], portChartV[port] };
        double[] segmentEnd = { segmentStart[0] + DIRECTION_U[turns],
                segmentStart[1] + DIRECTION_V[turns] };
        int entryEdgeId = ExtractedQuadMesh.NONE;
        int accumulatedTurns = 0;
        int accumulatedU = 0;
        int accumulatedV = 0;
        double[] cornerU = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] cornerV = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] first = { 0.0, 0.0, 1.0 };
        double[] second = { 0.0, 0.0, 1.0 };
        double[] third = { 0.0, 0.0, 1.0 };
        for (int step = 0; step < 2 * copy.faceCount(); step++) {
            readChartCorners(patchId, faceId, cornerU, cornerV);
            int insideCount = 0;
            int zeroMask = 0;
            for (int side = 0; side < HalfEdgeMesh.TRIANGLE_CORNERS; side++) {
                int next = (side + 1) % HalfEdgeMesh.TRIANGLE_CORNERS;
                first[0] = cornerU[side];
                first[1] = cornerV[side];
                second[0] = cornerU[next];
                second[1] = cornerV[next];
                third[0] = segmentEnd[0];
                third[1] = segmentEnd[1];
                int sign = ExactBarycentricOrient.sign(first, second, third);
                if (sign > 0) {
                    insideCount++;
                } else if (sign == 0) {
                    zeroMask |= 1 << side;
                }
            }
            if (insideCount + Integer.bitCount(zeroMask) == HalfEdgeMesh.TRIANGLE_CORNERS) {
                connectArrival(port, patchId, faceId, turns, segmentEnd, zeroMask,
                        accumulatedTurns, accumulatedU, accumulatedV);
                return;
            }
            int exitChartEdge = pickNextEdge(faceId, patchId, entryEdgeId, segmentStart,
                    segmentEnd, cornerU, cornerV, first, second, third);
            boolean counterClockwise = verification.counterClockwiseByPatch[patchId];
            int crossedEdgeId = chartEdgeCopyEdge(faceId, exitChartEdge, counterClockwise);
            int nextFace = neighborFace(faceId, crossedEdgeId);
            if (nextFace < 0) {
                throw new IllegalStateException("port " + port + " traced into a mesh boundary"
                        + " at copy edge " + crossedEdgeId);
            }
            int ownerArc = tmesh.topology.ownerArcByCopyEdge[crossedEdgeId];
            if (ownerArc != EmbeddedMeshTopology.UNCLAIMED) {
                int transition = verification.transitionTurnsByArcId[ownerArc];
                if (transition == IntegerGridMap.NOT_PLACED) {
                    throw new IllegalStateException("port " + port + " crossed arc " + ownerArc
                            + " which has no resolved transition");
                }
                mapPointAcrossArc(ownerArc, patchId, segmentStart);
                mapPointAcrossArc(ownerArc, patchId, segmentEnd);
                int mappedTurns = mapTurnsAcrossArc(ownerArc, patchId, turns);
                accumulatedTurns = composeCrossing(ownerArc, patchId, accumulatedTurns);
                int[] translation = crossingTranslation(ownerArc, patchId, accumulatedU,
                        accumulatedV);
                accumulatedU = translation[0];
                accumulatedV = translation[1];
                turns = mappedTurns;
                patchId = otherPatchAcross(ownerArc, patchId);
            } else if (!patchByCopyFace.get(nextFace).equals(patchId)) {
                throw new IllegalStateException("port " + port + " crossed unclaimed copy edge "
                        + crossedEdgeId + " into a different patch");
            }
            entryEdgeId = crossedEdgeId;
            faceId = nextFace;
        }
        throw new IllegalStateException("port " + port + " did not terminate within twice the"
                + " face count");
    }

    /**
     * The accumulated automorphism's quarter turns after crossing an arc.
     *
     * @param arcId            arc crossed
     * @param fromPatch        patch being left
     * @param accumulatedTurns quarter turns accumulated so far
     * @return the composed quarter turns
     */
    private int composeCrossing(int arcId, int fromPatch, int accumulatedTurns) {
        return mapTurnsAcrossArc(arcId, fromPatch, accumulatedTurns);
    }

    /**
     * The accumulated automorphism's translation after crossing an arc, the
     * crossing applied on the outside of the existing transform.
     *
     * @param arcId        arc crossed
     * @param fromPatch    patch being left
     * @param accumulatedU translation grid u accumulated so far
     * @param accumulatedV translation grid v accumulated so far
     * @return the composed translation as {@code {u, v}}
     */
    private int[] crossingTranslation(int arcId, int fromPatch, int accumulatedU,
            int accumulatedV) {
        double[] mapped = { accumulatedU, accumulatedV };
        mapPointAcrossArc(arcId, fromPatch, mapped);
        return new int[] { (int) mapped[0], (int) mapped[1] };
    }

    /**
     * EBC13's PickNextEdge: the chart edge the directed segment leaves through,
     * preferring the candidate with fewest corners exactly on the segment's line so
     * a collinear mesh edge is pivoted past rather than slid along.
     *
     * @param faceId       face being traversed, for the error message
     * @param patchId      patch whose chart is current, for the error message
     * @param entryEdgeId  copy edge the segment entered through, excluded
     * @param segmentStart segment start in the current chart
     * @param segmentEnd   segment end in the current chart
     * @param cornerU      the face's corner grid u values in chart order
     * @param cornerV      the face's corner grid v values in chart order
     * @param first        scratch triple
     * @param second       scratch triple
     * @param third        scratch triple
     * @throws IllegalStateException when no edge admits the segment
     * @return the exit edge's chart index
     */
    private int pickNextEdge(int faceId, int patchId, int entryEdgeId, double[] segmentStart,
            double[] segmentEnd, double[] cornerU, double[] cornerV, double[] first,
            double[] second, double[] third) {
        boolean counterClockwise = verification.counterClockwiseByPatch[patchId];
        first[0] = segmentStart[0];
        first[1] = segmentStart[1];
        second[0] = segmentEnd[0];
        second[1] = segmentEnd[1];
        int bestEdge = ExtractedQuadMesh.NONE;
        int bestZeros = Integer.MAX_VALUE;
        for (int chartEdge = 0; chartEdge < HalfEdgeMesh.TRIANGLE_CORNERS; chartEdge++) {
            if (chartEdgeCopyEdge(faceId, chartEdge, counterClockwise) == entryEdgeId) {
                continue;
            }
            int next = (chartEdge + 1) % HalfEdgeMesh.TRIANGLE_CORNERS;
            third[0] = cornerU[chartEdge];
            third[1] = cornerV[chartEdge];
            int signFrom = ExactBarycentricOrient.sign(first, second, third);
            third[0] = cornerU[next];
            third[1] = cornerV[next];
            int signTo = ExactBarycentricOrient.sign(first, second, third);
            if (signFrom > 0 || signTo < 0) {
                continue;
            }
            int zeros = (signFrom == 0 ? 1 : 0) + (signTo == 0 ? 1 : 0);
            if (zeros < bestZeros) {
                bestZeros = zeros;
                bestEdge = chartEdge;
            }
        }
        if (bestEdge == ExtractedQuadMesh.NONE) {
            throw new IllegalStateException("segment (" + segmentStart[0] + ", "
                    + segmentStart[1] + ") -> (" + segmentEnd[0] + ", " + segmentEnd[1]
                    + ") strands in face " + faceId + " of patch " + patchId);
        }
        return bestEdge;
    }

    /**
     * Connects a traced port to the opposite port at its arrival point: classify
     * the point onto its entity, look up the quad vertex, and find the port holding
     * the reverse direction, following a collinear edge when the reverse wedge lies
     * in a neighbouring chart.
     *
     * @param port             port whose trace arrived
     * @param patchId          chart the segment ended in
     * @param faceId           face the segment ended in
     * @param turns            traced direction in that chart
     * @param segmentEnd       arrival point in that chart
     * @param zeroMask         bit per chart side the point lies exactly on
     * @param accumulatedTurns automorphism turns from the port's chart to this one
     * @param accumulatedU     automorphism grid u translation
     * @param accumulatedV     automorphism grid v translation
     * @throws IllegalStateException when no quad vertex or opposite port matches
     */
    private void connectArrival(int port, int patchId, int faceId, int turns,
            double[] segmentEnd, int zeroMask, int accumulatedTurns, int accumulatedU,
            int accumulatedV) {
        boolean counterClockwise = verification.counterClockwiseByPatch[patchId];
        int arrivalVertex = arrivalQuadVertex(patchId, faceId, segmentEnd, zeroMask,
                counterClockwise);
        int reverseTurns = (turns + 2) % IntegerGridMap.QUARTER_TURNS;
        int opposite = findPort(arrivalVertex, faceId, reverseTurns);
        if (opposite == ExtractedQuadMesh.NONE) {
            for (int candidateEdge : arrivalIncidentEdges(faceId, zeroMask, counterClockwise)) {
                int neighbor = neighborFace(faceId, candidateEdge);
                if (neighbor < 0) {
                    continue;
                }
                int ownerArc = tmesh.topology.ownerArcByCopyEdge[candidateEdge];
                int neighborTurns = reverseTurns;
                if (ownerArc != EmbeddedMeshTopology.UNCLAIMED) {
                    neighborTurns = mapTurnsAcrossArc(ownerArc, patchId, reverseTurns);
                }
                opposite = findPort(arrivalVertex, neighbor, neighborTurns);
                if (opposite != ExtractedQuadMesh.NONE) {
                    if (ownerArc != EmbeddedMeshTopology.UNCLAIMED) {
                        mapPointAcrossArc(ownerArc, patchId, segmentEnd);
                        accumulatedTurns = composeCrossing(ownerArc, patchId, accumulatedTurns);
                        int[] translation = crossingTranslation(ownerArc, patchId, accumulatedU,
                                accumulatedV);
                        accumulatedU = translation[0];
                        accumulatedV = translation[1];
                    }
                    fanFallbackConnectionCount++;
                    break;
                }
            }
        }
        if (opposite == ExtractedQuadMesh.NONE) {
            throw new IllegalStateException("port " + port + " arrived at quad vertex "
                    + arrivalVertex + " in face " + faceId + " but no opposite port holds the"
                    + " reverse direction");
        }
        if (segmentEnd[0] != portChartU[opposite] || segmentEnd[1] != portChartV[opposite]) {
            throw new IllegalStateException("port " + port + " arrived at ("
                    + segmentEnd[0] + ", " + segmentEnd[1] + ") but the opposite port sits at ("
                    + portChartU[opposite] + ", " + portChartV[opposite] + ")");
        }
        if (portConnection[opposite] != ExtractedQuadMesh.NONE) {
            throw new IllegalStateException("port " + port + " connects to port " + opposite
                    + " which is already connected to " + portConnection[opposite]);
        }
        portConnection[port] = opposite;
        portConnection[opposite] = port;
        connectionTurns[port] = accumulatedTurns;
        connectionTranslationU[port] = accumulatedU;
        connectionTranslationV[port] = accumulatedV;
        int inverseTurns = (IntegerGridMap.QUARTER_TURNS - accumulatedTurns)
                % IntegerGridMap.QUARTER_TURNS;
        double[] inverseTranslation = new double[GlobalGridMap.GRID_COORDINATES];
        IntegerGridMap.rotate(inverseTurns, -accumulatedU, -accumulatedV, inverseTranslation);
        connectionTurns[opposite] = inverseTurns;
        connectionTranslationU[opposite] = (int) inverseTranslation[0];
        connectionTranslationV[opposite] = (int) inverseTranslation[1];
        if (quadEdgeCount == quadEdgeVertexA.length) {
            quadEdgeVertexA = Arrays.copyOf(quadEdgeVertexA, quadEdgeCount * 2);
            quadEdgeVertexB = Arrays.copyOf(quadEdgeVertexB, quadEdgeCount * 2);
        }
        quadEdgeVertexA[quadEdgeCount] = portOwner[port];
        quadEdgeVertexB[quadEdgeCount] = portOwner[opposite];
        quadEdgeCount++;
    }

    /**
     * The quad vertex at a trace's arrival point, classified by which chart sides
     * the point lies exactly on: none is a face preimage, one an edge preimage, two
     * the shared corner's vertex preimage.
     *
     * @param patchId          chart the point is expressed in
     * @param faceId           face containing the point
     * @param segmentEnd       the arrival point
     * @param zeroMask         bit per chart side the point lies exactly on
     * @param counterClockwise whether the patch chart winds counter-clockwise
     * @throws IllegalStateException when no generated quad vertex matches
     * @return the arrival quad vertex id
     */
    private int arrivalQuadVertex(int patchId, int faceId, double[] segmentEnd, int zeroMask,
            boolean counterClockwise) {
        int zeroCount = Integer.bitCount(zeroMask);
        if (zeroCount == 0) {
            List<Integer> candidates = quadVerticesByCopyFace.get(faceId);
            if (candidates != null) {
                for (int candidate : candidates) {
                    if (chartU[candidate] == segmentEnd[0]
                            && chartV[candidate] == segmentEnd[1]) {
                        return candidate;
                    }
                }
            }
            throw new IllegalStateException("no face preimage at (" + segmentEnd[0] + ", "
                    + segmentEnd[1] + ") in face " + faceId);
        }
        if (zeroCount == 1) {
            int chartEdge = Integer.numberOfTrailingZeros(zeroMask);
            int edgeId = chartEdgeCopyEdge(faceId, chartEdge, counterClockwise);
            List<Integer> candidates = quadVerticesByCopyEdge.get(edgeId);
            double[] mapped = new double[GlobalGridMap.GRID_COORDINATES];
            if (candidates != null) {
                for (int candidate : candidates) {
                    mapped[0] = chartU[candidate];
                    mapped[1] = chartV[candidate];
                    if (chartPatchId[candidate] != patchId) {
                        mapPointAcrossArc(tmesh.topology.ownerArcByCopyEdge[edgeId],
                                chartPatchId[candidate], mapped);
                    }
                    if (mapped[0] == segmentEnd[0] && mapped[1] == segmentEnd[1]) {
                        return candidate;
                    }
                }
            }
            throw new IllegalStateException("no edge preimage at (" + segmentEnd[0] + ", "
                    + segmentEnd[1] + ") on copy edge " + edgeId);
        }
        int sharedCorner = sharedZeroCorner(zeroMask);
        int copyVertex = copy.faceVertexAt(faceId,
                faceCornerIndex(sharedCorner, counterClockwise));
        Integer quadVertex = quadVertexByCopyVertex.get(copyVertex);
        if (quadVertex == null) {
            throw new IllegalStateException("trace arrived exactly on copy vertex " + copyVertex
                    + " which holds no preimage");
        }
        return quadVertex;
    }

    /**
     * The chart corner shared by the two zero sides of an arrival mask.
     *
     * @param zeroMask bit per chart side the point lies exactly on
     * @return the shared corner's chart index
     */
    private static int sharedZeroCorner(int zeroMask) {
        for (int side = 0; side < HalfEdgeMesh.TRIANGLE_CORNERS; side++) {
            int previous = (side + HalfEdgeMesh.TRIANGLE_CORNERS - 1) % HalfEdgeMesh.TRIANGLE_CORNERS;
            if ((zeroMask & 1 << side) != 0 && (zeroMask & 1 << previous) != 0) {
                return side;
            }
        }
        throw new IllegalStateException("zero mask " + zeroMask + " names no shared corner");
    }

    /**
     * The copy edges of the arrival face incident to the arrival entity, the
     * candidates a collinear reverse direction may have been claimed across.
     *
     * @param faceId           face the trace ended in
     * @param zeroMask         bit per chart side the point lies exactly on
     * @param counterClockwise whether the patch chart winds counter-clockwise
     * @return the candidate copy edge ids
     */
    private int[] arrivalIncidentEdges(int faceId, int zeroMask, boolean counterClockwise) {
        int zeroCount = Integer.bitCount(zeroMask);
        if (zeroCount == 0) {
            return new int[0];
        }
        if (zeroCount == 1) {
            int chartEdge = Integer.numberOfTrailingZeros(zeroMask);
            return new int[] { chartEdgeCopyEdge(faceId, chartEdge, counterClockwise) };
        }
        int sharedCorner = sharedZeroCorner(zeroMask);
        int previous = (sharedCorner + HalfEdgeMesh.TRIANGLE_CORNERS - 1) % HalfEdgeMesh.TRIANGLE_CORNERS;
        return new int[] { chartEdgeCopyEdge(faceId, sharedCorner, counterClockwise),
                chartEdgeCopyEdge(faceId, previous, counterClockwise) };
    }

    /**
     * The port of a quad vertex holding one direction in one face's chart.
     *
     * @param quadVertex quad vertex whose ports are scanned
     * @param faceId     required port face
     * @param turns      required direction as quarter turns
     * @return the port id, or {@link ExtractedQuadMesh#NONE}
     */
    private int findPort(int quadVertex, int faceId, int turns) {
        for (int port = portStart[quadVertex]; port < portStart[quadVertex + 1]; port++) {
            if (portFace[port] == faceId && portDirectionTurns[port] == turns) {
                return port;
            }
        }
        return ExtractedQuadMesh.NONE;
    }

    /**
     * EBC13 Algorithm 6: extracts the quads by cycling connections, turning left at
     * every vertex by taking the next port in its clockwise list. The fold-free map
     * forces every cycle to close after exactly four corners.
     *
     * @throws IllegalStateException when a cycle is not a quad
     */
    private void extractQuads() {
        boolean[] departed = new boolean[portCount];
        for (int start = 0; start < portCount; start++) {
            if (departed[start]) {
                continue;
            }
            int current = start;
            int corners = 0;
            int[] corner = new int[ExtractedQuadMesh.QUAD_CORNERS];
            while (true) {
                departed[current] = true;
                int arrival = portConnection[current];
                if (corners == ExtractedQuadMesh.QUAD_CORNERS) {
                    throw new IllegalStateException("port cycle through port " + start
                            + " exceeds four corners; the fold-free map forbids this");
                }
                corner[corners] = portOwner[arrival];
                corners++;
                current = nextClockwisePort(arrival);
                if (current == start) {
                    break;
                }
            }
            if (corners != ExtractedQuadMesh.QUAD_CORNERS) {
                throw new IllegalStateException("port cycle through port " + start + " closed"
                        + " after " + corners + " corners instead of four");
            }
            if (quadCount * ExtractedQuadMesh.QUAD_CORNERS == quadCorner.length) {
                quadCorner = Arrays.copyOf(quadCorner, quadCorner.length * 2);
            }
            for (int index = 0; index < ExtractedQuadMesh.QUAD_CORNERS; index++) {
                quadCorner[quadCount * ExtractedQuadMesh.QUAD_CORNERS + index] = corner[index];
            }
            quadCount++;
        }
    }

    /**
     * The next port clockwise around a port's owner.
     *
     * @param port port whose successor is taken
     * @return the cyclically next port id
     */
    private int nextClockwisePort(int port) {
        int owner = portOwner[port];
        int next = port + 1;
        return next == portStart[owner + 1] ? portStart[owner] : next;
    }

    /**
     * Final whole-mesh invariants: every port connected and consumed, the quad
     * count matching the quantization, and the Euler characteristic matching the
     * surface.
     *
     * @throws IllegalStateException when any invariant fails
     */
    private void requireInvariants() {
        for (int port = 0; port < portCount; port++) {
            if (portConnection[port] == ExtractedQuadMesh.NONE) {
                throw new IllegalStateException("port " + port + " was never connected");
            }
        }
        if (quadCount * ExtractedQuadMesh.QUAD_CORNERS != portCount) {
            throw new IllegalStateException("extracted " + quadCount + " quads from "
                    + portCount + " ports; every port must depart exactly one quad edge");
        }
        int surfaceEuler = copy.vertexCount() - copy.edgeCount() + copy.faceCount();
        int quadEuler = quadVertexCount - quadEdgeCount + quadCount;
        if (quadEuler != surfaceEuler) {
            throw new IllegalStateException("quad mesh Euler characteristic " + quadEuler
                    + " does not match the surface's " + surfaceEuler);
        }
        if (expectedQuadCount != ExtractedQuadMesh.NONE && quadCount != expectedQuadCount) {
            throw new IllegalStateException("extracted " + quadCount + " quads but the"
                    + " quantization prescribes " + expectedQuadCount);
        }
    }

    /**
     * Packs the growth arrays into the result.
     *
     * @return the extracted quad mesh
     */
    private ExtractedQuadMesh pack() {
        ExtractedQuadMesh mesh = new ExtractedQuadMesh();
        mesh.quadVertexCount = quadVertexCount;
        mesh.vertexKind = Arrays.copyOf(vertexKind, quadVertexCount);
        mesh.anchorEntityId = Arrays.copyOf(anchorEntityId, quadVertexCount);
        mesh.chartPatchId = Arrays.copyOf(chartPatchId, quadVertexCount);
        mesh.chartU = Arrays.copyOf(chartU, quadVertexCount);
        mesh.chartV = Arrays.copyOf(chartV, quadVertexCount);
        mesh.positions = new float[quadVertexCount * ExtractedQuadMesh.POSITION_FLOATS];
        for (int quadVertex = 0; quadVertex < quadVertexCount; quadVertex++) {
            mesh.positions[quadVertex * ExtractedQuadMesh.POSITION_FLOATS] = positionX[quadVertex];
            mesh.positions[quadVertex * ExtractedQuadMesh.POSITION_FLOATS + 1] = positionY[quadVertex];
            mesh.positions[quadVertex * ExtractedQuadMesh.POSITION_FLOATS + 2] = positionZ[quadVertex];
        }
        mesh.portCount = portCount;
        mesh.portStart = portStart;
        mesh.portOwner = Arrays.copyOf(portOwner, portCount);
        mesh.portFace = Arrays.copyOf(portFace, portCount);
        mesh.portDirectionTurns = Arrays.copyOf(portDirectionTurns, portCount);
        mesh.portChartU = Arrays.copyOf(portChartU, portCount);
        mesh.portChartV = Arrays.copyOf(portChartV, portCount);
        mesh.portConnection = portConnection;
        mesh.connectionTurns = connectionTurns;
        mesh.connectionTranslationU = connectionTranslationU;
        mesh.connectionTranslationV = connectionTranslationV;
        mesh.quadCount = quadCount;
        mesh.quadCorner = Arrays.copyOf(quadCorner,
                quadCount * ExtractedQuadMesh.QUAD_CORNERS);
        mesh.quadEdgeCount = quadEdgeCount;
        mesh.quadEdgeVertexA = Arrays.copyOf(quadEdgeVertexA, quadEdgeCount);
        mesh.quadEdgeVertexB = Arrays.copyOf(quadEdgeVertexB, quadEdgeCount);
        return mesh;
    }
}
