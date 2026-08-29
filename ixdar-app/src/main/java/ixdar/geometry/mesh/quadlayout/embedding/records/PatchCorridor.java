package ixdar.geometry.mesh.quadlayout.embedding.records;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.gridmap.PatchRegions;

/**
 * The region of the working copy one patch covers, found by flooding outwards from inside it and
 * stopping at its own boundary arcs.
 *
 * <p>Local by necessity: a live zero-patch encloses no faces, so {@link PatchRegions} cannot answer
 * this while the contraction is still running.
 */
public final class PatchCorridor {

    public final ArcNetwork tmesh;

    /** Faces of the patch flooded last, refilled per call; see {@link #patchFaces}. */
    public final IntIdList faceScratch = new IntIdList(0);

    /** Generation per copy face marking the faces the current flood has reached. */
    public int[] visitStampByCopyFace = new int[0];

    /** Current generation of {@link #visitStampByCopyFace}. */
    public int visitStamp;

    /**
     * Stores the T-mesh whose patches are enclosed.
     *
     * @param tmesh embedded T-mesh the patches belong to
     */
    public PatchCorridor(ArcNetwork tmesh) {
        this.tmesh = tmesh;
    }

    /**
     * The copy faces one patch covers, in {@link #faceScratch}: a shared buffer, so the answer
     * is only valid until the next call.
     *
     * @param patchId patch whose faces are wanted
     * @throws IllegalStateException when no side of any boundary arc floods an interior
     * @return the copy faces it covers
     */
    public IntIdList patchFaces(int patchId) {
        growStamps();
        return floodWithin(seedFaceInside(patchId));
    }

    /**
     * Whether a patch has a boundary arc a flood of it can start from. A patch whose every
     * boundary arc is dead or embedded as a point encloses nothing to find.
     *
     * @param patchId patch to test
     * @return true when {@link #patchFaces} can seed inside it
     */
    public boolean hasSeedableBoundary(int patchId) {
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int boundaryArcId : tmesh.patches.get(patchId).sideArcIds.get(side)) {
                EmbeddedArc boundaryArc = tmesh.arcs.get(boundaryArcId);
                if (boundaryArc.alive && boundaryArc.path.copyVertexPath.size() >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A side of the patch with the last flood on both of its sides: the flood ran round that arc
     * instead of stopping at it, so what it found is not the patch's cover. An arc with the same
     * patch either side separates nothing and is skipped.
     *
     * @param patchId patch that was flooded
     * @return the arc the flood ran around, or {@link ArcNetwork#NONE} when it stayed inside
     */
    public int foreignArcOnLastFlood(int patchId) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int boundaryArcId : tmesh.patches.get(patchId).sideArcIds.get(side)) {
                EmbeddedArc boundaryArc = tmesh.arcs.get(boundaryArcId);
                if (tmesh.topology.resolvePatch(boundaryArc.leftPatchId)
                        == tmesh.topology.resolvePatch(boundaryArc.rightPatchId)) {
                    continue;
                }
                for (int edgeId : boundaryArc.path.copyEdgePath) {
                    int halfEdge = copy.edgeHalfEdge(edgeId);
                    if (flooded(copy.halfEdgeFace(halfEdge))
                            && flooded(copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge)))) {
                        return boundaryArcId;
                    }
                }
            }
        }
        return ArcNetwork.NONE;
    }

    /**
     * The arcs whose claims bound the flood {@link #patchFaces} ran last, which says whose cell
     * the flood actually filled — a flood seeded on the wrong side of a boundary arc reports the
     * neighbouring patch's bounding arcs, not its own.
     *
     * @return the owning arc ids of every claimed edge touching a flooded face, ascending
     */
    public List<Integer> boundingArcsOfLastFlood() {
        HalfEdgeMesh copy = tmesh.topology.copy;
        Set<Integer> owners = new TreeSet<>();
        for (int cursor = 0; cursor < faceScratch.size(); cursor++) {
            int faceId = faceScratch.get(cursor);
            for (int corner = 0; corner < copy.faceHalfEdgeCount(faceId); corner++) {
                int ownerArcId = tmesh.topology.ownerArcByCopyEdge[copy.faceEdgeAt(faceId, corner)];
                if (ownerArcId != EmbeddedMeshTopology.UNCLAIMED) {
                    owners.add(ownerArcId);
                }
            }
        }
        return new ArrayList<>(owners);
    }

    /**
     * Whether a copy face lies in the flood {@link #patchFaces} ran last.
     *
     * @param copyFaceId copy face to test
     * @return true when that flood reached the face
     */
    private boolean flooded(int copyFaceId) {
        return copyFaceId >= 0 && copyFaceId < visitStampByCopyFace.length
                && visitStampByCopyFace[copyFaceId] == visitStamp;
    }

    /**
     * Sizes the stamp arrays to the copy's id bounds, which grow as re-routes refine it, and
     * restarts the generations when one is about to overflow.
     */
    private void growStamps() {
        int faceIdBound = tmesh.topology.sourceFaceByCopyFace.length;
        if (visitStampByCopyFace.length < faceIdBound) {
            visitStampByCopyFace = Arrays.copyOf(visitStampByCopyFace, faceIdBound);
        }
        if (visitStamp == Integer.MAX_VALUE) {
            Arrays.fill(visitStampByCopyFace, 0);
            visitStamp = 0;
        }
    }

    /**
     * A copy face lying inside a patch, taken from the interior side of one of its boundary arcs.
     *
     * <p>The interior side comes from {@code leftPatchId}, which records the direction
     * {@code addPatch} walked rather than a fact about the surface, so a layout walked the other
     * way seeds outside. See {@code PatchInteriorSeedTest}.
     *
     * @param patchId patch to seed a flood inside
     * @throws IllegalStateException when the patch has no live boundary arc to take a side from
     * @return a copy face it covers
     */
    private int seedFaceInside(int patchId) {
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int boundaryArcId : tmesh.patches.get(patchId).sideArcIds.get(side)) {
                EmbeddedArc boundaryArc = tmesh.arcs.get(boundaryArcId);
                if (!boundaryArc.alive || boundaryArc.path.copyVertexPath.size() < 2) {
                    continue;
                }
                int faceId = seedFromArc(boundaryArc, boundaryArc.leftPatchId == patchId);
                if (faceId != EmbeddedMeshTopology.UNCLAIMED) {
                    return faceId;
                }
            }
        }
        throw new IllegalStateException("patch " + patchId
                + " has no live boundary arc to seed its interior from");
    }

    /**
     * The faces reachable from a seed without crossing an arc. Every arc bounds patches and none
     * runs through one, so the claims are the wall — including arcs the patch does not list,
     * which is what closes its boundary while a collapse has one of its corners half moved.
     *
     * @param seed face to flood from
     * @return the reachable faces, seed first
     */
    private IntIdList floodWithin(int seed) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        visitStamp++;
        faceScratch.clear();
        visitStampByCopyFace[seed] = visitStamp;
        faceScratch.add(seed);
        for (int cursor = 0; cursor < faceScratch.size(); cursor++) {
            int faceId = faceScratch.get(cursor);
            for (int corner = 0; corner < copy.faceHalfEdgeCount(faceId); corner++) {
                int edgeId = copy.faceEdgeAt(faceId, corner);
                if (tmesh.topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int halfEdge = copy.edgeHalfEdge(edgeId);
                int neighbour = copy.halfEdgeFace(halfEdge) == faceId
                        ? copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge))
                        : copy.halfEdgeFace(halfEdge);
                if (neighbour != EmbeddedMeshTopology.UNCLAIMED
                        && visitStampByCopyFace[neighbour] != visitStamp) {
                    visitStampByCopyFace[neighbour] = visitStamp;
                    faceScratch.add(neighbour);
                }
            }
        }
        return faceScratch;
    }

    /**
     * The copy face on one side of a boundary arc's first hop.
     *
     * <p>A half-edge carries the face on its left, so walking the arc's path forwards yields the
     * face to the arc's left and backwards the face to its right.
     *
     * @param boundaryArc arc to take a side from
     * @param takeLeft    whether to take the face left of the arc's forward direction
     * @return the copy face on that side, or {@link EmbeddedMeshTopology#UNCLAIMED} off the mesh
     */
    private int seedFromArc(EmbeddedArc boundaryArc, boolean takeLeft) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        List<Integer> path = boundaryArc.path.copyVertexPath;
        int from = takeLeft ? path.get(0) : path.get(1);
        int to = takeLeft ? path.get(1) : path.get(0);
        int halfEdge = copy.edgeHalfEdge(tmesh.topology.edgeBetween(from, to));
        if (copy.halfEdgeVertex(halfEdge) != from) {
            halfEdge = copy.halfEdgeTwin(halfEdge);
        }
        return copy.halfEdgeFace(halfEdge);
    }
}
