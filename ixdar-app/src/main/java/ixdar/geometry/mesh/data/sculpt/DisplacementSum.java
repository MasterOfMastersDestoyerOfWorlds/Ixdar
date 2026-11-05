package ixdar.geometry.mesh.data.sculpt;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.field.Vector3Field;

/**
 * Every stroke's move over one refined surface, summed: distinct strokes add, while the copies of
 * one stroke (its reflections) keep the larger move per vertex, blending directions so a stroke
 * meeting its mirror copy on the midline leaves no seam. Silhouette drags and smoothing take the
 * largest.
 */
public final class DisplacementSum {

    public static final int NORMAL_SMOOTHING_PASSES = 2;

    public static final int RELAX_ITERATIONS = 4;

    public static final int DRAG_SMOOTHING_PASSES = 6;

    public static final float MAXIMUM_SMOOTH_STEP = 0.6f;

    public static final float OUTLINE_PROBE_FRACTION = 0.005f;

    public static final float INNER_CONTOUR_VISIBILITY = 0.5f;

    public static final float SHADOW_FEATHER = 0.5f;

    public static final float FULL_FACING_COSINE = 0.5f;

    /** The refined surface every stroke moves, unmoved. */
    public final MeshTopology mesh;

    /** Dense index of each sparse vertex id of {@link #mesh}. */
    public final int[] activeVertexById;

    /** Vertex normals averaged over the one-ring, packed xyz by dense index: raw normals fold. */
    public float[] normalXyz;

    /** Whether strokes move vertices; false only creases their chains. */
    public final boolean displace;

    /** Crease weight a chain writes on its edges, times its mean point weight. */
    public final float creaseScale;

    /** Triangle tree over {@link #mesh} for depth tests, null when not displacing. */
    public final TriangleBvh bvh;

    /** Scratch node stack for {@link #bvh}. */
    public final int[] bvhStack;

    /** Snap and edge search a chain seeds with. */
    public final StrokeChain chain = new StrokeChain();

    /** Seed vertex ids the last {@link SculptStroke#seed} wrote. */
    public int[] sources = new int[1];

    /** A silhouette's drag per seed, packed xyz, parallel to {@link #sources}. */
    public float[] seedDragXyz = new float[3];

    /** Crease every chain asked for, by edge id of {@link #mesh}. */
    public final float[] strokeCreaseByEdgeId;

    /** Summed move of every finished stroke, packed xyz by dense index. */
    public final float[] offsetXyz;

    /** Largest silhouette drag per vertex, packed xyz by dense index. */
    public float[] dragByActiveVertexXyz;

    /** Largest smoothing step per vertex, by dense index. */
    public final float[] smoothByActiveVertex;

    /** Largest raise of the stroke being added, by dense index. */
    public final float[] raiseByActiveVertex;

    /** Deepest sink of the stroke being added, by dense index. */
    public final float[] lowerByActiveVertex;

    /** Offset-weighted raise directions of the stroke being added, packed xyz. */
    public final float[] raiseDirectionXyz;

    /** Offset-weighted sink directions of the stroke being added, packed xyz. */
    public final float[] lowerDirectionXyz;

    /** Dense indices the stroke being added has moved, {@link #touchedLength} of them. */
    public final int[] touched;

    /** Whether each dense index is in {@link #touched}. */
    public final boolean[] touchedByActiveVertex;

    /** How many entries of {@link #touched} are in use. */
    public int touchedLength;

    /** Stroke whose copies are being added, -1 before the first. */
    public int currentSource = -1;

    /** Per stroke of the pass's text: the largest distance it moved a vertex. */
    public final float[] peakDisplacementBySource;

    /** Per stroke of the pass's text: how many vertices it moved, its copies included. */
    public final int[] movedVerticesBySource;

    /** Falloff gather of the stroke being added. */
    public final EdgeDistances falloff = new EdgeDistances();

    /** Distance from what the stroke's camera cannot see, for the shadow feather. */
    public final EdgeDistances shadow = new EdgeDistances();

    /** Gathered vertex ids the stroke's camera cannot see. */
    public final int[] hiddenIds;

    /** Visibility by dense vertex index, NaN where not yet asked, for each camera's eye and forward. */
    public final Map<String, float[]> visibilityByCamera = new HashMap<>();

    /** Per vertex by dense index, the stroke of the pass's text that moved it farthest, -1 for none. */
    public final int[] strongestSourceByActiveVertex;

    /** Per vertex by dense index, how far {@link #strongestSourceByActiveVertex} moved it. */
    public final float[] strongestMoveByActiveVertex;

    /**
     * An empty sum over a refined surface, its normals smoothed and, when displacing, its triangle
     * tree built.
     *
     * @param mesh        the refined surface
     * @param displace    whether strokes move vertices
     * @param creaseScale crease weight a chain writes
     * @param strokes     how many strokes the pass's text holds
     */
    public DisplacementSum(MeshTopology mesh, boolean displace, float creaseScale, int strokes) {
        this(mesh, displace, creaseScale, strokes, null);
    }

    /**
     * An empty sum over a refined surface, sharing another sum's triangle tree so sums that split a
     * pass's strokes between them do not each build one.
     *
     * @param mesh        the refined surface
     * @param displace    whether strokes move vertices
     * @param creaseScale crease weight a chain writes
     * @param strokes     how many strokes the pass's text holds
     * @param tree        a triangle tree over the same surface, read-only here; null builds one
     */
    public DisplacementSum(MeshTopology mesh, boolean displace, float creaseScale, int strokes, TriangleBvh tree) {
        this.mesh = mesh;
        this.displace = displace;
        this.creaseScale = creaseScale;
        int vertexCount = mesh.vertexCount();
        activeVertexById = mesh.activeIndexById();
        normalXyz = new float[vertexCount * 3];
        Vector3f normal = new Vector3f();
        Vector3f center = mesh.center(new Vector3f());
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            mesh.vertexNormal(vertexId, normal);
            if (normal.length() < SculptStroke.MINIMUM_NORMAL_LENGTH) {
                mesh.vertexPosition(vertexId, normal).sub(center);
            }
            normal.normalize();
            normalXyz[activeVertex * 3] = normal.x;
            normalXyz[activeVertex * 3 + 1] = normal.y;
            normalXyz[activeVertex * 3 + 2] = normal.z;
        }
        for (int pass = 0; pass < NORMAL_SMOOTHING_PASSES; pass++) {
            normalXyz = ringSum(mesh, activeVertexById, normalXyz, false);
        }
        bvh = !displace ? null : tree != null ? tree : TriangleBvh.over(mesh, new int[mesh.triangleCount()]);
        bvhStack = new int[bvh == null ? 0 : Math.max(1, bvh.nodeCount)];
        strokeCreaseByEdgeId = new float[mesh.edgeIdBound()];
        offsetXyz = new float[vertexCount * 3];
        dragByActiveVertexXyz = new float[vertexCount * 3];
        smoothByActiveVertex = new float[vertexCount];
        raiseByActiveVertex = new float[vertexCount];
        lowerByActiveVertex = new float[vertexCount];
        raiseDirectionXyz = new float[vertexCount * 3];
        lowerDirectionXyz = new float[vertexCount * 3];
        touched = new int[vertexCount];
        touchedByActiveVertex = new boolean[vertexCount];
        peakDisplacementBySource = new float[strokes];
        movedVerticesBySource = new int[strokes];
        hiddenIds = new int[vertexCount];
        strongestSourceByActiveVertex = new int[vertexCount];
        Arrays.fill(strongestSourceByActiveVertex, -1);
        strongestMoveByActiveVertex = new float[vertexCount];
    }

    /**
     * Add one stroke copy: seed it, gather the surface within its reach, and deposit its kernel,
     * faded to what its camera sees, at every gathered vertex.
     *
     * @param stroke the copy; copies of one stroke are added one after another
     */
    public void add(SculptStroke stroke) {
        if (stroke.source != currentSource) {
            finishStroke();
            currentSource = stroke.source;
        }
        int count = stroke.seed(this);
        if (!displace || count == 0) {
            return;
        }
        // One direction for the whole stroke, its seeds' mean normal, and a falloff measured across
        // it: per-vertex normals over an earlier pass's walls point sideways.
        Vector3f direction = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int seed = 0; seed < count; seed++) {
            int at = activeVertexById[sources[seed]] * 3;
            direction.add(normal.set(normalXyz[at], normalXyz[at + 1], normalXyz[at + 2]).normalize());
        }
        direction.normalize();
        // The edge search only gathers; chains and fills measure their falloff straight to the drawn
        // stroke, since edge paths over mixed-density faces zigzag into the mesh's staircase.
        falloff.expand(mesh, sources, count, stroke.gatherReach(), -1, stroke.refines() || stroke.smooth
                ? direction : null);
        boolean seeing = stroke.seesThroughCamera() && bvh != null;
        int hiddenCount = 0;
        for (int settled = 0; settled < falloff.settledLength && seeing; settled++) {
            int vertexId = falloff.settledVertexIds[settled];
            int at = activeVertexById[vertexId] * 3;
            if (falloff.distance(vertexId) < stroke.radius && visibility(stroke, at / 3,
                    normal.set(normalXyz[at], normalXyz[at + 1], normalXyz[at + 2]), 1f) == 0f) {
                hiddenIds[hiddenCount++] = vertexId;
            }
        }
        if (seeing) {
            shadow.expand(mesh, hiddenIds, hiddenCount, stroke.radius * SHADOW_FEATHER, -1, null);
        }
        float peak = 0f;
        int moved = 0;
        Vector3f position = new Vector3f();
        for (int settled = 0; settled < falloff.settledLength; settled++) {
            int vertexId = falloff.settledVertexIds[settled];
            int activeVertex = activeVertexById[vertexId];
            float kernel = stroke.kernel(mesh.vertexPosition(vertexId, position), direction,
                    falloff.distance(vertexId) / stroke.radius);
            if (seeing && kernel > 0f) {
                int at = activeVertex * 3;
                kernel *= visibility(stroke, activeVertex, normal.set(normalXyz[at], normalXyz[at + 1],
                        normalXyz[at + 2]), 1f) * Math.min(1f, shadow.distance(vertexId)
                                / (stroke.radius * SHADOW_FEATHER));
                // A camera sees a surface turning edge-on ever worse: the move fades out as the
                // surface turns from facing it to grazing, the lift's own cut-off.
                float[] eye = stroke.eyeForward;
                mesh.vertexPosition(vertexId, position).sub(eye[0], eye[1], eye[2]).normalize();
                float facing = -normal.dot(position) / Math.max(SculptStroke.MINIMUM_NORMAL_LENGTH, normal.length());
                kernel *= Math.max(0f, Math.min(1f, (facing - StrokeSketch.GRAZING_COSINE)
                        / (FULL_FACING_COSINE - StrokeSketch.GRAZING_COSINE)));
            }
            moved += kernel > 0f ? 1 : 0;
            peak = Math.max(peak, stroke.deposit(this, activeVertex, kernel, falloff.origin(vertexId),
                    direction));
        }
        peakDisplacementBySource[stroke.source] = Math.max(peakDisplacementBySource[stroke.source], peak);
        movedVerticesBySource[stroke.source] += moved;
    }

    /**
     * Grow {@link #sources} and {@link #seedDragXyz} to hold a number of seeds.
     *
     * @param count seeds the next write needs room for
     */
    public void ensureSources(int count) {
        if (sources.length < count) {
            sources = new int[count];
            seedDragXyz = new float[count * 3];
        }
    }

    /**
     * Take one copy's signed move at a vertex: the stroke keeps its largest raise and deepest sink
     * there, their directions blended by offset.
     *
     * @param activeVertex the vertex, a dense index
     * @param offset       the signed move along the copy's direction
     * @param direction    the copy's mean normal
     */
    public void raiseOrLower(int activeVertex, float offset, Vector3f direction) {
        if (!touchedByActiveVertex[activeVertex]) {
            touchedByActiveVertex[activeVertex] = true;
            touched[touchedLength++] = activeVertex;
        }
        float[] directionXyz = offset > 0f ? raiseDirectionXyz : lowerDirectionXyz;
        raiseByActiveVertex[activeVertex] = Math.max(raiseByActiveVertex[activeVertex], offset);
        lowerByActiveVertex[activeVertex] = Math.min(lowerByActiveVertex[activeVertex], offset);
        directionXyz[activeVertex * 3] += Math.abs(offset) * direction.x;
        directionXyz[activeVertex * 3 + 1] += Math.abs(offset) * direction.y;
        directionXyz[activeVertex * 3 + 2] += Math.abs(offset) * direction.z;
    }

    /**
     * The surface moved by the sum: the summed strokes plus the blurred drags, then what moved
     * relaxed tangentially and smoothed where smooth strokes asked.
     *
     * @param tangentialRelax fraction of the tangential Laplacian each relax iteration applies
     * @return the moved surface, vertex order kept
     */
    public MeshTopology sculpted(float tangentialRelax) {
        finishStroke();
        // Each vertex took the drag of its nearest contour vertex, a field with seams between
        // neighbours that pulled different ways; blurring it keeps those seams from folding.
        for (int pass = 0; pass < DRAG_SMOOTHING_PASSES; pass++) {
            dragByActiveVertexXyz = ringSum(mesh, activeVertexById, dragByActiveVertexXyz, true);
        }
        int vertexCount = mesh.vertexCount();
        float[] combinedXyz = new float[vertexCount * 3];
        float[] relaxWeight = new float[vertexCount];
        boolean relaxing = false;
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            float lengthSquared = 0f;
            for (int axis = 0; axis < 3; axis++) {
                int at = activeVertex * 3 + axis;
                combinedXyz[at] = offsetXyz[at] + dragByActiveVertexXyz[at];
                lengthSquared += combinedXyz[at] * combinedXyz[at];
            }
            relaxWeight[activeVertex] = lengthSquared > 0f ? tangentialRelax : 0f;
            relaxing |= relaxWeight[activeVertex] > 0f || smoothByActiveVertex[activeVertex] > 0f;
        }
        MeshTopology sculpted = MeshVertexOffset.apply(mesh, Vector3Field.copyOf(combinedXyz));
        if (!relaxing) {
            return sculpted;
        }
        // Relax what moved tangentially, which evens out stretched quads without shrinking the
        // bumps, and smooth fully where smooth strokes asked for it.
        int[] sculptedActiveById = new int[sculpted.vertexIdBound()];
        float[] relaxed = sculpted.packedPositions(sculptedActiveById);
        Vector3f laplacian = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int iteration = 0; iteration < RELAX_ITERATIONS; iteration++) {
            float[] next = relaxed.clone();
            float[] ring = ringSum(sculpted, sculptedActiveById, relaxed, false);
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                float smooth = smoothByActiveVertex[activeVertex];
                if (relaxWeight[activeVertex] == 0f && smooth == 0f) {
                    continue;
                }
                int vertexId = sculpted.vertexIdAt(activeVertex);
                int at = activeVertex * 3;
                laplacian.set(ring[at], ring[at + 1], ring[at + 2]).sub(relaxed[at], relaxed[at + 1], relaxed[at + 2])
                        .div(Math.max(1, sculpted.vertexEdgeCount(vertexId)))
                        .sub(relaxed[at], relaxed[at + 1], relaxed[at + 2]);
                sculpted.vertexNormal(vertexId, normal);
                float along = normal.dot(laplacian);
                for (int axis = 0; axis < 3; axis++) {
                    next[at + axis] += smooth * laplacian.get(axis)
                            + relaxWeight[activeVertex] * (laplacian.get(axis) - along * normal.get(axis));
                }
            }
            relaxed = next;
        }
        return MeshVertexOffset.atPositions(sculpted, relaxed);
    }

    /**
     * Whether a stroke's camera sees a vertex: it faces the eye and, as a depth test against the
     * surface, nothing lies between them. Cached on the stroke.
     *
     * @param stroke       the stroke whose eye looks
     * @param activeVertex vertex asked about, a dense index
     * @param normal       the vertex's normal, any length, or null for a contour vertex: then one on
     *                     the outline, {@link #INNER_CONTOUR_VISIBILITY} in front of more surface
     * @param slackEdges   how many of the vertex's longest edges in front of it still count as
     *                     itself, so a grazing ray through its own faces does not hide it
     * @return one when seen, zero when not, and for a contour vertex as {@code normal} says
     */
    public float visibility(SculptStroke stroke, int activeVertex, Vector3f normal, float slackEdges) {
        if (stroke.visibilityByActiveVertex == null || stroke.visibilityByActiveVertex.length != mesh.vertexCount()) {
            // What a camera sees of this surface is the same for every stroke drawn through it, so
            // strokes share it; a silhouette also asks about contours and keeps its own.
            float[] fresh = new float[mesh.vertexCount()];
            Arrays.fill(fresh, Float.NaN);
            stroke.visibilityByActiveVertex = stroke instanceof SilhouetteStroke ? fresh
                    : visibilityByCamera.computeIfAbsent(Arrays.toString(stroke.eyeForward), camera -> fresh);
        }
        if (!Float.isNaN(stroke.visibilityByActiveVertex[activeVertex])) {
            return stroke.visibilityByActiveVertex[activeVertex];
        }
        float[] eyeForward = stroke.eyeForward;
        int vertexId = mesh.vertexIdAt(activeVertex);
        Vector3f position = mesh.vertexPosition(vertexId, new Vector3f());
        float[] toVertex = { position.x - eyeForward[0], position.y - eyeForward[1], position.z - eyeForward[2] };
        float distance = position.distance(eyeForward[0], eyeForward[1], eyeForward[2]);
        float seen = faces(mesh, vertexId, normal, eyeForward) ? 1f : 0f;
        if (seen > 0f && bvh != null) {
            float longestEdge = 0f;
            for (int adjacency = 0; adjacency < mesh.vertexEdgeCount(vertexId); adjacency++) {
                longestEdge = Math.max(longestEdge, mesh.edgeLength(mesh.vertexEdgeAt(vertexId, adjacency)));
            }
            seen = bvh.nearestHit(eyeForward, toVertex, 1.0 - slackEdges * longestEdge / distance, bvhStack) >= 0
                    ? 0f : seen;
        }
        if (seen > 0f && bvh != null && normal == null) {
            // A seen contour is on the outline when the view ray just past it, outward across the
            // view, meets nothing; otherwise it is an inner contour in front of more surface.
            Vector3f outward = mesh.vertexNormal(vertexId, new Vector3f());
            Vector3f along = new Vector3f(toVertex[0], toVertex[1], toVertex[2]).div(distance);
            outward.fma(-outward.dot(along), along);
            seen = INNER_CONTOUR_VISIBILITY;
            if (outward.length() >= SculptStroke.MINIMUM_NORMAL_LENGTH) {
                outward.normalize(OUTLINE_PROBE_FRACTION * distance).add(toVertex[0], toVertex[1], toVertex[2]);
                float[] probe = { outward.x, outward.y, outward.z };
                seen = bvh.nearestHit(eyeForward, probe, Double.POSITIVE_INFINITY, bvhStack) < 0 ? 1f : seen;
            }
        }
        stroke.visibilityByActiveVertex[activeVertex] = seen;
        return seen;
    }

    /**
     * Whether a vertex faces an eye; a vertex with no normal (a contour asked about, a degenerate
     * one) counts as facing it.
     *
     * @param mesh       surface the vertex is on
     * @param vertexId   the vertex, a sparse id
     * @param normal     its normal, any length, or null
     * @param eyeForward the eye then axis
     * @return true when the eye is on the normal's side
     */
    public static boolean faces(MeshTopology mesh, int vertexId, Vector3f normal, float[] eyeForward) {
        if (normal == null || normal.length() < SculptStroke.MINIMUM_NORMAL_LENGTH) {
            return true;
        }
        Vector3f position = mesh.vertexPosition(vertexId, new Vector3f());
        return normal.dot(position.x - eyeForward[0], position.y - eyeForward[1], position.z - eyeForward[2]) < 0f;
    }

    /**
     * Fold the stroke just added into {@link #offsetXyz}: its largest raise and deepest sink per
     * vertex along their blended directions, then clear its scratch for the next stroke. The
     * vertices it folds are {@link #touched}'s first {@link #touchedLength} entries until it runs.
     */
    public void finishStroke() {
        Vector3f direction = new Vector3f();
        for (int index = 0; index < touchedLength; index++) {
            int activeVertex = touched[index];
            int at = activeVertex * 3;
            float move = raiseByActiveVertex[activeVertex] - lowerByActiveVertex[activeVertex];
            if (move > strongestMoveByActiveVertex[activeVertex]) {
                strongestMoveByActiveVertex[activeVertex] = move;
                strongestSourceByActiveVertex[activeVertex] = currentSource;
            }
            direction.set(raiseDirectionXyz[at], raiseDirectionXyz[at + 1], raiseDirectionXyz[at + 2]);
            if (direction.length() >= SculptStroke.MINIMUM_NORMAL_LENGTH) {
                direction.mul(raiseByActiveVertex[activeVertex] / direction.length());
                offsetXyz[at] += direction.x;
                offsetXyz[at + 1] += direction.y;
                offsetXyz[at + 2] += direction.z;
            }
            direction.set(lowerDirectionXyz[at], lowerDirectionXyz[at + 1], lowerDirectionXyz[at + 2]);
            if (direction.length() >= SculptStroke.MINIMUM_NORMAL_LENGTH) {
                direction.mul(lowerByActiveVertex[activeVertex] / direction.length());
                offsetXyz[at] += direction.x;
                offsetXyz[at + 1] += direction.y;
                offsetXyz[at + 2] += direction.z;
            }
            raiseByActiveVertex[activeVertex] = 0f;
            lowerByActiveVertex[activeVertex] = 0f;
            Arrays.fill(raiseDirectionXyz, at, at + 3, 0f);
            Arrays.fill(lowerDirectionXyz, at, at + 3, 0f);
            touchedByActiveVertex[activeVertex] = false;
        }
        touchedLength = 0;
    }

    /**
     * One pass of one-ring smoothing over a per-vertex xyz field: each vertex's own value plus its
     * neighbours', summed or averaged.
     *
     * @param surface          surface whose edges give the rings
     * @param activeVertexById dense index of each sparse vertex id
     * @param xyz              the field, packed xyz by dense vertex index; left unchanged
     * @param mean             whether to divide each sum by the number of values in it
     * @return the smoothed field, a new array
     */
    private static float[] ringSum(MeshTopology surface, int[] activeVertexById, float[] xyz, boolean mean) {
        float[] summed = xyz.clone();
        for (int activeVertex = 0; activeVertex < surface.vertexCount(); activeVertex++) {
            int vertexId = surface.vertexIdAt(activeVertex);
            int neighbours = surface.vertexEdgeCount(vertexId);
            for (int adjacency = 0; adjacency < neighbours; adjacency++) {
                int neighbour = activeVertexById[surface.edgeOtherVertex(surface.vertexEdgeAt(vertexId, adjacency),
                        vertexId)];
                for (int axis = 0; axis < 3; axis++) {
                    summed[activeVertex * 3 + axis] += xyz[neighbour * 3 + axis];
                }
            }
            for (int axis = 0; axis < 3 && mean; axis++) {
                summed[activeVertex * 3 + axis] /= neighbours + 1;
            }
        }
        return summed;
    }
}
