package ixdar.geometry.mesh.data.ops;

/**
 * What {@link MeshRepair} found and what it did about it: one count per defect class plus
 * per-shell and per-hole arrays. Rendered by {@link #toText()} in a fixed order so two runs over
 * the same mesh produce byte-identical output.
 */
public final class MeshRepairReport {

    /** Bundle slot the {@code repair_mesh} node parks this report on. */
    public static final String SLOT = "_repair_report";

    /** Edge-marks label carrying the boundary edges the repair left open. */
    public static final String OPEN_BOUNDARY_LABEL = "open_boundary";

    /** Vertices in the mesh handed to the repair. */
    public int inputVertexCount;

    /** Faces in the mesh handed to the repair. */
    public int inputFaceCount;

    /** Vertices the epsilon weld removed; zero when {@code weld_epsilon} is not positive. */
    public int weldedVertexCount;

    /** Faces whose winding the orientation pass reversed. */
    public int orientedFaceCount;

    /** Orientation contradictions: edges a Mobius walk reached twice with opposite demands. */
    public int mobiusEdgeCount;

    /** Edges carrying more than two faces, or two faces that run the edge the same way. */
    public int nonManifoldEdgeCount;

    /** Vertex copies minted to pull non-manifold fans apart. */
    public int splitVertexCount;

    /** Faces the fan split could not separate and had to detach on all three corners. */
    public int detachedFaceCount;

    /** Faces with a repeated corner or exactly zero area. */
    public int degenerateFaceCount;

    /** Degenerate faces resolved by collapsing their shortest edge. */
    public int degenerateCollapsedCount;

    /** Degenerate faces deleted because a collapse would have broken a neighbour. */
    public int degenerateDeletedCount;

    /** Edge-connected shells found after the topological repairs. */
    public int shellCount;

    /** Shells dropped as debris or as enclosed bubbles. */
    public int droppedShellCount;

    /** Faces lost with the dropped shells. */
    public int droppedShellFaceCount;

    /** Boundary loops found on the kept shells. */
    public int holeCount;

    /** Boundary loops filled and faired. */
    public int filledHoleCount;

    /** Boundary loops left open, either over the cap or with no valid triangulation. */
    public int openHoleCount;

    /** Boundary loops left open because they exceed {@code max_hole_edges}. */
    public int oversizedHoleCount;

    /** Boundary loops inside the cap that no triangulation could close. */
    public int unfillableHoleCount;

    /** Triangles the hole filling added. */
    public int fillFaceCount;

    /** Vertices the hole-fill refinement added. */
    public int fillVertexCount;

    /** Face corners left without a texture coordinate because hole filling minted them. */
    public int unsetUvCornerCount;

    /** Vertices in the repaired mesh. */
    public int outputVertexCount;

    /** Faces in the repaired mesh. */
    public int outputFaceCount;

    /** Edges of the repaired mesh with a single incident face. */
    public int outputBoundaryEdgeCount;

    /**
     * Edges of the repaired mesh whose two faces still run the same way. The non-manifold split
     * pulls every contradiction apart, so this is zero whenever the half-edge build accepts the
     * result; {@link #mobiusEdgeCount} is the count in the input, not what survives.
     */
    public int unorientedEdgeCount;

    /** Face count of each shell, largest first. */
    public int[] shellFaceCounts = new int[0];

    /** Whether each shell of {@link #shellFaceCounts} sits inside another shell. */
    public boolean[] shellContained = new boolean[0];

    /** Whether each shell of {@link #shellFaceCounts} survived into the output. */
    public boolean[] shellKept = new boolean[0];

    /** Edge count of each boundary loop, largest first. */
    public int[] holeEdgeCounts = new int[0];

    /** Whether each loop of {@link #holeEdgeCounts} was filled. */
    public boolean[] holeFilled = new boolean[0];

    /**
     * Whether the repaired surface is still torn: a boundary loop the filling could not close, or
     * an orientation contradiction that survived the split. Debris and bubble shells are a
     * deliberate drop, not a tear, so they do not count.
     *
     * @return true when a hole stayed open or a contradiction survived
     */
    public boolean hasUnrepaired() {
        return openHoleCount > 0 || unorientedEdgeCount > 0;
    }

    /**
     * One-line summary for a node log line.
     *
     * @return counts of the classes that were not clean, in a fixed order
     */
    public String summaryLine() {
        return "V " + inputVertexCount + "->" + outputVertexCount
                + " F " + inputFaceCount + "->" + outputFaceCount
                + " welded=" + weldedVertexCount
                + " oriented=" + orientedFaceCount
                + " mobius=" + mobiusEdgeCount
                + " nonManifoldEdges=" + nonManifoldEdgeCount
                + " splitVertices=" + splitVertexCount
                + " degenerate=" + degenerateFaceCount
                + " shells=" + shellCount + " dropped=" + droppedShellCount
                + " holes=" + holeCount + " filled=" + filledHoleCount
                + " open=" + openHoleCount
                + " oversized=" + oversizedHoleCount
                + " unfillable=" + unfillableHoleCount
                + " boundaryEdges=" + outputBoundaryEdgeCount;
    }

    /**
     * The full report as text, one field per line in declaration order followed by the shell and
     * hole tables. Byte-stable: every value is an integer or a flag and every order is fixed.
     *
     * @return multi-line report ending in a newline
     */
    public String toText() {
        StringBuilder text = new StringBuilder();
        text.append("mesh repair report\n");
        appendCount(text, "inputVertexCount", inputVertexCount);
        appendCount(text, "inputFaceCount", inputFaceCount);
        appendCount(text, "weldedVertexCount", weldedVertexCount);
        appendCount(text, "orientedFaceCount", orientedFaceCount);
        appendCount(text, "mobiusEdgeCount", mobiusEdgeCount);
        appendCount(text, "nonManifoldEdgeCount", nonManifoldEdgeCount);
        appendCount(text, "splitVertexCount", splitVertexCount);
        appendCount(text, "detachedFaceCount", detachedFaceCount);
        appendCount(text, "degenerateFaceCount", degenerateFaceCount);
        appendCount(text, "degenerateCollapsedCount", degenerateCollapsedCount);
        appendCount(text, "degenerateDeletedCount", degenerateDeletedCount);
        appendCount(text, "shellCount", shellCount);
        appendCount(text, "droppedShellCount", droppedShellCount);
        appendCount(text, "droppedShellFaceCount", droppedShellFaceCount);
        appendCount(text, "holeCount", holeCount);
        appendCount(text, "filledHoleCount", filledHoleCount);
        appendCount(text, "openHoleCount", openHoleCount);
        appendCount(text, "oversizedHoleCount", oversizedHoleCount);
        appendCount(text, "unfillableHoleCount", unfillableHoleCount);
        appendCount(text, "fillFaceCount", fillFaceCount);
        appendCount(text, "fillVertexCount", fillVertexCount);
        appendCount(text, "unsetUvCornerCount", unsetUvCornerCount);
        appendCount(text, "outputVertexCount", outputVertexCount);
        appendCount(text, "outputFaceCount", outputFaceCount);
        appendCount(text, "outputBoundaryEdgeCount", outputBoundaryEdgeCount);
        appendCount(text, "unorientedEdgeCount", unorientedEdgeCount);
        for (int shell = 0; shell < shellFaceCounts.length; shell++) {
            text.append("  shell ").append(shell)
                    .append(" faces=").append(shellFaceCounts[shell])
                    .append(" contained=").append(shellContained[shell])
                    .append(" kept=").append(shellKept[shell])
                    .append('\n');
        }
        for (int hole = 0; hole < holeEdgeCounts.length; hole++) {
            text.append("  hole ").append(hole)
                    .append(" edges=").append(holeEdgeCounts[hole])
                    .append(" filled=").append(holeFilled[hole])
                    .append('\n');
        }
        return text.toString();
    }

    private static void appendCount(StringBuilder text, String name, int value) {
        text.append("  ").append(name).append('=').append(value).append('\n');
    }
}
