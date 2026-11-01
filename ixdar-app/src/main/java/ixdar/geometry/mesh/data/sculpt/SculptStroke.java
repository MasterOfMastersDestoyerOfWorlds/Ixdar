package ixdar.geometry.mesh.data.sculpt;

import java.util.Arrays;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * One copy of a stroke a sculpt pass applies, its own or a reflection: depth, radius, lifted points
 * and weights, and the eye it was drawn through. Chains, fills and silhouettes seed and shape their
 * falloff each their own way.
 */
public abstract class SculptStroke {

    public static final float FALLOFF_STEEPEST_SLOPE = 1.5396f;

    public static final float GATHER_REACH = 2f;

    public static final float MINIMUM_NORMAL_LENGTH = 1e-6f;

    /** Signed displacement at the stroke in world units, + raising; 1 for a silhouette. */
    public float depth;

    /** Falloff radius in world units; a silhouette's reach. */
    public float radius;

    /** Whether the stroke smooths within its radius instead of moving the surface. */
    public boolean smooth;

    /** Whether the stroke also applies reflected across x. */
    public boolean mirror;

    /** Lifted points packed xyz; a silhouette's are view rays from its eye. */
    public float[] pointXyz = new float[0];

    /** Whether consecutive points are joined into segments (a chain), not lone samples or rays. */
    public boolean joinsPoints;

    /** Weight per point, 1 except inside a bowl fill. */
    public float[] pointWeight = new float[0];

    /** The camera's eye then unit axis, six floats, or null for a stroke every eye sees. */
    public float[] eyeForward;

    /** Index of the stroke in the pass's text that this is a copy of. */
    public int source;

    /** Edge length the stroke refines the faces it reaches to; set by {@link #refineTargets}. */
    public float refineTarget;
    /** Visibility from {@link #eyeForward} by dense vertex index, NaN where not yet asked; null before. */
    public float[] visibilityByActiveVertex;

    /** Where along the element the last {@link #elementFraction} found the nearest point, in [0, 1]. */
    public float elementAlong;

    /**
     * Read one stroke of the text form: its header's depth, radius and flags, then its points with
     * optional weights.
     *
     * @param strokeText one stroke, {@code "d r=R [flags]: x y z [w], ..."}
     * @param source     its index in the pass's text
     * @throws IllegalArgumentException when it names no radius, its eye is malformed, or a
     *                                  silhouette has none
     * @return the stroke, or null for text with no header
     */
    public static SculptStroke parse(String strokeText, int source) {
        int colon = strokeText.indexOf(StrokeSculpt.HEADER_SEPARATOR);
        if (colon < 0) {
            return null;
        }
        String[] header = strokeText.substring(0, colon).trim().split(StrokeSculpt.FIELD_SEPARATOR);
        List<String> flags = Arrays.asList(header);
        SculptStroke stroke = flags.contains(StrokeSculpt.SILHOUETTE_FLAG) ? new SilhouetteStroke()
                : flags.contains(StrokeSculpt.FILL_FLAG) ? new FillStroke() : new ChainStroke();
        stroke.joinsPoints = !flags.contains(StrokeSculpt.SILHOUETTE_FLAG) && !flags.contains(StrokeSculpt.FILL_FLAG);
        stroke.source = source;
        stroke.depth = Float.parseFloat(header[0]);
        stroke.radius = Float.NaN;
        stroke.mirror = flags.contains(StrokeSculpt.MIRROR_FLAG);
        stroke.smooth = flags.contains(StrokeSculpt.SMOOTH_FLAG);
        for (String flag : header) {
            stroke.radius = flag.startsWith(StrokeSculpt.RADIUS_PREFIX)
                    ? Float.parseFloat(flag.substring(StrokeSculpt.RADIUS_PREFIX.length())) : stroke.radius;
            if (flag.startsWith(StrokeSculpt.EYE_PREFIX)) {
                String[] values = flag.substring(StrokeSculpt.EYE_PREFIX.length()).split(StrokeSculpt.EYE_VALUE_SEPARATOR);
                if (values.length != StrokeSculpt.EYE_AND_FORWARD_VALUES) {
                    throw new IllegalArgumentException("a stroke's eye is eye=ex/ey/ez/fx/fy/fz: " + flag);
                }
                stroke.eyeForward = new float[StrokeSculpt.EYE_AND_FORWARD_VALUES];
                for (int value = 0; value < StrokeSculpt.EYE_AND_FORWARD_VALUES; value++) {
                    stroke.eyeForward[value] = Float.parseFloat(values[value]);
                }
            }
        }
        if (stroke instanceof SilhouetteStroke && stroke.eyeForward == null || !(stroke.radius > 0f)) {
            throw new IllegalArgumentException("a stroke needs r=<world radius>, a silhouette also its view's eye=: "
                    + strokeText);
        }
        String[] pointTexts = strokeText.substring(colon + 1).split(StrokeSculpt.POINT_SEPARATOR);
        float[] xyz = new float[pointTexts.length * 3];
        float[] weights = new float[pointTexts.length];
        int pointCount = 0;
        for (String pointText : pointTexts) {
            String[] fields = pointText.trim().split(StrokeSculpt.FIELD_SEPARATOR);
            if (fields.length < 3) {
                continue;
            }
            for (int axis = 0; axis < 3; axis++) {
                xyz[pointCount * 3 + axis] = Float.parseFloat(fields[axis]);
            }
            weights[pointCount++] = fields.length >= StrokeSculpt.WEIGHTED_POINT_FIELDS ? Float.parseFloat(fields[3]) : 1f;
        }
        stroke.pointXyz = Arrays.copyOf(xyz, pointCount * 3);
        stroke.pointWeight = Arrays.copyOf(weights, pointCount);
        return stroke;
    }

    /**
     * Reflect the stroke in place across one axis: its points and its eye.
     *
     * @param axis 0, 1 or 2 for x, y or z
     * @return this stroke, reflected
     */
    public SculptStroke reflect(int axis) {
        for (int coordinate = axis; coordinate < pointXyz.length; coordinate += 3) {
            pointXyz[coordinate] = -pointXyz[coordinate];
        }
        if (eyeForward != null) {
            eyeForward[axis] = -eyeForward[axis];
            eyeForward[axis + 3] = -eyeForward[axis + 3];
        }
        return this;
    }

    /**
     * Set every refining copy's {@link #refineTarget}: a third of its width, or of its mean gap to
     * the nearest stroke moving the other way, finer by the steepness of its falloff.
     *
     * @param copies            the pass's copies
     * @param minimumEdgeLength edge length refinement stops at
     * @param edgesPerWidth     fraction of the width or gap edges are split below
     */
    public static void refineTargets(List<SculptStroke> copies, float minimumEdgeLength, float edgesPerWidth) {
        for (SculptStroke copy : copies) {
            float gap = copy.radius;
            for (SculptStroke other : copies) {
                // Summed bumps of one sign closer than their radius merge into one smooth bump, so
                // only a stroke moving the other way leaves a slope across the gap to resolve; a
                // stroke redrawn over another (a second pass at a nose) would otherwise refine its
                // whole width to a third of nothing.
                if (!copy.refines() || other.source == copy.source || !other.refines() || other.pointXyz.length == 0
                        || copy.pointXyz.length == 0 || copy.depth * other.depth >= 0f) {
                    continue;
                }
                double meanNearest = 0.0;
                for (float distance : nearestDistances(copy.pointXyz, other.pointXyz)) {
                    meanNearest += distance / (copy.pointXyz.length / 3);
                }
                gap = Math.min(gap, (float) meanNearest);
            }
            // A steep stroke stretches its faces up its slope, so it refines finer by that slope.
            float slope = FALLOFF_STEEPEST_SLOPE * Math.abs(copy.depth) / copy.radius;
            copy.refineTarget = Math.max(minimumEdgeLength, gap * edgesPerWidth / Math.max(1f, slope));
        }
    }

    /**
     * Whether a reflection of this stroke is no copy of its own: it lies on this one, every point
     * within half the radius, as a stroke drawn on the mirror plane does.
     *
     * @param reflection the reflected copy
     * @return true when the reflection is dropped or merged into this copy
     */
    public boolean absorbs(SculptStroke reflection) {
        float farthest = 0f;
        for (float distance : nearestDistances(reflection.pointXyz, pointXyz)) {
            farthest = Math.max(farthest, distance);
        }
        return farthest <= radius * 0.5f;
    }

    /**
     * Whether the stroke refines the faces it reaches; a smooth stroke adds no detail to resolve,
     * and the faces it would add are what its umbrella smoothing writes in as streaks.
     *
     * @return true for a ridge, valley or fill that is not a smooth
     */
    public boolean refines() {
        return !smooth;
    }

    /**
     * Lower each reached vertex's required edge length to {@link #refineTarget}: vertices within the
     * radius across the stroke's mean normal and within its gather depth along it, facing its eye.
     *
     * @param mesh                   the surface being refined
     * @param vertexXyz              its positions, packed xyz by dense vertex index
     * @param nearestVertex          vertex index over the surface
     * @param requiredByActiveVertex required edge length by dense vertex index, lowered in place
     */
    public void requireEdgeLengths(MeshTopology mesh, float[] vertexXyz, NearestVertex nearestVertex,
            float[] requiredByActiveVertex) {
        if (!refines() || pointXyz.length == 0) {
            return;
        }
        // Reach is measured across the stroke's direction, as its falloff is, so the walls of an
        // earlier pit under the stroke refine too; depth is bounded by the stroke's own depth beyond
        // the falloff gather.
        float depthReach = radius * GATHER_REACH + Math.abs(depth);
        float[] low = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY };
        float[] high = { Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
        Vector3f direction = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int coordinate = 0; coordinate < pointXyz.length; coordinate++) {
            low[coordinate % 3] = Math.min(low[coordinate % 3], pointXyz[coordinate] - depthReach);
            high[coordinate % 3] = Math.max(high[coordinate % 3], pointXyz[coordinate] + depthReach);
            if (coordinate % 3 == 2 && mesh.vertexNormal(nearestVertex.nearest(pointXyz[coordinate - 2],
                    pointXyz[coordinate - 1], pointXyz[coordinate], Float.POSITIVE_INFINITY), normal).length()
                    >= MINIMUM_NORMAL_LENGTH) {
                direction.add(normal.normalize());
            }
        }
        if (direction.length() >= MINIMUM_NORMAL_LENGTH) {
            direction.normalize();
        }
        Vector3f offset = new Vector3f();
        Vector3f scratch = new Vector3f();
        for (int activeVertex = 0; activeVertex < requiredByActiveVertex.length; activeVertex++) {
            int at = activeVertex * 3;
            if (requiredByActiveVertex[activeVertex] <= refineTarget || vertexXyz[at] < low[0]
                    || vertexXyz[at] > high[0] || vertexXyz[at + 1] < low[1] || vertexXyz[at + 1] > high[1]
                    || vertexXyz[at + 2] < low[2] || vertexXyz[at + 2] > high[2]) {
                continue;
            }
            // Reach is measured to the stroke's elements, as its kernel is: a chain's segments, so
            // the surface between points drawn farther apart than the radius refines too.
            for (int point = 0; point < pointXyz.length; point += 3) {
                int end = joinsPoints && point + 3 < pointXyz.length ? point + 3 : point;
                float across = elementFraction(point, end, offset.set(vertexXyz[at], vertexXyz[at + 1],
                        vertexXyz[at + 2]), direction, scratch);
                offset.set(vertexXyz[at] - pointXyz[point], vertexXyz[at + 1] - pointXyz[point + 1],
                        vertexXyz[at + 2] - pointXyz[point + 2]);
                offset.sub((pointXyz[end] - pointXyz[point]) * elementAlong, (pointXyz[end + 1] - pointXyz[point + 1])
                        * elementAlong, (pointXyz[end + 2] - pointXyz[point + 2]) * elementAlong);
                if (Math.abs(offset.dot(direction)) <= depthReach && across <= 1f) {
                    // Refinement skips only what faces away; the depth test is left to the
                    // displacement, which casts one ray per vertex instead of one per round.
                    int vertexId = mesh.vertexIdAt(activeVertex);
                    boolean facing = eyeForward == null
                            || DisplacementSum.faces(mesh, vertexId, mesh.vertexNormal(vertexId, normal), eyeForward);
                    requiredByActiveVertex[activeVertex] = facing ? refineTarget : requiredByActiveVertex[activeVertex];
                    break;
                }
            }
        }
    }

    /**
     * The seed vertices the stroke's falloff spreads from, written to the sum's
     * {@link DisplacementSum#sources}; a chain also writes its crease.
     *
     * @param sum the pass's displacement sum over the refined surface
     * @return how many seeds were written
     */
    public abstract int seed(DisplacementSum sum);

    /**
     * How far along mesh edges the falloff gathers vertices from the seeds.
     *
     * @return the gather cutoff in world units
     */
    public float gatherReach() {
        return radius * GATHER_REACH;
    }

    /**
     * The falloff weight at a gathered vertex.
     *
     * @param position     the vertex's position, overwritten
     * @param direction    the stroke's mean normal, unit length
     * @param edgeFraction the vertex's edge distance from the seeds over the radius
     * @return the kernel, in [0, the largest point weight]
     */
    public abstract float kernel(Vector3f position, Vector3f direction, float edgeFraction);

    /**
     * A vertex's distance across the stroke's direction to one element (a segment, or a sample when
     * its ends are one point) over the radius; where along it lands in {@link #elementAlong}.
     *
     * @param start     the element's first point's offset in {@link #pointXyz}
     * @param end       its last point's offset, {@code start} for a sample
     * @param position  the vertex's position, overwritten
     * @param direction the stroke's mean normal, unit length
     * @param scratch   scratch vector, overwritten
     * @return the distance over the radius
     */
    public float elementFraction(int start, int end, Vector3f position, Vector3f direction, Vector3f scratch) {
        position.sub(pointXyz[start], pointXyz[start + 1], pointXyz[start + 2]);
        position.fma(-position.dot(direction), direction);
        scratch.set(pointXyz[end] - pointXyz[start], pointXyz[end + 1] - pointXyz[start + 1],
                pointXyz[end + 2] - pointXyz[start + 2]);
        scratch.fma(-scratch.dot(direction), direction);
        float lengthSquared = scratch.lengthSquared();
        elementAlong = lengthSquared == 0f ? 0f : Math.max(0f, Math.min(1f, position.dot(scratch) / lengthSquared));
        return position.fma(-elementAlong, scratch).length() / radius;
    }

    /**
     * Whether the stroke moves only what its camera sees, fading over a feather before what it
     * cannot, so an occluder's shadow edge does not fold.
     *
     * @return true for a chain or fill with an eye
     */
    public boolean seesThroughCamera() {
        return eyeForward != null;
    }

    /**
     * Add the stroke's move at one gathered vertex to the sum: along the stroke's direction scaled by
     * the kernel, or a smoothing step for a smooth stroke.
     *
     * @param sum          the pass's displacement sum
     * @param activeVertex the vertex, a dense index
     * @param kernel       the falloff weight there
     * @param seed         index into the sum's sources of the seed the vertex is nearest
     * @param direction    the stroke's mean normal
     * @return the length of the move added, 0 for a smoothing step
     */
    public float deposit(DisplacementSum sum, int activeVertex, float kernel, int seed, Vector3f direction) {
        if (smooth) {
            sum.smoothByActiveVertex[activeVertex] = Math.max(sum.smoothByActiveVertex[activeVertex],
                    DisplacementSum.MAXIMUM_SMOOTH_STEP * kernel);
            return 0f;
        }
        float offset = depth * kernel;
        sum.raiseOrLower(activeVertex, offset, direction);
        return Math.abs(offset);
    }

    /**
     * The point of a packed set nearest a position.
     *
     * @param points   the points, packed xyz
     * @param position where to measure from
     * @return the nearest point's index, the first on a tie, or -1 for an empty set
     */
    public static int nearestPoint(float[] points, Vector3f position) {
        int nearest = -1;
        float nearestDistance = Float.POSITIVE_INFINITY;
        for (int point = 0; point < points.length / 3; point++) {
            float distance = position.distanceSquared(points[point * 3], points[point * 3 + 1], points[point * 3 + 2]);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = point;
            }
        }
        return nearest;
    }

    /**
     * Each point's distance to the nearest of another packed set: how far one copy lies from
     * another, read as its gap, its overlap with its reflection, or its coincidence with it.
     *
     * @param points the points measured, packed xyz
     * @param others the points measured to, packed xyz
     * @return one distance per point, infinite when {@code others} is empty
     */
    public static float[] nearestDistances(float[] points, float[] others) {
        float[] distances = new float[points.length / 3];
        Vector3f sample = new Vector3f();
        for (int point = 0; point < distances.length; point++) {
            sample.set(points[point * 3], points[point * 3 + 1], points[point * 3 + 2]);
            int nearest = nearestPoint(others, sample) * 3;
            distances[point] = nearest < 0 ? Float.POSITIVE_INFINITY
                    : sample.distance(others[nearest], others[nearest + 1], others[nearest + 2]);
        }
        return distances;
    }
}
