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
import ixdar.geometry.mesh.nodes.api.UvField;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.ExactBarycentricOrient;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.gridmap.IntegerGridMap;
import ixdar.platform.Platforms;

/**
 * Extracts the quad mesh of a fold-free integer grid UV field: vertices at
 * integer grid point preimages, connectivity by tracing unit iso-segments face
 * by face, faces by clockwise port cycling. Each seam crossing's grid
 * automorphism is derived face-locally from the shared edge's corner UVs.
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

    /** Chart corner to face vertex index when the face's UV image winds clockwise. */
    private static final int[] CLOCKWISE_CORNER_PERMUTATION = { 0, 2, 1 };

    /** Entries of a transition triple: quarter turns, u translation, v translation. */
    private static final int TRANSITION_ENTRIES = 3;

    public final HalfEdgeMesh copy;

    /** Per-corner grid coordinates over the copy, the field being extracted. */
    public final UvField uvField;

    /** The layout network, read only for node valences at layout vertices. */
    public final ArcNetwork tmesh;

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

    /** Copy face whose frame each vertex's grid coordinates are stored in. */
    private int[] frameFace = new int[16];

    private double[] chartU = new double[16];
    private double[] chartV = new double[16];
    private float[] positionX = new float[16];
    private float[] positionY = new float[16];
    private float[] positionZ = new float[16];

    /**
     * Ports over all quad vertices, in surface clockwise order per vertex; valid
     * up to portCount.
     */
    private int portCount;

    private int[] portStart;
    private int[] portOwner = new int[16];
    private int[] portFace = new int[16];
    private int[] portDirectionTurns = new int[16];
    private double[] portChartU = new double[16];
    private double[] portChartV = new double[16];
    private int[] portConnection;

    /** Extracted quads; four corner quad vertex ids each, valid up to quadCount. */
    private int quadCount;

    private int[] quadCorner = new int[16];

    /** Unique undirected quad edges, one per traced connection pair. */
    private int quadEdgeCount;

    /**
     * Stores the mesh and UV field to extract from.
     *
     * @param copy    the working copy mesh the field covers
     * @param uvField per-corner grid coordinates of the relaxed, verified map
     * @param tmesh   the layout network, for node valences at layout vertices
     */
    public QuadMeshExtraction(HalfEdgeMesh copy, UvField uvField, ArcNetwork tmesh) {
        this.copy = copy;
        this.uvField = uvField;
        this.tmesh = tmesh;
    }

    /**
     * Runs the extraction and packs the result.
     *
     * @throws IllegalStateException when any extraction invariant fails
     * @return the extracted quad mesh
     */
    public ExtractedQuadMesh build() {
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
     * EBC13 Algorithm 3: finds every integer grid point preimage, classified onto
     * the copy vertex, edge interior, or face interior it sits on, each entity
     * enumerated once in the frame of the first face reaching it.
     */
    private void generateQuadVertices() {
        Set<Integer> seenVertices = new HashSet<>();
        Set<Integer> seenEdges = new HashSet<>();
        double[] cornerU = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] cornerV = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        for (int activeFace = 0; activeFace < copy.faceCount(); activeFace++) {
            int faceId = copy.faceIdAt(activeFace);
            for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
                int copyVertex = copy.faceVertexAt(faceId, corner);
                cornerU[corner] = uvField.u(faceId, corner);
                cornerV[corner] = uvField.v(faceId, corner);
                if (seenVertices.add(copyVertex)
                        && cornerU[corner] == Math.rint(cornerU[corner])
                        && cornerV[corner] == Math.rint(cornerV[corner])) {
                    Vector3f position = copy.vertexPosition(copyVertex);
                    int quadVertex = emitQuadVertex(ExtractedQuadMesh.KIND_MESH_VERTEX,
                            copyVertex, faceId, cornerU[corner], cornerV[corner],
                            position.x, position.y, position.z);
                    quadVertexByCopyVertex.put(copyVertex, quadVertex);
                    vertexPreimageCount++;
                }
            }
            for (int edgeIndex = 0; edgeIndex < HalfEdgeMesh.TRIANGLE_CORNERS; edgeIndex++) {
                int edgeId = copy.faceEdgeAt(faceId, edgeIndex);
                if (seenEdges.add(edgeId)) {
                    generateEdgePreimages(faceId, edgeIndex, edgeId, cornerU, cornerV);
                }
            }
            generateFacePreimages(faceId, cornerU, cornerV);
        }
    }

    /**
     * Finds the integer grid points strictly inside one copy edge's grid image by
     * scanning the dominant axis and confirming each candidate exactly collinear.
     *
     * @param faceId    face providing the corner coordinates and the storage frame
     * @param edgeIndex edge position within the face, endpoints at
     *                  {@code edgeIndex} and the next corner
     * @param edgeId    the copy edge the preimages anchor to
     * @param cornerU   the face's corner grid u values in copy corner order
     * @param cornerV   the face's corner grid v values in copy corner order
     */
    private void generateEdgePreimages(int faceId, int edgeIndex, int edgeId,
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
                        faceId, candidate[0], candidate[1],
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
     * Finds the integer grid points strictly inside one face's grid triangle by an
     * integer bounding-box scan with three exact strict side tests.
     *
     * @param faceId  the copy face the preimages anchor to
     * @param cornerU the face's corner grid u values in copy corner order
     * @param cornerV the face's corner grid v values in copy corner order
     */
    private void generateFacePreimages(int faceId, double[] cornerU, double[] cornerV) {
        int faceSign = chartSign(faceId, cornerU, cornerV);
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
                    inside = ExactBarycentricOrient.sign(first, second, candidate) == faceSign;
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
                        faceId, u, v, x, y, z);
                quadVerticesByCopyFace.computeIfAbsent(faceId, key -> new ArrayList<>())
                        .add(quadVertex);
                facePreimageCount++;
            }
        }
    }

    /**
     * Twice the signed area of a grid triangle, in plain floating point for
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
     * @param kind        one of the {@link ExtractedQuadMesh} kind constants
     * @param anchorId    copy vertex, edge, or face id carrying the preimage
     * @param frameFaceId face whose frame the grid coordinates are stored in
     * @param gridU       integer grid u in that frame
     * @param gridV       integer grid v in that frame
     * @param x           surface x
     * @param y           surface y
     * @param z           surface z
     * @return the new quad vertex id
     */
    private int emitQuadVertex(int kind, int anchorId, int frameFaceId, double gridU,
            double gridV, float x, float y, float z) {
        if (quadVertexCount == vertexKind.length) {
            int grown = vertexKind.length * 2;
            vertexKind = Arrays.copyOf(vertexKind, grown);
            anchorEntityId = Arrays.copyOf(anchorEntityId, grown);
            frameFace = Arrays.copyOf(frameFace, grown);
            chartU = Arrays.copyOf(chartU, grown);
            chartV = Arrays.copyOf(chartV, grown);
            positionX = Arrays.copyOf(positionX, grown);
            positionY = Arrays.copyOf(positionY, grown);
            positionZ = Arrays.copyOf(positionZ, grown);
        }
        vertexKind[quadVertexCount] = kind;
        anchorEntityId[quadVertexCount] = anchorId;
        frameFace[quadVertexCount] = frameFaceId;
        chartU[quadVertexCount] = gridU;
        chartV[quadVertexCount] = gridV;
        positionX[quadVertexCount] = x;
        positionY[quadVertexCount] = y;
        positionZ[quadVertexCount] = z;
        return quadVertexCount++;
    }

    /**
     * The exact orientation sign of a face's grid triangle in copy corner order.
     *
     * @param faceId  face being read, for the error message
     * @param cornerU the face's corner grid u values in copy corner order
     * @param cornerV the face's corner grid v values in copy corner order
     * @throws IllegalStateException when the triangle is exactly degenerate
     * @return {@code 1} counter-clockwise, {@code -1} clockwise
     */
    private static int chartSign(int faceId, double[] cornerU, double[] cornerV) {
        double[] first = { cornerU[0], cornerV[0], 1.0 };
        double[] second = { cornerU[1], cornerV[1], 1.0 };
        double[] third = { cornerU[2], cornerV[2], 1.0 };
        int sign = ExactBarycentricOrient.sign(first, second, third);
        if (sign == 0) {
            throw new IllegalStateException("copy face " + faceId + " has an exactly degenerate"
                    + " grid triangle; the extraction needs a fold-free map");
        }
        return sign;
    }

    /**
     * The face vertex index sitting at one normalized chart corner, undoing the
     * corner swap that presents a clockwise face image as counter-clockwise.
     *
     * @param chartCorner      corner in normalized chart order
     * @param counterClockwise whether the face's UV image winds counter-clockwise
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
     * @param counterClockwise whether the face's UV image winds counter-clockwise
     * @return the copy edge id
     */
    private int chartEdgeCopyEdge(int faceId, int chartEdge, boolean counterClockwise) {
        return copy.faceEdgeAt(faceId, counterClockwise ? chartEdge : 2 - chartEdge);
    }

    /**
     * Reads one face's corner grid coordinates in normalized chart order, so the
     * corners always wind counter-clockwise in the grid plane.
     *
     * @param faceId  copy face to read
     * @param cornerU receives the corner grid u values
     * @param cornerV receives the corner grid v values
     * @throws IllegalStateException when the face's grid triangle is degenerate
     * @return whether the face's UV image winds counter-clockwise
     */
    private boolean readChartCorners(int faceId, double[] cornerU, double[] cornerV) {
        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
            cornerU[corner] = uvField.u(faceId, corner);
            cornerV[corner] = uvField.v(faceId, corner);
        }
        if (chartSign(faceId, cornerU, cornerV) > 0) {
            return true;
        }
        double swapU = cornerU[1];
        double swapV = cornerV[1];
        cornerU[1] = cornerU[2];
        cornerV[1] = cornerV[2];
        cornerU[2] = swapU;
        cornerV[2] = swapV;
        return false;
    }

    /**
     * The corner index of a copy vertex within a face.
     *
     * @param faceId     face to scan
     * @param copyVertex vertex to find
     * @throws IllegalStateException when the face does not hold the vertex
     * @return the face vertex adjacency index
     */
    private int cornerOfVertex(int faceId, int copyVertex) {
        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
            if (copy.faceVertexAt(faceId, corner) == copyVertex) {
                return corner;
            }
        }
        throw new IllegalStateException("copy face " + faceId + " does not hold copy vertex "
                + copyVertex);
    }

    /**
     * Derives the grid automorphism carrying one face's frame onto its neighbour's
     * across their shared edge, from the edge's endpoint UVs on both sides. Exact
     * because the verified map stores seam copies bitwise related by quarter-turn
     * integer transitions.
     *
     * @param fromFace   face whose frame points are currently expressed in
     * @param toFace     neighbouring face across the edge
     * @param edgeId     the shared copy edge
     * @param transition receives {@code {quarterTurns, translationU, translationV}}
     * @throws IllegalStateException when no exact grid transition fits
     */
    private void deriveEdgeTransition(int fromFace, int toFace, int edgeId,
            double[] transition) {
        int halfEdge = copy.edgeHalfEdge(edgeId);
        int vertexA = copy.halfEdgeVertex(halfEdge);
        int vertexB = copy.halfEdgeEndVertex(halfEdge);
        int fromCornerA = cornerOfVertex(fromFace, vertexA);
        int fromCornerB = cornerOfVertex(fromFace, vertexB);
        int toCornerA = cornerOfVertex(toFace, vertexA);
        int toCornerB = cornerOfVertex(toFace, vertexB);
        double fromAu = uvField.u(fromFace, fromCornerA);
        double fromAv = uvField.v(fromFace, fromCornerA);
        double fromBu = uvField.u(fromFace, fromCornerB);
        double fromBv = uvField.v(fromFace, fromCornerB);
        double toAu = uvField.u(toFace, toCornerA);
        double toAv = uvField.v(toFace, toCornerA);
        double toBu = uvField.u(toFace, toCornerB);
        double toBv = uvField.v(toFace, toCornerB);
        double[] rotated = new double[IntegerGridMap.GRID_COORDINATES];
        for (int turns = 0; turns < AXIS_DIRECTIONS; turns++) {
            IntegerGridMap.rotate(turns, fromAu, fromAv, rotated);
            double translationU = toAu - rotated[0];
            double translationV = toAv - rotated[1];
            if (translationU != Math.rint(translationU)
                    || translationV != Math.rint(translationV)) {
                continue;
            }
            IntegerGridMap.rotate(turns, fromBu, fromBv, rotated);
            if (rotated[0] + translationU == toBu && rotated[1] + translationV == toBv) {
                transition[0] = turns;
                transition[1] = translationU;
                transition[2] = translationV;
                return;
            }
        }
        throw new IllegalStateException("copy edge " + edgeId + " admits no exact grid"
                + " transition from face " + fromFace + " to face " + toFace);
    }

    /**
     * Applies a derived transition to a grid point, in place.
     *
     * @param transition the {@code {quarterTurns, translationU, translationV}} triple
     * @param pointUv    the point, replaced by its image in the neighbouring frame
     */
    private static void applyTransition(double[] transition, double[] pointUv) {
        IntegerGridMap.rotate((int) transition[0], pointUv[0], pointUv[1], pointUv);
        pointUv[0] += transition[1];
        pointUv[1] += transition[2];
    }

    /**
     * A direction's quarter turns after a derived transition.
     *
     * @param transition the {@code {quarterTurns, translationU, translationV}} triple
     * @param turns      direction as quarter turns
     * @return the direction's quarter turns in the neighbouring frame
     */
    private static int applyTransitionTurns(double[] transition, int turns) {
        return (turns + (int) transition[0]) % AXIS_DIRECTIONS;
    }

    /**
     * EBC13 Algorithm 4: enumerates each quad vertex's outgoing iso-line directions
     * in surface clockwise order, checking the count against the T-mesh valence.
     *
     * @throws IllegalStateException when a vertex's port count is wrong
     */
    private void enumeratePorts() {
        portStart = new int[quadVertexCount + 1];
        for (int quadVertex = 0; quadVertex < quadVertexCount; quadVertex++) {
            portStart[quadVertex] = portCount;
            if (vertexKind[quadVertex] == ExtractedQuadMesh.KIND_FACE_INTERIOR) {
                faceInteriorPorts(quadVertex);
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
     * Emits the four ports of a face-interior quad vertex in surface clockwise
     * order, which is grid clockwise exactly when the face image preserves the
     * surface winding.
     *
     * @param quadVertex face-interior quad vertex
     */
    private void faceInteriorPorts(int quadVertex) {
        int faceId = anchorEntityId[quadVertex];
        double[] cornerU = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] cornerV = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        boolean counterClockwise = readChartCorners(faceId, cornerU, cornerV);
        for (int step = 0; step < AXIS_DIRECTIONS; step++) {
            int turns = counterClockwise ? (AXIS_DIRECTIONS - step) % AXIS_DIRECTIONS : step;
            emitPort(quadVertex, faceId, turns, chartU[quadVertex], chartV[quadVertex]);
        }
    }

    /**
     * Emits the ports of an edge-interior quad vertex: each incident face's
     * half-plane wedge claims its directions, the two wedges concatenated in
     * surface clockwise order.
     *
     * @param quadVertex edge-interior quad vertex
     */
    private void edgePorts(int quadVertex) {
        int edgeId = anchorEntityId[quadVertex];
        int halfEdge = copy.edgeHalfEdge(edgeId);
        double[] apex = new double[IntegerGridMap.GRID_COORDINATES];
        double[] transition = new double[TRANSITION_ENTRIES];
        double[] cornerU = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] cornerV = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        for (int side = 0; side < 2; side++) {
            int faceId = copy.halfEdgeFace(side == 0 ? halfEdge : copy.halfEdgeTwin(halfEdge));
            if (faceId < 0) {
                continue;
            }
            apex[0] = chartU[quadVertex];
            apex[1] = chartV[quadVertex];
            if (faceId != frameFace[quadVertex]) {
                deriveEdgeTransition(frameFace[quadVertex], faceId, edgeId, transition);
                applyTransition(transition, apex);
            }
            boolean counterClockwise = readChartCorners(faceId, cornerU, cornerV);
            int chartEdge = 0;
            while (chartEdge < HalfEdgeMesh.TRIANGLE_CORNERS
                    && chartEdgeCopyEdge(faceId, chartEdge, counterClockwise) != edgeId) {
                chartEdge++;
            }
            int nextCorner = (chartEdge + 1) % HalfEdgeMesh.TRIANGLE_CORNERS;
            wedgePorts(quadVertex, faceId, counterClockwise, apex[0], apex[1],
                    cornerU[nextCorner], cornerV[nextCorner], cornerU[chartEdge],
                    cornerV[chartEdge]);
        }
    }

    /**
     * Emits the ports of a mesh-vertex quad vertex by walking its corner fan in
     * surface clockwise order, each corner wedge claiming its directions.
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
            boolean counterClockwise = readChartCorners(faceId, cornerU, cornerV);
            int chartCorner = 0;
            while (chartCorner < HalfEdgeMesh.TRIANGLE_CORNERS && copy.faceVertexAt(faceId,
                    faceCornerIndex(chartCorner, counterClockwise)) != copyVertex) {
                chartCorner++;
            }
            int clockwiseCorner = (chartCorner + 1) % HalfEdgeMesh.TRIANGLE_CORNERS;
            int counterClockwiseCorner = (chartCorner + 2) % HalfEdgeMesh.TRIANGLE_CORNERS;
            wedgePorts(quadVertex, faceId, counterClockwise, cornerU[chartCorner],
                    cornerV[chartCorner], cornerU[clockwiseCorner], cornerV[clockwiseCorner],
                    cornerU[counterClockwiseCorner], cornerV[counterClockwiseCorner]);
            int crossEdge = copy.faceEdgeAt(faceId,
                    faceCornerIndex(chartCorner, counterClockwise));
            faceId = copy.faceAcrossEdge(faceId, crossEdge);
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
     * Emits the directions one wedge claims in surface clockwise order: strictly
     * inside it, or collinear with its grid clockwise boundary, found by stepping
     * from a direction outside the wedge (EBC13 Algorithm 4).
     *
     * @param quadVertex       owning quad vertex
     * @param faceId           copy face whose frame the directions live in
     * @param counterClockwise whether the face's UV image winds counter-clockwise
     * @param apexU            the vertex's grid u in that frame
     * @param apexV            the vertex's grid v in that frame
     * @param clockwiseU       grid u of the grid clockwise wedge boundary point
     * @param clockwiseV       grid v of the grid clockwise wedge boundary point
     * @param counterUcw       grid u of the grid counter-clockwise boundary point
     * @param counterVcw       grid v of the grid counter-clockwise boundary point
     * @throws IllegalStateException when every direction claims the wedge
     */
    private void wedgePorts(int quadVertex, int faceId, boolean counterClockwise, double apexU,
            double apexV, double clockwiseU, double clockwiseV, double counterUcw,
            double counterVcw) {
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
            int turns = counterClockwise
                    ? ((outside - step) % AXIS_DIRECTIONS + AXIS_DIRECTIONS) % AXIS_DIRECTIONS
                    : (outside + step) % AXIS_DIRECTIONS;
            if (wedgeClaims(turns, apexU, apexV, clockwiseU, clockwiseV, counterUcw,
                    counterVcw)) {
                emitPort(quadVertex, faceId, turns, apexU, apexV);
            }
        }
    }

    /**
     * Whether a wedge claims a direction: strictly between its boundaries, or
     * collinear with the grid clockwise boundary and pointing the same way. The
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
     * @param faceId copy face whose frame holds the direction
     * @param turns  direction as quarter turns in that frame
     * @param apexU  the owner's grid u in that frame
     * @param apexV  the owner's grid v in that frame
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
     * Traces one port's unit segment face by face: cross triangle edges by exact
     * side tests, carry the segment through each crossing's derived transition,
     * and connect to the opposite port at the segment's end.
     *
     * @param port port to trace
     * @throws IllegalStateException when the segment strands, hits a boundary, or
     *                               its endpoint has no matching quad vertex
     */
    private void tracePort(int port) {
        int faceId = portFace[port];
        int turns = portDirectionTurns[port];
        double[] segmentStart = { portChartU[port], portChartV[port] };
        double[] segmentEnd = { segmentStart[0] + DIRECTION_U[turns],
                segmentStart[1] + DIRECTION_V[turns] };
        int entryEdgeId = ExtractedQuadMesh.NONE;
        double[] transition = new double[TRANSITION_ENTRIES];
        double[] cornerU = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] cornerV = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] first = { 0.0, 0.0, 1.0 };
        double[] second = { 0.0, 0.0, 1.0 };
        double[] third = { 0.0, 0.0, 1.0 };
        for (int step = 0; step < 2 * copy.faceCount(); step++) {
            boolean counterClockwise = readChartCorners(faceId, cornerU, cornerV);
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
                connectArrival(port, faceId, turns, segmentEnd, zeroMask, counterClockwise);
                return;
            }
            int exitChartEdge = pickNextEdge(faceId, counterClockwise, entryEdgeId,
                    segmentStart, segmentEnd, cornerU, cornerV, first, second, third);
            int crossedEdgeId = chartEdgeCopyEdge(faceId, exitChartEdge, counterClockwise);
            int nextFace = copy.faceAcrossEdge(faceId, crossedEdgeId);
            if (nextFace < 0) {
                throw new IllegalStateException("port " + port + " traced into a mesh boundary"
                        + " at copy edge " + crossedEdgeId);
            }
            deriveEdgeTransition(faceId, nextFace, crossedEdgeId, transition);
            applyTransition(transition, segmentStart);
            applyTransition(transition, segmentEnd);
            turns = applyTransitionTurns(transition, turns);
            entryEdgeId = crossedEdgeId;
            faceId = nextFace;
        }
        throw new IllegalStateException("port " + port + " did not terminate within twice the"
                + " face count");
    }

    /**
     * EBC13's PickNextEdge: the chart edge the directed segment leaves through,
     * preferring the candidate with fewest corners exactly on the segment's line so
     * a collinear mesh edge is pivoted past rather than slid along.
     *
     * @param faceId           face being traversed
     * @param counterClockwise whether the face's UV image winds counter-clockwise
     * @param entryEdgeId      copy edge the segment entered through, excluded
     * @param segmentStart     segment start in the face's frame
     * @param segmentEnd       segment end in the face's frame
     * @param cornerU          the face's corner grid u values in chart order
     * @param cornerV          the face's corner grid v values in chart order
     * @param first            scratch triple
     * @param second           scratch triple
     * @param third            scratch triple
     * @throws IllegalStateException when no edge admits the segment
     * @return the exit edge's chart index
     */
    private int pickNextEdge(int faceId, boolean counterClockwise, int entryEdgeId,
            double[] segmentStart, double[] segmentEnd, double[] cornerU, double[] cornerV,
            double[] first, double[] second, double[] third) {
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
                    + ") strands in face " + faceId);
        }
        return bestEdge;
    }

    /**
     * Connects a traced port to the opposite port at its arrival point: classify
     * the point onto its entity, look up the quad vertex, and find the port holding
     * the reverse direction, following a collinear edge when the reverse wedge lies
     * in a neighbouring face's frame.
     *
     * @param port             port whose trace arrived
     * @param faceId           face the segment ended in
     * @param turns            traced direction in that face's frame
     * @param segmentEnd       arrival point in that face's frame
     * @param zeroMask         bit per chart side the point lies exactly on
     * @param counterClockwise whether the face's UV image winds counter-clockwise
     * @throws IllegalStateException when no quad vertex or opposite port matches
     */
    private void connectArrival(int port, int faceId, int turns, double[] segmentEnd,
            int zeroMask, boolean counterClockwise) {
        int arrivalVertex = arrivalQuadVertex(faceId, segmentEnd, zeroMask, counterClockwise);
        int reverseTurns = (turns + 2) % AXIS_DIRECTIONS;
        int opposite = findPort(arrivalVertex, faceId, reverseTurns);
        if (opposite == ExtractedQuadMesh.NONE) {
            double[] transition = new double[TRANSITION_ENTRIES];
            for (int candidateEdge : arrivalIncidentEdges(faceId, zeroMask, counterClockwise)) {
                int neighbor = copy.faceAcrossEdge(faceId, candidateEdge);
                if (neighbor < 0) {
                    continue;
                }
                deriveEdgeTransition(faceId, neighbor, candidateEdge, transition);
                int neighborTurns = applyTransitionTurns(transition, reverseTurns);
                opposite = findPort(arrivalVertex, neighbor, neighborTurns);
                if (opposite != ExtractedQuadMesh.NONE) {
                    applyTransition(transition, segmentEnd);
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
        quadEdgeCount++;
    }

    /**
     * The quad vertex at a trace's arrival point, classified by which chart sides
     * the point lies exactly on: none is a face preimage, one an edge preimage, two
     * the shared corner's vertex preimage.
     *
     * @param faceId           face containing the point
     * @param segmentEnd       the arrival point in that face's frame
     * @param zeroMask         bit per chart side the point lies exactly on
     * @param counterClockwise whether the face's UV image winds counter-clockwise
     * @throws IllegalStateException when no generated quad vertex matches
     * @return the arrival quad vertex id
     */
    private int arrivalQuadVertex(int faceId, double[] segmentEnd, int zeroMask,
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
            double[] mapped = new double[IntegerGridMap.GRID_COORDINATES];
            double[] transition = new double[TRANSITION_ENTRIES];
            if (candidates != null) {
                for (int candidate : candidates) {
                    mapped[0] = chartU[candidate];
                    mapped[1] = chartV[candidate];
                    if (frameFace[candidate] != faceId) {
                        deriveEdgeTransition(frameFace[candidate], faceId, edgeId, transition);
                        applyTransition(transition, mapped);
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
     * @param counterClockwise whether the face's UV image winds counter-clockwise
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
     * The port of a quad vertex holding one direction in one face's frame.
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
        mesh.portConnection = portConnection;
        mesh.quadCount = quadCount;
        mesh.quadCorner = Arrays.copyOf(quadCorner,
                quadCount * ExtractedQuadMesh.QUAD_CORNERS);
        mesh.quadEdgeCount = quadEdgeCount;
        return mesh;
    }
}
