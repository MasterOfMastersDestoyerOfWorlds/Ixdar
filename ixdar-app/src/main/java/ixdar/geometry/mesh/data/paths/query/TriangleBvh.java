package ixdar.geometry.mesh.data.paths.query;

import java.util.Arrays;

import ixdar.geometry.mesh.data.representation.TriangleGeometry;

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

    private static final int MAX_COLUMNS = 1 << 22;

    private static final int LEAF_TRIANGLES = 64;

    private static final int GROWTH = 2;

    private static final int CHILDREN = 2;

    private static final int INITIAL_NODES = 64;

    private static final int BOX_FLOATS = 6;

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

    /** Lower then upper box corner of each triangle, as {@link #buildColumns} read them. */
    private float[] columnBounds = new float[0];

    /** Lower corner of the box around every filed triangle. */
    private float[] columnLow = new float[0];

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
     * Files every triangle of the soup into a grid of columns along each axis, about an edge wide,
     * in every column its box overlaps, for {@link #facing} to read; the tree is not needed.
     */
    public void buildColumns() {
        columnBounds = new float[triangleCount * BOX_FLOATS];
        columnLow = new float[] { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY };
        float[] high = { Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY };
        int included = 0;
        double edgeLengthSum = 0;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            if (triangleGroup != null && triangleGroup[triangle] < 0) {
                continue;
            }
            included++;
            double edgeSquared = 0;
            for (int component = 0; component < TriangleGeometry.COMPONENTS; component++) {
                float cornerA = positions[triangleCorners[triangle * TRIANGLE_CORNERS]
                        * TriangleGeometry.COMPONENTS + component];
                float cornerB = positions[triangleCorners[triangle * TRIANGLE_CORNERS + 1]
                        * TriangleGeometry.COMPONENTS + component];
                float cornerC = positions[triangleCorners[triangle * TRIANGLE_CORNERS + 2]
                        * TriangleGeometry.COMPONENTS + component];
                float boxLow = Math.min(cornerA, Math.min(cornerB, cornerC));
                float boxHigh = Math.max(cornerA, Math.max(cornerB, cornerC));
                columnBounds[triangle * BOX_FLOATS + component] = boxLow;
                columnBounds[triangle * BOX_FLOATS + TriangleGeometry.COMPONENTS + component] =
                        boxHigh;
                columnLow[component] = Math.min(columnLow[component], boxLow);
                high[component] = Math.max(high[component], boxHigh);
                edgeSquared += ((double) cornerB - cornerA) * ((double) cornerB - cornerA);
            }
            edgeLengthSum += Math.sqrt(edgeSquared);
        }
        double cell = Math.max(edgeLengthSum / Math.max(1, included), Float.MIN_NORMAL);
        columnStep = new double[TriangleGeometry.COMPONENTS];
        columnsAlongOf = new int[TriangleGeometry.COMPONENTS];
        columnsAcrossOf = new int[TriangleGeometry.COMPONENTS];
        columnStart = new int[TriangleGeometry.COMPONENTS][];
        columnMembers = new int[TriangleGeometry.COMPONENTS][];
        for (int axis = 0; axis < TriangleGeometry.COMPONENTS; axis++) {
            int across = (axis + 1) % TriangleGeometry.COMPONENTS;
            int along = (axis + 2) % TriangleGeometry.COMPONENTS;
            double step = Math.max(cell, Math.sqrt((double) (high[across] - columnLow[across])
                    * (high[along] - columnLow[along]) / MAX_COLUMNS));
            double perStep = 1 / step;
            int columnsAcross = Math.max(1,
                    (int) Math.ceil((high[across] - columnLow[across]) * perStep));
            int columnsAlong = Math.max(1,
                    (int) Math.ceil((high[along] - columnLow[along]) * perStep));
            int columns = columnsAcross * columnsAlong;
            columnStep[axis] = perStep;
            columnsAcrossOf[axis] = columnsAcross;
            columnsAlongOf[axis] = columnsAlong;
            int[] start = new int[columns + 1];
            int[] members = new int[0];
            // Pass 0 counts each column's triangles, pass 1 files them.
            for (int pass = 0; pass < 2; pass++) {
                int[] cursor = pass == 0 ? start : Arrays.copyOf(start, columns);
                if (pass == 1) {
                    members = new int[start[columns]];
                }
                for (int triangle = 0; triangle < triangleCount; triangle++) {
                    if (triangleGroup != null && triangleGroup[triangle] < 0) {
                        continue;
                    }
                    int box = triangle * BOX_FLOATS;
                    int firstColumn = columnOf(columnBounds[box + across], across, axis);
                    int lastColumn = columnOf(columnBounds[box + TriangleGeometry.COMPONENTS
                            + across], across, axis);
                    int firstRow = columnOf(columnBounds[box + along], along, axis);
                    int lastRow = columnOf(columnBounds[box + TriangleGeometry.COMPONENTS + along],
                            along, axis);
                    for (int column = firstColumn; column <= lastColumn; column++) {
                        for (int row = firstRow; row <= lastRow; row++) {
                            if (pass == 0) {
                                cursor[column * columnsAlong + row + 1]++;
                            } else {
                                members[cursor[column * columnsAlong + row]++] = triangle;
                            }
                        }
                    }
                }
                if (pass == 0) {
                    for (int column = 0; column < columns; column++) {
                        start[column + 1] += start[column];
                    }
                }
            }
            columnStart[axis] = start;
            columnMembers[axis] = members;
        }
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
        double[] centre = new double[TriangleGeometry.COMPONENTS];
        double[] normal = new double[TriangleGeometry.COMPONENTS];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            if (triangleGroup != null && triangleGroup[triangle] < 0
                    || asked != null && !asked[triangle]) {
                continue;
            }
            int cornerA = triangleCorners[triangle * TRIANGLE_CORNERS] * TriangleGeometry.COMPONENTS;
            int cornerB = triangleCorners[triangle * TRIANGLE_CORNERS + 1]
                    * TriangleGeometry.COMPONENTS;
            int cornerC = triangleCorners[triangle * TRIANGLE_CORNERS + 2]
                    * TriangleGeometry.COMPONENTS;
            int axis = 0;
            for (int component = 0; component < TriangleGeometry.COMPONENTS; component++) {
                int next = (component + 1) % TriangleGeometry.COMPONENTS;
                int after = (component + 2) % TriangleGeometry.COMPONENTS;
                normal[component] = (positions[cornerB + next] - positions[cornerA + next])
                        * (positions[cornerC + after] - positions[cornerA + after])
                        - (positions[cornerB + after] - positions[cornerA + after])
                        * (positions[cornerC + next] - positions[cornerA + next]);
                centre[component] = (positions[cornerA + component] + positions[cornerB + component]
                        + positions[cornerC + component]) / TRIANGLE_CORNERS;
                if (Math.abs(normal[component]) > Math.abs(normal[axis])) {
                    axis = component;
                }
            }
            int across = (axis + 1) % TriangleGeometry.COMPONENTS;
            int along = (axis + 2) % TriangleGeometry.COMPONENTS;
            int column = columnOf(centre[across], across, axis) * columnsAlongOf[axis]
                    + columnOf(centre[along], along, axis);
            double below = columnLow[axis] - 1;
            int group = triangleGroup == null ? -1 : triangleGroup[triangle];
            int ahead = 0;
            int line = 0;
            for (int member = columnStart[axis][column]; member < columnStart[axis][column + 1];
                    member++) {
                int other = columnMembers[axis][member];
                int box = other * BOX_FLOATS;
                // The same span test the crossing test opens with, read off the cached box.
                if (centre[across] < columnBounds[box + across]
                        || centre[across] > columnBounds[box + TriangleGeometry.COMPONENTS + across]
                        || centre[along] < columnBounds[box + along]
                        || centre[along] > columnBounds[box + TriangleGeometry.COMPONENTS + along]
                        || triangleGroup != null && triangleGroup[other] == group) {
                    continue;
                }
                int otherA = triangleCorners[other * TRIANGLE_CORNERS];
                int otherB = triangleCorners[other * TRIANGLE_CORNERS + 1];
                int otherC = triangleCorners[other * TRIANGLE_CORNERS + 2];
                int crosses = TriangleGeometry.positiveAxisRaysCross(axis, centre[0], centre[1],
                        centre[2], below, positions, otherA, otherB, otherC,
                        TriangleGeometry.positiveAxisInverseDeterminant(axis, positions, otherA,
                                otherB, otherC));
                ahead += crosses & TriangleGeometry.CROSSES_FROM_ORIGIN;
                line += (crosses & TriangleGeometry.CROSSES_FROM_BEHIND) == 0 ? 0 : 1;
            }
            facing[triangle] = line % 2 == 0 ? FACING_UNDECIDED
                    : (ahead % 2 == 0) == (normal[axis] > 0) ? FACING_OUT_OF_VOLUME
                            : FACING_INTO_VOLUME;
        }
        return facing;
    }

    /**
     * Column or row of one coordinate in an axis's grid, clamped to the grid.
     *
     * @param value coordinate
     * @param component which coordinate it is
     * @param axis the grid's axis
     * @return the column index across, or the row index along, the axis
     */
    private int columnOf(double value, int component, int axis) {
        int count = component == (axis + 1) % TriangleGeometry.COMPONENTS ? columnsAcrossOf[axis]
                : columnsAlongOf[axis];
        return Math.min(count - 1, (int) ((value - columnLow[component]) * columnStep[axis]));
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
