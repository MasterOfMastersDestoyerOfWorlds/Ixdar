package ixdar.geometry.mesh.data.sculpt;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.MeshDistance;
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

    /** The unit directions offsets are measured along, packed xyz by dense vertex index. */
    public float[] normalXyz = new float[0];

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
     * @param normalXyz direction per dense vertex index, packed xyz, any length; kept normalised
     * @param goal      the goal, in its own frame
     * @param frame     the mesh whose bounding box defines the surface's frame (the base)
     */
    public void project(MeshTopology surface, float[] normalXyz, MeshTopology goal, MeshTopology frame) {
        float scale = goal.boundsDiagonal() / Math.max(SculptStroke.MINIMUM_NORMAL_LENGTH, frame.boundsDiagonal());
        int vertexCount = surface.vertexCount();
        this.normalXyz = normalXyz.clone();
        Vector3f unit = new Vector3f();
        for (int at = 0; at < normalXyz.length; at += 3) {
            unit.set(normalXyz[at], normalXyz[at + 1], normalXyz[at + 2]).normalize();
            this.normalXyz[at] = unit.x;
            this.normalXyz[at + 1] = unit.y;
            this.normalXyz[at + 2] = unit.z;
        }
        normalXyz = this.normalXyz;
        area = surface.vertexAreas();
        float[] originXyz = MeshDistance.inFrame(MeshDistance.normalizedPositions(surface, frame),
                goal.center(new Vector3f()), goal.boundsDiagonal());
        MeshProjection hits = MeshProjection.along(originXyz, normalXyz, goal);
        offset = hits.offset;
        reachable = new boolean[vertexCount];
        // A goal wound inside out (its signed volume negative) has every face normal reversed, so a
        // hit faces the vertex's way when its face normal points against the vertex normal.
        float facingSign = goal.signedVolume() < 0.0 ? -1f : 1f;
        FaceAnchors nearest = new FaceAnchors(goal);
        float[] anchor = new float[FaceAnchors.FIELDS];
        Vector3f faceNormal = new Vector3f();
        Vector3f point = new Vector3f();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int at = activeVertex * 3;
            int face = hits.hitFace[activeVertex];
            reachable[activeVertex] = face >= 0 && facingSign * goal.faceNormal(face, faceNormal).dot(
                    normalXyz[at], normalXyz[at + 1], normalXyz[at + 2]) > 0f;
            if (!reachable[activeVertex]) {
                // Without a nearest goal point either there is nothing to measure; a back face's
                // distance would sculpt toward the wrong side.
                offset[activeVertex] = 0f;
                if (nearest.anchor(originXyz[at], originXyz[at + 1], originXyz[at + 2], anchor, 0) >= 0) {
                    FaceAnchors.position(goal, anchor, 0, point).sub(originXyz[at], originXyz[at + 1],
                            originXyz[at + 2]);
                    offset[activeVertex] = point.dot(normalXyz[at], normalXyz[at + 1], normalXyz[at + 2]);
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
