package ixdar.geometry.mesh.quadlayout.gridmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * The set of copy faces each patch occupies, flooding across only the edges no arc claims.
 *
 * <p>Verifies that the patches partition the surface and throws rather than returning a partial
 * map, so it is meaningful only at the contraction's fixed point.
 */
public final class PatchRegions {

    public final EmbeddedTMesh tmesh;

    /** Patch id owning each copy face, keyed by copy face id. */
    public final Map<Integer, Integer> patchIdByCopyFace;

    /** Copy face ids in each patch's region, keyed by patch id. */
    public final Map<Integer, List<Integer>> copyFacesByPatch;

    /**
     * Stores the T-mesh whose regions are computed.
     *
     * @param tmesh embedded T-mesh whose patches to enclose
     */
    public PatchRegions(EmbeddedTMesh tmesh) {
        this.tmesh = tmesh;
        this.patchIdByCopyFace = new HashMap<>();
        this.copyFacesByPatch = new HashMap<>();
    }

    /**
     * Computes the regions and verifies they partition the surface.
     *
     * @throws IllegalStateException when the regions do not partition the surface: a
     *                               component enclosed by no single patch's arcs, a patch
     *                               with no region, or a face left unassigned
     * @return this, populated
     */
    public PatchRegions build() {
        Map<Set<Integer>, Integer> patchByBoundaryArcs = indexPatchesByBoundaryArcs();
        Set<Integer> visited = new HashSet<>();
        int componentCount = 0;
        EmbeddedMeshTopology topology = tmesh.topology;
        for (int faceIndex = 0; faceIndex < topology.copy.faceCount(); faceIndex++) {
            int seedFace = topology.copy.faceIdAt(faceIndex);
            if (!visited.add(seedFace)) {
                continue;
            }
            componentCount++;
            List<Integer> component = new ArrayList<>();
            Set<Integer> boundaryArcs = new HashSet<>();
            floodComponent(seedFace, visited, component, boundaryArcs);
            Integer patchId = patchByBoundaryArcs.get(boundaryArcs);
            if (patchId == null) {
                throw new IllegalStateException("a region of " + component.size() + " faces is"
                        + " enclosed by arcs " + boundaryArcs + " that are not exactly one patch's"
                        + " boundary; the layout is torn");
            }
            if (copyFacesByPatch.containsKey(patchId)) {
                throw new IllegalStateException("patch " + patchId + " encloses two separate"
                        + " regions; its boundary does not seal it");
            }
            copyFacesByPatch.put(patchId, component);
            for (int faceId : component) {
                patchIdByCopyFace.put(faceId, patchId);
            }
        }
        if (componentCount != patchByBoundaryArcs.size()) {
            throw new IllegalStateException("the copy mesh splits into " + componentCount
                    + " regions but the T-mesh has " + patchByBoundaryArcs.size() + " live patches");
        }
        return this;
    }

    /**
     * Finds the first face component whose boundary is not one live patch.
     *
     * @param boundaryArcs receives the unmatched component's boundary arc ids
     * @return unmatched component faces, or an empty list when every component matches
     */
    public List<Integer> findFirstUnmatchedRegion(Set<Integer> boundaryArcs) {
        boundaryArcs.clear();
        Map<Set<Integer>, Integer> patchByBoundaryArcs = indexPatchesByBoundaryArcs();
        Set<Integer> visited = new HashSet<>();
        EmbeddedMeshTopology topology = tmesh.topology;
        for (int faceIndex = 0; faceIndex < topology.copy.faceCount(); faceIndex++) {
            int seedFace = topology.copy.faceIdAt(faceIndex);
            if (!visited.add(seedFace)) {
                continue;
            }
            List<Integer> component = new ArrayList<>();
            Set<Integer> componentBoundary = new HashSet<>();
            floodComponent(seedFace, visited, component, componentBoundary);
            if (!patchByBoundaryArcs.containsKey(componentBoundary)) {
                boundaryArcs.addAll(componentBoundary);
                return component;
            }
        }
        return List.of();
    }

    /**
     * The boundary arc set of every live patch, keyed for matching against a flooded
     * component's boundary arcs.
     *
     * @throws IllegalStateException when two patches share the same boundary arc set, which
     *                               would make the region match ambiguous
     * @return map from a patch's boundary arc set to its patch id
     */
    private Map<Set<Integer>, Integer> indexPatchesByBoundaryArcs() {
        Map<Set<Integer>, Integer> index = new HashMap<>();
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            Set<Integer> boundaryArcs = new HashSet<>();
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                boundaryArcs.addAll(patch.sideArcIds.get(side));
            }
            if (index.put(boundaryArcs, patch.patchId) != null) {
                throw new IllegalStateException("two patches share boundary arc set "
                        + boundaryArcs + "; regions cannot be matched to patches");
            }
        }
        return index;
    }

    /**
     * Floods one connected region of copy faces from a seed, crossing only unclaimed edges,
     * collecting its faces and the arcs claiming the edges on its boundary.
     *
     * @param seedFace     face to start from
     * @param visited      global visited set, extended with every face reached
     * @param component    receives the region's face ids
     * @param boundaryArcs receives the ids of arcs claiming the region's perimeter edges
     */
    private void floodComponent(int seedFace, Set<Integer> visited, List<Integer> component,
            Set<Integer> boundaryArcs) {
        EmbeddedMeshTopology topology = tmesh.topology;
        Deque<Integer> frontier = new ArrayDeque<>();
        frontier.add(seedFace);
        while (!frontier.isEmpty()) {
            int faceId = frontier.poll();
            component.add(faceId);
            for (int corner = 0; corner < topology.copy.faceHalfEdgeCount(faceId); corner++) {
                int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                int owner = topology.ownerArcByCopyEdge[edgeId];
                if (owner != EmbeddedMeshTopology.UNCLAIMED) {
                    boundaryArcs.add(owner);
                    continue;
                }
                int neighbor = topology.copy.faceAcrossEdge(faceId, edgeId);
                if (neighbor != EmbeddedMeshTopology.UNCLAIMED && visited.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }
    }
}
