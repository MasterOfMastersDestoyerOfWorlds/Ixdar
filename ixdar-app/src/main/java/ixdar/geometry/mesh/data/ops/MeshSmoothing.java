package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.paths.query.RayBatch;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
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
     * @return the displacement per active vertex
     */
    public static PackedPoints umbrella(MeshTopology mesh, float lambda, float mu, int passes, boolean[] movable) {
        int[] activeOf = new int[mesh.vertexIdBound()];
        PackedPoints start = mesh.packedPositions(activeOf);
        PackedPoints current = start.copy();
        PackedPoints next = start.copy();
        int[][] rings = mesh.oneRings();
        for (int pass = 0; pass < passes; pass++) {
            next.lerpToward(current.ringMean(rings), pass % 2 == 1 && mu != 0f ? mu : lambda, movable);
            unfold(mesh, activeOf, current, next, null, 0);
            current.copyFrom(next);
        }
        current.addScaled(start, -1f);
        return current;
    }

    /**
     * Shorten a move until no face folds ({@link MeshFolds}), against its own corners or turned over
     * against those it started with, and, given a surface, no edge passes through a face; what was so
     * before the move may stay.
     *
     * @param mesh     the surface's topology
     * @param activeOf active vertex index by vertex id
     * @param from     positions before the move by dense index, read-only; may hold more points than
     *                 the mesh's vertices
     * @param to       positions after the move by dense index, shortened in place
     * @param surface  triangle tree over the mesh at {@code from}, refitted in place and left at
     *                 {@code to}; each step then starts at {@link #CLEARANCE_KEPT} of its clearance.
     *                 Null skips crossings
     * @param halvings rounds that halve the steps around a failure, each tapered into the ring around
     *                 it, before rounds that cancel them; 0 cancels at once
     * @return how many faces and edges the whole move would have folded or crossed
     */
    public static int unfold(MeshTopology mesh, int[] activeOf, PackedPoints from, PackedPoints to,
            TriangleBvh surface, int halvings) {
        return unfold(mesh, activeOf, from, to, surface, halvings, new MeshFolds(mesh, activeOf, from));
    }

    /**
     * {@link #unfold(MeshTopology, int[], PackedPoints, PackedPoints, TriangleBvh, int)} with the
     * caller's fold test, so a caller that counts folds around the move builds it once. Its first
     * round refreshes every vertex, so only the test's start state matters.
     *
     * @param mesh     the surface's topology
     * @param activeOf active vertex index by vertex id
     * @param from     positions before the move by dense index, read-only
     * @param to       positions after the move by dense index, shortened in place
     * @param surface  triangle tree over the mesh at {@code from}, or null to skip crossings
     * @param halvings rounds that halve the steps around a failure before rounds that cancel them
     * @param folds    fold test built at {@code from}, in any later state; left at the accepted move
     * @return how many faces and edges the whole move would have folded or crossed
     */
    public static int unfold(MeshTopology mesh, int[] activeOf, PackedPoints from, PackedPoints to,
            TriangleBvh surface, int halvings, MeshFolds folds) {
        int vertexCount = mesh.vertexCount();
        PackedPoints step = new PackedPoints(vertexCount);
        float[] kept = new float[vertexCount];
        float[] stepLength = new float[vertexCount];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            Vector3f move = to.at(activeVertex).sub(from.at(activeVertex));
            step.set(activeVertex, move);
            stepLength[activeVertex] = move.length();
        }
        boolean[] listed = new boolean[vertexCount];
        int[] seeds = new int[vertexCount];
        Dijkstra walk = new Dijkstra();
        RayBatch rays = surface == null ? null : new RayBatch(surface, mesh.vertexIdBound());
        int[] hitByEdgeId = new int[mesh.edgeIdBound()];
        boolean[] crossedBefore = new boolean[mesh.edgeIdBound()];
        int[] changed = new int[vertexCount];
        int changedLength = vertexCount;
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            changed[activeVertex] = activeVertex;
        }
        boolean[] retestByEdgeId = new boolean[mesh.edgeIdBound()];
        Arrays.fill(retestByEdgeId, true);
        boolean everyEdge = true;
        int prevented = 0;
        for (int round = -1;; round++) {
            for (int index = 0; index < changedLength; index++) {
                int vertex = changed[index];
                Vector3f moved = from.at(vertex).add(step.at(vertex).mul(kept[vertex]));
                to.set(vertex, moved);
                if (surface != null && round >= 0) {
                    surface.positions.set(mesh.vertexIdAt(vertex), moved);
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
            if (rays != null) {
                rays.forEach(mesh.edgeCount(), (scratch, activeEdge) -> {
                    int edgeId = mesh.edgeIdAt(activeEdge);
                    if (!retestByEdgeId[edgeId]) {
                        return;
                    }
                    int halfEdge = mesh.edgeHalfEdge(edgeId);
                    int startId = mesh.halfEdgeVertex(halfEdge);
                    int endId = mesh.halfEdgeEndVertex(halfEdge);
                    int start = activeOf[startId];
                    int end = activeOf[endId];
                    scratch.origin.set(to.get(start, 0), to.get(start, 1), to.get(start, 2));
                    scratch.direction.set(to.get(end, 0), to.get(end, 1), to.get(end, 2)).sub(scratch.origin);
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
            if (round < 0 && rays != null) {
                rays.forEach(vertexCount, (scratch, activeVertex) -> {
                    int vertexId = mesh.vertexIdAt(activeVertex);
                    scratch.origin.set(from.get(activeVertex, 0), from.get(activeVertex, 1), from.get(activeVertex, 2));
                    scratch.direction.set(step.get(activeVertex, 0), step.get(activeVertex, 1), step.get(activeVertex, 2));
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
     * @param from          positions before the move by dense index, read-only
     * @param stepLength    each vertex's step length
     * @param kept          the fraction of its step each vertex keeps, lowered in place
     * @param changed       the cut vertices first; vertices the ramp lowers are appended
     * @param changedLength how many leading entries of {@code changed} are cut
     * @param listed        scratch, all false, per active vertex; left all false
     * @param walk          scratch search, reused across calls
     * @return how many entries of {@code changed} are now in use
     */
    private static int ramp(MeshTopology mesh, int[] activeOf, PackedPoints from, float[] stepLength, float[] kept,
            int[] changed, int changedLength, boolean[] listed, Dijkstra walk) {
        walk.start(kept.length);
        for (int index = 0; index < changedLength; index++) {
            listed[changed[index]] = true;
            walk.relax(changed[index], kept[changed[index]], -1);
        }
        int length = changedLength;
        for (int activeVertex = walk.settle(); activeVertex >= 0; activeVertex = walk.settle()) {
            float value = kept[activeVertex];
            int vertexId = mesh.vertexIdAt(activeVertex);
            Vector3f position = from.at(activeVertex);
            for (int slot = 0; slot < mesh.vertexEdgeCount(vertexId); slot++) {
                int neighbour = activeOf[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, slot), vertexId)];
                if (stepLength[neighbour] == 0f) {
                    continue;
                }
                float edge = (float) Math.sqrt(from.distanceSquared(neighbour, position));
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
}
