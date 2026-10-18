package unit.graph;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.GroupBuilder;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.nodes.math.FloatMathNode;
import ixdar.nodegraph.syntax.PythonParser;
import ixdar.tools.graph.DslGraphLayout;
import unit.mesh.SumAndProductGroup;

/**
 * The DSL graph view's layout of small runs' node trees: depths are longest incoming chains,
 * edges are the wired input sources, the critical path is the slowest chain, and an expanded
 * group shows its evaluated body wired through its parameters and outputs.
 */
class DslGraphLayoutTest {

    private static final String BASE = "base";
    private static final String REFINED = "refined";
    private static final String SMOOTHED = "smoothed";
    private static final String JOINED = "joined";
    private static final String REPAIRED = "repaired";
    private static final String RESULT = FloatMathNode.RESULT.name;

    private static final String DIAMOND = String.join("\n",
            BASE + " = float_math(a=1.0, b=2.0)",
            REFINED + " = float_math(a=base.result, b=2.0)",
            SMOOTHED + " = float_math(operation=\"MULTIPLY\", a=base.result, b=0.5)",
            JOINED + " = float_math(a=refined.result, b=smoothed.result)",
            REPAIRED + " = float_math(a=joined.result, b=base.result)",
            "");

    private static final String NAMED_OUTPUTS = """
            def stats(a: FLOAT, b: FLOAT) -> FLOAT:
                total = float_math(a=a, b=b)
                product = float_math(operation="MULTIPLY", a=a, b=b)
                return(sum=total.result, product=product.result)
            end
            s = stats(a=3.0, b=4.0)
            r = float_math(a=s.sum, b=s.product)
            """;

    private static final String NESTED = """
            def inner(a: FLOAT, b: FLOAT = 2.0) -> FLOAT:
                sum = float_math(a=a, b=b)
                doubled = float_math(operation="MULTIPLY", a=sum.result, b=2.0)
            end
            def outer(x: FLOAT) -> FLOAT:
                first = inner(a=x)
                second = inner(a=first.result, b=10.0)
            end
            top = outer(x=1.0)
            """;

    private static final String STATS = "s";
    private static final String READER = "r";
    private static final String TOP = "top";
    private static final String FIRST = "top/first";
    private static final String FIRST_SUM = "top/first/sum";
    private static final String FIRST_DOUBLED = "top/first/doubled";
    private static final String SECOND = "top/second";
    private static final String STATS_TOTAL = "s/total";
    private static final String COMPILED = "stats";
    private static final String COMPILED_TOTAL = "stats/total";
    private static final String COMPILED_PRODUCT = "stats/product";
    private static final List<String> BOUND_A_AND_B = List.of("a = 3.0", "b = 4.0");
    private static final int DIAMOND_REFERENCES = 6;
    private static final float CHARACTER_WIDTH = 8f;

    @Test
    void depthIsTheLongestIncomingChainAndEdgesAreTheWiredInputs() throws Exception {
        NodeGraphRuntime runtime = run(DIAMOND);
        DslGraphLayout graph = new DslGraphLayout(runtime.root, Set.of());

        int references = 0;
        for (GraphNodeContext record : runtime.root.children) {
            for (Object source : record.inputSources.values()) {
                if (source instanceof PythonParser.NodeReference) {
                    references++;
                }
            }
        }
        assertEquals(DIAMOND_REFERENCES, references);
        assertEquals(references, graph.edgeCount, "one edge per wired input");
        assertEquals(runtime.root.children.size(), graph.nodeCount);

        int base = graph.nodeIndex(BASE);
        int repaired = graph.nodeIndex(REPAIRED);
        assertEquals(0, graph.nodeDepth[base]);
        assertEquals(1, graph.nodeDepth[graph.nodeIndex(REFINED)]);
        assertEquals(2, graph.nodeDepth[graph.nodeIndex(JOINED)], "joined's longest chain runs through refined");
        assertEquals(3, graph.nodeDepth[repaired], "repaired follows the chain through joined, not its b input");

        int fromBase = -1;
        for (int edge = 0; edge < graph.edgeCount; edge++) {
            if (graph.edgeTarget[edge] == repaired && graph.edgeSource[edge] == base) {
                fromBase = edge;
            }
        }
        assertTrue(fromBase >= 0, "the base -> repaired reference is an edge");
        assertEquals(FloatMathNode.B.name, graph.inputPortName[graph.edgeTargetPort[fromBase]]);
        assertEquals(RESULT, graph.outputPortName[graph.edgeSourcePort[fromBase]]);
        assertEquals(1, graph.outputPortStart[base + 1] - graph.outputPortStart[base],
                "base's three references share its one result output port");
        assertEquals(2, graph.literalRows[graph.nodeIndex(SMOOTHED)].length,
                "smoothed lists its operation and b, and draws its a input as a port");
    }

    @Test
    void criticalPathIsTheSlowestChain() throws Exception {
        NodeGraphRuntime runtime = run(DIAMOND);
        Map<String, Long> timing = Map.of(BASE, 5L, REFINED, 10L, SMOOTHED, 20L, JOINED, 2L, REPAIRED, 1L);
        for (Map.Entry<String, Long> node : timing.entrySet()) {
            runtime.node(node.getKey()).elapsedNanos = node.getValue() * NodeGraphRuntime.NANOS_PER_MILLI;
        }
        DslGraphLayout graph = new DslGraphLayout(runtime.root, Set.of());

        String[] path = new String[graph.criticalPath.length];
        for (int step = 0; step < path.length; step++) {
            path[step] = graph.nodeId[graph.criticalPath[step]];
        }
        assertArrayEquals(new String[] { BASE, SMOOTHED, JOINED, REPAIRED }, path);
        assertEquals(28, graph.criticalPathMs);
        assertEquals(graph.nodeIndex(SMOOTHED), graph.slowestNode);
        for (int edge = 0; edge < graph.edgeCount; edge++) {
            String from = graph.nodeId[graph.edgeSource[edge]];
            String to = graph.nodeId[graph.edgeTarget[edge]];
            boolean onPath = from.equals(BASE) && to.equals(SMOOTHED) || from.equals(SMOOTHED) && to.equals(JOINED)
                    || from.equals(JOINED) && to.equals(REPAIRED);
            assertEquals(onPath, graph.edgeCritical[edge], from + " -> " + to);
        }
    }

    @Test
    void placedColumnsRunLeftToRightWithoutOverlap() throws Exception {
        DslGraphLayout graph = new DslGraphLayout(run(DIAMOND).root, Set.of());
        graph.place(text -> text.length() * CHARACTER_WIDTH);

        for (int node = 0; node < graph.nodeCount; node++) {
            for (int other = 0; other < graph.nodeCount; other++) {
                if (graph.nodeDepth[other] == graph.nodeDepth[node] + 1) {
                    assertTrue(graph.boxX[other] > graph.boxX[node] + graph.boxWidth[node],
                            graph.nodeId[other] + " sits right of " + graph.nodeId[node]);
                }
                boolean sameColumn = other != node && graph.nodeDepth[other] == graph.nodeDepth[node];
                assertFalse(sameColumn && graph.boxY[other] < graph.boxY[node] + graph.boxHeight[node]
                        && graph.boxY[node] < graph.boxY[other] + graph.boxHeight[other],
                        graph.nodeId[node] + " and " + graph.nodeId[other] + " overlap");
            }
        }
        for (int edge = 0; edge < graph.edgeCount; edge++) {
            int span = graph.nodeDepth[graph.edgeTarget[edge]] - graph.nodeDepth[graph.edgeSource[edge]];
            assertEquals(span > 1, !Float.isNaN(graph.edgeLaneY[edge]), "only a multi-column edge takes a lane");
        }
        int base = graph.nodeIndex(BASE);
        assertEquals(graph.boxX[base] + graph.boxWidth[base], graph.outputPortX[graph.outputPortStart[base]],
                "output ports sit on the box's right edge");
    }

    @Test
    void anExpandedGroupShowsItsBodyWiredThroughItsParametersAndOutputs() throws Exception {
        NodeGraphRuntime runtime = run(NAMED_OUTPUTS);
        DslGraphLayout collapsed = new DslGraphLayout(runtime.root, Set.of());
        assertArrayEquals(new String[] { STATS, READER }, collapsed.nodeId);
        assertTrue(collapsed.nodeExpandable[collapsed.nodeIndex(STATS)]);
        assertEquals(runtime.node(STATS).elapsedMs(), collapsed.nodeMs[collapsed.nodeIndex(STATS)],
                "a collapsed call shows the group's whole time");
        assertEquals(2, collapsed.edgeCount);
        assertEquals(BOUND_A_AND_B, List.of(collapsed.literalRows[collapsed.nodeIndex(STATS)]));

        DslGraphLayout expanded = new DslGraphLayout(runtime.root, Set.of(STATS));
        assertArrayEquals(new String[] { STATS_TOTAL, "s/product", READER }, expanded.nodeId);
        assertEquals(1, expanded.groupCount);
        assertEquals(STATS, expanded.groupPath[0]);
        assertEquals(2, expanded.edgeCount, "r reads the body nodes the group's outputs come from");
        for (int edge = 0; edge < expanded.edgeCount; edge++) {
            assertEquals(READER, expanded.nodeId[expanded.edgeTarget[edge]]);
            assertTrue(expanded.nodeId[expanded.edgeSource[edge]].startsWith(STATS + GraphNodeContext.PATH_SEPARATOR));
        }
        assertEquals(BOUND_A_AND_B, List.of(expanded.literalRows[expanded.nodeIndex(STATS_TOTAL)]),
                "a parameter input shows what the call bound it to");

        expanded.place(text -> text.length() * CHARACTER_WIDTH);
        int total = expanded.nodeIndex(STATS_TOTAL);
        assertTrue(expanded.groupFrameX[0] < expanded.boxX[total]
                && expanded.groupFrameX[0] + expanded.groupFrameWidth[0] > expanded.boxX[total] + expanded.boxWidth[total]
                && expanded.groupFrameY[0] < expanded.boxY[total]
                && expanded.groupFrameY[0] + expanded.groupFrameHeight[0] > expanded.boxY[total] + expanded.boxHeight[total],
                "the frame encloses the group's body");
    }

    @Test
    void nestedExpandedGroupsResolveParametersThroughEveryLevel() throws Exception {
        NodeGraphRuntime runtime = run(NESTED);
        DslGraphLayout graph = new DslGraphLayout(runtime.root, Set.of(TOP, FIRST));
        assertArrayEquals(new String[] { FIRST_SUM, FIRST_DOUBLED, SECOND }, graph.nodeId);
        assertArrayEquals(new String[] { FIRST, TOP }, graph.groupPath, "frames deepest first");
        assertEquals(List.of("a = 1.0", "b = 2.0"), List.of(graph.literalRows[graph.nodeIndex(FIRST_SUM)]),
                "a reaches top's x, b is inner's default");
        assertEquals(2, graph.edgeCount);
        int second = graph.nodeIndex(SECOND);
        boolean fromDoubled = false;
        for (int edge = 0; edge < graph.edgeCount; edge++) {
            fromDoubled |= graph.edgeTarget[edge] == second
                    && graph.nodeId[graph.edgeSource[edge]].equals(FIRST_DOUBLED);
        }
        assertTrue(fromDoubled, "a group without a return feeds its last body node's outputs");
        assertFalse(graph.nodeExpandable[graph.nodeIndex(FIRST_SUM)]);
        assertTrue(graph.nodeExpandable[second], "the second call stays collapsed");
    }

    @Test
    void aPulledCompiledGroupShowsOnlyTheBodyNodesThatRan() throws Exception {
        GroupBuilder builder = new GroupBuilder();
        PythonParser.ParsedNode stats = builder.node(COMPILED, SumAndProductGroup::new)
                .input(FloatMathNode.A.name, 3f).input(FloatMathNode.B.name, 4f);
        builder.node(READER, FloatMathNode::new).input(FloatMathNode.A.name, stats.port(SumAndProductGroup.SUM.name));
        NodeGraphRuntime runtime = new NodeGraphRuntime(builder.statements);
        runtime.pull("stats.sum");

        DslGraphLayout graph = new DslGraphLayout(runtime.root, Set.of(COMPILED));
        assertArrayEquals(new String[] { COMPILED_TOTAL }, graph.nodeId,
                "the product was never pulled and the reader never ran");
        assertEquals(0, graph.edgeCount);
    }

    @Test
    void anOutputACompiledGroupComputesDrawsAnEdgeFromEveryWiredOutputItRead() throws Exception {
        GroupBuilder builder = new GroupBuilder();
        PythonParser.ParsedNode stats = builder.node(COMPILED, SumAndProductGroup::new)
                .input(FloatMathNode.A.name, 3f).input(FloatMathNode.B.name, 4f);
        builder.node(READER, FloatMathNode::new)
                .input(FloatMathNode.A.name, stats.port(SumAndProductGroup.DIFFERENCE.name));
        NodeGraphRuntime runtime = new NodeGraphRuntime(builder.statements);
        runtime.executeGraphResult(runtime.root.body, READER, RESULT);

        assertTrue(runtime.node(COMPILED).outputSources.get(SumAndProductGroup.DIFFERENCE.name)
                instanceof List<?> computedFrom && computedFrom.size() == 2,
                "the computed output records the two wired outputs it was computed from");
        DslGraphLayout collapsed = new DslGraphLayout(runtime.root, Set.of());
        assertEquals(1, collapsed.edgeCount, "collapsed, the reader reads the call's difference");

        DslGraphLayout expanded = new DslGraphLayout(runtime.root, Set.of(COMPILED));
        int reader = expanded.nodeIndex(READER);
        Set<String> sources = new HashSet<>();
        for (int edge = 0; edge < expanded.edgeCount; edge++) {
            assertEquals(reader, expanded.edgeTarget[edge]);
            assertEquals(FloatMathNode.A.name, expanded.inputPortName[expanded.edgeTargetPort[edge]]);
            sources.add(expanded.nodeId[expanded.edgeSource[edge]]);
        }
        assertEquals(Set.of(COMPILED_TOTAL, COMPILED_PRODUCT), sources,
                "expanded, the one input fans out to both body nodes behind the computed output");
    }

    private static NodeGraphRuntime run(String source) throws Exception {
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(source);
        String last = runtime.root.body.get(runtime.root.body.size() - 1).id;
        runtime.executeGraphResult(runtime.root.body, last, RESULT);
        return runtime;
    }
}
