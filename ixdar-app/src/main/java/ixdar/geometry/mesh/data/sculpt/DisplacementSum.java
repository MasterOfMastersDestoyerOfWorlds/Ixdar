package ixdar.geometry.mesh.data.sculpt;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;

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

    /** Vertex normals averaged over the one-ring, by dense index: raw normals fold. */
    public PackedPoints normalXyz;

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

    /** A silhouette's drag per seed, parallel to {@link #sources}. */
    public PackedPoints seedDragXyz = new PackedPoints(1);

    /** Crease every chain asked for, by edge id of {@link #mesh}. */
    public final float[] strokeCreaseByEdgeId;

    /** Summed move of every finished stroke, by dense index. */
    public final PackedPoints offsetXyz;

    /** Largest silhouette drag per vertex, by dense index. */
    public PackedPoints dragByActiveVertexXyz;

    /** Largest smoothing step per vertex, by dense index. */
    public final float[] smoothByActiveVertex;

    /** Largest raise of the stroke being added, by dense index. */
    public final float[] raiseByActiveVertex;

    /** Deepest sink of the stroke being added, by dense index. */
    public final float[] lowerByActiveVertex;

    /** Offset-weighted raise directions of the stroke being added, by dense index. */
    public final PackedPoints raiseDirectionXyz;

    /** Offset-weighted sink directions of the stroke being added, by dense index. */
    public final PackedPoints lowerDirectionXyz;

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
        normalXyz = new PackedPoints(vertexCount);
        Vector3f normal = new Vector3f();
        Vector3f center = mesh.center(new Vector3f());
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            mesh.vertexNormal(vertexId, normal);
            if (normal.length() < SculptStroke.MINIMUM_NORMAL_LENGTH) {
                mesh.vertexPosition(vertexId, normal).sub(center);
            }
            normalXyz.set(activeVertex, normal.normalize());
        }
        int[][] rings = mesh.oneRings();
        for (int pass = 0; pass < NORMAL_SMOOTHING_PASSES; pass++) {
            normalXyz = normalXyz.ringMean(rings, true);
        }
        bvh = !displace ? null : tree != null ? tree : TriangleBvh.over(mesh, new int[mesh.triangleCount()]);
        bvhStack = new int[bvh == null ? 0 : Math.max(1, bvh.nodeCount)];
        strokeCreaseByEdgeId = new float[mesh.edgeIdBound()];
        offsetXyz = new PackedPoints(vertexCount);
        dragByActiveVertexXyz = new PackedPoints(vertexCount);
        smoothByActiveVertex = new float[vertexCount];
        raiseByActiveVertex = new float[vertexCount];
        lowerByActiveVertex = new float[vertexCount];
        raiseDirectionXyz = new PackedPoints(vertexCount);
        lowerDirectionXyz = new PackedPoints(vertexCount);
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
        Vector3f direction = new Vector3f();
        for (int seed = 0; seed < count; seed++) {
            int activeVertex = activeVertexById[sources[seed]];
            direction.add(normalXyz.at(activeVertex).normalize());
        }
        direction.normalize();
        falloff.expand(mesh, sources, count, stroke.gatherReach(), -1, stroke.refines() || stroke.smooth
                ? direction : null);
        boolean seeing = stroke.seesThroughCamera() && bvh != null;
        int hiddenCount = 0;
        for (int settled = 0; settled < falloff.settledLength && seeing; settled++) {
            int vertexId = falloff.settledVertexIds[settled];
            int activeVertex = activeVertexById[vertexId];
            if (falloff.distance(vertexId) < stroke.radius
                    && visibility(stroke, activeVertex, normalXyz.at(activeVertex), 1f) == 0f) {
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
                Vector3f normal = normalXyz.at(activeVertex);
                kernel *= visibility(stroke, activeVertex, normal, 1f)
                        * Math.min(1f, shadow.distance(vertexId) / (stroke.radius * SHADOW_FEATHER));
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
            seedDragXyz = new PackedPoints(count);
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
        PackedPoints directionXyz = offset > 0f ? raiseDirectionXyz : lowerDirectionXyz;
        raiseByActiveVertex[activeVertex] = Math.max(raiseByActiveVertex[activeVertex], offset);
        lowerByActiveVertex[activeVertex] = Math.min(lowerByActiveVertex[activeVertex], offset);
        directionXyz.add(activeVertex, new Vector3f(direction).mul(Math.abs(offset)));
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
        int vertexCount = mesh.vertexCount();
        int[][] rings = mesh.oneRings();
        for (int pass = 0; pass < DRAG_SMOOTHING_PASSES; pass++) {
            dragByActiveVertexXyz = dragByActiveVertexXyz.ringMean(rings, true);
        }
        PackedPoints moveXyz = offsetXyz.copy();
        moveXyz.addScaled(dragByActiveVertexXyz, 1f);
        PackedPoints movedXyz = mesh.packedPositions(null);
        movedXyz.addScaled(moveXyz, 1f);
        float[] relaxWeight = new float[vertexCount];
        boolean relaxing = false;
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            relaxWeight[activeVertex] = moveXyz.at(activeVertex).lengthSquared() > 0f ? tangentialRelax : 0f;
            relaxing |= relaxWeight[activeVertex] > 0f || smoothByActiveVertex[activeVertex] > 0f;
        }
        MeshTopology sculpted = MeshVertexOffset.atPositions(mesh, movedXyz);
        if (!relaxing) {
            return sculpted;
        }
        int[] sculptedActiveById = new int[sculpted.vertexIdBound()];
        PackedPoints relaxed = sculpted.packedPositions(sculptedActiveById);
        Vector3f normal = new Vector3f();
        int[][] sculptedRings = sculpted.oneRings();
        for (int iteration = 0; iteration < RELAX_ITERATIONS; iteration++) {
            PackedPoints next = relaxed.copy();
            PackedPoints ring = relaxed.ringMean(sculptedRings);
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                float smooth = smoothByActiveVertex[activeVertex];
                float relax = relaxWeight[activeVertex];
                if (relax == 0f && smooth == 0f) {
                    continue;
                }
                Vector3f laplacian = ring.at(activeVertex).sub(relaxed.at(activeVertex));
                sculpted.vertexNormal(sculpted.vertexIdAt(activeVertex), normal);
                float along = normal.dot(laplacian);
                next.add(activeVertex, normal.mul(-along).add(laplacian).mul(relax).add(laplacian.mul(smooth)));
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
        Vector3f position = mesh.vertexPosition(vertexId);
        Vector3f eye = new Vector3f(eyeForward[0], eyeForward[1], eyeForward[2]);
        Vector3f toVertex = new Vector3f(position).sub(eye);
        float distance = position.distance(eye);
        float seen = faces(mesh, vertexId, normal, eyeForward) ? 1f : 0f;
        if (seen > 0f && bvh != null) {
            float longestEdge = 0f;
            for (int adjacency = 0; adjacency < mesh.vertexEdgeCount(vertexId); adjacency++) {
                longestEdge = Math.max(longestEdge, mesh.edgeLength(mesh.vertexEdgeAt(vertexId, adjacency)));
            }
            seen = bvh.nearestHit(eye, toVertex, 1.0 - slackEdges * longestEdge / distance, bvhStack) >= 0
                    ? 0f : seen;
        }
        if (seen > 0f && bvh != null && normal == null) {
            Vector3f outward = mesh.vertexNormal(vertexId, new Vector3f());
            Vector3f along = new Vector3f(toVertex).div(distance);
            outward.fma(-outward.dot(along), along);
            seen = INNER_CONTOUR_VISIBILITY;
            if (outward.length() >= SculptStroke.MINIMUM_NORMAL_LENGTH) {
                outward.normalize(OUTLINE_PROBE_FRACTION * distance).add(toVertex);
                seen = bvh.nearestHit(eye, outward, Double.POSITIVE_INFINITY, bvhStack) < 0 ? 1f : seen;
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
        Vector3f position = mesh.vertexPosition(vertexId);
        return normal.dot(position.x - eyeForward[0], position.y - eyeForward[1], position.z - eyeForward[2]) < 0f;
    }

    /**
     * Fold the stroke just added into {@link #offsetXyz}: its largest raise and deepest sink per
     * vertex along their blended directions, then clear its scratch for the next stroke. The
     * vertices it folds are {@link #touched}'s first {@link #touchedLength} entries until it runs.
     */
    public void finishStroke() {
        for (int index = 0; index < touchedLength; index++) {
            int activeVertex = touched[index];
            float move = raiseByActiveVertex[activeVertex] - lowerByActiveVertex[activeVertex];
            if (move > strongestMoveByActiveVertex[activeVertex]) {
                strongestMoveByActiveVertex[activeVertex] = move;
                strongestSourceByActiveVertex[activeVertex] = currentSource;
            }
            Vector3f direction = raiseDirectionXyz.at(activeVertex);
            if (direction.length() >= SculptStroke.MINIMUM_NORMAL_LENGTH) {
                offsetXyz.add(activeVertex, direction.mul(raiseByActiveVertex[activeVertex] / direction.length()));
            }
            direction = lowerDirectionXyz.at(activeVertex);
            if (direction.length() >= SculptStroke.MINIMUM_NORMAL_LENGTH) {
                offsetXyz.add(activeVertex, direction.mul(lowerByActiveVertex[activeVertex] / direction.length()));
            }
            raiseByActiveVertex[activeVertex] = 0f;
            lowerByActiveVertex[activeVertex] = 0f;
            raiseDirectionXyz.set(activeVertex, 0f, 0f, 0f);
            lowerDirectionXyz.set(activeVertex, 0f, 0f, 0f);
            touchedByActiveVertex[activeVertex] = false;
        }
        touchedLength = 0;
    }
}
