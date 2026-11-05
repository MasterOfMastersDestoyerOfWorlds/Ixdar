package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.paths.query.RayBatch;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.numeric.graph.Dijkstra;

/**
 * Umbrella smoothing over a mesh's own edge adjacency: each pass moves a vertex a weight of the way
 * toward the mean of its edge neighbours. Alternating a positive and a negative weight is Taubin's
 * lambda/mu smoothing (SIGGRAPH 1995), which evens out a surface without shrinking it.
 */
public final class MeshSmoothing {

    public static final float CLEARANCE_KEPT = 0.5f;

    public static final float RAMP_SLOPE = 1f;

    public static final int RAMP_EASING_PASSES = 4;

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
        float[] start = mesh.packedPositions(activeOf);
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
        return unfold(mesh, activeOf, from, to, surface, halvings, new MeshFolds(mesh, activeOf, from));
    }

    /**
     * {@link #unfold(MeshTopology, int[], float[], float[], TriangleBvh, int)} with the caller's fold
     * test, so a caller that counts folds around the move builds it once. Its first round refreshes
     * every vertex, so only the test's start state matters.
     *
     * @param mesh     the surface's topology
     * @param activeOf active vertex index by vertex id
     * @param from     packed positions before the move, read-only
     * @param to       packed positions after the move, shortened in place
     * @param surface  triangle tree over the mesh at {@code from}, or null to skip crossings
     * @param halvings rounds that halve the steps around a failure before rounds that cancel them
     * @param folds    fold test built at {@code from}, in any later state; left at the accepted move
     * @return how many faces and edges the whole move would have folded or crossed
     */
    public static int unfold(MeshTopology mesh, int[] activeOf, float[] from, float[] to, TriangleBvh surface,
            int halvings, MeshFolds folds) {
        int vertexCount = mesh.vertexCount();
        float[] step = new float[vertexCount * 3];
        for (int index = 0; index < step.length; index++) {
            step[index] = to[index] - from[index];
        }
        float[] kept = new float[vertexCount];
        float[] stepLength = new float[vertexCount];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int at = activeVertex * 3;
            stepLength[activeVertex] = (float) Math.sqrt(
                    step[at] * step[at] + step[at + 1] * step[at + 1] + step[at + 2] * step[at + 2]);
        }
        boolean[] listed = new boolean[vertexCount];
        int[] seeds = new int[vertexCount];
        Dijkstra walk = new Dijkstra();
        RayBatch rays = surface == null ? null : new RayBatch(surface, mesh.vertexIdBound());
        int[] hitByEdgeId = new int[mesh.edgeIdBound()];
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
            // Edge rays are cast in parallel, then their hits are read in order.
            if (rays != null) {
                rays.forEach(mesh.edgeCount(), (scratch, activeEdge) -> {
                    int edgeId = mesh.edgeIdAt(activeEdge);
                    if (!retestByEdgeId[edgeId]) {
                        return;
                    }
                    int halfEdge = mesh.edgeHalfEdge(edgeId);
                    int startId = mesh.halfEdgeVertex(halfEdge);
                    int endId = mesh.halfEdgeEndVertex(halfEdge);
                    for (int axis = 0; axis < 3; axis++) {
                        scratch.origin[axis] = to[activeOf[startId] * 3 + axis];
                        scratch.direction[axis] = to[activeOf[endId] * 3 + axis] - scratch.origin[axis];
                    }
                    scratch.markById[startId] = ++scratch.mark;
                    scratch.markById[endId] = scratch.mark;
                    hitByEdgeId[edgeId] = surface.nearestHitAvoiding(scratch.origin, scratch.direction, 1.0,
                            scratch.markById, scratch.mark, scratch.stack, scratch.parameter);
                });
            }
            for (int activeEdge = 0; activeEdge < mesh.edgeCount() && surface != null; activeEdge++) {
                int edgeId = mesh.edgeIdAt(activeEdge);
                if (!retestByEdgeId[edgeId]) {
                    continue;
                }
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                int startId = mesh.halfEdgeVertex(halfEdge);
                int endId = mesh.halfEdgeEndVertex(halfEdge);
                int hit = hitByEdgeId[edgeId];
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
            if (round < 0 && rays != null) {
                rays.forEach(vertexCount, (scratch, activeVertex) -> {
                    int vertexId = mesh.vertexIdAt(activeVertex);
                    System.arraycopy(from, activeVertex * 3, scratch.origin, 0, 3);
                    System.arraycopy(step, activeVertex * 3, scratch.direction, 0, 3);
                    if (stepLength[activeVertex] == 0f) {
                        return;
                    }
                    scratch.markById[vertexId] = ++scratch.mark;
                    for (int slot = 0; slot < mesh.vertexEdgeCount(vertexId); slot++) {
                        scratch.markById[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, slot), vertexId)] = scratch.mark;
                    }
                    kept[activeVertex] = surface.nearestHitAvoiding(scratch.origin, scratch.direction, 1.0,
                            scratch.markById, scratch.mark, scratch.stack, scratch.parameter) < 0 ? 1f
                                    : (float) Math.min(1.0, scratch.parameter[0] * CLEARANCE_KEPT);
                });
            }
            if (round < 0 && surface != null && halvings > 0) {
                int seedLength = 0;
                for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                    seeds[seedLength] = activeVertex;
                    seedLength += kept[activeVertex] < 1f ? 1 : 0;
                }
                ramp(mesh, activeOf, from, stepLength, kept, seeds, seedLength, listed, walk);
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
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                if (next[activeVertex] < kept[activeVertex]) {
                    kept[activeVertex] = next[activeVertex];
                    changed[changedLength++] = activeVertex;
                }
            }
            if (halvings > 0) {
                changedLength = ramp(mesh, activeOf, from, stepLength, kept, changed, changedLength, listed, walk);
            }
            for (int index = 0; index < changedLength; index++) {
                int vertexId = mesh.vertexIdAt(changed[index]);
                for (int face = 0; face < mesh.vertexFaceCount(vertexId); face++) {
                    int faceId = mesh.vertexFaceAt(vertexId, face);
                    for (int side = 0; side < mesh.faceEdgeCount(faceId); side++) {
                        retestByEdgeId[mesh.faceEdgeAt(faceId, side)] = true;
                    }
                }
            }
        }
    }

    /**
     * Spread cuts as a ramp: no vertex keeps more of its step than a neighbour keeps plus
     * {@link #RAMP_SLOPE} edge lengths of its own step, so a cut cannot leave a vertex behind its
     * neighbours and fold the faces between them.
     *
     * @param mesh          the surface's topology
     * @param activeOf      active vertex index by vertex id
     * @param from          packed positions before the move, read-only
     * @param stepLength    each vertex's step length
     * @param kept          the fraction of its step each vertex keeps, lowered in place
     * @param changed       the cut vertices first; vertices the ramp lowers are appended
     * @param changedLength how many leading entries of {@code changed} are cut
     * @param listed        scratch, all false, per active vertex; left all false
     * @param walk          scratch search, reused across calls
     * @return how many entries of {@code changed} are now in use
     */
    private static int ramp(MeshTopology mesh, int[] activeOf, float[] from, float[] stepLength, float[] kept,
            int[] changed, int changedLength, boolean[] listed, Dijkstra walk) {
        // Dijkstra from the cut vertices over active indices, each settled at the fraction it keeps.
        walk.start(kept.length);
        for (int index = 0; index < changedLength; index++) {
            listed[changed[index]] = true;
            walk.relax(changed[index], kept[changed[index]], -1);
        }
        int length = changedLength;
        for (int activeVertex = walk.settle(); activeVertex >= 0; activeVertex = walk.settle()) {
            float value = kept[activeVertex];
            int vertexId = mesh.vertexIdAt(activeVertex);
            for (int slot = 0; slot < mesh.vertexEdgeCount(vertexId); slot++) {
                int neighbour = activeOf[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, slot), vertexId)];
                if (stepLength[neighbour] == 0f) {
                    continue;
                }
                float edge = (float) Math.sqrt(Vector3f.distanceSquared(from[activeVertex * 3],
                        from[activeVertex * 3 + 1], from[activeVertex * 3 + 2], from[neighbour * 3],
                        from[neighbour * 3 + 1], from[neighbour * 3 + 2]));
                float bound = value + RAMP_SLOPE * edge / stepLength[neighbour];
                if (bound < kept[neighbour]) {
                    kept[neighbour] = bound;
                    walk.relax(neighbour, bound, activeVertex);
                    if (!listed[neighbour]) {
                        listed[neighbour] = true;
                        changed[length++] = neighbour;
                    }
                }
            }
        }
        // A distance ramp is straight-sided and creases where two meet; easing each vertex down to
        // its ring's mean rounds the creases off and never undoes a cut.
        for (int pass = 0; pass < RAMP_EASING_PASSES; pass++) {
            for (int index = 0; index < length; index++) {
                int activeVertex = changed[index];
                int vertexId = mesh.vertexIdAt(activeVertex);
                int degree = mesh.vertexEdgeCount(vertexId);
                float mean = 0f;
                for (int slot = 0; slot < degree; slot++) {
                    mean += kept[activeOf[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, slot), vertexId)]] / degree;
                }
                kept[activeVertex] = degree == 0 ? kept[activeVertex] : Math.min(kept[activeVertex], mean);
            }
        }
        for (int index = 0; index < length; index++) {
            listed[changed[index]] = false;
        }
        return length;
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
