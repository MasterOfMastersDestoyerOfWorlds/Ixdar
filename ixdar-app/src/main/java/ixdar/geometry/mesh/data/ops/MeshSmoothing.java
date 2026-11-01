package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Umbrella smoothing over a mesh's own edge adjacency: each pass moves a vertex a weight of the way
 * toward the mean of its edge neighbours. Alternating a positive and a negative weight is Taubin's
 * lambda/mu smoothing (SIGGRAPH 1995), which evens out a surface without shrinking it.
 */
public final class MeshSmoothing {

    public static final float CLEARANCE_KEPT = 0.5f;

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
        float[] mean = new float[current.length];
        for (int pass = 0; pass < passes; pass++) {
            float weight = pass % 2 == 1 && mu != 0f ? mu : lambda;
            neighbourMean(mesh, activeOf, current, 3, mean);
            for (int index = 0; index < current.length; index++) {
                if (movable == null || movable[index / 3]) {
                    next[index] = current[index] + weight * (mean[index] - current[index]);
                }
            }
            // Taubin passes alone fold slivers into pleats; a face folded already may unfold.
            unfold(mesh, activeOf, current, next, null, 0);
            System.arraycopy(next, 0, current, 0, current.length);
        }
        for (int index = 0; index < current.length; index++) {
            current[index] -= start[index];
        }
        return current;
    }

    /**
     * Shorten a move until no face folds ({@link MeshFolds}), against its own corners or turned over
     * against those it started with, and, given a surface, no edge passes through a face; what was so
     * before the move may stay.
     *
     * @param mesh     the surface's topology
     * @param activeOf active vertex index by vertex id
     * @param from     packed positions before the move, read-only; may run past the mesh's vertices
     * @param to       packed positions after the move, shortened in place
     * @param surface  triangle tree over the mesh at {@code from}, refitted in place and left at
     *                 {@code to}; each step then starts at {@link #CLEARANCE_KEPT} of its clearance.
     *                 Null skips crossings
     * @param halvings rounds that halve the steps around a failure, each tapered into the ring around
     *                 it, before rounds that cancel them; 0 cancels at once
     * @return how many faces and edges the whole move would have folded or crossed
     */
    public static int unfold(MeshTopology mesh, int[] activeOf, float[] from, float[] to, TriangleBvh surface,
            int halvings) {
        int vertexCount = mesh.vertexCount();
        float[] step = new float[vertexCount * 3];
        for (int index = 0; index < step.length; index++) {
            step[index] = to[index] - from[index];
        }
        float[] kept = new float[vertexCount];
        float[] ringCut = new float[vertexCount];
        int[] stack = new int[surface == null ? 0 : Math.max(1, surface.nodeCount)];
        int[] markById = new int[mesh.vertexIdBound()];
        int mark = 0;
        float[] origin = new float[3];
        float[] direction = new float[3];
        MeshFolds folds = new MeshFolds(mesh, activeOf, from);
        boolean[] crossedBefore = new boolean[mesh.edgeIdBound()];
        // Only faces and edges near a vertex whose step changed are tested again, until a round finds
        // nothing; then every edge is, once, before the move is accepted.
        int[] changed = new int[vertexCount];
        int changedLength = vertexCount;
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            changed[activeVertex] = activeVertex;
        }
        boolean[] retestByEdgeId = new boolean[mesh.edgeIdBound()];
        Arrays.fill(retestByEdgeId, true);
        boolean everyEdge = true;
        int prevented = 0;
        // Round -1 reads the unmoved surface: its folds and crossings are allowed to stay.
        for (int round = -1;; round++) {
            for (int index = 0; index < changedLength; index++) {
                int at = changed[index] * 3;
                for (int axis = 0; axis < 3; axis++) {
                    to[at + axis] = from[at + axis] + step[at + axis] * kept[changed[index]];
                }
                if (surface != null && round >= 0) {
                    System.arraycopy(to, at, surface.positions, mesh.vertexIdAt(changed[index]) * 3, 3);
                }
            }
            float cut = round < halvings ? 0.5f : 0f;
            float[] next = kept.clone();
            int failing = 0;
            boolean shortened = false;
            if (round >= 0) {
                folds.update(to, changed, changedLength);
            }
            for (int activeFace = 0; activeFace < mesh.faceCount() && round >= 0; activeFace++) {
                int faceId = mesh.faceIdAt(activeFace);
                if (!folds.failedByFaceId[faceId] || folds.foldedAtStart[faceId]) {
                    continue;
                }
                failing++;
                for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                    int activeVertex = activeOf[mesh.faceVertexAt(faceId, corner)];
                    shortened |= kept[activeVertex] > 0f;
                    next[activeVertex] = Math.min(next[activeVertex], kept[activeVertex] * cut);
                }
            }
            if (surface != null && round >= 0) {
                surface.refit();
            }
            for (int activeEdge = 0; activeEdge < mesh.edgeCount() && surface != null; activeEdge++) {
                int edgeId = mesh.edgeIdAt(activeEdge);
                if (!retestByEdgeId[edgeId]) {
                    continue;
                }
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                int startId = mesh.halfEdgeVertex(halfEdge);
                int endId = mesh.halfEdgeEndVertex(halfEdge);
                for (int axis = 0; axis < 3; axis++) {
                    origin[axis] = to[activeOf[startId] * 3 + axis];
                    direction[axis] = to[activeOf[endId] * 3 + axis] - origin[axis];
                }
                markById[startId] = ++mark;
                markById[endId] = mark;
                int hit = surface.nearestHitAvoiding(origin, direction, 1.0, markById, mark, stack);
                crossedBefore[edgeId] |= round < 0 && hit >= 0;
                if (round < 0 || hit < 0 || crossedBefore[edgeId]) {
                    continue;
                }
                failing++;
                for (int end = 0; end < TriangleBvh.TRIANGLE_CORNERS + 2; end++) {
                    int vertexId = end == 0 ? startId : end == 1 ? endId : surface.triangleCorners[hit * 3 + end - 2];
                    int activeVertex = activeOf[vertexId];
                    shortened |= kept[activeVertex] > 0f;
                    next[activeVertex] = Math.min(next[activeVertex], kept[activeVertex] * cut);
                }
            }
            if (round < 0) {
                Arrays.fill(kept, 1f);
            }
            // Each step starts at part of its clearance from the unmoved surface, so a push through a
            // thin wall stops short of it whole instead of being cut back piece by piece.
            for (int activeVertex = 0; activeVertex < vertexCount && round < 0 && surface != null; activeVertex++) {
                int vertexId = mesh.vertexIdAt(activeVertex);
                System.arraycopy(from, activeVertex * 3, origin, 0, 3);
                System.arraycopy(step, activeVertex * 3, direction, 0, 3);
                if (direction[0] == 0f && direction[1] == 0f && direction[2] == 0f) {
                    continue;
                }
                markById[vertexId] = ++mark;
                for (int slot = 0; slot < mesh.vertexEdgeCount(vertexId); slot++) {
                    markById[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, slot), vertexId)] = mark;
                }
                kept[activeVertex] = surface.nearestHitAvoiding(origin, direction, 1.0, markById, mark, stack) < 0 ? 1f
                        : (float) Math.min(1.0, surface.hitParameter * CLEARANCE_KEPT);
            }
            if (round < 0) {
                continue;
            }
            prevented = round == 0 ? failing : prevented;
            // A face can stay folded with its corners home when its corner normals are its own state's.
            if (failing > 0 && !shortened || failing == 0 && (everyEdge || surface == null)) {
                return prevented;
            }
            Arrays.fill(retestByEdgeId, failing == 0);
            everyEdge = failing == 0;
            changedLength = 0;
            if (failing == 0) {
                continue;
            }
            // This round's cut tapers into the ring around it, so the shortened region leaves no crease;
            // an earlier cut has tapered already and does not spread further.
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                next[activeVertex] = kept[activeVertex] - next[activeVertex];
            }
            if (halvings > 0) {
                neighbourMean(mesh, activeOf, next, 1, ringCut);
            }
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                int vertexId = mesh.vertexIdAt(activeVertex);
                int degree = mesh.vertexEdgeCount(vertexId);
                float cutHere = halvings > 0 ? Math.max(next[activeVertex],
                        (next[activeVertex] + degree * ringCut[activeVertex]) / (degree + 1)) : next[activeVertex];
                float tapered = Math.max(0f, kept[activeVertex] - cutHere);
                if (tapered == kept[activeVertex]) {
                    continue;
                }
                changed[changedLength++] = activeVertex;
                for (int face = 0; face < mesh.vertexFaceCount(vertexId); face++) {
                    int faceId = mesh.vertexFaceAt(vertexId, face);
                    for (int side = 0; side < mesh.faceEdgeCount(faceId); side++) {
                        retestByEdgeId[mesh.faceEdgeAt(faceId, side)] = true;
                    }
                }
                kept[activeVertex] = tapered;
            }
        }
    }

    /**
     * Each vertex's edge neighbours' mean of a per-vertex field; a vertex with none keeps its own.
     *
     * @param mesh       surface whose edges give the rings
     * @param activeOf   active vertex index by vertex id
     * @param values     the field, {@code components} floats per active vertex; read-only
     * @param components floats per vertex
     * @param dest       receives the means, laid out as {@code values}
     */
    private static void neighbourMean(MeshTopology mesh, int[] activeOf, float[] values, int components,
            float[] dest) {
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            int degree = mesh.vertexEdgeCount(vertexId);
            int at = activeVertex * components;
            Arrays.fill(dest, at, at + components, 0f);
            for (int slot = 0; slot < degree; slot++) {
                int neighbour = activeOf[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, slot), vertexId)];
                for (int component = 0; component < components; component++) {
                    dest[at + component] += values[neighbour * components + component] / degree;
                }
            }
            if (degree == 0) {
                System.arraycopy(values, at, dest, at, components);
            }
        }
    }
}
