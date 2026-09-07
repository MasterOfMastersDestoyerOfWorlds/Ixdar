package ixdar.geometry.mesh.data;

import java.util.Arrays;

/**
 * Axis-aligned bounding-volume hierarchy over a triangle soup held as flat positions and corner
 * indices, so a ray visits only the triangles whose boxes it enters.
 *
 * <p>Set the soup, call {@link #build()}, then query.
 */
public final class TriangleBvh {

    /** Corners of a triangle. */
    public static final int TRIANGLE_CORNERS = 3;

    /** Triangles at or below which a node stops splitting. */
    private static final int LEAF_TRIANGLES = 64;

    /** Growth factor of the node arrays. */
    private static final int GROWTH = 2;

    /** Children a split node gets. */
    private static final int CHILDREN = 2;

    /** Nodes the arrays start with. */
    private static final int INITIAL_NODES = 64;

    /** Floats one box occupies: the lower corner then the upper one. */
    private static final int BOX_FLOATS = 6;

    /** Half, for box midpoints. */
    private static final float HALF = 0.5f;

    /** Vertex positions as xyz triples. */
    public float[] positions = new float[0];

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

    /** Lower box corner of each node, xyz triples. */
    public float[] nodeMin = new float[0];

    /** Upper box corner of each node, xyz triples. */
    public float[] nodeMax = new float[0];

    /** First child of each node; the second is the one after it, and {@code -1} marks a leaf. */
    public int[] nodeLeftChild = new int[0];

    /** Offset into {@link #triangleOrder} of each leaf's run. */
    public int[] nodeTriangleStart = new int[0];

    /** Length of each leaf's run in {@link #triangleOrder}. */
    public int[] nodeTriangleCount = new int[0];

    /** Lower then upper box corner of each triangle, six floats per triangle index. */
    private float[] triangleBounds = new float[0];

    /** Box of the low side of the last {@link #partition}, as one {@link #BOX_FLOATS} block. */
    private final float[] splitLowBox = new float[BOX_FLOATS];

    /** Box of the high side of the last {@link #partition}, as one {@link #BOX_FLOATS} block. */
    private final float[] splitHighBox = new float[BOX_FLOATS];

    /** Box a scanning {@link #addNode} accumulates into. */
    private final float[] splitScanBox = new float[BOX_FLOATS];

    /** Node stack of the top-down build. */
    private int[] pending = new int[0];

    /**
     * Builds the tree over the triangles the soup offers, splitting each node at the midpoint of
     * its widest extent. Triangles in a negative group are left out.
     */
    public void build() {
        if (triangleBounds.length < triangleCount * BOX_FLOATS) {
            triangleBounds = new float[triangleCount * BOX_FLOATS];
            triangleOrder = new int[triangleCount];
        }
        int inTree = 0;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            if (triangleGroup != null && triangleGroup[triangle] < 0) {
                continue;
            }
            triangleOrder[inTree++] = triangle;
            float lowX = Float.POSITIVE_INFINITY;
            float lowY = Float.POSITIVE_INFINITY;
            float lowZ = Float.POSITIVE_INFINITY;
            float highX = Float.NEGATIVE_INFINITY;
            float highY = Float.NEGATIVE_INFINITY;
            float highZ = Float.NEGATIVE_INFINITY;
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int vertex = triangleCorners[triangle * TRIANGLE_CORNERS + corner];
                float x = positions[vertex * TriangleGeometry.COMPONENTS];
                float y = positions[vertex * TriangleGeometry.COMPONENTS + 1];
                float z = positions[vertex * TriangleGeometry.COMPONENTS + 2];
                lowX = Math.min(lowX, x);
                lowY = Math.min(lowY, y);
                lowZ = Math.min(lowZ, z);
                highX = Math.max(highX, x);
                highY = Math.max(highY, y);
                highZ = Math.max(highZ, z);
            }
            triangleBounds[triangle * BOX_FLOATS] = lowX;
            triangleBounds[triangle * BOX_FLOATS + 1] = lowY;
            triangleBounds[triangle * BOX_FLOATS + 2] = lowZ;
            triangleBounds[triangle * BOX_FLOATS + TriangleGeometry.COMPONENTS] = highX;
            triangleBounds[triangle * BOX_FLOATS + TriangleGeometry.COMPONENTS + 1] = highY;
            triangleBounds[triangle * BOX_FLOATS + TriangleGeometry.COMPONENTS + 2] = highZ;
        }
        nodeCount = 0;
        nodeMin = new float[INITIAL_NODES * TriangleGeometry.COMPONENTS];
        nodeMax = new float[INITIAL_NODES * TriangleGeometry.COMPONENTS];
        nodeLeftChild = new int[INITIAL_NODES];
        nodeTriangleStart = new int[INITIAL_NODES];
        nodeTriangleCount = new int[INITIAL_NODES];
        pending = new int[INITIAL_NODES];
        if (inTree == 0) {
            return;
        }
        int top = 0;
        pending[top++] = addNode(0, inTree);
        while (top > 0) {
            int node = pending[--top];
            int start = nodeTriangleStart[node];
            int count = nodeTriangleCount[node];
            if (count <= LEAF_TRIANGLES) {
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
    }

    /**
     * Counts the triangles the ray from an origin along one positive axis crosses, skipping a
     * whole group.
     *
     * @param axis 0, 1 or 2 for the {@code +x}, {@code +y} or {@code +z} ray
     * @param originX ray origin x
     * @param originY ray origin y
     * @param originZ ray origin z
     * @param skipGroup group whose triangles are ignored, or {@code -1} to test them all
     * @param stack scratch node stack of at least {@link #nodeCount} entries
     * @return the number of crossings
     */
    public int positiveAxisRayCrossings(int axis, double originX, double originY, double originZ,
            int skipGroup, int[] stack) {
        if (nodeCount == 0) {
            return 0;
        }
        double alongAxis = axis == 0 ? originX : axis == 1 ? originY : originZ;
        int firstOther = (axis + 1) % TriangleGeometry.COMPONENTS;
        int secondOther = (axis + 2) % TriangleGeometry.COMPONENTS;
        double alongFirstOther = firstOther == 0 ? originX : firstOther == 1 ? originY : originZ;
        double alongSecondOther = secondOther == 0 ? originX : secondOther == 1 ? originY : originZ;
        int crossings = 0;
        int top = 0;
        stack[top++] = 0;
        while (top > 0) {
            int node = stack[--top];
            int box = node * TriangleGeometry.COMPONENTS;
            if (nodeMax[box + axis] < alongAxis
                    || alongFirstOther < nodeMin[box + firstOther]
                    || alongFirstOther > nodeMax[box + firstOther]
                    || alongSecondOther < nodeMin[box + secondOther]
                    || alongSecondOther > nodeMax[box + secondOther]) {
                continue;
            }
            int left = nodeLeftChild[node];
            if (left >= 0) {
                stack[top++] = left;
                stack[top++] = left + 1;
                continue;
            }
            int start = nodeTriangleStart[node];
            int end = start + nodeTriangleCount[node];
            for (int index = start; index < end; index++) {
                int triangle = triangleOrder[index];
                if (triangleGroup != null && triangleGroup[triangle] == skipGroup) {
                    continue;
                }
                if (TriangleGeometry.positiveAxisRayCrosses(axis, originX, originY, originZ,
                        positions, triangleCorners[triangle * TRIANGLE_CORNERS],
                        triangleCorners[triangle * TRIANGLE_CORNERS + 1],
                        triangleCorners[triangle * TRIANGLE_CORNERS + 2])) {
                    crossings++;
                }
            }
        }
        return crossings;
    }

    /**
     * Appends a node covering one run of {@link #triangleOrder}, scanning the run for its box.
     *
     * @param start offset of the run
     * @param count triangles in the run
     * @return the new node's index
     */
    private int addNode(int start, int count) {
        Arrays.fill(splitScanBox, 0, TriangleGeometry.COMPONENTS, Float.POSITIVE_INFINITY);
        Arrays.fill(splitScanBox, TriangleGeometry.COMPONENTS, BOX_FLOATS,
                Float.NEGATIVE_INFINITY);
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
    private int addNode(int start, int count, float[] box) {
        if (nodeCount == nodeLeftChild.length) {
            int capacity = nodeLeftChild.length * GROWTH;
            nodeMin = Arrays.copyOf(nodeMin, capacity * TriangleGeometry.COMPONENTS);
            nodeMax = Arrays.copyOf(nodeMax, capacity * TriangleGeometry.COMPONENTS);
            nodeLeftChild = Arrays.copyOf(nodeLeftChild, capacity);
            nodeTriangleStart = Arrays.copyOf(nodeTriangleStart, capacity);
            nodeTriangleCount = Arrays.copyOf(nodeTriangleCount, capacity);
        }
        int node = nodeCount++;
        nodeLeftChild[node] = -1;
        nodeTriangleStart[node] = start;
        nodeTriangleCount[node] = count;
        System.arraycopy(box, 0, nodeMin, node * TriangleGeometry.COMPONENTS,
                TriangleGeometry.COMPONENTS);
        System.arraycopy(box, TriangleGeometry.COMPONENTS, nodeMax,
                node * TriangleGeometry.COMPONENTS, TriangleGeometry.COMPONENTS);
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
        int box = node * TriangleGeometry.COMPONENTS;
        float spanX = nodeMax[box] - nodeMin[box];
        float spanY = nodeMax[box + 1] - nodeMin[box + 1];
        float spanZ = nodeMax[box + 2] - nodeMin[box + 2];
        int axis = spanX >= spanY && spanX >= spanZ ? 0 : spanY >= spanZ ? 1 : 2;
        float midpoint = HALF * (nodeMin[box + axis] + nodeMax[box + axis]);
        Arrays.fill(splitLowBox, 0, TriangleGeometry.COMPONENTS, Float.POSITIVE_INFINITY);
        Arrays.fill(splitLowBox, TriangleGeometry.COMPONENTS, BOX_FLOATS, Float.NEGATIVE_INFINITY);
        Arrays.fill(splitHighBox, 0, TriangleGeometry.COMPONENTS, Float.POSITIVE_INFINITY);
        Arrays.fill(splitHighBox, TriangleGeometry.COMPONENTS, BOX_FLOATS, Float.NEGATIVE_INFINITY);
        int low = start;
        int high = start + count;
        while (low < high) {
            int triangle = triangleOrder[low];
            int triangleBox = triangle * BOX_FLOATS + axis;
            float centre = HALF * (triangleBounds[triangleBox]
                    + triangleBounds[triangleBox + TriangleGeometry.COMPONENTS]);
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
     * Grows a box to hold one triangle.
     *
     * @param box lower then upper corner, updated in place
     * @param triangle triangle whose bounds to union in
     */
    private void growBox(float[] box, int triangle) {
        int bounds = triangle * BOX_FLOATS;
        for (int component = 0; component < TriangleGeometry.COMPONENTS; component++) {
            box[component] = Math.min(box[component], triangleBounds[bounds + component]);
            box[TriangleGeometry.COMPONENTS + component] =
                    Math.max(box[TriangleGeometry.COMPONENTS + component],
                            triangleBounds[bounds + TriangleGeometry.COMPONENTS + component]);
        }
    }
}
