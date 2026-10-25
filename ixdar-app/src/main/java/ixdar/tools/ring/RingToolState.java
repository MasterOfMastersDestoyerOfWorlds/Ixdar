package ixdar.tools.ring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.rings.RingSegmentMode;
import ixdar.tools.Tool;

/**
 * What one ring tool edit can change, captured whole: the confirmed rings, the draft, the graph
 * rings turned into tool rings and the sub-tool working on them. Traced splines are shared, never
 * copied, since the tool replaces rather than mutates them.
 */
public final class RingToolState {

    /** Copies of the confirmed rings as {@link RingTool#confirmedRings} held them when captured. */
    public final List<ConfirmedRing> confirmedRings = new ArrayList<>();

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

    /** Serials of the walls the draft's ends sit on when it is a bisector, or null. */
    public final int[] draftBisectorEnds;

    /** Whether the bisector draft ran without crossing itself. */
    public final boolean draftSimple;

    /** Wall vertex a bisector's first end waited on for its second, or -1. */
    public final int bisectorStartVertexId;

    /** Serial of the wall that first end sat on, or -1. */
    public final int bisectorStartWall;

    /** Sub-tool the ring tool handed its input to. */
    public final Tool activeSubTool;

    /** Sub-tool that owned the draft, the rings when there was none. */
    public final RingSubTool draftOwner;

    /**
     * Capture the tool's editable state as it stands.
     *
     * @param tool the ring tool
     */
    public RingToolState(RingTool tool) {
        for (ConfirmedRing ring : tool.confirmedRings) {
            confirmedRings.add(new ConfirmedRing(ring));
        }
        convertedGraphLabels = new HashSet<>(tool.convertedGraphLabels);
        draft = tool.draft;
        draftBisectorEnds = tool.bisector.draftEnds;
        draftSimple = tool.bisector.draftSimple;
        bisectorStartVertexId = tool.bisector.startVertexId;
        bisectorStartWall = tool.bisector.startWall;
        activeSubTool = tool.activeSubTool;
        draftOwner = tool.draftOwner;
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
        boolean sameRings = confirmedRings.size() == other.confirmedRings.size();
        for (int ring = 0; sameRings && ring < confirmedRings.size(); ring++) {
            ConfirmedRing mine = confirmedRings.get(ring);
            ConfirmedRing theirs = other.confirmedRings.get(ring);
            sameRings = mine.spline.equals(theirs.spline) && mine.deleted == theirs.deleted;
        }
        return draft == other.draft && draftSourceRing == other.draftSourceRing
                && bisectorStartVertexId == other.bisectorStartVertexId
                && Arrays.equals(draftAuthoredVertexId, other.draftAuthoredVertexId)
                && Arrays.equals(draftBaseNormal, other.draftBaseNormal)
                && draftMode == other.draftMode
                && sameRings
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
        for (ConfirmedRing captured : confirmedRings) {
            ConfirmedRing ring = new ConfirmedRing(captured);
            String statementId = ring.statementId;
            String sourceLabel = ring.sourceLabel;
            if (statementId == null) {
                statementId = tool.savedStatementByRing.get(ring.spline);
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
                        || ring.unsaved;
            } else if (tool.savedAuthoredByStatement.containsKey(statementId)) {
                unsaved = !Arrays.equals(ring.authoredVertexId,
                        tool.savedAuthoredByStatement.get(statementId))
                        || !Arrays.equals(ring.baseNormal, tool.savedNormalByStatement.get(statementId))
                        || ring.mode != tool.savedModeByStatement.get(statementId);
            } else {
                unsaved = ring.unsaved;
            }
            ring.statementId = statementId;
            ring.unsaved = unsaved;
            ring.sourceLabel = sourceLabel;
            tool.confirmedRings.add(ring);
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
        tool.bisector.draftEnds = draftBisectorEnds;
        tool.bisector.draftSimple = draftSimple;
        tool.bisector.startVertexId = bisectorStartVertexId;
        tool.bisector.startWall = bisectorStartWall;
        // Back in the sub-tool the state was captured in, so a bisector draft is edited in the
        // bisector and a ring draft in the rings.
        tool.switchSubTool(activeSubTool);
        tool.draftOwner = draftOwner;
        boolean selectionKept = false;
        for (int held : draftAuthoredVertexId) {
            selectionKept |= held == tool.selectedAnchorVertexId;
        }
        if (!selectionKept) {
            tool.selectedAnchorVertexId = -1;
        }
        tool.hoveredAnchor = -1;
        tool.closedRing.hoveredRing = -1;
        tool.draggingAnchor = false;
        tool.invalidateRings();
    }
}
