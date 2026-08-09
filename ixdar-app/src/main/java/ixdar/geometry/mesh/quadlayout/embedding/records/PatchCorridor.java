package ixdar.geometry.mesh.quadlayout.embedding.records;

import java.util.List;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.gridmap.PatchRegions;

/**
 * The region of the working copy one patch covers, found by flooding outwards from inside it and
 * stopping at its own boundary arcs.
 *
 * <p>Local by necessity: a live zero-patch encloses no faces, so {@link PatchRegions} cannot answer
 * this while the contraction is still running.
 */
public final class PatchCorridor {

    public final EmbeddedTMesh tmesh;

    /**
     * Stores the T-mesh whose patches are enclosed.
     *
     * @param tmesh embedded T-mesh the patches belong to
     */
    public PatchCorridor(EmbeddedTMesh tmesh) {
        this.tmesh = tmesh;
    }

    /**
     * The copy faces one patch covers.
     *
     * @param patchId patch whose faces are wanted
     * @throws IllegalStateException when no side of any boundary arc floods an interior
     * @return the copy faces it covers
     */
    public IntIdList patchFaces(int patchId) {
        return floodWithin(patchWall(patchId), seedFaceInside(patchId));
    }

    /**
     * The copy vertices bounding a patch's faces, as a fresh re-route corridor.
     *
     * @param patchId  patch to enclose
     * @param rerouter router whose scratch corridor is filled
     * @throws IllegalStateException when the patch has no live boundary arc to take a side from
     * @return the corridor the router may search inside
     */
    public ActiveIdSet corridorVertices(int patchId, ArcRerouter rerouter) {
        IntIdList faces = patchFaces(patchId);
        ActiveIdSet corridor = rerouter.freshCorridor();
        HalfEdgeMesh copy = tmesh.topology.copy;
        for (int index = 0; index < faces.size(); index++) {
            int faceId = faces.get(index);
            for (int corner = 0; corner < copy.faceHalfEdgeCount(faceId); corner++) {
                corridor.add(copy.faceVertexAt(faceId, corner));
            }
        }
        return corridor;
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
     * The copy edges a patch's boundary arcs run along — the wall a flood of its interior may not
     * cross.
     *
     * @param patchId patch whose boundary is wanted
     * @return the boundary's copy edges
     */
    private ActiveIdSet patchWall(int patchId) {
        ActiveIdSet wall = new ActiveIdSet(tmesh.topology.copy.edgeCount());
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int boundaryArcId : tmesh.patches.get(patchId).sideArcIds.get(side)) {
                for (int edgeId : tmesh.arcs.get(boundaryArcId).path.copyEdgePath) {
                    wall.add(edgeId);
                }
            }
        }
        return wall;
    }

    /**
     * The faces reachable from a seed without crossing a wall edge.
     *
     * @param wall edges the flood stops at
     * @param seed face to flood from
     * @return the reachable faces, seed first
     */
    private IntIdList floodWithin(ActiveIdSet wall, int seed) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        ActiveIdSet visited = new ActiveIdSet(copy.faceCount());
        IntIdList faces = new IntIdList(copy.faceCount());
        visited.add(seed);
        faces.add(seed);
        for (int cursor = 0; cursor < faces.size(); cursor++) {
            int faceId = faces.get(cursor);
            for (int corner = 0; corner < copy.faceHalfEdgeCount(faceId); corner++) {
                int edgeId = copy.faceEdgeAt(faceId, corner);
                if (wall.contains(edgeId)) {
                    continue;
                }
                int halfEdge = copy.edgeHalfEdge(edgeId);
                int neighbour = copy.halfEdgeFace(halfEdge) == faceId
                        ? copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge))
                        : copy.halfEdgeFace(halfEdge);
                if (neighbour != EmbeddedMeshTopology.UNCLAIMED && !visited.contains(neighbour)) {
                    visited.add(neighbour);
                    faces.add(neighbour);
                }
            }
        }
        return faces;
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
