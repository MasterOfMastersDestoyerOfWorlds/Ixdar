package ixdar.scenes.ring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ixdar.geometry.mesh.data.paths.RingSegmentMode;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;

/**
 * What one ring tool edit can change, captured whole: the confirmed rings, the draft and the
 * graph rings turned into tool rings. The traced splines are shared, not copied, since the tool
 * replaces them rather than mutating them, so restoring one is exact and needs no re-trace.
 */
public final class RingToolState {

    /** Confirmed rings, as {@link RingTool#confirmedRings} held them. */
    public final List<SurfaceSpline> confirmedRings;

    /** Authored anchors of each confirmed ring. */
    public final List<int[]> confirmedAuthoredVertexId;

    /** Base normal of each confirmed ring. */
    public final List<float[]> confirmedBaseNormal;

    /** Segment mode of each confirmed ring. */
    public final List<RingSegmentMode> confirmedMode;

    /** Statement id of each confirmed ring when captured, {@code null} for one not saved yet. */
    public final List<String> confirmedStatementIds;

    /** Whether each confirmed ring differed from the working .dsl when captured. */
    public final List<Boolean> confirmedRingUnsaved;

    /** Graph ring label each confirmed ring stood for, or {@code null}. */
    public final List<String> confirmedSourceLabel;

    /** Whether each confirmed ring was deleted and awaiting a save. */
    public final List<Boolean> confirmedRingDeleted;

    /** Graph ring labels hidden because a tool ring replaced them. */
    public final Set<String> convertedGraphLabels;

    /** The draft, or null when there was none. */
    public final SurfaceSpline draft;

    /** The draft's authored anchors in ring order. */
    public final int[] draftAuthoredVertexId;

    /** Base normal the draft's plane leans toward. */
    public final float[] draftBaseNormal;

    /** Segment mode the draft was traced in. */
    public final RingSegmentMode draftMode;

    /** Confirmed ring the draft re-opened, or -1. */
    public final int draftSourceRing;

    /** Graph ring label the draft was converted from, or null. */
    public final String draftSourceLabel;

    /** Depth the draft was traced at. */
    public final int draftDepth;

    /**
     * Capture the tool's editable state as it stands.
     *
     * @param tool the ring tool
     */
    public RingToolState(RingTool tool) {
        confirmedRings = new ArrayList<>(tool.confirmedRings);
        confirmedAuthoredVertexId = new ArrayList<>(tool.confirmedAuthoredVertexId);
        confirmedBaseNormal = new ArrayList<>(tool.confirmedBaseNormal);
        confirmedMode = new ArrayList<>(tool.confirmedMode);
        confirmedStatementIds = new ArrayList<>(tool.confirmedStatementIds);
        confirmedRingUnsaved = new ArrayList<>(tool.confirmedRingUnsaved);
        confirmedSourceLabel = new ArrayList<>(tool.confirmedSourceLabel);
        confirmedRingDeleted = new ArrayList<>(tool.confirmedRingDeleted);
        convertedGraphLabels = new HashSet<>(tool.convertedGraphLabels);
        draft = tool.draft;
        draftAuthoredVertexId = tool.draftAuthoredVertexId;
        draftBaseNormal = Arrays.copyOf(tool.draftBaseNormal, tool.draftBaseNormal.length);
        draftMode = tool.draftMode;
        draftSourceRing = tool.draftSourceRing;
        draftSourceLabel = tool.draftSourceLabel;
        draftDepth = tool.draftDepth;
    }

    /**
     * Whether another capture holds the same rings and draft, so the action between them was no
     * edit; statement ids and unsaved flags are a save's business and are not compared.
     *
     * @param other a later capture
     * @return true when nothing an undo would restore differs
     */
    public boolean sameEdit(RingToolState other) {
        return draft == other.draft && draftSourceRing == other.draftSourceRing
                && Arrays.equals(draftAuthoredVertexId, other.draftAuthoredVertexId)
                && Arrays.equals(draftBaseNormal, other.draftBaseNormal)
                && draftMode == other.draftMode
                && confirmedRings.equals(other.confirmedRings)
                && confirmedRingDeleted.equals(other.confirmedRingDeleted)
                && convertedGraphLabels.equals(other.convertedGraphLabels);
    }

    /**
     * Put the captured rings and draft back on the tool. A ring's statement id and unsaved flag
     * are re-derived from what the saves since the capture wrote, so a save never lies about the
     * working .dsl.
     *
     * @param tool the ring tool the state was captured from
     */
    public void restore(RingTool tool) {
        tool.confirmedRings.clear();
        tool.confirmedRings.addAll(confirmedRings);
        tool.confirmedAuthoredVertexId.clear();
        tool.confirmedAuthoredVertexId.addAll(confirmedAuthoredVertexId);
        tool.confirmedBaseNormal.clear();
        tool.confirmedBaseNormal.addAll(confirmedBaseNormal);
        tool.confirmedMode.clear();
        tool.confirmedMode.addAll(confirmedMode);
        tool.confirmedStatementIds.clear();
        tool.confirmedRingUnsaved.clear();
        tool.confirmedSourceLabel.clear();
        tool.confirmedRingDeleted.clear();
        tool.confirmedRingDeleted.addAll(confirmedRingDeleted);
        for (int ring = 0; ring < confirmedRings.size(); ring++) {
            String statementId = confirmedStatementIds.get(ring);
            String sourceLabel = confirmedSourceLabel.get(ring);
            if (statementId == null) {
                statementId = tool.savedStatementByRing.get(confirmedRings.get(ring));
                sourceLabel = statementId == null ? sourceLabel : statementId;
            }
            if (statementId != null && !tool.knownRingStatementIds.contains(statementId)) {
                // A save since the capture removed the statement, so the ring is the file's no
                // longer and the next save writes it afresh.
                statementId = null;
                sourceLabel = null;
            }
            boolean unsaved;
            if (statementId == null) {
                // A proposed ring the graph still proposes is as saved as it was when captured.
                unsaved = sourceLabel == null
                        || !tool.scene.ringMarksByLabel.containsKey(sourceLabel)
                        || confirmedRingUnsaved.get(ring);
            } else if (tool.savedAuthoredByStatement.containsKey(statementId)) {
                unsaved = !Arrays.equals(confirmedAuthoredVertexId.get(ring),
                        tool.savedAuthoredByStatement.get(statementId))
                        || !Arrays.equals(confirmedBaseNormal.get(ring),
                                tool.savedNormalByStatement.get(statementId))
                        || confirmedMode.get(ring) != tool.savedModeByStatement.get(statementId);
            } else {
                unsaved = confirmedRingUnsaved.get(ring);
            }
            tool.confirmedStatementIds.add(statementId);
            tool.confirmedRingUnsaved.add(unsaved);
            tool.confirmedSourceLabel.add(sourceLabel);
        }
        tool.convertedGraphLabels.clear();
        tool.convertedGraphLabels.addAll(convertedGraphLabels);
        tool.draft = draft;
        tool.draftAuthoredVertexId = draftAuthoredVertexId;
        System.arraycopy(draftBaseNormal, 0, tool.draftBaseNormal, 0, draftBaseNormal.length);
        tool.draftMode = draftMode;
        tool.draftSourceRing = draftSourceRing;
        tool.draftSourceLabel = draftSourceLabel;
        tool.draftDepth = draftDepth;
        boolean selectionKept = false;
        for (int held : draftAuthoredVertexId) {
            selectionKept |= held == tool.selectedAnchorVertexId;
        }
        if (!selectionKept) {
            tool.selectedAnchorVertexId = -1;
        }
        tool.hoveredAnchor = -1;
        tool.hoveredRing = -1;
        tool.draggingAnchor = false;
        tool.invalidateRings();
    }
}
