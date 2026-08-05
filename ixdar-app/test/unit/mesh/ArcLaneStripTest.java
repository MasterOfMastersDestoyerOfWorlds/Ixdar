package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;

/**
 * Five arcs running the length of a five-triangle strip, each between its own pair of
 * nodes, packed into the bottom thousandth of the strip. Every arc crosses every face,
 * so all five compete for the same interior, which is what makes the carve cascade.
 */
class ArcLaneStripTest {

    /** Corners of a triangle. */
    private static final int CORNERS = 3;

    /** Arcs running the strip; each contributes two nodes. */
    private static final int ARCS = 5;

    /** Quads along the strip, each cut into two triangles but one crossed per arc. */
    private static final int COLUMNS = 3;

    /** Faces of the strip, which every arc must traverse. */
    private static final int FACES = 5;

    /** Height of the lowest arc above the strip's bottom edge. */
    private static final double PACKING = 1.0e-3;

    /**
     * Least share of its source face's area a copy triangle may cover: the floor
     * {@code GridMapOptimizer} divides the parametrization reference by.
     */
    private static final double MINIMUM_AREA_FRACTION = 1.0e-9;

    /**
     * The five packed arcs are carved as five vertex-disjoint edge paths across the
     * strip, and no copy triangle falls below the optimizer's area floor.
     */
    @Test
    void packedArcsCarveDisjointPathsWithoutCollapsing() {
        HalfEdgeMesh strip = buildStrip();
        SnappingCarve carve = new SnappingCarve(strip);
        int[][] pathPoints = new int[ARCS][FACES + 1];
        for (int arc = 0; arc < ARCS; arc++) {
            double height = PACKING * (arc + 1);
            for (int crossing = 0; crossing <= FACES; crossing++) {
                int bottom = strip.vertexIdAt((crossing + 1) / 2);
                int top = strip.vertexIdAt(COLUMNS + 1 + crossing / 2);
                int edgeId = edgeBetween(strip, bottom, top);
                boolean fromBottom =
                        strip.halfEdgeVertex(strip.edgeHalfEdge(edgeId)) == bottom;
                pathPoints[arc][crossing] = carve.addEdgePoint(edgeId,
                        fromBottom ? height : 1.0 - height);
            }
            for (int face = 0; face < FACES; face++) {
                carve.addChord(face, pathPoints[arc][face], pathPoints[arc][face + 1], arc, arc);
            }
        }
        EmbeddedMeshTopology topology = carve.build().topology;

        Set<Integer> seenVertices = new HashSet<>();
        for (int arc = 0; arc < ARCS; arc++) {
            List<Integer> path = carve.pathByArc[arc].copyVertexPath;
            assertEquals(FACES + 1, path.size(), "arc " + arc + " path length");
            for (int step = 1; step < path.size(); step++) {
                assertNotEquals(EmbeddedMeshTopology.UNCLAIMED,
                        topology.edgeBetween(path.get(step - 1), path.get(step)),
                        "arc " + arc + " hop " + step + " has no copy edge behind it");
            }
            for (int vertexId : path) {
                assertTrue(seenVertices.add(vertexId),
                        "copy vertex " + vertexId + " is shared by two arcs");
            }
        }

        for (int faceIndex = 0; faceIndex < topology.copy.faceCount(); faceIndex++) {
            int copyFace = topology.copy.faceIdAt(faceIndex);
            int sourceFace = topology.sourceFaceByCopyFace[copyFace];
            assertNotEquals(EmbeddedMeshTopology.UNCLAIMED, sourceFace,
                    "copy face " + copyFace + " has no source face");
            double fraction = Math.abs(barycentricArea(topology, sourceFace, copyFace));
            assertTrue(fraction >= MINIMUM_AREA_FRACTION, "copy face " + copyFace + " covers "
                    + fraction + " of source face " + sourceFace
                    + ", below the optimizer's floor " + MINIMUM_AREA_FRACTION);
        }
    }

    /**
     * Twice the signed area of a copy face in its source face's barycentric frame,
     * which is its share of that face.
     *
     * @param topology   the carved working copy
     * @param sourceFace source face the corners are read in
     * @param copyFace   copy face to measure
     * @return its signed share of the source face
     */
    private double barycentricArea(EmbeddedMeshTopology topology, int sourceFace, int copyFace) {
        double[][] corner = new double[CORNERS][];
        for (int index = 0; index < CORNERS; index++) {
            corner[index] = topology.barycentricOf(sourceFace,
                    topology.copy.faceVertexAt(copyFace, index));
            assertTrue(corner[index] != null, "copy face " + copyFace + " corner " + index
                    + " has no barycentric in source face " + sourceFace);
        }
        return (corner[1][1] - corner[0][1]) * (corner[2][2] - corner[0][2])
                - (corner[2][1] - corner[0][1]) * (corner[1][2] - corner[0][2]);
    }

    /**
     * The edge joining two vertices of a mesh.
     *
     * @param mesh mesh to search
     * @param from one endpoint's vertex id
     * @param to   the other endpoint's vertex id
     * @return the edge id
     */
    private int edgeBetween(HalfEdgeMesh mesh, int from, int to) {
        for (int index = 0; index < mesh.vertexEdgeCount(from); index++) {
            int edgeId = mesh.vertexEdgeAt(from, index);
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int start = mesh.halfEdgeVertex(halfEdge);
            int other = start == from ? mesh.halfEdgeEndVertex(halfEdge) : start;
            if (other == to) {
                return edgeId;
            }
        }
        throw new IllegalStateException("no edge joins vertices " + from + " and " + to);
    }

    /**
     * A strip of five triangles between two rows of vertices, wide enough that an
     * arc running its length crosses every face.
     *
     * @return the strip
     */
    private HalfEdgeMesh buildStrip() {
        int columns = COLUMNS + 1;
        float[] positions = new float[columns * 2 * CORNERS];
        for (int column = 0; column < columns; column++) {
            positions[column * CORNERS] = column;
            positions[(columns + column) * CORNERS] = column;
            positions[(columns + column) * CORNERS + 1] = 1.0f;
        }
        List<Integer> faces = new ArrayList<>();
        for (int column = 0; column < COLUMNS; column++) {
            faces.add(column);
            faces.add(column + 1);
            faces.add(columns + column);
            if (faces.size() / CORNERS >= FACES) {
                break;
            }
            faces.add(columns + column);
            faces.add(column + 1);
            faces.add(columns + column + 1);
            if (faces.size() / CORNERS >= FACES) {
                break;
            }
        }
        int[] faceIndices = new int[faces.size()];
        for (int index = 0; index < faces.size(); index++) {
            faceIndices[index] = faces.get(index);
        }
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
    }
}
