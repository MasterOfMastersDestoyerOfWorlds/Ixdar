package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.List;

/**
 * LCBK19's snap as decimation of the finished arrangement: each minted path
 * vertex whose lane can shift onto an adjacent free original vertex is snapped
 * there, undoing refinement wherever it is free.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class ArrangementDecimation {

    /** Fixpoint passes allowed; each pass scans every arc path once. */
    private static final int PASS_CAP = 8;

    public final EmbeddedMeshTopology topology;

    /** Embedded path per arc id, snapped in place. */
    public final ArcEdgePath[] pathByArc;

    /** Copy vertices below this id are originals of the source mesh. */
    public final int originalVertexCount;

    /** Minted path vertices snapped onto a free original neighbor. */
    public int snappedVertexCount;

    /** Minted path vertices left in place — no legal snap target. */
    public int keptVertexCount;

    /**
     * Stores the arrangement to decimate.
     *
     * @param topology            working copy with provenance and claims
     * @param pathByArc           per-arc paths, rewritten in place
     * @param originalVertexCount vertex-id bound of the source mesh's copies
     */
    public ArrangementDecimation(EmbeddedMeshTopology topology, ArcEdgePath[] pathByArc,
            int originalVertexCount) {
        this.topology = topology;
        this.pathByArc = pathByArc;
        this.originalVertexCount = originalVertexCount;
    }

    /**
     * Snap every path vertex that has a legal target, repeating while any pass
     * moved one — a snap frees edges that may unlock a neighboring lane's snap.
     *
     * @return this, with counters filled
     */
    public ArrangementDecimation build() {
        boolean changed = true;
        for (int pass = 0; pass < PASS_CAP && changed; pass++) {
            changed = false;
            for (ArcEdgePath path : pathByArc) {
                if (path != null) {
                    changed |= snapPath(path);
                }
            }
        }
        for (ArcEdgePath path : pathByArc) {
            if (path == null) {
                continue;
            }
            for (int index = 1; index < path.copyVertexPath.size() - 1; index++) {
                if (path.copyVertexPath.get(index) >= originalVertexCount) {
                    keptVertexCount++;
                }
            }
        }
        return this;
    }

    /**
     * One snapping sweep over a path's interior vertices.
     *
     * @param path arc path to sweep
     * @return whether any vertex snapped
     */
    private boolean snapPath(ArcEdgePath path) {
        boolean changed = false;
        List<Integer> vertices = path.copyVertexPath;
        for (int index = 1; index < vertices.size() - 1; index++) {
            int vertex = vertices.get(index);
            if (vertex < originalVertexCount
                    || topology.ownerNodeByCopyVertex[vertex] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            int target = snapTarget(vertices.get(index - 1), vertex, vertices.get(index + 1));
            if (target == EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            applySnap(path, index, target);
            snappedVertexCount++;
            changed = true;
        }
        return changed;
    }

    /**
     * A legal snap target for one path vertex: a free original neighbor whose
     * detour sweeps exactly the two faces {@code (prev,v,w)} and {@code (v,next,w)}
     * — real faces contain no structure, so the move is isotopic by construction.
     *
     * @param previous path vertex before the candidate
     * @param vertex   minted path vertex to snap
     * @param next     path vertex after the candidate
     * @return the target vertex, or {@link EmbeddedMeshTopology#UNCLAIMED}
     */
    private int snapTarget(int previous, int vertex, int next) {
        for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
            int spokeEdge = topology.copy.vertexEdgeAt(vertex, index);
            int candidate = topology.otherEndpoint(spokeEdge, vertex);
            if (candidate >= originalVertexCount
                    || topology.ownerNodeByCopyVertex[candidate] != EmbeddedMeshTopology.UNCLAIMED
                    || topology.ownerArcByCopyVertex[candidate] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            if (!faceExists(previous, vertex, candidate) || !faceExists(vertex, next, candidate)) {
                continue;
            }
            int previousEdge = topology.edgeBetween(previous, candidate);
            int nextEdge = topology.edgeBetween(candidate, next);
            if (previousEdge == EmbeddedMeshTopology.UNCLAIMED
                    || nextEdge == EmbeddedMeshTopology.UNCLAIMED
                    || topology.ownerArcByCopyEdge[previousEdge] != EmbeddedMeshTopology.UNCLAIMED
                    || topology.ownerArcByCopyEdge[nextEdge] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            return candidate;
        }
        return EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * Move a path through its snap target: release the old vertex and its two lane
     * edges, claim the target and its two.
     *
     * @param path   arc path being rewritten
     * @param index  interior index of the vertex being snapped
     * @param target free original vertex taking the lane
     */
    private void applySnap(ArcEdgePath path, int index, int target) {
        List<Integer> vertices = path.copyVertexPath;
        List<Integer> edges = path.copyEdgePath;
        int arcId = path.arcId;
        int previous = vertices.get(index - 1);
        int next = vertices.get(index + 1);
        int vertex = vertices.get(index);
        topology.ownerArcByCopyEdge[edges.get(index - 1)] = EmbeddedMeshTopology.UNCLAIMED;
        topology.ownerArcByCopyEdge[edges.get(index)] = EmbeddedMeshTopology.UNCLAIMED;
        topology.ownerArcByCopyVertex[vertex] = EmbeddedMeshTopology.UNCLAIMED;
        int previousEdge = topology.edgeBetween(previous, target);
        int nextEdge = topology.edgeBetween(target, next);
        topology.ownerArcByCopyEdge[previousEdge] = arcId;
        topology.ownerArcByCopyEdge[nextEdge] = arcId;
        topology.ownerArcByCopyVertex[target] = arcId;
        vertices.set(index, target);
        edges.set(index - 1, previousEdge);
        edges.set(index, nextEdge);
    }

    /**
     * Whether three vertices bound an actual copy face.
     *
     * @param cornerA first corner
     * @param cornerB second corner
     * @param cornerC third corner
     * @return true when a face has exactly these corners
     */
    private boolean faceExists(int cornerA, int cornerB, int cornerC) {
        for (int index = 0; index < topology.copy.vertexFaceCount(cornerB); index++) {
            int faceId = topology.copy.vertexFaceAt(cornerB, index);
            boolean hasA = false;
            boolean hasC = false;
            for (int corner = 0; corner < 3; corner++) {
                int cornerVertex = topology.copy.faceVertexAt(faceId, corner);
                hasA |= cornerVertex == cornerA;
                hasC |= cornerVertex == cornerC;
            }
            if (hasA && hasC) {
                return true;
            }
        }
        return false;
    }
}
