package ixdar.geometry.mesh.data.paths.query;

import java.util.Arrays;
import java.util.stream.IntStream;

import org.joml.Matrix3f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;
import ixdar.geometry.mesh.data.representation.TriangulationRule;

/**
 * Axis-aligned bounding-volume hierarchy over a triangle soup held as flat positions and corner
 * indices, so a ray visits only the triangles whose boxes it enters.
 *
 * <p>Set the soup, call {@link #build()}, then query.
 */
public final class TriangleBvh {

    public static final int TRIANGLE_CORNERS = 3;

    public static final int FACING_OUT_OF_VOLUME = 1;

    public static final int FACING_INTO_VOLUME = -1;

    public static final int FACING_UNDECIDED = 0;

    public static final double MINIMUM_RAY_PARAMETER = 1e-6;

    public static final double[] SLANT_CONE_TILT_DEGREES = {30, 55, 75};

    public static final int SLANT_AZIMUTHS = 8;

    public static final int HEMISPHERE_RAYS = 1 + SLANT_CONE_TILT_DEGREES.length * SLANT_AZIMUTHS;

    private static final double[][] RAY_TRIG = rayTrig();


    private static final double EDGE_SLACK = 1e-9;

    private static final double WINDING_MARGIN = 1e-4;

    private static final int FACING_TASK = 4096;


    private static final int PARALLEL_BUILD_TRIANGLES = 65536;

    private static final int BUILD_SUBTREES = 64;

    private static final int MAX_COLUMNS = 1 << 22;

    private static final int LEAF_TRIANGLES = 64;

    private static final int GROWTH = 2;

    private static final int CHILDREN = 2;

    private static final int INITIAL_NODES = 64;

    private static final int BOX_CORNERS = 2;

    private static final float HALF = 0.5f;

    /** Vertex positions, indexed by the corners of {@link #triangleCorners}. */
    public PackedPoints positions = new PackedPoints(0);

    /** Three position indices per triangle. */
    public int[] triangleCorners = new int[0];

    /**
     * Group of each triangle; a negative group leaves the triangle out of the tree. Null when the
     * soup has no groups and every triangle takes part.
     */
    public int[] triangleGroup;

    /** Triangles the soup offers, taken from the front of {@link #triangleCorners}. */
    public int triangleCount;

    /** Indices of the triangles in the tree, permuted so every node owns one contiguous run. */
    public int[] triangleOrder = new int[0];

    /** Nodes in the tree, root first. */
    public int nodeCount;

    /** Lower box corner of each node, one point per node. */
    public PackedPoints nodeMin = new PackedPoints(0);

    /** Upper box corner of each node, one point per node. */
    public PackedPoints nodeMax = new PackedPoints(0);

    /** First child of each node; the second is the one after it, and {@code -1} marks a leaf. */
    public int[] nodeLeftChild = new int[0];

    /** Offset into {@link #triangleOrder} of each leaf's run. */
    public int[] nodeTriangleStart = new int[0];

    /** Length of each leaf's run in {@link #triangleOrder}. */
    public int[] nodeTriangleCount = new int[0];

    /**
     * Distance along a {@link #facing} line under which a crossing marks the triangle in
     * {@link #crowded}; zero leaves {@link #crowded} unset.
     */
    public double crowdMargin;

    /**
     * Per triangle, whether the last {@link #facing} line through it crossed another sheet closer
     * than {@link #crowdMargin}, where rays at other slants may pass the sheet's other side.
     */
    public boolean[] crowded;

    /** Whether {@link #facing} fills {@link #lineCells}. */
    public boolean recordLineCells;

    /**
     * Per triangle, the cell, column times three plus axis as {@link #cellsUnder} lists them, of
     * the last {@link #facing} line through it, so the answer stands while no triangle under that
     * cell changes; null unless {@link #recordLineCells}.
     */
    public int[] lineCells;

    /**
     * Per triangle, whether {@link #anyHit}, {@link #windingAlong} and the hemisphere queries pass
     * it by, so a triangle can leave a built tree without a rebuild; null passes none by.
     */
    public boolean[] retired;

    /**
     * A second tree whose triangles {@link #anyHit}, {@link #windingAlong} and the hemisphere
     * queries count as this tree's own, so triangles can join without a rebuild; its hits are
     * reported as {@link Integer#MAX_VALUE}. Query stacks must fit both trees.
     */
    public TriangleBvh companion;

    /**
     * Whether {@link #build} splits subtrees on several threads. The tree answers every query the
     * same, but its nodes are numbered differently from a serial build.
     */
    public boolean parallelBuild;

    /** Most triangles a leaf holds; smaller leaves build deeper trees that rays cross faster. */
    public int leafTriangles = LEAF_TRIANGLES;

    /** Ray parameter of the last {@link #nearestHit}'s hit, its bound when it met nothing. */
    public double hitParameter;

    /**
     * Per triangle, which side the last {@link #facing} call found open to infinity along the axis
     * it cast, on a line crossing the soup an odd number of times: {@link #FACING_OUT_OF_VOLUME}
     * when only the front's ray crosses nothing, {@link #FACING_INTO_VOLUME} when only the back's
     * does, otherwise {@link #FACING_UNDECIDED}.
     */
    public int[] exposedSide = new int[0];

    /**
     * First triangle that only {@link #exposedSide} sees, such as a cap closing a hole for its rays;
     * {@link #facing}'s parity counts only the triangles before it, which are all it classifies.
     */
    public int firstExposureOnly = Integer.MAX_VALUE;

    /** Triangles below this index are left out of {@link #build}, for a tree over a tail run. */
    public int firstTriangle;

    /**
     * When set, the only triangles {@link #build} files, ascending, in place of the run from
     * {@link #firstTriangle}; a tree over a few triangles of a large soup then reads only those.
     */
    public int[] filedOnly;

    /** Lower then upper box corner of each triangle, points {@code 2t} and {@code 2t + 1}. */
    private PackedPoints triangleBounds = new PackedPoints(0);

    /** Box of the low side of the last {@link #partition}, lower then upper corner. */
    private final PackedPoints splitLowBox = new PackedPoints(BOX_CORNERS);

    /** Box of the high side of the last {@link #partition}, lower then upper corner. */
    private final PackedPoints splitHighBox = new PackedPoints(BOX_CORNERS);

    /** Box a scanning {@link #addNode} accumulates into, lower then upper corner. */
    private final PackedPoints splitScanBox = new PackedPoints(BOX_CORNERS);

    /** Entries of {@link #triangleOrder} the last {@link #build} filed. */
    private int filedTriangles;

    /**
     * Per node, its box as lower then upper corner, points {@code 2n} and {@code 2n + 1}, so sibling
     * boxes sit side by side.
     */
    private PackedPoints nodeBoxes = new PackedPoints(0);

    /** Per entry of {@link #triangleOrder}, that triangle's three corners, point three times the entry on. */
    private PackedPoints packedCorners = new PackedPoints(0);
    /** Node stack of the top-down build. */
    private int[] pending = new int[0];

    /**
     * Lower then upper box corner of each triangle, points {@code 2t} and {@code 2t + 1}, as
     * {@link #buildColumns} read them.
     */
    private PackedPoints columnBounds = new PackedPoints(0);

    /** Lower corner of the box around every filed triangle. */
    private Vector3f columnLow = new Vector3f();

    /** Reciprocal column width of each axis's grid. */
    private double[] columnStep = new double[0];

    /** Columns across each axis's grid, counted on the axis after it. */
    private int[] columnsAcrossOf = new int[0];

    /** Rows along each axis's grid, counted on the axis two after it. */
    private int[] columnsAlongOf = new int[0];

    /** Offsets into {@link #columnMembers} of each column of each axis's grid. */
    private int[][] columnStart = new int[0][];

    /** Triangles filed in each column of each axis's grid, column by column. */
    private int[][] columnMembers = new int[0][];

    /**
     * A built tree over a mesh's fan triangulation, positions indexed by sparse vertex id.
     *
     * @param mesh           surface to triangulate
     * @param faceOfTriangle receives each triangle's face id; at least the mesh's triangle count long
     * @return the built tree
     */
    public static TriangleBvh over(MeshTopology mesh, int[] faceOfTriangle) {
        TriangleBvh bvh = new TriangleBvh();
        bvh.triangleCorners = mesh.triangulate(TriangulationRule.FAN, faceOfTriangle);
        bvh.triangleCount = mesh.triangleCount();
        bvh.positions = SemanticPatchDecomposer.positionsById(mesh);
        bvh.build();
        return bvh;
    }

    /**
     * The nearest triangle a ray meets before a parameter bound (Moller and Trumbore 1997, "Fast,
     * Minimum Storage Ray/Triangle Intersection", JGT 2(1)); boxes the ray misses, or enters past
     * the best hit so far, are skipped.
     *
     * @param origin       ray origin xyz, its first three entries read
     * @param direction    ray direction xyz, its length the parameter's unit
     * @param maxParameter largest ray parameter a hit may have
     * @param stack        scratch node stack of at least {@link #nodeCount} entries
     * @return the triangle hit, or -1; its ray parameter is left in {@link #hitParameter}
     */
    public int nearestHit(Vector3f origin, Vector3f direction, double maxParameter, int[] stack) {
        return nearestHitAvoiding(origin, direction, maxParameter, null, 0, stack);
    }

    /**
     * {@link #nearestHit}, ignoring every triangle with a corner marked: a ray from a vertex or along
     * an edge passes the faces at its own ends, which it only touches.
     *
     * @param origin       ray origin xyz, its first three entries read
     * @param direction    ray direction xyz, its length the parameter's unit
     * @param maxParameter largest ray parameter a hit may have
     * @param markByCorner mark by position index; null ignores nothing
     * @param mark         the value that marks a corner
     * @param stack        scratch node stack of at least {@link #nodeCount} entries
     * @return the triangle hit, or -1; its ray parameter is left in {@link #hitParameter}
     */
    public int nearestHitAvoiding(Vector3f origin, Vector3f direction, double maxParameter, int[] markByCorner,
            int mark, int[] stack) {
        return nearestHitAvoiding(origin, direction, maxParameter, markByCorner, mark, stack, null);
    }

    /**
     * {@link #nearestHitAvoiding}, also giving the hit's ray parameter to the caller, so threads that
     * share the tree need not read {@link #hitParameter}.
     *
     * @param origin       ray origin xyz, its first three entries read
     * @param direction    ray direction xyz, its length the parameter's unit
     * @param maxParameter largest ray parameter a hit may have
     * @param markByCorner mark by position index; null ignores nothing
     * @param mark         the value that marks a corner
     * @param stack        scratch node stack of at least {@link #nodeCount} entries
     * @param hitOut       receives the hit's parameter, or {@code maxParameter} without one, in its
     *                     first entry; null leaves it only in {@link #hitParameter}
     * @return the triangle hit, or -1
     */
    public int nearestHitAvoiding(Vector3f origin, Vector3f direction, double maxParameter, int[] markByCorner,
            int mark, int[] stack, double[] hitOut) {
        int nearestTriangle = -1;
        double nearest = maxParameter;
        double inverseX = 1.0 / direction.x;
        double inverseY = 1.0 / direction.y;
        double inverseZ = 1.0 / direction.z;
        boolean bounded = !Double.isInfinite(maxParameter);
        double endX = origin.x + direction.x * maxParameter;
        double endY = origin.y + direction.y * maxParameter;
        double endZ = origin.z + direction.z * maxParameter;
        double rayLowX = Math.min(origin.x, endX);
        double rayLowY = Math.min(origin.y, endY);
        double rayLowZ = Math.min(origin.z, endZ);
        double rayHighX = Math.max(origin.x, endX);
        double rayHighY = Math.max(origin.y, endY);
        double rayHighZ = Math.max(origin.z, endZ);
        float originX = origin.x;
        float originY = origin.y;
        float originZ = origin.z;
        int top = 0;
        stack[top++] = 0;
        while (top > 0 && nodeCount > 0) {
            int node = stack[--top];
            double lowX = (nodeMin.get(node, 0) - originX) * inverseX;
            double highX = (nodeMax.get(node, 0) - originX) * inverseX;
            double lowY = (nodeMin.get(node, 1) - originY) * inverseY;
            double highY = (nodeMax.get(node, 1) - originY) * inverseY;
            double lowZ = (nodeMin.get(node, 2) - originZ) * inverseZ;
            double highZ = (nodeMax.get(node, 2) - originZ) * inverseZ;
            double enter = Math.max(MINIMUM_RAY_PARAMETER, Math.max(Math.min(lowX, highX),
                    Math.max(Math.min(lowY, highY), Math.min(lowZ, highZ))));
            double exit = Math.min(nearest, Math.min(Math.max(lowX, highX),
                    Math.min(Math.max(lowY, highY), Math.max(lowZ, highZ))));
            if (enter > exit) {
                continue;
            }
            int left = nodeLeftChild[node];
            if (left >= 0) {
                stack[top++] = left;
                stack[top++] = left + 1;
                continue;
            }
            int end = nodeTriangleStart[node] + nodeTriangleCount[node];
            for (int index = nodeTriangleStart[node]; index < end; index++) {
                int triangle = triangleOrder[index];
                int low = triangle * BOX_CORNERS;
                if (bounded && (triangleBounds.get(low, 0) > rayHighX || triangleBounds.get(low, 1) > rayHighY
                        || triangleBounds.get(low, 2) > rayHighZ
                        || triangleBounds.get(low + 1, 0) < rayLowX
                        || triangleBounds.get(low + 1, 1) < rayLowY
                        || triangleBounds.get(low + 1, 2) < rayLowZ)) {
                    continue;
                }
                if (markByCorner != null && (markByCorner[triangleCorners[triangle * TRIANGLE_CORNERS]] == mark
                        || markByCorner[triangleCorners[triangle * TRIANGLE_CORNERS + 1]] == mark
                        || markByCorner[triangleCorners[triangle * TRIANGLE_CORNERS + 2]] == mark)) {
                    continue;
                }
                double parameter = rayParameter(triangle, origin, direction, 0);
                if (parameter < nearest) {
                    nearest = parameter;
                    nearestTriangle = triangle;
                }
            }
        }
        if (hitOut != null) {
            hitOut[0] = nearest;
        } else {
            hitParameter = nearest;
        }
        return nearestTriangle;
    }

    /**
     * Some triangle a ray meets, the walk stopping at the first; unlike {@link #nearestHit} it
     * writes no field, so threads may share the tree.
     *
     * @param origin    ray origin xyz, its first three entries read
     * @param direction ray direction xyz
     * @param stack     scratch node stack of at least {@link #nodeCount} entries, one per thread
     * @return a triangle hit, or -1 when the ray escapes
     */
    public int anyHit(Vector3f origin, Vector3f direction, int[] stack) {
        return anyHit(origin, direction, Double.POSITIVE_INFINITY, stack);
    }

    /**
     * Some triangle a ray meets before a parameter bound. The nearer child box is walked first,
     * so a ray that is blocked close by stops early.
     *
     * @param origin       ray origin xyz
     * @param direction    ray direction xyz
     * @param maxParameter hits at or past this ray parameter do not count
     * @param stack        scratch node stack of at least {@link #nodeCount} entries
     * @return a triangle hit, or -1
     */
    public int anyHit(Vector3f origin, Vector3f direction, double maxParameter, int[] stack) {
        int hit = ownHit(origin, direction, maxParameter, stack);
        return hit >= 0 || companion == null ? hit
                : companion.anyHit(origin, direction, maxParameter, stack) >= 0 ? Integer.MAX_VALUE : -1;
    }

    /**
     * {@link #anyHit} over this tree's own triangles, leaving out the companion.
     *
     * @param origin       ray origin xyz
     * @param direction    ray direction xyz
     * @param maxParameter hits at or past this ray parameter do not count
     * @param stack        scratch node stack of at least {@link #nodeCount} entries
     * @return a triangle hit, or -1
     */
    private int ownHit(Vector3f origin, Vector3f direction, double maxParameter, int[] stack) {
        if (nodeCount == 0) {
            return -1;
        }
        Vector3d inverse = new Vector3d(1.0 / direction.x, 1.0 / direction.y, 1.0 / direction.z);
        int top = 0;
        if (boxEnter(0, origin, inverse, maxParameter) < maxParameter) {
            stack[top++] = 0;
        }
        while (top > 0) {
            int node = stack[--top];
            int left = nodeLeftChild[node];
            if (left >= 0) {
                double leftEnter = boxEnter(left, origin, inverse, maxParameter);
                double rightEnter = boxEnter(left + 1, origin, inverse, maxParameter);
                int near = leftEnter <= rightEnter ? left : left + 1;
                double nearEnter = Math.min(leftEnter, rightEnter);
                double farEnter = Math.max(leftEnter, rightEnter);
                if (farEnter < maxParameter) {
                    stack[top++] = near == left ? left + 1 : left;
                }
                if (nearEnter < maxParameter) {
                    stack[top++] = near;
                }
                continue;
            }
            int end = nodeTriangleStart[node] + nodeTriangleCount[node];
            for (int index = nodeTriangleStart[node]; index < end; index++) {
                int triangle = triangleOrder[index];
                if ((retired == null || !retired[triangle])
                        && filedRayParameter(index, origin, direction, EDGE_SLACK) < maxParameter) {
                    return triangle;
                }
            }
        }
        return -1;
    }

    /**
     * The winding number of a ray's origin, each crossing past {@link #MINIMUM_RAY_PARAMETER}
     * counting +1 out through a front and -1 out through a back; on a closed surface, nonzero means
     * no ray from there escapes.
     *
     * @param origin    ray origin xyz
     * @param direction ray direction xyz
     * @param stack     scratch node stack of at least {@link #nodeCount} entries
     * @return the signed crossing count
     */
    public int windingAlong(Vector3f origin, Vector3f direction, int[] stack) {
        return windingAlong(origin, direction, stack, 0);
    }

    /**
     * {@link #windingAlong}, or zero when a crossing lies nearer than a margin.
     *
     * @param origin    ray origin xyz
     * @param direction ray direction xyz
     * @param stack     scratch node stack of at least {@link #nodeCount} entries
     * @param margin    ray parameter below which a crossing voids the count
     * @return the signed crossing count, or zero
     */
    private int windingAlong(Vector3f origin, Vector3f direction, int[] stack, double margin) {
        int own = ownWinding(origin, direction, stack, margin);
        if (companion == null || own == Integer.MIN_VALUE) {
            return own == Integer.MIN_VALUE ? 0 : own;
        }
        int other = companion.ownWinding(origin, direction, stack, margin);
        return other == Integer.MIN_VALUE ? 0 : own + other;
    }

    /**
     * {@link #windingAlong} over this tree's own triangles.
     *
     * @param origin    ray origin xyz
     * @param direction ray direction xyz
     * @param stack     scratch node stack of at least {@link #nodeCount} entries
     * @param margin    ray parameter below which a crossing voids the count
     * @return the signed crossing count, or {@link Integer#MIN_VALUE} when voided
     */
    private int ownWinding(Vector3f origin, Vector3f direction, int[] stack, double margin) {
        if (nodeCount == 0) {
            return 0;
        }
        Vector3d inverse = new Vector3d(1.0 / direction.x, 1.0 / direction.y, 1.0 / direction.z);
        double unbounded = Double.POSITIVE_INFINITY;
        int winding = 0;
        int top = 0;
        if (boxEnter(0, origin, inverse, unbounded) < unbounded) {
            stack[top++] = 0;
        }
        while (top > 0) {
            int node = stack[--top];
            int left = nodeLeftChild[node];
            if (left >= 0) {
                for (int child = left; child <= left + 1; child++) {
                    if (boxEnter(child, origin, inverse, unbounded) < unbounded) {
                        stack[top++] = child;
                    }
                }
                continue;
            }
            int end = nodeTriangleStart[node] + nodeTriangleCount[node];
            for (int index = nodeTriangleStart[node]; index < end; index++) {
                int triangle = triangleOrder[index];
                if (retired != null && retired[triangle]) {
                    continue;
                }
                double parameter = filedRayParameter(index, origin, direction, 0);
                if (parameter < margin) {
                    return Integer.MIN_VALUE;
                }
                if (parameter < unbounded) {
                    int corner = triangle * TRIANGLE_CORNERS;
                    winding += TriangleGeometry.facesAlong(positions, triangleCorners[corner],
                            triangleCorners[corner + 1], triangleCorners[corner + 2], direction) ? 1 : -1;
                }
            }
        }
        return winding;
    }

    /**
     * The winding number behind a point, counted along an axis and along a ray slanted off it;
     * zero unless both agree and neither meets a sheet within {@link #WINDING_MARGIN}, where rays
     * at other slants could pass on the sheet's other side of {@link #MINIMUM_RAY_PARAMETER}.
     *
     * @param origin ray origin xyz
     * @param axis   unit axis xyz
     * @param stack  scratch node stack of at least {@link #nodeCount} entries
     * @return the agreed count, or zero
     */
    public int agreedWinding(Vector3f origin, Vector3f axis, int[] stack) {
        Vector3f slant = hemisphereDirection(MeshTopology.tangentFrame(axis), 1);
        int alongAxis = windingAlong(origin, axis, stack, WINDING_MARGIN);
        return alongAxis != 0 && alongAxis == windingAlong(origin, slant, stack, WINDING_MARGIN)
                ? alongAxis : 0;
    }

    /**
     * Whether either ray {@link #agreedWinding} counts along meets a triangle of this tree.
     *
     * @param origin ray origin xyz
     * @param axis   unit axis xyz
     * @param stack  scratch node stack of at least {@link #nodeCount} entries
     * @return true when one of them hits
     */
    public boolean meetsWindingRays(Vector3f origin, Vector3f axis, int[] stack) {
        Vector3f slant = hemisphereDirection(MeshTopology.tangentFrame(axis), 1);
        return anyHit(origin, axis, stack) >= 0 || anyHit(origin, slant, stack) >= 0;
    }

    /**
     * Ray parameter at which a ray enters a node's box.
     *
     * @param node         node whose box is tested
     * @param origin       ray origin xyz
     * @param inverse      reciprocals of the direction's components
     * @param maxParameter bound past which the box does not count
     * @return the entry parameter, or positive infinity when the ray misses the box in range
     */
    private double boxEnter(int node, Vector3f origin, Vector3d inverse, double maxParameter) {
        return TriangleGeometry.boxEntry(nodeBoxes, node * BOX_CORNERS, origin, inverse, MINIMUM_RAY_PARAMETER,
                maxParameter);
    }

    /**
     * Whether some ray from a point into the hemisphere round an axis escapes: the axis itself,
     * then {@link #SLANT_AZIMUTHS} rays on each cone of {@link #SLANT_CONE_TILT_DEGREES}, each cone
     * turned half a step from the last. Writes no field, so threads may share the tree.
     *
     * @param origin ray origin xyz
     * @param axis   unit hemisphere axis xyz
     * @param stack  scratch node stack of at least {@link #nodeCount} entries, one per thread
     * @return true when one of the rays meets no triangle
     */
    public boolean hemisphereEscapes(Vector3f origin, Vector3f axis, int[] stack) {
        Matrix3f frame = MeshTopology.tangentFrame(axis);
        Vector3f direction = new Vector3f();
        for (int ray = 0; ray < HEMISPHERE_RAYS; ray++) {
            direction.set(hemisphereComponent(frame, ray, 0), hemisphereComponent(frame, ray, 1),
                    hemisphereComponent(frame, ray, 2));
            if (anyHit(origin, direction, stack) < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link #hemisphereEscapes}, noting which triangle stops each ray when none escapes. Triangles
     * joining later cannot unblock a ray, so the answer stands while every noted triangle stays.
     * A companion's hit is noted by its own triangle index.
     *
     * @param origin   ray origin xyz
     * @param axis     unit hemisphere axis xyz
     * @param stack    scratch node stack of at least {@link #nodeCount} entries, one per thread
     * @param blockers receives {@link #HEMISPHERE_RAYS} triangles when no ray escapes
     * @return true when one of the rays meets no triangle
     */
    public boolean hemisphereEscapes(Vector3f origin, Vector3f axis, int[] stack, int[] blockers) {
        return hemisphereEscapes(origin, axis, stack, blockers, blockers, 0);
    }

    /**
     * {@link #hemisphereEscapes(Vector3f, Vector3f, int[], int[])} trying, before each ray walks the
     * tree, a few triangles likely to stop it, such as the ones round the origin: a hit is a hit.
     *
     * @param origin      ray origin xyz
     * @param axis        unit hemisphere axis xyz
     * @param stack       scratch node stack of at least {@link #nodeCount} entries, one per thread
     * @param blockers    receives {@link #HEMISPHERE_RAYS} triangles when no ray escapes
     * @param nearby      triangles to try first, as this method notes them
     * @param nearbyCount how many of them
     * @return true when one of the rays meets no triangle
     */
    public boolean hemisphereEscapes(Vector3f origin, Vector3f axis, int[] stack, int[] blockers, int[] nearby,
            int nearbyCount) {
        Matrix3f frame = MeshTopology.tangentFrame(axis);
        Vector3f direction = new Vector3f();
        rays:
        for (int ray = 0; ray < HEMISPHERE_RAYS; ray++) {
            direction.set(hemisphereComponent(frame, ray, 0), hemisphereComponent(frame, ray, 1),
                    hemisphereComponent(frame, ray, 2));
            for (int index = 0; index < nearbyCount; index++) {
                if (blocks(nearby[index], origin, direction)) {
                    blockers[ray] = nearby[index];
                    continue rays;
                }
            }
            int hit = ownHit(origin, direction, Double.POSITIVE_INFINITY, stack);
            if (hit < 0 && companion != null) {
                hit = companion.anyHit(origin, direction, stack);
            }
            if (hit < 0) {
                return true;
            }
            blockers[ray] = hit;
        }
        return false;
    }

    /**
     * Whether a triangle, as {@link #hemisphereEscapes} notes them, stops a ray: one of this tree's
     * own that is filed and not retired, or one of its companion's, tested as a walk would test it.
     *
     * @param triangle  a noted triangle
     * @param origin    ray origin xyz
     * @param direction ray direction xyz
     * @return true when the ray meets it
     */
    private boolean blocks(int triangle, Vector3f origin, Vector3f direction) {
        if (triangle < 0) {
            return false;
        }
        if (triangle < triangleCount && (triangleGroup == null || triangleGroup[triangle] >= 0)
                && (retired == null || !retired[triangle])) {
            return rayParameter(triangle, origin, direction, EDGE_SLACK) < Double.POSITIVE_INFINITY;
        }
        return triangle >= triangleCount && companion != null && triangle < companion.triangleCount
                && companion.rayParameter(triangle, origin, direction, EDGE_SLACK) < Double.POSITIVE_INFINITY;
    }

    /**
     * One of the hemisphere's rays: ray 0 is the axis, then {@link #SLANT_AZIMUTHS} per cone of
     * {@link #SLANT_CONE_TILT_DEGREES}, each cone turned half a step from the last.
     *
     * @param frame the axis's {@link MeshTopology#tangentFrame(Vector3f)}
     * @param ray   ray index below {@link #HEMISPHERE_RAYS}
     * @return a new unit direction
     */
    private static Vector3f hemisphereDirection(Matrix3f frame, int ray) {
        return new Vector3f(hemisphereComponent(frame, ray, 0), hemisphereComponent(frame, ray, 1),
                hemisphereComponent(frame, ray, 2));
    }

    /**
     * One component of {@link #hemisphereDirection}, so the per-ray loops of
     * {@link #hemisphereEscapes} refill one scratch vector instead of allocating a ray each.
     *
     * @param frame     the axis's {@link MeshTopology#tangentFrame(Vector3f)}
     * @param ray       ray index below {@link #HEMISPHERE_RAYS}
     * @param component 0, 1 or 2 for x, y or z
     * @return that component of the unit direction
     */
    private static float hemisphereComponent(Matrix3f frame, int ray, int component) {
        if (ray == 0) {
            return frame.get(2, component);
        }
        double[] trig = RAY_TRIG[ray];
        return (float) (frame.get(2, component) * trig[0] + trig[1]
                * (frame.get(1, component) * trig[2] - frame.get(0, component) * trig[3]));
    }

    /**
     * Cosine and sine of each hemisphere ray's tilt, then of its turn round the axis.
     *
     * @return four values per ray, ray 0 unused
     */
    private static double[][] rayTrig() {
        double[][] trig = new double[HEMISPHERE_RAYS][];
        for (int ray = 1; ray < HEMISPHERE_RAYS; ray++) {
            int cone = (ray - 1) / SLANT_AZIMUTHS;
            double tilt = Math.toRadians(SLANT_CONE_TILT_DEGREES[cone]);
            double turn = 2 * Math.PI * ((ray - 1) % SLANT_AZIMUTHS + 0.5 * cone) / SLANT_AZIMUTHS;
            trig[ray] = new double[] {Math.cos(tilt), Math.sin(tilt), Math.cos(turn), Math.sin(turn)};
        }
        return trig;
    }

    /**
     * {@link TriangleGeometry#rayParameter} for one triangle, past {@link #MINIMUM_RAY_PARAMETER}.
     *
     * @param triangle  triangle to test
     * @param origin    ray origin xyz
     * @param direction ray direction xyz
     * @param slack     how far outside the triangle, in barycentric units, still counts
     * @return the ray parameter of the crossing, or positive infinity
     */
    private double rayParameter(int triangle, Vector3f origin, Vector3f direction, double slack) {
        int corner = triangle * TRIANGLE_CORNERS;
        return TriangleGeometry.rayParameter(positions, triangleCorners[corner], triangleCorners[corner + 1],
                triangleCorners[corner + 2], origin, direction, slack, MINIMUM_RAY_PARAMETER);
    }

    /**
     * {@link #rayParameter} for the triangle at one entry of {@link #triangleOrder}, read from its
     * packed corners: the same floats, the same arithmetic.
     *
     * @param index     entry of {@link #triangleOrder}
     * @param origin    ray origin xyz
     * @param direction ray direction xyz
     * @param slack     how far outside the triangle, in barycentric units, still counts
     * @return the ray parameter of the crossing, or positive infinity
     */
    private double filedRayParameter(int index, Vector3f origin, Vector3f direction, double slack) {
        int corner = index * TRIANGLE_CORNERS;
        return TriangleGeometry.rayParameter(packedCorners, corner, corner + 1, corner + 2, origin, direction,
                slack, MINIMUM_RAY_PARAMETER);
    }

    /**
     * Builds the tree over the triangles the soup offers, splitting each node at the midpoint of
     * its widest extent. Triangles in a negative group are left out.
     */
    public void build() {
        buildNodes();
        pack();
    }

    /** Lays out the boxes and corners the ray walks read, from the tree and {@link #positions}. */
    private void pack() {
        nodeBoxes = new PackedPoints(nodeCount * BOX_CORNERS);
        int filed = filedTriangles;
        boolean parallel = filed >= PARALLEL_BUILD_TRIANGLES;
        IntStream nodes = IntStream.range(0, nodeCount);
        (parallel ? nodes.parallel() : nodes).forEach(node -> {
            nodeBoxes.set(node * BOX_CORNERS, nodeMin.get(node, 0), nodeMin.get(node, 1), nodeMin.get(node, 2));
            nodeBoxes.set(node * BOX_CORNERS + 1, nodeMax.get(node, 0), nodeMax.get(node, 1),
                    nodeMax.get(node, 2));
        });
        IntStream slots = IntStream.range(0, filed * TRIANGLE_CORNERS).map(slot ->
                triangleCorners[triangleOrder[slot / TRIANGLE_CORNERS] * TRIANGLE_CORNERS + slot % TRIANGLE_CORNERS]);
        packedCorners = positions.gather((parallel ? slots.parallel() : slots).toArray());
    }

    /** The tree build over the triangles; see {@link #build}. */
    private void buildNodes() {
        if (triangleBounds.size() < triangleCount * BOX_CORNERS) {
            triangleBounds = new PackedPoints(triangleCount * BOX_CORNERS);
            triangleOrder = new int[triangleCount];
        }
        int inTree = 0;
        int listed = filedOnly == null ? triangleCount - firstTriangle : filedOnly.length;
        for (int entry = 0; entry < listed; entry++) {
            int triangle = filedOnly == null ? firstTriangle + entry : filedOnly[entry];
            if (triangleGroup != null && triangleGroup[triangle] < 0) {
                continue;
            }
            triangleOrder[inTree++] = triangle;
            boundTriangle(triangle);
        }
        filedTriangles = inTree;
        nodeCount = 0;
        nodeMin = new PackedPoints(INITIAL_NODES);
        nodeMax = new PackedPoints(INITIAL_NODES);
        nodeLeftChild = new int[INITIAL_NODES];
        nodeTriangleStart = new int[INITIAL_NODES];
        nodeTriangleCount = new int[INITIAL_NODES];
        pending = new int[INITIAL_NODES];
        if (inTree == 0) {
            return;
        }
        int root = addNode(0, inTree);
        if (!parallelBuild || inTree < PARALLEL_BUILD_TRIANGLES) {
            splitBelow(root, 0);
            return;
        }
        int[] frontier = splitBelow(root, Math.max(leafTriangles, inTree / BUILD_SUBTREES));
        TriangleBvh[] parts = new TriangleBvh[frontier.length];
        IntStream.range(0, frontier.length).parallel().forEach(index -> {
            int node = frontier[index];
            TriangleBvh part = new TriangleBvh();
            part.triangleBounds = triangleBounds;
            part.triangleOrder = triangleOrder;
            part.leafTriangles = leafTriangles;
            part.nodeMin = new PackedPoints(INITIAL_NODES);
            part.nodeMax = new PackedPoints(INITIAL_NODES);
            part.nodeLeftChild = new int[INITIAL_NODES];
            part.nodeTriangleStart = new int[INITIAL_NODES];
            part.nodeTriangleCount = new int[INITIAL_NODES];
            part.pending = new int[INITIAL_NODES];
            PackedPoints box = new PackedPoints(BOX_CORNERS);
            box.set(0, nodeMin.at(node));
            box.set(1, nodeMax.at(node));
            part.splitBelow(part.addNode(nodeTriangleStart[node], nodeTriangleCount[node], box), 0);
            parts[index] = part;
        });
        for (int index = 0; index < frontier.length; index++) {
            graft(frontier[index], parts[index]);
        }
    }

    /**
     * Copies a separately built subtree into this tree: its root over an existing node, the rest
     * appended with their child indices moved along.
     *
     * @param node this tree's node the subtree's root replaces
     * @param part the subtree
     */
    private void graft(int node, TriangleBvh part) {
        int offset = nodeCount - 1;
        int needed = nodeCount + part.nodeCount - 1;
        if (needed > nodeLeftChild.length) {
            int capacity = Math.max(needed, nodeLeftChild.length * GROWTH);
            nodeMin.resize(capacity);
            nodeMax.resize(capacity);
            nodeLeftChild = Arrays.copyOf(nodeLeftChild, capacity);
            nodeTriangleStart = Arrays.copyOf(nodeTriangleStart, capacity);
            nodeTriangleCount = Arrays.copyOf(nodeTriangleCount, capacity);
        }
        for (int local = 0; local < part.nodeCount; local++) {
            int target = local == 0 ? node : offset + local;
            int child = part.nodeLeftChild[local];
            nodeLeftChild[target] = child < 0 ? -1 : offset + child;
            nodeTriangleStart[target] = part.nodeTriangleStart[local];
            nodeTriangleCount[target] = part.nodeTriangleCount[local];
            nodeMin.set(target, part.nodeMin.at(local));
            nodeMax.set(target, part.nodeMax.at(local));
        }
        nodeCount = needed;
    }

    /**
     * Splits a node and its descendants until every leaf holds at most {@link #leafTriangles}
     * triangles, leaving unsplit any node of at most {@code stopAt} triangles.
     *
     * @param root   the node to start from
     * @param stopAt nodes this small are left whole; zero splits all the way down
     * @return the nodes left whole that still hold more than a leaf's worth
     */
    private int[] splitBelow(int root, int stopAt) {
        int[] frontier = new int[0];
        int frontierCount = 0;
        int top = 0;
        pending[top++] = root;
        while (top > 0) {
            int node = pending[--top];
            int start = nodeTriangleStart[node];
            int count = nodeTriangleCount[node];
            if (count <= leafTriangles) {
                continue;
            }
            if (count <= stopAt) {
                if (frontierCount == frontier.length) {
                    frontier = Arrays.copyOf(frontier, Math.max(CHILDREN, frontier.length * GROWTH));
                }
                frontier[frontierCount++] = node;
                continue;
            }
            int middle = partition(node, start, count);
            int left;
            if (middle == start || middle == start + count) {
                middle = start + count / CHILDREN;
                left = addNode(start, middle - start);
                addNode(middle, start + count - middle);
            } else {
                left = addNode(start, middle - start, splitLowBox);
                addNode(middle, start + count - middle, splitHighBox);
            }
            nodeLeftChild[node] = left;
            nodeTriangleCount[node] = 0;
            if (top + CHILDREN > pending.length) {
                pending = Arrays.copyOf(pending, pending.length * GROWTH);
            }
            pending[top++] = left;
            pending[top++] = left + 1;
        }
        return Arrays.copyOf(frontier, frontierCount);
    }

    /**
     * Refits the built tree's boxes to moved {@link #positions}, keeping its shape: cheaper than
     * {@link #build()} and as exact, though a tree whose triangles moved far gets slower to query.
     */
    public void refit() {
        for (int node = nodeCount - 1; node >= 0; node--) {
            int left = nodeLeftChild[node];
            splitScanBox.set(0, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
            splitScanBox.set(1, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
            int end = nodeTriangleStart[node] + nodeTriangleCount[node];
            for (int index = nodeTriangleStart[node]; left < 0 && index < end; index++) {
                boundTriangle(triangleOrder[index]);
                growBox(splitScanBox, triangleOrder[index]);
            }
            for (int child = left; left >= 0 && child < left + CHILDREN; child++) {
                splitScanBox.set(0, Math.min(splitScanBox.get(0, 0), nodeMin.get(child, 0)),
                        Math.min(splitScanBox.get(0, 1), nodeMin.get(child, 1)),
                        Math.min(splitScanBox.get(0, 2), nodeMin.get(child, 2)));
                splitScanBox.set(1, Math.max(splitScanBox.get(1, 0), nodeMax.get(child, 0)),
                        Math.max(splitScanBox.get(1, 1), nodeMax.get(child, 1)),
                        Math.max(splitScanBox.get(1, 2), nodeMax.get(child, 2)));
            }
            nodeMin.set(node, splitScanBox.at(0));
            nodeMax.set(node, splitScanBox.at(1));
        }
        pack();
    }

    /**
     * Files every triangle of the soup into a grid of columns along each axis, about an edge wide,
     * in every column its box overlaps, for {@link #facing} to read; the tree is not needed.
     */
    public void buildColumns() {
        columnBounds = TriangleGeometry.boxes(positions, Arrays.copyOf(triangleCorners, triangleCount * TRIANGLE_CORNERS));
        int[] included = IntStream.range(0, triangleCount)
                .filter(triangle -> triangleGroup == null || triangleGroup[triangle] >= 0).toArray();
        PackedPoints extent = columnBounds.bounds(IntStream.of(included)
                .flatMap(triangle -> IntStream.of(triangle * BOX_CORNERS, triangle * BOX_CORNERS + 1)).toArray());
        columnLow = extent.at(0);
        Vector3f high = extent.at(1);
        double edgeLengthSum = IntStream.of(included).mapToDouble(triangle -> Math.sqrt(positions.distanceSquared(
                triangleCorners[triangle * TRIANGLE_CORNERS], positions.at(triangleCorners[triangle * TRIANGLE_CORNERS + 1]))))
                .sum();
        double cell = Math.max(edgeLengthSum / Math.max(1, included.length), Float.MIN_NORMAL);
        columnStep = new double[TriangleGeometry.COMPONENTS];
        columnsAlongOf = new int[TriangleGeometry.COMPONENTS];
        columnsAcrossOf = new int[TriangleGeometry.COMPONENTS];
        columnStart = new int[TriangleGeometry.COMPONENTS][];
        columnMembers = new int[TriangleGeometry.COMPONENTS][];
        IntStream.range(0, TriangleGeometry.COMPONENTS).parallel()
                .forEach(axis -> fileAxisColumns(axis, cell, high));
    }

    /**
     * Lays one axis's column grid over the boxes {@link #buildColumns} read and files every
     * triangle in the columns its box covers.
     *
     * @param axis the grid's axis
     * @param cell smallest column width
     * @param high upper corner of the box round every filed triangle
     */
    private void fileAxisColumns(int axis, double cell, Vector3f high) {
        int across = (axis + 1) % TriangleGeometry.COMPONENTS;
        int along = (axis + 2) % TriangleGeometry.COMPONENTS;
        double step = Math.max(cell, Math.sqrt((double) (high.get(across) - columnLow.get(across))
                * (high.get(along) - columnLow.get(along)) / MAX_COLUMNS));
        double perStep = 1 / step;
        int columnsAcross = Math.max(1,
                (int) Math.ceil((high.get(across) - columnLow.get(across)) * perStep));
        int columnsAlong = Math.max(1,
                (int) Math.ceil((high.get(along) - columnLow.get(along)) * perStep));
        int columns = columnsAcross * columnsAlong;
        columnStep[axis] = perStep;
        columnsAcrossOf[axis] = columnsAcross;
        columnsAlongOf[axis] = columnsAlong;
        int[] start = new int[columns + 1];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            if (triangleGroup != null && triangleGroup[triangle] < 0) {
                continue;
            }
            int low = triangle * BOX_CORNERS;
            int firstColumn = columnOf(columnBounds.get(low, across), across, axis);
            int lastColumn = columnOf(columnBounds.get(low + 1, across), across, axis);
            int firstRow = columnOf(columnBounds.get(low, along), along, axis);
            int lastRow = columnOf(columnBounds.get(low + 1, along), along, axis);
            for (int column = firstColumn; column <= lastColumn; column++) {
                for (int row = firstRow; row <= lastRow; row++) {
                    start[column * columnsAlong + row + 1]++;
                }
            }
        }
        for (int column = 0; column < columns; column++) {
            start[column + 1] += start[column];
        }
        int[] cursor = Arrays.copyOf(start, columns);
        int[] members = new int[start[columns]];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            if (triangleGroup != null && triangleGroup[triangle] < 0) {
                continue;
            }
            int low = triangle * BOX_CORNERS;
            int firstColumn = columnOf(columnBounds.get(low, across), across, axis);
            int lastColumn = columnOf(columnBounds.get(low + 1, across), across, axis);
            int firstRow = columnOf(columnBounds.get(low, along), along, axis);
            int lastRow = columnOf(columnBounds.get(low + 1, along), along, axis);
            for (int column = firstColumn; column <= lastColumn; column++) {
                for (int row = firstRow; row <= lastRow; row++) {
                    members[cursor[column * columnsAlong + row]++] = triangle;
                }
            }
        }
        columnStart[axis] = start;
        columnMembers[axis] = members;
    }

    /**
     * Which way each asked triangle faces the enclosed volume, by the parity of the soup crossed on
     * each side of its centre along its normal's main axis, reading the columns
     * {@link #buildColumns} filed.
     *
     * @param asked which triangles to classify, or null for all of them
     * @return per triangle {@link #FACING_OUT_OF_VOLUME}, {@link #FACING_INTO_VOLUME}, or
     *         {@link #FACING_UNDECIDED} when both sides are inside or both outside, as on a flap or
     *         a face another sheet shadows, and for a triangle not asked or in a negative group; a
     *         triangle's own group is never counted, so a polygon's fan triangles should share one
     */
    public int[] facing(boolean[] asked) {
        int[] facing = new int[triangleCount];
        exposedSide = new int[triangleCount];
        crowded = crowdMargin > 0 ? new boolean[triangleCount] : null;
        lineCells = recordLineCells ? new int[triangleCount] : null;
        IntStream.range(0, (triangleCount + FACING_TASK - 1) / FACING_TASK).parallel()
                .forEach(task -> faceRange(asked, facing, task * FACING_TASK,
                        Math.min(triangleCount, (task + 1) * FACING_TASK)));
        return facing;
    }

    /**
     * The line {@link #facing} reads for a triangle: through its centre along its normal's main
     * axis: {@link TriangleGeometry#centre} and {@link TriangleGeometry#cross}, as MeshRepair reads
     * the same line.
     *
     * @param triangle the triangle
     * @param centre   receives its centre
     * @param normal   receives its unnormalised normal
     * @return the line's axis
     */
    private int lineThrough(int triangle, Vector3d centre, Vector3d normal) {
        int corner = triangle * TRIANGLE_CORNERS;
        int cornerA = triangleCorners[corner];
        int cornerB = triangleCorners[corner + 1];
        int cornerC = triangleCorners[corner + 2];
        normal.set(TriangleGeometry.cross(positions, cornerA, cornerB, cornerC));
        centre.set(TriangleGeometry.centre(positions, cornerA, cornerB, cornerC));
        return normal.maxComponent();
    }

    /**
     * {@link #facing} over one run of triangles.
     *
     * @param asked  which triangles to classify, or null for all
     * @param facing per triangle, written for the run
     * @param first  first triangle of the run
     * @param end    one past its last triangle
     */
    private void faceRange(boolean[] asked, int[] facing, int first, int end) {
        Vector3d centre = new Vector3d();
        Vector3d normal = new Vector3d();
        for (int triangle = first; triangle < end; triangle++) {
            if (triangleGroup != null && triangleGroup[triangle] < 0
                    || asked != null && !asked[triangle]) {
                continue;
            }
            int axis = lineThrough(triangle, centre, normal);
            int across = (axis + 1) % TriangleGeometry.COMPONENTS;
            int along = (axis + 2) % TriangleGeometry.COMPONENTS;
            int column = columnOf(centre.get(across), across, axis) * columnsAlongOf[axis]
                    + columnOf(centre.get(along), along, axis);
            if (lineCells != null) {
                lineCells[triangle] = column * TRIANGLE_CORNERS + axis;
            }
            double below = columnLow.get(axis) - 1;
            int group = triangleGroup == null ? -1 : triangleGroup[triangle];
            int ahead = 0;
            int line = 0;
            int capAhead = 0;
            int capLine = 0;
            for (int member = columnStart[axis][column]; member < columnStart[axis][column + 1];
                    member++) {
                int other = columnMembers[axis][member];
                if (!TriangleGeometry.boxCovers(columnBounds, other * BOX_CORNERS, centre, across, along)
                        || triangleGroup != null && triangleGroup[other] == group) {
                    continue;
                }
                int otherA = triangleCorners[other * TRIANGLE_CORNERS];
                int otherB = triangleCorners[other * TRIANGLE_CORNERS + 1];
                int otherC = triangleCorners[other * TRIANGLE_CORNERS + 2];
                int crosses = TriangleGeometry.positiveAxisRaysCross(axis, centre, below, positions, otherA,
                        otherB, otherC, TriangleGeometry.positiveAxisInverseDeterminant(axis, positions, otherA,
                                otherB, otherC));
                int crossesAhead = crosses & TriangleGeometry.CROSSES_FROM_ORIGIN;
                int crossesLine = (crosses & TriangleGeometry.CROSSES_FROM_BEHIND) == 0 ? 0 : 1;
                if (crowded != null && crossesLine == 1 && other < firstExposureOnly
                        && axialGap(axis, centre, positions, otherA, otherB, otherC) < crowdMargin) {
                    crowded[triangle] = true;
                }
                if (other < firstExposureOnly) {
                    ahead += crossesAhead;
                    line += crossesLine;
                } else {
                    capAhead += crossesAhead;
                    capLine += crossesLine;
                }
            }
            facing[triangle] = line % 2 == 0 ? FACING_UNDECIDED
                    : (ahead % 2 == 0) == (normal.get(axis) > 0) ? FACING_OUT_OF_VOLUME
                            : FACING_INTO_VOLUME;
            int seen = line + capLine;
            int front = normal.get(axis) > 0 ? ahead + capAhead : seen - ahead - capAhead;
            exposedSide[triangle] = seen % 2 == 0 || (front == 0) == (seen == front)
                    ? FACING_UNDECIDED
                    : front == 0 ? FACING_OUT_OF_VOLUME : FACING_INTO_VOLUME;
        }
    }

    /**
     * How far along an axis line through a point another triangle's plane crosses it.
     *
     * @param axis    the line's axis
     * @param centre  a point on the line
     * @param at      points by corner index
     * @param cornerA first corner of the other triangle
     * @param cornerB second corner
     * @param cornerC third corner
     * @return the distance, infinite when the plane runs along the line
     */
    private static double axialGap(int axis, Vector3d centre, PackedPoints at, int cornerA, int cornerB,
            int cornerC) {
        double offset = 0;
        double normalOnAxis = 0;
        for (int component = 0; component < TriangleGeometry.COMPONENTS; component++) {
            int next = (component + 1) % TriangleGeometry.COMPONENTS;
            int after = (component + 2) % TriangleGeometry.COMPONENTS;
            double normal = (at.get(cornerB, next) - (double) at.get(cornerA, next))
                    * (at.get(cornerC, after) - (double) at.get(cornerA, after))
                    - (at.get(cornerB, after) - (double) at.get(cornerA, after))
                    * (at.get(cornerC, next) - (double) at.get(cornerA, next));
            offset += normal * (at.get(cornerA, component) - centre.get(component));
            normalOnAxis = component == axis ? normal : normalOnAxis;
        }
        return normalOnAxis == 0 ? Double.POSITIVE_INFINITY : Math.abs(offset / normalOnAxis);
    }

    /**
     * Column or row of one coordinate in an axis's grid, clamped to the grid.
     *
     * @param value coordinate
     * @param component which coordinate it is
     * @param axis the grid's axis
     * @return the column index across, or the row index along, the axis
     */
    /**
     * The cells, column times three plus axis, of the grids {@link #buildColumns} laid that the
     * boxes of loose triangles overlap, so a line along an axis in an unlisted cell meets none of
     * them. Listed rather than flagged, so the cost follows the triangles, not the grid.
     *
     * @param corners three points per triangle, corner by corner
     * @return the cells, ascending, each once
     */
    public int[] cellsUnder(PackedPoints corners) {
        int[] cells = new int[TRIANGLE_CORNERS * TriangleGeometry.COMPONENTS];
        int count = 0;
        PackedPoints boxes = TriangleGeometry.boxes(corners, IntStream.range(0, corners.size()).toArray());
        for (int axis = 0; axis < TriangleGeometry.COMPONENTS; axis++) {
            int across = (axis + 1) % TriangleGeometry.COMPONENTS;
            int along = (axis + 2) % TriangleGeometry.COMPONENTS;
            for (int low = 0; low < boxes.size(); low += BOX_CORNERS) {
                int lastAlong = clampedColumn(boxes.get(low + 1, along), along, axis);
                for (int row = clampedColumn(boxes.get(low, across), across, axis);
                        row <= clampedColumn(boxes.get(low + 1, across), across, axis); row++) {
                    for (int column = clampedColumn(boxes.get(low, along), along, axis); column <= lastAlong;
                            column++) {
                        if (count == cells.length) {
                            cells = Arrays.copyOf(cells, count * GROWTH);
                        }
                        cells[count++] = (row * columnsAlongOf[axis] + column) * TRIANGLE_CORNERS + axis;
                    }
                }
            }
        }
        return Arrays.stream(cells, 0, count).sorted().distinct().toArray();
    }

    /**
     * The column of an axis's grid that a line along the axis through a point runs in.
     *
     * @param axis  the line's axis
     * @param point a point on the line
     * @return the column index
     */
    public int columnAt(int axis, Vector3f point) {
        int across = (axis + 1) % TriangleGeometry.COMPONENTS;
        int along = (axis + 2) % TriangleGeometry.COMPONENTS;
        return clampedColumn(point.get(across), across, axis) * columnsAlongOf[axis]
                + clampedColumn(point.get(along), along, axis);
    }

    /**
     * Counts the counted triangles a ray from a point along one positive axis crosses, reading
     * only the point's column of the grid {@link #buildColumns} filed.
     *
     * @param axis      0, 1 or 2 for the {@code +x}, {@code +y} or {@code +z} ray
     * @param origin    the ray's start
     * @param labels    per triangle below {@link #firstExposureOnly}, a label; negative ones are
     *                  skipped
     * @param skipLabel triangles with this label are skipped too
     * @return the number of crossings
     */
    public int columnRayCrossings(int axis, Vector3d origin, int[] labels, int skipLabel) {
        int across = (axis + 1) % TriangleGeometry.COMPONENTS;
        int along = (axis + 2) % TriangleGeometry.COMPONENTS;
        int column = clampedColumn(origin.get(across), across, axis) * columnsAlongOf[axis]
                + clampedColumn(origin.get(along), along, axis);
        int crossings = 0;
        for (int member = columnStart[axis][column]; member < columnStart[axis][column + 1]; member++) {
            int triangle = columnMembers[axis][member];
            if (triangle >= firstExposureOnly || labels[triangle] < 0 || labels[triangle] == skipLabel) {
                continue;
            }
            if (TriangleGeometry.positiveAxisRayCrosses(axis, origin, positions,
                    triangleCorners[triangle * TRIANGLE_CORNERS], triangleCorners[triangle * TRIANGLE_CORNERS + 1],
                    triangleCorners[triangle * TRIANGLE_CORNERS + 2])) {
                crossings++;
            }
        }
        return crossings;
    }

    /**
     * {@link #columnOf} kept inside the grid.
     *
     * @param value     coordinate on the component
     * @param component across or along component of the axis's grid
     * @param axis      the grid's axis
     * @return the column on that component
     */
    private int clampedColumn(double value, int component, int axis) {
        return Math.max(0, columnOf(value, component, axis));
    }

    private int columnOf(double value, int component, int axis) {
        int count = component == (axis + 1) % TriangleGeometry.COMPONENTS ? columnsAcrossOf[axis]
                : columnsAlongOf[axis];
        return Math.min(count - 1, (int) ((value - columnLow.get(component)) * columnStep[axis]));
    }


    /**
     * Appends a node covering one run of {@link #triangleOrder}, scanning the run for its box.
     *
     * @param start offset of the run
     * @param count triangles in the run
     * @return the new node's index
     */
    private int addNode(int start, int count) {
        splitScanBox.set(0, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        splitScanBox.set(1, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
        for (int index = start; index < start + count; index++) {
            growBox(splitScanBox, triangleOrder[index]);
        }
        return addNode(start, count, splitScanBox);
    }

    /**
     * Appends a node covering one run of {@link #triangleOrder} with the box already known.
     *
     * @param start offset of the run
     * @param count triangles in the run
     * @param box lower then upper corner of the run's bounding box
     * @return the new node's index
     */
    private int addNode(int start, int count, PackedPoints box) {
        if (nodeCount == nodeLeftChild.length) {
            int capacity = nodeLeftChild.length * GROWTH;
            nodeMin.resize(capacity);
            nodeMax.resize(capacity);
            nodeLeftChild = Arrays.copyOf(nodeLeftChild, capacity);
            nodeTriangleStart = Arrays.copyOf(nodeTriangleStart, capacity);
            nodeTriangleCount = Arrays.copyOf(nodeTriangleCount, capacity);
        }
        int node = nodeCount++;
        nodeLeftChild[node] = -1;
        nodeTriangleStart[node] = start;
        nodeTriangleCount[node] = count;
        nodeMin.set(node, box.get(0, 0), box.get(0, 1), box.get(0, 2));
        nodeMax.set(node, box.get(1, 0), box.get(1, 1), box.get(1, 2));
        return node;
    }

    /**
     * Reorders one node's run so the triangles whose box midpoint falls below the midpoint of the
     * node's widest axis come first, and leaves the two sides' boxes in {@link #splitLowBox} and
     * {@link #splitHighBox}.
     *
     * @param node node being split
     * @param start offset of its run
     * @param count triangles in the run
     * @return the offset the second half starts at
     */
    private int partition(int node, int start, int count) {
        float spanX = nodeMax.get(node, 0) - nodeMin.get(node, 0);
        float spanY = nodeMax.get(node, 1) - nodeMin.get(node, 1);
        float spanZ = nodeMax.get(node, 2) - nodeMin.get(node, 2);
        int axis = spanX >= spanY && spanX >= spanZ ? 0 : spanY >= spanZ ? 1 : 2;
        float midpoint = HALF * (nodeMin.get(node, axis) + nodeMax.get(node, axis));
        splitLowBox.set(0, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        splitLowBox.set(1, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
        splitHighBox.set(0, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        splitHighBox.set(1, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
        int low = start;
        int high = start + count;
        while (low < high) {
            int triangle = triangleOrder[low];
            int triangleLow = triangle * BOX_CORNERS;
            float centre = HALF * (triangleBounds.get(triangleLow, axis)
                    + triangleBounds.get(triangleLow + 1, axis));
            if (centre < midpoint) {
                growBox(splitLowBox, triangle);
                low++;
            } else {
                growBox(splitHighBox, triangle);
                high--;
                triangleOrder[low] = triangleOrder[high];
                triangleOrder[high] = triangle;
            }
        }
        return low;
    }

    /**
     * Writes one triangle's box from its corners' {@link #positions} into its bounds.
     *
     * @param triangle triangle index
     */
    private void boundTriangle(int triangle) {
        int corner = triangle * TRIANGLE_CORNERS;
        for (int axis = 0; axis < TriangleGeometry.COMPONENTS; axis++) {
            float cornerA = positions.get(triangleCorners[corner], axis);
            float cornerB = positions.get(triangleCorners[corner + 1], axis);
            float cornerC = positions.get(triangleCorners[corner + 2], axis);
            triangleBounds.set(triangle * BOX_CORNERS, axis, Math.min(cornerA, Math.min(cornerB, cornerC)));
            triangleBounds.set(triangle * BOX_CORNERS + 1, axis, Math.max(cornerA, Math.max(cornerB, cornerC)));
        }
    }

    /**
     * Grows a box to hold one triangle.
     *
     * @param box lower then upper corner, updated in place
     * @param triangle triangle whose bounds to union in
     */
    private void growBox(PackedPoints box, int triangle) {
        int low = triangle * BOX_CORNERS;
        box.set(0, Math.min(box.get(0, 0), triangleBounds.get(low, 0)),
                Math.min(box.get(0, 1), triangleBounds.get(low, 1)),
                Math.min(box.get(0, 2), triangleBounds.get(low, 2)));
        box.set(1, Math.max(box.get(1, 0), triangleBounds.get(low + 1, 0)),
                Math.max(box.get(1, 1), triangleBounds.get(low + 1, 1)),
                Math.max(box.get(1, 2), triangleBounds.get(low + 1, 2)));
    }
}
