package ixdar.geometry.mesh.data.sculpt;

import java.util.Arrays;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Adaptive refinement under a pass's strokes: rounds of {@link MidpointSplit} over the faces whose
 * edges are longer than what the strokes reaching them require, mirrored when the pass is, ending
 * with the mirror twins of the final mesh's vertices.
 */
public final class StrokeRefinement {

    public static final int MAXIMUM_ROUNDS = 4;

    public static final float TWIN_EDGE_FRACTION = 0.1f;

    /** Edge length refinement stops at, however thin or close the strokes are. */
    public float minimumEdgeLength = 0.005f;

    /** Fraction of a stroke's width, or of its gap to the nearest other stroke, edges are split below. */
    public float edgesPerWidth = 1f / 3f;

    /** Axis the pass mirrors across, -1 for none. */
    public int symmetryAxis = -1;

    /**
     * Each final vertex's mirror twin by dense index, -1 for none, empty without symmetry: the vertex
     * at its mirror image within a tenth of its shortest edge.
     */
    public int[] twinByActiveVertex = new int[0];

    /** The float crease marks carried onto the refined mesh, null when the input had none. */
    public float[] creases;

    /**
     * Refine a surface under strokes until every reached face's edges are short enough, at most
     * {@link #MAXIMUM_ROUNDS} rounds; with {@code split} false only the twins are found.
     *
     * @param mesh    the pass's input surface
     * @param copies  the pass's stroke copies, their {@link SculptStroke#refineTarget} set
     * @param split   whether to refine at all
     * @param creases the input's float crease marks by edge id, or null
     * @return the refined surface, the input itself when nothing split
     */
    public MeshTopology refine(MeshTopology mesh, List<SculptStroke> copies, boolean split, float[] creases) {
        this.creases = creases;
        MidpointSplit splitter = new MidpointSplit();
        Vector3f position = new Vector3f();
        Vector3f other = new Vector3f();
        for (int round = 0;; round++) {
            int vertexCount = mesh.vertexCount();
            int[] activeVertexById = new int[mesh.vertexIdBound()];
            float[] vertexXyz = mesh.packedPositions(activeVertexById);
            NearestVertex nearestVertex = NearestVertex.over(mesh);
            // On a surface that is not exactly symmetric (a quad layout) most vertices have no twin
            // and keep their own displacement, which the reflected strokes already made symmetric.
            twinByActiveVertex = new int[symmetryAxis < 0 ? 0 : vertexCount];
            for (int activeVertex = 0; activeVertex < twinByActiveVertex.length; activeVertex++) {
                int vertexId = mesh.vertexIdAt(activeVertex);
                float shortest = Float.POSITIVE_INFINITY;
                for (int adjacency = 0; adjacency < mesh.vertexEdgeCount(vertexId); adjacency++) {
                    shortest = Math.min(shortest, mesh.edgeLength(mesh.vertexEdgeAt(vertexId, adjacency)));
                }
                position.set(vertexXyz[activeVertex * 3], vertexXyz[activeVertex * 3 + 1], vertexXyz[activeVertex * 3 + 2]);
                position.setComponent(symmetryAxis, -position.get(symmetryAxis));
                int twinId = nearestVertex.nearest(position.x, position.y, position.z, shortest * TWIN_EDGE_FRACTION);
                twinByActiveVertex[activeVertex] = twinId < 0 ? -1 : activeVertexById[twinId];
            }
            if (round == MAXIMUM_ROUNDS || !split) {
                return mesh;
            }
            float[] requiredByActiveVertex = new float[vertexCount];
            Arrays.fill(requiredByActiveVertex, Float.POSITIVE_INFINITY);
            for (SculptStroke copy : copies) {
                copy.requireEdgeLengths(mesh, vertexXyz, nearestVertex, requiredByActiveVertex);
            }
            for (int activeVertex = 0; activeVertex < twinByActiveVertex.length; activeVertex++) {
                int twin = twinByActiveVertex[activeVertex];
                requiredByActiveVertex[activeVertex] = Math.min(requiredByActiveVertex[activeVertex],
                        twin < 0 ? Float.POSITIVE_INFINITY : requiredByActiveVertex[twin]);
            }
            boolean[] splitByFaceId = new boolean[mesh.faceIdBound()];
            boolean splitting = false;
            for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
                int faceId = mesh.faceIdAt(activeFace);
                int corners = mesh.faceVertexCount(faceId);
                float required = Float.POSITIVE_INFINITY;
                float longest = 0f;
                for (int corner = 0; corner < corners; corner++) {
                    int vertexId = mesh.faceVertexAt(faceId, corner);
                    required = Math.min(required, requiredByActiveVertex[activeVertexById[vertexId]]);
                    mesh.vertexPosition(vertexId, position);
                    mesh.vertexPosition(mesh.faceVertexAt(faceId, (corner + 1) % corners), other);
                    longest = Math.max(longest, position.distance(other));
                }
                splitting |= longest > required;
                splitByFaceId[faceId] = longest > required || corners > MidpointSplit.QUAD_CORNERS;
            }
            if (!splitting) {
                return mesh;
            }
            MeshTopology refined = splitter.split(mesh, splitByFaceId);
            this.creases = splitter.carryCreases(refined, this.creases);
            mesh = refined;
        }
    }
}
