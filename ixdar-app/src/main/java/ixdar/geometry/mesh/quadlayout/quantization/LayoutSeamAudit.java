package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshPatch;

/**
 * Watertightness audit of the reassembled Coons-patch surface: every boundary
 * segment of a patch grid must be shared by exactly two patches, so a once-seen
 * segment is a crack and a thrice-seen one an overlap. Matching runs exact-hash
 * first, then a tolerant pass; survivors are genuine open seams.
 */
public final class LayoutSeamAudit {

    /** Hash cell size as a fraction of the mesh radius for exact matching. */
    public static final double HASH_CELL_EPSILON_FRACTION = 1.0e-4;

    /** Endpoint tolerance as a radius fraction for the second matching pass. */
    public static final double TOLERANT_MATCH_EPSILON_FRACTION = 2.0e-3;

    /** Open-seam rectangles listed in the log, worst first. */
    public static final int TOP_OFFENDER_LIMIT = 8;

    /** Floats per packed xyz grid point. */
    private static final int VEC3_COMPONENTS = 3;

    /** Separator between the two endpoint keys of a segment key. */
    private static final String ENDPOINT_SEPARATOR = "|";

    /** Separator between the cell coordinates of a point key. */
    private static final String COORDINATE_SEPARATOR = ",";

    public final LayoutPatchGeometry geometry;

    /** Patches with a tessellated grid that entered the audit. */
    public int auditedPatchCount;

    /** Total boundary segments across all audited patch grids. */
    public int boundarySegmentCount;

    /** Segments paired exactly (hash) or within tolerance with one partner. */
    public int closedSegmentCount;

    /** Segments whose hash bucket held more than two occurrences. */
    public int nonManifoldSegmentCount;

    /** Segments with no partner after both passes — genuine cracks. */
    public int openSegmentCount;

    /** Summed chord length of all open segments. */
    public double openSeamLength;

    /**
     * Stores the tessellated patch geometry to audit.
     *
     * @param geometry built patch geometry whose clean quads carry Coons grids
     */
    public LayoutSeamAudit(LayoutPatchGeometry geometry) {
        this.geometry = geometry;
    }

    /**
     * Collect every patch grid's boundary segments, match them across patches,
     * and log the watertightness summary plus the coverage attribution.
     *
     * @return this, with all public counters populated
     */
    public LayoutSeamAudit build() {
        double meshRadius = geometry.motorcycleGraph.seamless.mesh.radius();
        double cellSize = HASH_CELL_EPSILON_FRACTION * meshRadius;
        double tolerance = TOLERANT_MATCH_EPSILON_FRACTION * meshRadius;

        Map<String, List<SeamSegment>> segmentsByKey = new HashMap<>();
        for (LayoutPatchCurves patch : geometry.patches) {
            if (patch.coonsGrid == null) {
                continue;
            }
            auditedPatchCount++;
            for (SeamSegment segment : boundarySegments(patch)) {
                boundarySegmentCount++;
                segmentsByKey.computeIfAbsent(hashKey(segment, cellSize),
                        key -> new ArrayList<>()).add(segment);
            }
        }

        List<SeamSegment> unmatched = new ArrayList<>();
        for (List<SeamSegment> bucket : segmentsByKey.values()) {
            if (bucket.size() == 2) {
                closedSegmentCount += 2;
            } else if (bucket.size() == 1) {
                unmatched.add(bucket.get(0));
            } else {
                nonManifoldSegmentCount += bucket.size();
            }
        }

        for (int first = 0; first < unmatched.size(); first++) {
            SeamSegment candidate = unmatched.get(first);
            if (candidate.matched) {
                continue;
            }
            for (int second = first + 1; second < unmatched.size(); second++) {
                SeamSegment partner = unmatched.get(second);
                if (!partner.matched && candidate.coincides(partner, tolerance)) {
                    candidate.matched = true;
                    partner.matched = true;
                    closedSegmentCount += 2;
                    break;
                }
            }
        }

        Map<Integer, Integer> openByRectangle = new HashMap<>();
        for (SeamSegment segment : unmatched) {
            if (segment.matched) {
                continue;
            }
            openSegmentCount++;
            openSeamLength += segment.length();
            openByRectangle.merge(segment.rectangleId, 1, Integer::sum);
        }

        int validPatchCount = 0;
        for (TMeshPatch patch : geometry.motorcycleGraph.patches) {
            if (patch.validRectangle) {
                validPatchCount++;
            }
        }
        TJunctionElimination conforming = geometry.conforming;
        System.out.printf(
                "[seam-audit] patches=%d segments=%d closed=%d open=%d openLength=%.4f"
                        + " nonManifold=%d%n",
                auditedPatchCount, boundarySegmentCount, closedSegmentCount, openSegmentCount,
                openSeamLength, nonManifoldSegmentCount);
        System.out.printf(
                "[seam-audit] coverage: sourcePatches=%d valid=%d rendered=%d portals=%d"
                        + " collapsed=%d inconsistent=%d notClean=%d%n",
                geometry.motorcycleGraph.patches.size(), validPatchCount, auditedPatchCount,
                conforming.portalCount, conforming.collapsedPatchCount,
                conforming.inconsistentPatchCount,
                geometry.patches.size() - geometry.cleanQuadCount);
        List<Map.Entry<Integer, Integer>> offenders = new ArrayList<>(openByRectangle.entrySet());
        offenders.sort(Comparator.comparingInt(Map.Entry<Integer, Integer>::getValue).reversed());
        for (int rank = 0; rank < Math.min(TOP_OFFENDER_LIMIT, offenders.size()); rank++) {
            System.out.printf("[seam-audit] open rectangle=%d segments=%d%n",
                    offenders.get(rank).getKey(), offenders.get(rank).getValue());
        }
        return this;
    }

    /**
     * Extract the four boundary chains of a patch's Coons grid as individual
     * segments (rows 0 and S-1, columns 0 and S-1 of the sample grid).
     *
     * @param patch tessellated patch whose grid boundary gets decomposed
     * @return all boundary segments of the patch grid
     */
    private List<SeamSegment> boundarySegments(LayoutPatchCurves patch) {
        int samples = LayoutPatchGeometry.COONS_SAMPLES;
        List<SeamSegment> segments = new ArrayList<>();
        for (int index = 0; index < samples - 1; index++) {
            segments.add(segment(patch, 0, index, 0, index + 1));
            segments.add(segment(patch, samples - 1, index, samples - 1, index + 1));
            segments.add(segment(patch, index, 0, index + 1, 0));
            segments.add(segment(patch, index, samples - 1, index + 1, samples - 1));
        }
        return segments;
    }

    /**
     * Build one segment between two grid sample points.
     *
     * @param patch   tessellated patch supplying the grid
     * @param rowA    first sample's row (v index)
     * @param columnA first sample's column (u index)
     * @param rowB    second sample's row (v index)
     * @param columnB second sample's column (u index)
     * @return the segment between the two grid points
     */
    private SeamSegment segment(LayoutPatchCurves patch, int rowA, int columnA,
            int rowB, int columnB) {
        return new SeamSegment(patch.rectangleId,
                gridPoint(patch, rowA, columnA), gridPoint(patch, rowB, columnB));
    }

    /**
     * Read one xyz sample out of a patch's packed Coons grid.
     *
     * @param patch  tessellated patch supplying the grid
     * @param row    sample row (v index)
     * @param column sample column (u index)
     * @return the sample position as a new vector
     */
    private Vector3f gridPoint(LayoutPatchCurves patch, int row, int column) {
        int samples = LayoutPatchGeometry.COONS_SAMPLES;
        int base = (row * samples + column) * VEC3_COMPONENTS;
        return new Vector3f(patch.coonsGrid[base], patch.coonsGrid[base + 1],
                patch.coonsGrid[base + 2]);
    }

    /**
     * Orientation-independent hash key of a segment's endpoints quantized to the
     * hash cell size.
     *
     * @param segment  segment to key
     * @param cellSize quantization cell size in world units
     * @return canonical key string shared by exactly coincident segments
     */
    private static String hashKey(SeamSegment segment, double cellSize) {
        String first = pointKey(segment.start, cellSize);
        String second = pointKey(segment.end, cellSize);
        return first.compareTo(second) <= 0
                ? first + ENDPOINT_SEPARATOR + second
                : second + ENDPOINT_SEPARATOR + first;
    }

    /**
     * Quantize one point to its hash cell.
     *
     * @param point    point to quantize
     * @param cellSize quantization cell size in world units
     * @return cell coordinate key of the point
     */
    private static String pointKey(Vector3f point, double cellSize) {
        return Math.round(point.x / cellSize) + COORDINATE_SEPARATOR
                + Math.round(point.y / cellSize) + COORDINATE_SEPARATOR
                + Math.round(point.z / cellSize);
    }
}
