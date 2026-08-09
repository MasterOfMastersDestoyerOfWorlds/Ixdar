package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.FaceStripPath;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Refining a traced route onto the constraint mesh: which edge sits between each pair of
 * passages, where the trace crossed it, and which corner of it the arc would rather take.
 * An error here misplaces every chord after it.
 */
class SnappingPathChoiceTest {

    /** Corners of a triangle. */
    private static final int CORNERS = 3;

    /** Columns of the strip; two rows of this many vertices. */
    private static final int COLUMNS = 4;

    /** Faces of the strip a route walks. */
    private static final int FACES = 5;

    /** Arc the route is refined for. */
    private static final int ARC = 7;

    /** How far a crossing parameter may sit from its expected position. */
    private static final double TOLERANCE = 1.0e-9;

    /**
     * A route over five passages records four crossings, so a crossing's index is also the
     * index of the passage that reaches it. Every chord downstream relies on that.
     */
    @Test
    void passagesRunOneLongerThanCrossings() {
        FaceStripPath route = walk(strip());

        assertEquals(FACES, route.passageFaces.size(), "passages along the strip");
        assertEquals(FACES - 1, route.crossedEdges.size(), "crossings along the strip");
        assertEquals(route.passageFaces.size(), route.passageSourceFaces.size(),
            "a passage was recorded without the source face holding it");
    }

    /**
     * Each crossed edge is recorded with its endpoints in ascending order, so both
     * passages sharing it agree on which end a position is measured from.
     */
    @Test
    void crossedEdgesAreRecordedInAscendingEndpointOrder() {
        FaceStripPath route = walk(strip());

        for (int crossing = 0; crossing < route.crossedEdges.size(); crossing++) {
            int[] endpoints = route.crossedEdges.get(crossing);
            assertTrue(endpoints[0] < endpoints[1],
                "crossing " + crossing + " records its endpoints out of order");
        }
    }

    /**
     * Every crossing parameter lands inside the edge, since the traced route crosses the
     * edge rather than meeting it at a corner or missing it.
     */
    @Test
    void crossingParametersLieWithinTheirEdge() {
        FaceStripPath route = walk(strip());

        for (int crossing = 0; crossing < route.crossingParameters.size(); crossing++) {
            double at = route.crossingParameters.get(crossing);
            assertTrue(at >= -TOLERANCE && at <= 1.0 + TOLERANCE,
                "crossing " + crossing + " sits at " + at + ", off its edge");
        }
    }

    /**
     * The preferred corner at each crossing is the vertex the next crossed edge shares with
     * it, and the last crossing reports none because no next edge shares one.
     */
    @Test
    void preferredCornerIsTheOneSharedWithTheNextEdge() {
        FaceStripPath route = walk(strip());

        for (int crossing = 0; crossing + 1 < route.crossedEdges.size(); crossing++) {
            int corner = route.sharedCornerAt(crossing);
            int[] here = route.crossedEdges.get(crossing);
            int[] next = route.crossedEdges.get(crossing + 1);
            assertTrue(corner == here[0] || corner == here[1],
                "crossing " + crossing + " prefers a vertex off its own edge");
            assertTrue(corner == next[0] || corner == next[1],
                "crossing " + crossing + " prefers a vertex the next edge does not share");
        }
        assertEquals(EmbeddedMeshTopology.UNCLAIMED,
            route.sharedCornerAt(route.crossedEdges.size() - 1),
            "the last crossing claimed a shared corner it cannot have");
    }

    /**
     * Refines a route the length of the strip, one passage per face, entering each at the
     * midpoint of the edge it shares with the last.
     *
     * @param topology working copy standing as the constraint mesh
     * @return the route, refined
     */
    private FaceStripPath walk(EmbeddedMeshTopology topology) {
        FaceStripPath route = new FaceStripPath(topology, ARC);
        for (int face = 0; face < FACES; face++) {
            route.addPassage(face, entry(topology, face), exit(topology, face));
        }
        return route;
    }

    /**
     * Where the route enters one face: the midpoint of the edge shared with the previous
     * face, or a corner for the first face.
     *
     * @param topology working copy holding the strip
     * @param face     source active face being entered
     * @return the entry barycentric in that face
     */
    private double[] entry(EmbeddedMeshTopology topology, int face) {
        if (face == 0) {
            return new double[] { 1.0, 0.0, 0.0 };
        }
        return midpointOfSharedEdge(topology, face, face - 1);
    }

    /**
     * Where the route leaves one face: the midpoint of the edge shared with the next face,
     * or a corner for the last face.
     *
     * @param topology working copy holding the strip
     * @param face     source active face being left
     * @return the exit barycentric in that face
     */
    private double[] exit(EmbeddedMeshTopology topology, int face) {
        if (face == FACES - 1) {
            return new double[] { 0.0, 1.0, 0.0 };
        }
        return midpointOfSharedEdge(topology, face, face + 1);
    }

    /**
     * The midpoint of the edge two source faces share, as a barycentric of the first.
     *
     * @param topology working copy holding both faces
     * @param face     source active face the coordinates are in
     * @param neighbour source active face sharing an edge with it
     * @throws IllegalStateException when the two faces share no edge
     * @return that midpoint's barycentric
     */
    private double[] midpointOfSharedEdge(EmbeddedMeshTopology topology, int face,
            int neighbour) {
        int faceId = topology.sourceMesh.faceIdAt(face);
        int neighbourId = topology.sourceMesh.faceIdAt(neighbour);
        double[] barycentric = new double[CORNERS];
        int shared = 0;
        for (int corner = 0; corner < CORNERS; corner++) {
            int vertexId = topology.sourceMesh.faceVertexAt(faceId, corner);
            for (int other = 0; other < CORNERS; other++) {
                if (topology.sourceMesh.faceVertexAt(neighbourId, other) == vertexId) {
                    barycentric[corner] = 0.5;
                    shared++;
                }
            }
        }
        if (shared != 2) {
            throw new IllegalStateException("source faces " + face + " and " + neighbour
                + " share " + shared + " vertices, so they share no edge");
        }
        return barycentric;
    }

    /**
     * A strip of five triangles between two rows of four vertices, the fixture whose
     * crossings are short enough to reason about by hand.
     *
     * @return a working copy of that strip
     */
    private EmbeddedMeshTopology strip() {
        float[] positions = new float[COLUMNS * 2 * CORNERS];
        for (int column = 0; column < COLUMNS; column++) {
            positions[column * CORNERS] = column;
            positions[(COLUMNS + column) * CORNERS] = column;
            positions[(COLUMNS + column) * CORNERS + 1] = 1.0f;
        }
        List<Integer> faces = new ArrayList<>();
        for (int column = 0; column < COLUMNS - 1 && faces.size() / CORNERS < FACES; column++) {
            faces.add(column);
            faces.add(column + 1);
            faces.add(COLUMNS + column);
            if (faces.size() / CORNERS >= FACES) {
                break;
            }
            faces.add(COLUMNS + column);
            faces.add(column + 1);
            faces.add(COLUMNS + column + 1);
        }
        int[] faceIndices = new int[faces.size()];
        for (int index = 0; index < faces.size(); index++) {
            faceIndices[index] = faces.get(index);
        }
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
        return new EmbeddedMeshTopology(mesh);
    }
}
