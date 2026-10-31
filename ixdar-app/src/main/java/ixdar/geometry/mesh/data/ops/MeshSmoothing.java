package ixdar.geometry.mesh.data.ops;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Umbrella smoothing over a mesh's own edge adjacency: each pass moves a vertex a weight of the way
 * toward the mean of its edge neighbours. Alternating a positive and a negative weight is Taubin's
 * lambda/mu smoothing (SIGGRAPH 1995), which evens out a surface without shrinking it.
 */
public final class MeshSmoothing {

    public static final int FOLD_REPAIR_PASSES = 16;

    private MeshSmoothing() {
    }

    /**
     * How far each vertex moves under smoothing passes that alternate {@code lambda} and {@code mu}
     * (mu 0 repeats lambda); only movable vertices move, and no pass turns a face more than a right
     * angle.
     *
     * @param mesh    the surface, treated read-only
     * @param lambda  weight of the odd passes, toward the neighbours' mean
     * @param mu      weight of the even passes, negative to undo the shrinking; 0 to repeat lambda
     * @param passes  lambda and mu passes, counted together
     * @param movable per active vertex whether it may move, or null for all
     * @return packed xyz displacement per active vertex
     */
    public static float[] umbrella(MeshTopology mesh, float lambda, float mu, int passes, boolean[] movable) {
        int vertexCount = mesh.vertexCount();
        int[] activeOf = new int[mesh.vertexIdBound()];
        float[] start = new float[vertexCount * 3];
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            activeOf[vertexId] = activeVertex;
            mesh.vertexPosition(vertexId, position);
            start[activeVertex * 3] = position.x;
            start[activeVertex * 3 + 1] = position.y;
            start[activeVertex * 3 + 2] = position.z;
        }
        float[] current = start.clone();
        float[] next = start.clone();
        for (int pass = 0; pass < passes; pass++) {
            float weight = pass % 2 == 1 && mu != 0f ? mu : lambda;
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                int vertexId = mesh.vertexIdAt(activeVertex);
                int degree = mesh.vertexEdgeCount(vertexId);
                if (degree == 0 || movable != null && !movable[activeVertex]) {
                    continue;
                }
                position.zero();
                for (int slot = 0; slot < degree; slot++) {
                    int neighbour = activeOf[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, slot), vertexId)];
                    position.add(current[neighbour * 3], current[neighbour * 3 + 1], current[neighbour * 3 + 2]);
                }
                position.div(degree);
                for (int axis = 0; axis < 3; axis++) {
                    float here = current[activeVertex * 3 + axis];
                    next[activeVertex * 3 + axis] = here + weight * (position.get(axis) - here);
                }
            }
            // A face the pass would fold back against the surface, when it was not folded before the
            // pass, puts its corners back, until none does: Taubin passes alone fold slivers into
            // pleats. A face folded already may unfold.
            boolean[] foldedBefore = foldedFaces(mesh, activeOf, current);
            for (int repair = 0; repair < FOLD_REPAIR_PASSES; repair++) {
                boolean[] foldedAfter = foldedFaces(mesh, activeOf, next);
                boolean reverted = false;
                for (int activeFace = 0; activeFace < foldedAfter.length; activeFace++) {
                    if (foldedAfter[activeFace] && !foldedBefore[activeFace]) {
                        reverted = true;
                        int faceId = mesh.faceIdAt(activeFace);
                        for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                            int vertex = activeOf[mesh.faceVertexAt(faceId, corner)] * 3;
                            System.arraycopy(current, vertex, next, vertex, 3);
                        }
                    }
                }
                if (!reverted) {
                    break;
                }
            }
            System.arraycopy(next, 0, current, 0, current.length);
        }
        for (int index = 0; index < current.length; index++) {
            current[index] -= start[index];
        }
        return current;
    }

    /**
     * Which faces fold back against the surface: a face's (Newell) normal points against the sum of
     * its corners' vertex normals, each the sum of its faces' unit normals.
     *
     * @param mesh     the surface's topology
     * @param activeOf active vertex index by vertex id
     * @param xyz      packed positions per active vertex
     * @return folded flag per active face
     */
    private static boolean[] foldedFaces(MeshTopology mesh, int[] activeOf, float[] xyz) {
        float[] faceNormals = new float[mesh.faceCount() * 3];
        float[] vertexNormals = new float[xyz.length];
        Vector3f normal = new Vector3f();
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int size = mesh.faceVertexCount(faceId);
            normal.zero();
            for (int corner = 0; corner < size; corner++) {
                int from = activeOf[mesh.faceVertexAt(faceId, corner)] * 3;
                int to = activeOf[mesh.faceVertexAt(faceId, (corner + 1) % size)] * 3;
                normal.add((xyz[from + 1] - xyz[to + 1]) * (xyz[from + 2] + xyz[to + 2]),
                        (xyz[from + 2] - xyz[to + 2]) * (xyz[from] + xyz[to]),
                        (xyz[from] - xyz[to]) * (xyz[from + 1] + xyz[to + 1]));
            }
            if (normal.lengthSquared() > 0f) {
                normal.normalize();
            }
            for (int axis = 0; axis < 3; axis++) {
                faceNormals[activeFace * 3 + axis] = normal.get(axis);
                for (int corner = 0; corner < size; corner++) {
                    vertexNormals[activeOf[mesh.faceVertexAt(faceId, corner)] * 3 + axis] += normal.get(axis);
                }
            }
        }
        boolean[] folded = new boolean[mesh.faceCount()];
        for (int activeFace = 0; activeFace < folded.length; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            float alignment = 0f;
            for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                int vertex = activeOf[mesh.faceVertexAt(faceId, corner)] * 3;
                for (int axis = 0; axis < 3; axis++) {
                    alignment += faceNormals[activeFace * 3 + axis] * vertexNormals[vertex + axis];
                }
            }
            folded[activeFace] = alignment < 0f;
        }
        return folded;
    }
}
