package ixdar.tools.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.HeapSampler;
import ixdar.nodegraph.field.Vector3Value;
import ixdar.nodegraph.syntax.PythonParser;

/**
 * A run's node tree as a graph: a box per evaluated record, an expanded group call as its framed
 * body, an edge per wired input, and the slowest chain; {@link #place} lays it out, y up.
 */
public final class DslGraphLayout {
    public static final float TEXT_HEIGHT = 16f;
    public static final float ROW_HEIGHT = 22f;
    public static final float BOX_PADDING = 10f;
    public static final float MIN_BOX_WIDTH = 150f;
    public static final float PORT_ROW_GAP = 24f;
    public static final float COLUMN_GAP = 110f;
    public static final float BOX_GAP = 30f;
    public static final float LANE_CLEARANCE = 26f;
    public static final float GROUP_PADDING = 18f;
    public static final int MAX_LITERAL_CHARS = 32;
    public static final String ELLIPSIS = "...";
    public static final int ORDERING_SWEEPS = 4;
    public static final int HEADER_ROWS = 3;
    public static final double TENTHS = 10.0;
    public static final String COLLAPSED_GROUP_HINT = "  group, E expands";
    public static final String EXPANDED_GROUP_HINT = "   E collapses";

    public static final int ROLE_TITLE = 0;
    public static final int ROLE_TYPE = 1;
    public static final int ROLE_TIMING = 2;
    public static final int ROLE_INPUT_PORT = 3;
    public static final int ROLE_OUTPUT_PORT = 4;
    public static final int ROLE_LITERAL = 5;
    public static final int ROLE_GROUP_TITLE = 6;

    public final int nodeCount;

    /** The tree record each box shows: a leaf node, or a collapsed group call. */
    public final GraphNodeContext[] nodeRecord;

    /** Each box's record path, e.g. {@code layout/field}; the title it shows. */
    public final String[] nodeId;
    public final String[] nodeType;
    public final long[] nodeMs;
    public final long[] nodePeakHeapBytes;

    /** Whether the box is a collapsed group call with an evaluated body to expand into. */
    public final boolean[] nodeExpandable;

    /** Longest incoming chain of each node, 0 for a node no reference reaches. */
    public final int[] nodeDepth;
    public final int depthCount;

    /** The whole run's time, the root record's. */
    public final long totalMs;

    /** Node {@code n}'s input ports are {@code [inputPortStart[n], inputPortStart[n + 1])}. */
    public final int[] inputPortStart;
    public final String[] inputPortName;
    public final int[] outputPortStart;
    public final String[] outputPortName;

    public final int edgeCount;
    public final int[] edgeSource;
    public final int[] edgeSourcePort;
    public final int[] edgeTarget;
    public final int[] edgeTargetPort;
    public final boolean[] edgeCritical;

    /** Node indices of the slowest chain, source first. */
    public final int[] criticalPath;
    public final long criticalPathMs;

    /** Index of the node with the largest run time, or -1 for an empty graph. */
    public final int slowestNode;

    /** Literal, default and override inputs of each node as {@code name = value} rows, names sorted. */
    public final String[][] literalRows;

    /** Expanded group calls, each drawn as a frame around its body's boxes, deepest first. */
    public final int groupCount;
    public final String[] groupPath;
    public final String[] groupType;
    public final long[] groupMs;
    public final float[] groupFrameX;
    public final float[] groupFrameY;
    public final float[] groupFrameWidth;
    public final float[] groupFrameHeight;

    /** Order of each node within its depth column, top first, after {@link #place}. */
    public final int[] nodeOrder;
    public final float[] boxX;
    public final float[] boxY;
    public final float[] boxWidth;
    public final float[] boxHeight;
    public final float[] inputPortX;
    public final float[] inputPortY;
    public final float[] outputPortX;
    public final float[] outputPortY;

    /** Height of the lane a multi-column edge runs along, NaN for an edge between adjacent columns. */
    public final float[] edgeLaneY;

    public String[] textString = new String[0];

    /** Node each text row belongs to, or -1 for a group frame's title. */
    public int[] textNode = new int[0];
    public int[] textRole = new int[0];
    public float[] textCenterX = new float[0];
    public float[] textCenterY = new float[0];

    public float minX;
    public float minY;
    public float maxX;
    public float maxY;

    /**
     * Build the graph of a run's node tree: every evaluated top-level record, an expanded group
     * replaced by its evaluated body, edges from each record's wired input sources.
     *
     * @param root the run's root record, its children the evaluated top-level statements
     * @param expandedGroups paths of the group calls to show as their body
     */
    public DslGraphLayout(GraphNodeContext root, Set<String> expandedGroups) {
        totalMs = root.elapsedMs();
        List<GraphNodeContext> visible = new ArrayList<>();
        List<GraphNodeContext> groups = new ArrayList<>();
        Map<String, GraphNodeContext> recordsByPath = new HashMap<>();
        List<GraphNodeContext> stack = new ArrayList<>();
        for (int child = root.children.size() - 1; child >= 0; child--) {
            stack.add(root.children.get(child));
        }
        while (!stack.isEmpty()) {
            GraphNodeContext record = stack.remove(stack.size() - 1);
            recordsByPath.put(record.path, record);
            boolean hasBody = record.group != null && !record.children.isEmpty();
            if (!hasBody || !expandedGroups.contains(record.path)) {
                visible.add(record);
                for (GraphNodeContext hidden : record.subtree()) {
                    recordsByPath.put(hidden.path, hidden);
                }
                continue;
            }
            groups.add(record);
            for (int child = record.children.size() - 1; child >= 0; child--) {
                stack.add(record.children.get(child));
            }
        }
        groups.sort((first, second) -> Integer.compare(second.path.split(GraphNodeContext.PATH_SEPARATOR).length,
                first.path.split(GraphNodeContext.PATH_SEPARATOR).length));
        groupCount = groups.size();
        groupPath = new String[groupCount];
        groupType = new String[groupCount];
        groupMs = new long[groupCount];
        Set<String> expanded = new HashSet<>();
        for (int group = 0; group < groupCount; group++) {
            groupPath[group] = groups.get(group).path;
            groupType[group] = groups.get(group).type;
            groupMs[group] = groups.get(group).elapsedMs();
            expanded.add(groupPath[group]);
        }
        groupFrameX = new float[groupCount];
        groupFrameY = new float[groupCount];
        groupFrameWidth = new float[groupCount];
        groupFrameHeight = new float[groupCount];

        nodeCount = visible.size();
        nodeRecord = visible.toArray(new GraphNodeContext[0]);
        nodeId = new String[nodeCount];
        nodeType = new String[nodeCount];
        nodeMs = new long[nodeCount];
        nodePeakHeapBytes = new long[nodeCount];
        nodeExpandable = new boolean[nodeCount];
        nodeDepth = new int[nodeCount];
        literalRows = new String[nodeCount][];
        inputPortStart = new int[nodeCount + 1];
        outputPortStart = new int[nodeCount + 1];
        Map<String, Integer> indexByPath = new HashMap<>();
        for (int node = 0; node < nodeCount; node++) {
            GraphNodeContext record = nodeRecord[node];
            nodeId[node] = record.path;
            nodeType[node] = record.type;
            nodeMs[node] = record.elapsedMs();
            nodePeakHeapBytes[node] = record.peakHeapBytes;
            nodeExpandable[node] = record.group != null && !record.children.isEmpty();
            indexByPath.put(record.path, node);
        }

        List<String> inputNames = new ArrayList<>();
        List<Integer> sources = new ArrayList<>();
        List<String> sourcePortNames = new ArrayList<>();
        List<Integer> targets = new ArrayList<>();
        List<Integer> targetPorts = new ArrayList<>();
        for (int node = 0; node < nodeCount; node++) {
            GraphNodeContext record = nodeRecord[node];
            inputPortStart[node] = inputNames.size();
            List<String> literals = new ArrayList<>();
            for (Map.Entry<String, Object> input : new TreeMap<>(record.inputSources).entrySet()) {
                // Follow a source through expanded groups: a parameter to what the call bound it
                // to, a group output to the body node it reads.
                Object source = input.getValue();
                String consumerPath = record.path;
                Integer sourceNode = null;
                while (source instanceof PythonParser.NodeReference reference && sourceNode == null) {
                    GraphNodeContext sourceRecord = recordsByPath.get(reference.nodeId);
                    if (sourceRecord == null) {
                        break;
                    }
                    if (!expanded.contains(sourceRecord.path)) {
                        GraphNodeContext shown = sourceRecord;
                        while (shown != null && !indexByPath.containsKey(shown.path)) {
                            shown = shown.parent;
                        }
                        sourceNode = shown == null ? null : indexByPath.get(shown.path);
                        source = sourceNode == null ? null : reference;
                    } else if (consumerPath.startsWith(sourceRecord.path + GraphNodeContext.PATH_SEPARATOR)) {
                        source = sourceRecord.inputSources.get(reference.portName);
                        consumerPath = sourceRecord.path;
                    } else {
                        source = sourceRecord.outputSources.get(reference.portName);
                    }
                }
                if (sourceNode != null) {
                    targets.add(node);
                    targetPorts.add(inputNames.size());
                    inputNames.add(input.getKey());
                    sources.add(sourceNode);
                    sourcePortNames.add(((PythonParser.NodeReference) source).portName);
                    continue;
                }
                if (source == null) {
                    continue;
                }
                Object shown = source;
                if (source instanceof PythonParser.NodeReference unresolved) {
                    shown = unresolved.nodeId + "." + unresolved.portName;
                } else if (source instanceof Vector3Value vector) {
                    shown = "<" + vector.x() + ", " + vector.y() + ", " + vector.z() + ">";
                }
                String text = input.getKey() + " = " + shown;
                literals.add(text.length() > MAX_LITERAL_CHARS
                        ? text.substring(0, MAX_LITERAL_CHARS - ELLIPSIS.length()) + ELLIPSIS : text);
            }
            literalRows[node] = literals.toArray(new String[0]);
        }
        inputPortStart[nodeCount] = inputNames.size();
        inputPortName = inputNames.toArray(new String[0]);

        edgeCount = sources.size();
        edgeSource = new int[edgeCount];
        edgeSourcePort = new int[edgeCount];
        edgeTarget = new int[edgeCount];
        edgeTargetPort = new int[edgeCount];
        edgeCritical = new boolean[edgeCount];
        List<String> outputNames = new ArrayList<>();
        for (int node = 0; node < nodeCount; node++) {
            outputPortStart[node] = outputNames.size();
            for (int edge = 0; edge < edgeCount; edge++) {
                if (sources.get(edge) != node) {
                    continue;
                }
                int port = outputNames.subList(outputPortStart[node], outputNames.size())
                        .indexOf(sourcePortNames.get(edge));
                if (port < 0) {
                    port = outputNames.size() - outputPortStart[node];
                    outputNames.add(sourcePortNames.get(edge));
                }
                edgeSource[edge] = node;
                edgeSourcePort[edge] = outputPortStart[node] + port;
                edgeTarget[edge] = targets.get(edge);
                edgeTargetPort[edge] = targetPorts.get(edge);
            }
        }
        outputPortStart[nodeCount] = outputNames.size();
        outputPortName = outputNames.toArray(new String[0]);

        // Kahn's order over the edges: evaluation order is topological for a whole run but a pull
        // may evaluate a group's body nodes after records that come later in the tree.
        int[] waiting = new int[nodeCount];
        for (int edge = 0; edge < edgeCount; edge++) {
            waiting[edgeTarget[edge]]++;
        }
        int[] order = new int[nodeCount];
        int ordered = 0;
        for (int node = 0; node < nodeCount; node++) {
            if (waiting[node] == 0) {
                order[ordered++] = node;
            }
        }
        long[] chainMs = new long[nodeCount];
        int[] chainPrevious = new int[nodeCount];
        Arrays.fill(chainPrevious, -1);
        for (int next = 0; next < ordered; next++) {
            int node = order[next];
            chainMs[node] += nodeMs[node];
            for (int edge = 0; edge < edgeCount; edge++) {
                if (edgeSource[edge] != node) {
                    continue;
                }
                int target = edgeTarget[edge];
                nodeDepth[target] = Math.max(nodeDepth[target], nodeDepth[node] + 1);
                if (chainPrevious[target] < 0 || chainMs[node] > chainMs[chainPrevious[target]]) {
                    chainPrevious[target] = node;
                    chainMs[target] = chainMs[node];
                }
                if (--waiting[target] == 0) {
                    order[ordered++] = target;
                }
            }
        }
        int deepest = -1;
        int slowest = -1;
        int pathEnd = -1;
        for (int node = 0; node < nodeCount; node++) {
            deepest = Math.max(deepest, nodeDepth[node]);
            if (slowest < 0 || nodeMs[node] > nodeMs[slowest]) {
                slowest = node;
            }
            if (pathEnd < 0 || chainMs[node] > chainMs[pathEnd]) {
                pathEnd = node;
            }
        }
        depthCount = deepest + 1;
        slowestNode = slowest;
        List<Integer> path = new ArrayList<>();
        for (int node = pathEnd; node >= 0; node = chainPrevious[node]) {
            path.add(0, node);
        }
        criticalPath = path.stream().mapToInt(Integer::intValue).toArray();
        criticalPathMs = pathEnd < 0 ? 0 : chainMs[pathEnd];
        for (int edge = 0; edge < edgeCount; edge++) {
            edgeCritical[edge] = chainPrevious[edgeTarget[edge]] == edgeSource[edge]
                    && path.contains(edgeTarget[edge]);
        }

        nodeOrder = new int[nodeCount];
        boxX = new float[nodeCount];
        boxY = new float[nodeCount];
        boxWidth = new float[nodeCount];
        boxHeight = new float[nodeCount];
        inputPortX = new float[inputPortName.length];
        inputPortY = new float[inputPortName.length];
        outputPortX = new float[outputPortName.length];
        outputPortY = new float[outputPortName.length];
        edgeLaneY = new float[edgeCount];
    }

    /**
     * Lay the graph out: one column per depth, left to right, boxes in each column ordered by
     * barycenter sweeps to reduce crossings, every row of text placed inside its box, and a frame
     * around each expanded group's boxes.
     *
     * @param textWidth width of a string drawn at {@link #TEXT_HEIGHT}, in layout units
     */
    public void place(ToDoubleFunction<String> textWidth) {
        List<List<Integer>> columns = new ArrayList<>();
        for (int depth = 0; depth < depthCount; depth++) {
            columns.add(new ArrayList<>());
        }
        for (int node = 0; node < nodeCount; node++) {
            nodeOrder[node] = columns.get(nodeDepth[node]).size();
            columns.get(nodeDepth[node]).add(node);
        }
        double[] barycenter = new double[nodeCount];
        int[] neighbours = new int[nodeCount];
        for (int sweep = 0; sweep < ORDERING_SWEEPS; sweep++) {
            boolean forward = sweep % 2 == 0;
            Arrays.fill(barycenter, 0);
            Arrays.fill(neighbours, 0);
            for (int edge = 0; edge < edgeCount; edge++) {
                int moved = forward ? edgeTarget[edge] : edgeSource[edge];
                int fixed = forward ? edgeSource[edge] : edgeTarget[edge];
                barycenter[moved] += nodeOrder[fixed] - (columns.get(nodeDepth[fixed]).size() - 1) * 0.5;
                neighbours[moved]++;
            }
            for (List<Integer> column : columns) {
                for (int node : column) {
                    barycenter[node] = neighbours[node] == 0
                            ? nodeOrder[node] - (column.size() - 1) * 0.5
                            : barycenter[node] / neighbours[node];
                }
                column.sort((first, second) -> Double.compare(barycenter[first], barycenter[second]));
                for (int order = 0; order < column.size(); order++) {
                    nodeOrder[column.get(order)] = order;
                }
            }
        }

        List<String> strings = new ArrayList<>();
        List<Integer> nodes = new ArrayList<>();
        List<Integer> roles = new ArrayList<>();
        List<Float> offsetsX = new ArrayList<>();
        List<Float> offsetsY = new ArrayList<>();
        float[] rowWidths = new float[nodeCount];
        for (int node = 0; node < nodeCount; node++) {
            int inputs = inputPortStart[node + 1] - inputPortStart[node];
            int outputs = outputPortStart[node + 1] - outputPortStart[node];
            int portRows = Math.max(inputs, outputs);
            String timing = nodeMs[node] + " ms   "
                    + Math.round(nodePeakHeapBytes[node] / HeapSampler.BYTES_PER_MIB * TENTHS) / TENTHS + " MiB";
            String type = nodeExpandable[node] ? nodeType[node] + COLLAPSED_GROUP_HINT : nodeType[node];
            String[] headers = { nodeId[node], type, timing };
            int[] headerRoles = { ROLE_TITLE, ROLE_TYPE, ROLE_TIMING };
            float width = MIN_BOX_WIDTH - 2 * BOX_PADDING;
            int row = 0;
            for (int header = 0; header < headers.length; header++, row++) {
                float textSize = (float) textWidth.applyAsDouble(headers[header]);
                width = Math.max(width, textSize);
                strings.add(headers[header]);
                nodes.add(node);
                roles.add(headerRoles[header]);
                offsetsX.add(Float.NaN);
                offsetsY.add(row * ROW_HEIGHT);
            }
            for (int portRow = 0; portRow < portRows; portRow++, row++) {
                float rowWidth = PORT_ROW_GAP;
                if (portRow < inputs) {
                    String name = inputPortName[inputPortStart[node] + portRow];
                    float textSize = (float) textWidth.applyAsDouble(name);
                    rowWidth += textSize;
                    strings.add(name);
                    nodes.add(node);
                    roles.add(ROLE_INPUT_PORT);
                    offsetsX.add(textSize / 2);
                    offsetsY.add(row * ROW_HEIGHT);
                }
                if (portRow < outputs) {
                    String name = outputPortName[outputPortStart[node] + portRow];
                    float textSize = (float) textWidth.applyAsDouble(name);
                    rowWidth += textSize;
                    strings.add(name);
                    nodes.add(node);
                    roles.add(ROLE_OUTPUT_PORT);
                    offsetsX.add(-textSize / 2);
                    offsetsY.add(row * ROW_HEIGHT);
                }
                width = Math.max(width, rowWidth);
            }
            for (String literal : literalRows[node]) {
                float textSize = (float) textWidth.applyAsDouble(literal);
                width = Math.max(width, textSize);
                strings.add(literal);
                nodes.add(node);
                roles.add(ROLE_LITERAL);
                offsetsX.add(textSize / 2);
                offsetsY.add(row * ROW_HEIGHT);
                row++;
            }
            rowWidths[node] = width;
            boxWidth[node] = width + 2 * BOX_PADDING;
            boxHeight[node] = row * ROW_HEIGHT + 2 * BOX_PADDING;
        }

        float columnX = 0;
        float[] columnTop = new float[depthCount];
        for (int depth = 0; depth < depthCount; depth++) {
            List<Integer> column = columns.get(depth);
            float columnWidth = 0;
            float columnHeight = -BOX_GAP;
            for (int node : column) {
                columnWidth = Math.max(columnWidth, boxWidth[node]);
                columnHeight += boxHeight[node] + BOX_GAP;
            }
            float top = columnHeight / 2;
            columnTop[depth] = top;
            for (int node : column) {
                boxX[node] = columnX;
                boxY[node] = top - boxHeight[node];
                top -= boxHeight[node] + BOX_GAP;
            }
            columnX += columnWidth + COLUMN_GAP;
        }

        minX = Float.MAX_VALUE;
        minY = Float.MAX_VALUE;
        maxX = -Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
        for (int node = 0; node < nodeCount; node++) {
            float firstRowY = boxY[node] + boxHeight[node] - BOX_PADDING - ROW_HEIGHT / 2;
            float portRowY = firstRowY - HEADER_ROWS * ROW_HEIGHT;
            for (int port = inputPortStart[node]; port < inputPortStart[node + 1]; port++) {
                inputPortX[port] = boxX[node];
                inputPortY[port] = portRowY - (port - inputPortStart[node]) * ROW_HEIGHT;
            }
            for (int port = outputPortStart[node]; port < outputPortStart[node + 1]; port++) {
                outputPortX[port] = boxX[node] + boxWidth[node];
                outputPortY[port] = portRowY - (port - outputPortStart[node]) * ROW_HEIGHT;
            }
            minX = Math.min(minX, boxX[node]);
            minY = Math.min(minY, boxY[node]);
            maxX = Math.max(maxX, boxX[node] + boxWidth[node]);
            maxY = Math.max(maxY, boxY[node] + boxHeight[node]);
        }

        for (int edge = 0; edge < edgeCount; edge++) {
            int fromDepth = nodeDepth[edgeSource[edge]];
            int toDepth = nodeDepth[edgeTarget[edge]];
            float lane = -Float.MAX_VALUE;
            for (int depth = fromDepth + 1; depth < toDepth; depth++) {
                lane = Math.max(lane, columnTop[depth] + LANE_CLEARANCE);
            }
            edgeLaneY[edge] = toDepth - fromDepth > 1 ? lane : Float.NaN;
            if (toDepth - fromDepth > 1) {
                maxY = Math.max(maxY, lane);
            }
        }

        // Frames deepest first, so an outer frame encloses the inner frames and their titles.
        float[] groupTitleWidth = new float[groupCount];
        for (int group = 0; group < groupCount; group++) {
            String prefix = groupPath[group] + GraphNodeContext.PATH_SEPARATOR;
            float left = Float.MAX_VALUE;
            float bottom = Float.MAX_VALUE;
            float right = -Float.MAX_VALUE;
            float top = -Float.MAX_VALUE;
            for (int node = 0; node < nodeCount; node++) {
                if (nodeId[node].startsWith(prefix)) {
                    left = Math.min(left, boxX[node]);
                    bottom = Math.min(bottom, boxY[node]);
                    right = Math.max(right, boxX[node] + boxWidth[node]);
                    top = Math.max(top, boxY[node] + boxHeight[node]);
                }
            }
            for (int edge = 0; edge < edgeCount; edge++) {
                if (!Float.isNaN(edgeLaneY[edge]) && nodeId[edgeSource[edge]].startsWith(prefix)
                        && nodeId[edgeTarget[edge]].startsWith(prefix)) {
                    top = Math.max(top, edgeLaneY[edge]);
                }
            }
            for (int inner = 0; inner < group; inner++) {
                if (groupPath[inner].startsWith(prefix)) {
                    left = Math.min(left, groupFrameX[inner]);
                    bottom = Math.min(bottom, groupFrameY[inner]);
                    right = Math.max(right, groupFrameX[inner] + groupFrameWidth[inner]);
                    top = Math.max(top, groupFrameY[inner] + groupFrameHeight[inner]);
                }
            }
            String title = groupPath[group] + "   " + groupType[group] + "   " + groupMs[group] + " ms"
                    + EXPANDED_GROUP_HINT;
            groupTitleWidth[group] = (float) textWidth.applyAsDouble(title);
            groupFrameX[group] = left - GROUP_PADDING;
            groupFrameY[group] = bottom - GROUP_PADDING;
            groupFrameWidth[group] = Math.max(right - left + 2 * GROUP_PADDING,
                    groupTitleWidth[group] + 2 * BOX_PADDING);
            groupFrameHeight[group] = top - bottom + 2 * GROUP_PADDING + ROW_HEIGHT;
            strings.add(title);
            nodes.add(-1);
            roles.add(ROLE_GROUP_TITLE);
            offsetsX.add((float) group);
            offsetsY.add(0f);
            minX = Math.min(minX, groupFrameX[group]);
            minY = Math.min(minY, groupFrameY[group]);
            maxX = Math.max(maxX, groupFrameX[group] + groupFrameWidth[group]);
            maxY = Math.max(maxY, groupFrameY[group] + groupFrameHeight[group]);
        }

        int textCount = strings.size();
        textString = strings.toArray(new String[0]);
        textNode = new int[textCount];
        textRole = new int[textCount];
        textCenterX = new float[textCount];
        textCenterY = new float[textCount];
        for (int text = 0; text < textCount; text++) {
            int node = nodes.get(text);
            float offsetX = offsetsX.get(text);
            textNode[text] = node;
            textRole[text] = roles.get(text);
            if (node < 0) {
                int group = (int) offsetX;
                textCenterX[text] = groupFrameX[group] + BOX_PADDING + groupTitleWidth[group] / 2;
                textCenterY[text] = groupFrameY[group] + groupFrameHeight[group] - BOX_PADDING / 2 - ROW_HEIGHT / 2;
                continue;
            }
            float contentLeft = boxX[node] + BOX_PADDING;
            textCenterX[text] = Float.isNaN(offsetX) ? contentLeft + rowWidths[node] / 2
                    : offsetX > 0 ? contentLeft + offsetX : contentLeft + rowWidths[node] + offsetX;
            textCenterY[text] = boxY[node] + boxHeight[node] - BOX_PADDING - ROW_HEIGHT / 2 - offsetsY.get(text);
        }
    }

    /**
     * The node whose box contains a layout-space point.
     *
     * @param x layout x
     * @param y layout y
     * @return that node's index, or -1 when the point is in no box
     */
    public int nodeAt(float x, float y) {
        for (int node = 0; node < nodeCount; node++) {
            if (x >= boxX[node] && x <= boxX[node] + boxWidth[node]
                    && y >= boxY[node] && y <= boxY[node] + boxHeight[node]) {
                return node;
            }
        }
        return -1;
    }

    /**
     * The box showing a record path.
     *
     * @param path record path, e.g. {@code layout/field}
     * @return that node's index, or -1 when no box shows the path
     */
    public int nodeIndex(String path) {
        for (int node = 0; node < nodeCount; node++) {
            if (nodeId[node].equals(path)) {
                return node;
            }
        }
        return -1;
    }

    /**
     * The frame of an expanded group call.
     *
     * @param path the group call's record path
     * @return that group's index, or -1 when the call is not expanded
     */
    public int groupIndex(String path) {
        for (int group = 0; group < groupCount; group++) {
            if (groupPath[group].equals(path)) {
                return group;
            }
        }
        return -1;
    }
}
