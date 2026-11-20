package ixdar.geometry.mesh.data.sculpt;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.MeshDistance;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.ops.MeshProjection;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * A stroke key's goal offsets: each surface vertex's signed distance to the goal along its normal,
 * by {@link MeshProjection}; a miss or a back face falls back to the nearest goal point, marked
 * unreachable.
 */
public final class GoalProjection {

    /** Signed offset to the goal along {@link #normalXyz} by dense vertex index, + outward. */
    public float[] offset = new float[0];

    /** Whether the normal ray met a goal face turned the same way, by dense vertex index. */
    public boolean[] reachable = new boolean[0];

    /** Area each dense vertex stands for: a third of its fan triangles' area. */
    public float[] area = new float[0];

    /** The unit directions offsets are measured along, by dense vertex index. */
    public PackedPoints normalXyz = new PackedPoints(0);

    /**
     * A mesh carried from one frame into another the way {@code mesh-compare --normalize} relates
     * them: the first frame's bounding-box centre and half diagonal mapped onto the second's.
     *
     * @param mesh           the mesh to move
     * @param from           mesh whose bounding box defines the frame {@code mesh} is in
     * @param toCenter       the bounding-box centre of the frame to move it into
     * @param toHalfDiagonal that frame's bounding-box half diagonal
     * @return the moved mesh, vertex and face order kept
     */
    public static HalfEdgeMesh moved(MeshTopology mesh, MeshTopology from, Vector3f toCenter, float toHalfDiagonal) {
        return MeshVertexOffset.atPositions(mesh, MeshDistance.inFrame(MeshDistance.normalizedPositions(mesh, from),
                toCenter, 2f * toHalfDiagonal));
    }

    /**
     * Project every surface vertex, carried into the goal's frame as mesh-compare --normalize relates
     * them, onto the goal: the nearer hit along its normal either way, else the nearest goal point.
     *
     * @param surface   the surface whose vertices move
     * @param normals   direction per dense vertex index, any length; kept normalised
     * @param goal      the goal, in its own frame
     * @param frame     the mesh whose bounding box defines the surface's frame (the base)
     */
    public void project(MeshTopology surface, PackedPoints normals, MeshTopology goal, MeshTopology frame) {
        float scale = goal.boundsDiagonal() / Math.max(SculptStroke.MINIMUM_NORMAL_LENGTH, frame.boundsDiagonal());
        int vertexCount = surface.vertexCount();
        PackedPoints units = new PackedPoints(normals.size());
        for (int point = 0; point < units.size(); point++) {
            units.set(point, normals.at(point).normalize());
        }
        normalXyz = units;
        area = surface.vertexAreas();
        PackedPoints origins = MeshDistance.inFrame(MeshDistance.normalizedPositions(surface, frame),
                goal.center(new Vector3f()), goal.boundsDiagonal());
        MeshProjection hits = MeshProjection.along(origins, units, goal);
        offset = hits.offset;
        reachable = new boolean[vertexCount];
        float facingSign = goal.signedVolume() < 0.0 ? -1f : 1f;
        FaceAnchors nearest = new FaceAnchors(goal);
        float[] anchor = new float[FaceAnchors.FIELDS];
        Vector3f faceNormal = new Vector3f();
        Vector3f point = new Vector3f();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int face = hits.hitFace[activeVertex];
            Vector3f unit = units.at(activeVertex);
            reachable[activeVertex] = face >= 0 && facingSign * goal.faceNormal(face, faceNormal).dot(unit) > 0f;
            if (!reachable[activeVertex]) {
                offset[activeVertex] = 0f;
                Vector3f origin = origins.at(activeVertex);
                if (nearest.anchor(origin, anchor, 0) >= 0) {
                    offset[activeVertex] = FaceAnchors.position(goal, anchor, 0, point).sub(origin).dot(unit);
                }
            }
            offset[activeVertex] /= scale;
        }
    }

    /**
     * The share of the surface's area its normals cannot carry onto the goal.
     *
     * @return unreachable area over total area, in [0, 1]
     */
    public float unreachableShare() {
        double unreachableArea = 0.0;
        double totalArea = 0.0;
        for (int activeVertex = 0; activeVertex < area.length; activeVertex++) {
            totalArea += area[activeVertex];
            unreachableArea += reachable[activeVertex] ? 0.0 : area[activeVertex];
        }
        return totalArea > 0.0 ? (float) (unreachableArea / totalArea) : 0f;
    }
}
