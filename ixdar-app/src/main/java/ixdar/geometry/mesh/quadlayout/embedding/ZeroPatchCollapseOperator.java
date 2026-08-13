package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * Operator (3), the simple zero-patch collapse: requires a bigon, so the
 * patch's zero-length sides must already be collapsed.
 *
 * <p>
 * A feature or border arc survives over a plain one, ties to the lower id; the
 * other's claims are released. Two feature arcs bounding one zero-patch throw.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class ZeroPatchCollapseOperator {

    public final EmbeddedTMesh tmesh;

    public int collapsedCount;

    /**
     * List returned by {@link #liveBoundaryArcs}, reused across calls; each call
     * clears and refills it, so callers must not hold it across another call.
     */
    public final List<Integer> boundaryArcsScratch = new ArrayList<>();

    /**
     * Stores the T-mesh whose simple zero-patches are collapsed.
     *
     * @param tmesh embedded T-mesh to operate on
     */
    public ZeroPatchCollapseOperator(EmbeddedTMesh tmesh) {
        this.tmesh = tmesh;
    }

    /**
     * The lowest-id live bigon of two non-zero arcs between the same two nodes, or
     * {@link EmbeddedTMesh#NONE} when none remains. Only
     * {@link EmbeddedTMesh#changedPatches} is tested, since an untouched patch
     * cannot have newly become one; ready patches keep their stamp, so repeating
     * the query repeats the answer.
     *
     * @throws IllegalStateException when
     *                               {@link EmbeddedTMesh#VALIDATE_EVERY_COLLAPSE}
     *                               is set and a full scan disagrees, meaning some
     *                               mutator does not call
     *                               {@link EmbeddedTMesh#markPatchChanged}
     * @return a collapsible simple zero-patch id, or {@link EmbeddedTMesh#NONE}
     */
    public int nextSimpleZeroPatch() {
        int found = EmbeddedTMesh.NONE;
        int keep = 0;
        for (int index = 0; index < tmesh.changedPatchCount; index++) {
            int patchId = tmesh.changedPatches[index];
            EmbeddedPatch patch = tmesh.patches.get(patchId);
            if (patch.alive && isReadyBigon(patch)) {
                tmesh.changedPatches[keep++] = patchId;
                if (found == EmbeddedTMesh.NONE || patchId < found) {
                    found = patchId;
                }
            } else {
                tmesh.patchIsChanged[patchId] = false;
            }
        }
        tmesh.changedPatchCount = keep;
        if (EmbeddedTMesh.VALIDATE_EVERY_COLLAPSE) {
            int scanned = EmbeddedTMesh.NONE;
            for (EmbeddedPatch patch : tmesh.patches) {
                if (patch.alive && isReadyBigon(patch)) {
                    scanned = patch.patchId;
                    break;
                }
            }
            if (scanned != found) {
                throw new IllegalStateException("the changed-patch set answers operator (3) with "
                        + found + " but a full scan finds " + scanned + "; a mutator that can turn"
                        + " a patch into a bigon is not calling markPatchChanged");
            }
        }
        return found;
    }

    /**
     * Collapses one simple zero-patch: keeps one of its two arcs, re-points the
     * arc's other patch onto the survivor, discards the other arc, and retires the
     * patch.
     *
     * @param patchId simple zero-patch (a ready bigon) to collapse
     * @throws IllegalStateException when the patch is not a ready bigon
     */
    public void collapse(int patchId) {
        EmbeddedPatch patch = tmesh.patches.get(patchId);
        if (!isReadyBigon(patch)) {
            throw new IllegalStateException("patch " + patchId + " is not a simple zero-patch with"
                    + " its zero sides collapsed; its live boundary arcs are "
                    + liveBoundaryArcs(patch));
        }
        List<Integer> boundaryArcs = liveBoundaryArcs(patch);
        int survivorArc = chooseSurvivor(boundaryArcs.get(0), boundaryArcs.get(1));
        int dyingArc = survivorArc == boundaryArcs.get(0) ? boundaryArcs.get(1) : boundaryArcs.get(0);

        int farPatch = otherPatchOf(dyingArc, patchId);
        if (farPatch != EmbeddedTMesh.NONE) {
            tmesh.replaceArcInPatch(farPatch, dyingArc, survivorArc);
            tmesh.topology.aliasPatchInto(patchId, farPatch, tmesh.patches.size());
        }
        tmesh.removePatch(patchId);
        tmesh.discardArc(dyingArc);
        collapsedCount++;
    }

    /**
     * Whether a patch is a bigon ready for operator (3): exactly two live boundary
     * arcs, both non-zero, running between the same two nodes, on opposite sides
     * with the other two sides empty — the shape a simple zero-patch takes once its
     * zero sides have collapsed.
     *
     * @param patch patch to test
     * @return true when the patch is a ready bigon
     */
    private boolean isReadyBigon(EmbeddedPatch patch) {
        int nonEmptySides = 0;
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            if (!patch.sideArcIds.get(side).isEmpty()) {
                nonEmptySides++;
            }
        }
        if (nonEmptySides != 2) {
            return false;
        }
        List<Integer> boundaryArcs = liveBoundaryArcs(patch);
        if (boundaryArcs.size() != 2) {
            return false;
        }
        EmbeddedArc first = tmesh.arcs.get(boundaryArcs.get(0));
        EmbeddedArc second = tmesh.arcs.get(boundaryArcs.get(1));
        if (first.quantizedLength == 0 || second.quantizedLength == 0) {
            return false;
        }
        boolean sameEnds = first.startNodeId == second.startNodeId
                && first.endNodeId == second.endNodeId;
        boolean reversedEnds = first.startNodeId == second.endNodeId
                && first.endNodeId == second.startNodeId;
        return sameEnds || reversedEnds;
    }

    /**
     * The live arcs on a patch's boundary, in the reused
     * {@link #boundaryArcsScratch} list.
     *
     * @param patch patch to read
     * @return its live boundary arc ids, valid until the next call
     */
    private List<Integer> liveBoundaryArcs(EmbeddedPatch patch) {
        boundaryArcsScratch.clear();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideArcs = patch.sideArcIds.get(side);
            for (int index = 0; index < sideArcs.size(); index++) {
                int arcId = sideArcs.get(index);
                if (tmesh.arcs.get(arcId).alive) {
                    boundaryArcsScratch.add(arcId);
                }
            }
        }
        return boundaryArcsScratch;
    }

    /**
     * Which of a zero-patch's two arcs survives: a feature arc over a plain one,
     * else the lower id.
     *
     * @param firstArc  one boundary arc
     * @param secondArc the other
     * @throws IllegalStateException when both arcs are feature arcs, a feature
     *                               curve doubling back on itself that the input
     *                               should never produce
     * @return the id of the surviving arc
     */
    private int chooseSurvivor(int firstArc, int secondArc) {
        boolean firstFeature = tmesh.arcs.get(firstArc).feature;
        boolean secondFeature = tmesh.arcs.get(secondArc).feature;
        if (firstFeature && secondFeature) {
            throw new IllegalStateException("a zero-patch is bounded by two feature arcs "
                    + firstArc + " and " + secondArc + "; a feature curve doubles back on itself");
        }
        if (firstFeature != secondFeature) {
            return firstFeature ? firstArc : secondArc;
        }
        return Math.min(firstArc, secondArc);
    }

    /**
     * The patch bordering an arc other than a given one.
     *
     * @param arcId   arc whose two patches are known
     * @param notThis the patch to exclude
     * @return the arc's other patch, or {@link EmbeddedTMesh#NONE} when it has none
     */
    private int otherPatchOf(int arcId, int notThis) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        if (arc.leftPatchId != notThis && arc.leftPatchId != EmbeddedTMesh.NONE
                && tmesh.patches.get(arc.leftPatchId).alive) {
            return arc.leftPatchId;
        }
        if (arc.rightPatchId != notThis && arc.rightPatchId != EmbeddedTMesh.NONE
                && tmesh.patches.get(arc.rightPatchId).alive) {
            return arc.rightPatchId;
        }
        return EmbeddedTMesh.NONE;
    }
}
