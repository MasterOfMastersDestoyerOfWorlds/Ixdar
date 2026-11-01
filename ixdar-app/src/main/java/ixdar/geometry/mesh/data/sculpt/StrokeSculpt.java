package ixdar.geometry.mesh.data.sculpt;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshSmoothing;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.field.Vector3Field;

/**
 * Sculpts a surface with weighted strokes in one pass: the faces they reach are refined once, then
 * every stroke's move is summed on the refined surface ({@link DisplacementSum}), relaxed, and
 * symmetrised when asked.
 */
public final class StrokeSculpt {

    public static final String CREASE_LABEL = "crease";

    public static final String STROKE_SEPARATOR = ";";

    public static final String HEADER_SEPARATOR = ":";

    public static final String POINT_SEPARATOR = ",";

    public static final String FIELD_SEPARATOR = "\\s+";

    public static final String RADIUS_PREFIX = "r=";

    public static final String FILL_FLAG = "fill";

    public static final String MIRROR_FLAG = "mirror";

    public static final String SMOOTH_FLAG = "smooth";

    public static final String SILHOUETTE_FLAG = "silhouette";

    public static final String EYE_PREFIX = "eye=";

    public static final String EYE_VALUE_SEPARATOR = "/";

    public static final int EYE_AND_FORWARD_VALUES = 6;

    public static final int WEIGHTED_POINT_FIELDS = 4;

    public static final int REFLECTIONS = 4;

    public static final int UNFOLD_HALVINGS = 6;

    public static final String SCULPT_SLOT = "_stroke_sculpt";

    /** Whether strokes move vertices (mode B) or only crease their chains (mode A). */
    public boolean displace = true;

    /** Crease weight every chain writes on its edges, whatever its depth. */
    public float creaseScale = 2f;

    /** Fraction of the tangential Laplacian each relax iteration applies to moved vertices. */
    public float tangentialRelax = 0.5f;

    /** Axis the pass is mirrored across and symmetrised about: 0, 1 or 2 for x, y or z, -1 for none. */
    public int symmetryAxis = -1;

    /** Per stroke of the last {@link #apply}'s text, in order: the largest distance it moved a vertex. */
    public float[] peakDisplacementByStroke = new float[0];

    /** Per stroke of the last {@link #apply}'s text: how many vertices it moved, its mirror copies included. */
    public int[] movedVerticesByStroke = new int[0];

    /**
     * The finest edges the last {@link #apply}'s refinement reaches: its floor, or the input's mean
     * edge after every round; a stroke steeper than they can draw terraces.
     */
    public float finestEdgeLength;

    /** Vertices the last {@link #apply} refined its input to. */
    public int refinedVertices;

    /**
     * The last {@link #apply}'s refined surface before any stroke moved it: the output's vertices in
     * the same dense order, at their places on the input's surface.
     */
    public MeshTopology refined;

    /** The refinement settings and, after {@link #apply}, its mirror twins. */
    public final StrokeRefinement refinement = new StrokeRefinement();

    /**
     * Sculpt a mesh with strokes {@code "d r=R [fill] [mirror] [smooth] [silhouette] [eye=...]: x y z
     * [w], ..."}, depth and radius in world units: a vertex moves d times weight times falloff.
     *
     * @param base    geometry to sculpt; its float crease marks are carried over
     * @param strokes strokes, each a header then its lifted points (silhouettes: view rays) with
     *                optional weights
     * @throws IllegalArgumentException when a stroke names no radius, its eye flag is malformed, or a
     *                                  silhouette has none
     * @return the sculpted geometry with the strokes' crease marks merged in
     */
    public GeometryBundle apply(GeometryBundle base, String strokes) {
        MeshTopology mesh = base.mesh();
        String[] strokeTexts = strokes == null || strokes.isBlank() ? new String[0] : strokes.split(STROKE_SEPARATOR);
        peakDisplacementByStroke = new float[strokeTexts.length];
        movedVerticesByStroke = new int[strokeTexts.length];
        refinedVertices = mesh == null ? 0 : mesh.vertexCount();
        refined = mesh;
        if (mesh == null || mesh.vertexCount() == 0 || strokeTexts.length == 0) {
            return base;
        }
        // Every stroke and its reflections: across the symmetry axis, and across x for its own
        // mirror flag, less those that repeat it or that a fill joins.
        List<SculptStroke> copies = new ArrayList<>();
        for (int stroke = 0; stroke < strokeTexts.length; stroke++) {
            SculptStroke parsed = SculptStroke.parse(strokeTexts[stroke], stroke);
            int secondAxis = parsed != null && parsed.mirror && symmetryAxis != 0 ? 0 : -1;
            int earliest = copies.size();
            for (int reflection = 0; reflection < REFLECTIONS && parsed != null; reflection++) {
                boolean acrossFirst = (reflection & 1) != 0;
                boolean acrossSecond = (reflection & 2) != 0;
                if (acrossFirst && symmetryAxis < 0 || acrossSecond && secondAxis < 0) {
                    continue;
                }
                SculptStroke copy = SculptStroke.parse(strokeTexts[stroke], stroke);
                for (int axis = 0; axis < 3; axis++) {
                    if ((acrossFirst && axis == symmetryAxis) ^ (acrossSecond && axis == secondAxis)) {
                        copy.reflect(axis);
                    }
                }
                boolean taken = false;
                for (int earlier = copies.size() - 1; earlier >= earliest && !taken && copy.pointWeight.length > 0;
                        earlier--) {
                    taken = copies.get(earlier).absorbs(copy);
                }
                if (!taken) {
                    copies.add(copy);
                }
            }
        }
        SculptStroke.refineTargets(copies, refinement.minimumEdgeLength, refinement.edgesPerWidth);
        double meanEdge = 0.0;
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            meanEdge += mesh.edgeLength(mesh.edgeIdAt(activeEdge)) / (double) mesh.edgeCount();
        }
        finestEdgeLength = Math.max(refinement.minimumEdgeLength,
                (float) meanEdge / (1 << StrokeRefinement.MAXIMUM_ROUNDS));
        refinement.symmetryAxis = symmetryAxis;
        refined = refinement.refine(mesh, copies, displace, EdgeMarks.floats(base, CREASE_LABEL));
        refinedVertices = refined.vertexCount();
        DisplacementSum sum = new DisplacementSum(refined, displace, creaseScale, strokeTexts.length);
        for (SculptStroke copy : copies) {
            sum.add(copy);
        }
        peakDisplacementByStroke = sum.peakDisplacementBySource;
        movedVerticesByStroke = sum.movedVerticesBySource;
        MeshTopology sculpted = displace ? sum.sculpted(tangentialRelax) : refined;
        int[] twins = refinement.twinByActiveVertex;
        float[] unmoved = packedPositions(refined, null);
        if (displace && symmetryAxis >= 0) {
            // Each vertex's displacement becomes the mean of its own and its mirror twin's reflected
            // displacement, so the pass leaves a symmetric input symmetric.
            float[] moved = packedPositions(sculpted, null);
            for (int index = 0; index < moved.length; index++) {
                moved[index] -= unmoved[index];
            }
            float[] symmetric = new float[moved.length];
            for (int activeVertex = 0; activeVertex < twins.length; activeVertex++) {
                int twin = twins[activeVertex];
                for (int axis = 0; axis < 3; axis++) {
                    float own = moved[activeVertex * 3 + axis];
                    float reflected = twin < 0 ? own : axis == symmetryAxis ? -moved[twin * 3 + axis] : moved[twin * 3 + axis];
                    symmetric[activeVertex * 3 + axis] = (own + reflected) * 0.5f;
                }
            }
            sculpted = MeshVertexOffset.apply(refined, Vector3Field.copyOf(symmetric));
        }
        if (displace) {
            // The pass must not turn the mesh inside out: each vertex's step stops short of the
            // surface it would cross, and is pulled back where it would still fold a face. The sum's
            // tree is over the unmoved refined surface, which is what the limiter starts from.
            float[] stepXyz = packedPositions(sculpted, null);
            MeshSmoothing.unfold(refined, sum.activeVertexById, unmoved, stepXyz, sum.bvh, UNFOLD_HALVINGS);
            for (int index = 0; index < stepXyz.length; index++) {
                stepXyz[index] -= unmoved[index];
            }
            sculpted = MeshVertexOffset.apply(refined, Vector3Field.copyOf(stepXyz));
        }
        float[] existingCreases = refinement.creases;
        float[] creases = new float[sculpted.edgeIdBound()];
        for (int edgeId = 0; edgeId < sum.strokeCreaseByEdgeId.length; edgeId++) {
            float crease = Math.max(sum.strokeCreaseByEdgeId[edgeId],
                    existingCreases != null && edgeId < existingCreases.length ? existingCreases[edgeId] : 0f);
            if (crease > 0f && refined.hasEdge(edgeId)) {
                // The vertex offset keeps vertex order but not ids, so find the edge by its ends.
                int halfEdge = refined.edgeHalfEdge(edgeId);
                int sculptedEdge = sculpted == refined ? edgeId : sculpted.edgeBetween(
                        sculpted.vertexIdAt(sum.activeVertexById[refined.halfEdgeVertex(halfEdge)]),
                        sculpted.vertexIdAt(sum.activeVertexById[refined.halfEdgeEndVertex(halfEdge)]));
                if (sculptedEdge >= 0) {
                    creases[sculptedEdge] = Math.max(creases[sculptedEdge], crease);
                }
            }
        }
        GeometryBundle carried = refined == mesh ? base : GeometryBundle.ofMesh(refined);
        return EdgeMarks.with(carried.withMesh(sculpted), CREASE_LABEL, creases);
    }

    /**
     * A mesh's vertex positions packed xyz in dense vertex order, the order a vertex offset keeps.
     *
     * @param mesh             surface to read
     * @param activeVertexById receives each sparse vertex id's dense index when not null; at least
     *                         the mesh's vertex id bound long
     * @return the packed positions, three floats per vertex
     */
    public static float[] packedPositions(MeshTopology mesh, int[] activeVertexById) {
        Vector3f position = new Vector3f();
        float[] xyz = new float[mesh.vertexCount() * 3];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (activeVertexById != null) {
                activeVertexById[vertexId] = activeVertex;
            }
            mesh.vertexPosition(vertexId, position);
            xyz[activeVertex * 3] = position.x;
            xyz[activeVertex * 3 + 1] = position.y;
            xyz[activeVertex * 3 + 2] = position.z;
        }
        return xyz;
    }
}
