package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.GroupBuilder;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.nodes.math.FloatMathNode;
import ixdar.nodegraph.syntax.PythonParser;

/**
 * The node tree, named group outputs, parameter defaults, path overrides, pull evaluation, input
 * sources, compiled groups and the group library of {@link NodeGraphRuntime}, on float-math
 * graphs small enough to check by hand.
 */
class NodeGraphTreeTest {

    // Two nested levels: outer calls inner twice, each call two float nodes.
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

    private static final String NAMED_OUTPUTS = """
            def stats(a: FLOAT, b: FLOAT) -> FLOAT:
                total = float_math(a=a, b=b)
                product = float_math(operation="MULTIPLY", a=a, b=b)
                return(sum=total.result, product=product.result)
            end
            s = stats(a=3.0, b=4.0)
            r = float_math(a=s.sum, b=s.product)
            """;

    private static final String TOP = "top";
    private static final String FIRST = "top/first";
    private static final String SECOND = "top/second";
    private static final String FIRST_SUM = "top/first/sum";
    private static final String FIRST_SUM_B_OVERRIDE = "top/first/sum.b";
    private static final String SECOND_SUM = "top/second/sum";
    private static final String SECOND_DOUBLED = "top/second/doubled";
    private static final String FLOAT_MATH = "float_math";
    private static final String RESULT = FloatMathNode.RESULT.name;
    private static final String OPERATION = FloatMathNode.OPERATION.name;
    private static final String A = FloatMathNode.A.name;
    private static final String B = FloatMathNode.B.name;
    private static final String SUM = SumAndProductGroup.SUM.name;
    private static final String PRODUCT_PORT = SumAndProductGroup.PRODUCT.name;
    private static final String STATS_CALL = "s";
    private static final String TOTAL = "s/total";
    private static final String PRODUCT = "s/product";
    private static final String READER = "r";
    private static final String COMPILED_CALL = "stats";
    private static final String COMPILED_TOTAL = "stats/total";
    private static final String COMPILED_PRODUCT = "stats/product";
    private static final String COMPILED_SUM_PULL = "stats.sum";
    private static final String STATS_SUM_PULL = "s.sum";
    private static final String READER_PULL = "r.result";
    private static final float TOLERANCE = 1e-6f;
    private static final int CUBE_CORNERS = 8;

    @Test
    void treeRecordsEveryNodeOfATwoLevelNestedGroup() throws Exception {
        NodeGraphRuntime runtime = run(NESTED, Map.of());
        List<String> paths = new ArrayList<>();
        for (GraphNodeContext record : runtime.treeRecords()) {
            paths.add(record.path);
        }
        assertEquals(List.of(TOP, FIRST, FIRST_SUM, "top/first/doubled", SECOND,
                SECOND_SUM, SECOND_DOUBLED), paths);

        GraphNodeContext inner = runtime.node(SECOND_SUM);
        assertEquals(FLOAT_MATH, inner.type);
        assertNotNull(inner.node, "a leaf keeps its node instance");
        assertEquals(6f, (Float) inner.getInputValue(A), TOLERANCE);
        assertEquals(10f, (Float) inner.getInputValue(B), TOLERANCE);
        assertEquals(16f, (Float) inner.getOutput(RESULT), TOLERANCE);
        assertTrue(inner.peakHeapBytes > 0, "peak heap is sampled per node");

        GraphNodeContext call = runtime.node(FIRST);
        assertEquals("inner", call.type);
        assertNull(call.node, "a DSL group call has no node instance");
        assertEquals(2f, (Float) call.getInputValue(B), TOLERANCE, "the default binds b");
        assertEquals(FIRST_SUM, call.children.get(0).path);
        assertTrue(runtime.node(TOP).elapsedNanos >= call.elapsedNanos);
        assertEquals(32f, (Float) runtime.lastOutput(RESULT), TOLERANCE);
        assertTrue(runtime.treeRecords().stream().anyMatch(record ->
                SECOND_DOUBLED.equals(record.path) && FLOAT_MATH.equals(record.type)));
    }

    @Test
    void namedOutputsAreReadAsPortsOfTheCall() throws Exception {
        NodeGraphRuntime runtime = run(NAMED_OUTPUTS, Map.of());
        assertEquals(7f, (Float) runtime.getNodeOutput(STATS_CALL, SUM), TOLERANCE);
        assertEquals(12f, (Float) runtime.getNodeOutput(STATS_CALL, PRODUCT_PORT), TOLERANCE);
        assertEquals(19f, (Float) runtime.lastOutput(RESULT), TOLERANCE);
    }

    @Test
    void pathOverridesReachParametersAndNodesInsideGroups() throws Exception {
        NodeGraphRuntime inside = run(NESTED, Map.of(FIRST_SUM_B_OVERRIDE, 5f));
        assertEquals(12f, (Float) inside.getNodeOutput(FIRST, RESULT), TOLERANCE);
        assertEquals(12f, (Float) inside.node(SECOND_SUM).getInputValue(A), TOLERANCE,
                "the second call reads the overridden first, its own b untouched");
        assertEquals(44f, (Float) inside.lastOutput(RESULT), TOLERANCE);

        NodeGraphRuntime parameter = run(NESTED, Map.of("top.x", 2f));
        assertEquals(36f, (Float) parameter.lastOutput(RESULT), TOLERANCE);
    }

    @Test
    void unknownOverridePathOrPortFailsNamingTheNearestRealOnes() {
        IllegalArgumentException path = assertThrows(IllegalArgumentException.class,
                () -> run(NESTED, Map.of("top/frist/sum.b", 5f)));
        assertTrue(path.getMessage().contains("nearest: " + FIRST_SUM), path.getMessage());

        IllegalArgumentException port = assertThrows(IllegalArgumentException.class,
                () -> run(NESTED, Map.of("top/first/sum.c", 5f)));
        assertTrue(port.getMessage().contains("has no port 'c'"), port.getMessage());
        assertTrue(port.getMessage().contains(OPERATION), port.getMessage());
    }

    @Test
    void pullEvaluatesOnlyWhatTheRequestedOutputDependsOn() throws Exception {
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(NAMED_OUTPUTS);
        assertEquals(7f, (Float) runtime.pull(STATS_SUM_PULL), TOLERANCE);
        assertNotNull(runtime.node(TOTAL));
        assertNull(runtime.node(PRODUCT), "the product output was never read");
        assertNull(runtime.node(READER));

        assertEquals(12f, (Float) runtime.pull("s/product.result"), TOLERANCE);
        assertNotNull(runtime.node(PRODUCT));
    }

    @Test
    void fullRunsAndPullsReportTheRunTotalTheSameWayAndMoveTheRevision() throws Exception {
        NodeGraphRuntime full = NodeGraphRuntime.fromSource(NAMED_OUTPUTS);
        int created = full.revision;
        long before = System.nanoTime();
        full.executeGraphResult(full.root.body, READER, RESULT);
        long fullWall = System.nanoTime() - before;
        assertEquals(created + 2, full.revision, "the run's reset and its evaluation each count");
        assertTotalIsTheRunsOwnTime(full, fullWall);

        NodeGraphRuntime pulled = NodeGraphRuntime.fromSource(NAMED_OUTPUTS);
        int start = pulled.revision;
        before = System.nanoTime();
        pulled.pull(STATS_SUM_PULL);
        pulled.pull(STATS_SUM_PULL);
        pulled.pull(READER_PULL);
        long pullWall = System.nanoTime() - before;
        assertEquals(start + 3, pulled.revision, "each pull is one revision");
        assertTotalIsTheRunsOwnTime(pulled, pullWall);

        GraphNodeContext root = pulled.root;
        pulled.executeGraphResult(pulled.root.body, READER, RESULT);
        assertTrue(pulled.root != root && pulled.root.elapsedNanos <= System.nanoTime() - before,
                "a full run starts a fresh report rather than adding to the pulls'");
    }

    /**
     * Asserts a run's total spans its top-level records and no more than the wall time its entry
     * points took.
     *
     * @param runtime the runtime whose report is checked
     * @param wallNanos wall time measured around the entry points
     */
    private static void assertTotalIsTheRunsOwnTime(NodeGraphRuntime runtime, long wallNanos) {
        long topLevel = 0;
        for (GraphNodeContext record : runtime.root.children) {
            topLevel += record.elapsedNanos;
        }
        assertTrue(runtime.root.elapsedNanos >= topLevel, "the total covers every top-level record");
        assertTrue(runtime.root.elapsedNanos <= wallNanos, "the total is not summed twice");
    }

    @Test
    void aMissingArgumentWithoutDefaultFails() {
        RuntimeException missing = assertThrows(RuntimeException.class,
                () -> run("def g(a: FLOAT) -> FLOAT:\n n = float_math(a=a)\nend\nq = g()\n", Map.of()));
        assertTrue(missing.getMessage().contains("missing argument 'a'"), missing.getMessage());
    }

    @Test
    void libraryGroupsAreCallableWithoutADefinition() throws Exception {
        NodeGraphRuntime runtime = run("c = subdivided_cube(size=2.0, levels=0)\n", Map.of());
        Object mesh = runtime.getNodeOutput("c", "geometry");
        assertTrue(mesh instanceof GeometryBundle bundle && bundle.mesh().vertexCount() == CUBE_CORNERS);

        NodeGraphRuntime ownDefinition = NodeGraphRuntime.fromResource("dsl/test_function.dsl");
        Object cube = ownDefinition.pull("result/c.mesh");
        assertTrue(cube instanceof GeometryBundle bundle && bundle.mesh().vertexCount() == CUBE_CORNERS,
                "a program's own group shadows the library's");
    }

    @Test
    void everyRecordNamesEachInputsSourceInADslGraph() throws Exception {
        NodeGraphRuntime named = run(NAMED_OUTPUTS, Map.of());
        assertSource(named.node(TOTAL), A, STATS_CALL, A);
        assertSource(named.node(PRODUCT), B, STATS_CALL, B);
        assertEquals(SumAndProductGroup.MULTIPLY, named.node(PRODUCT).inputSources.get(OPERATION));
        assertEquals(3f, (Float) named.node(STATS_CALL).inputSources.get(A), TOLERANCE);
        assertSource(named.node(READER), A, STATS_CALL, SUM);
        assertSource(named.node(READER), B, STATS_CALL, PRODUCT_PORT);
        PythonParser.NodeReference sum = (PythonParser.NodeReference) named.node(STATS_CALL)
                .outputSources.get(SUM);
        assertEquals(TOTAL, sum.nodeId, "a group output's source is the inner node");
        assertEquals(RESULT, sum.portName);

        NodeGraphRuntime nested = run(NESTED, Map.of(FIRST_SUM_B_OVERRIDE, 5f));
        assertSource(nested.node(SECOND), A, FIRST, RESULT);
        assertEquals(10f, (Float) nested.node(SECOND).inputSources.get(B), TOLERANCE);
        assertEquals(2f, (Float) nested.node(FIRST).inputSources.get(B), TOLERANCE,
                "an omitted parameter's source is its default");
        assertEquals(5f, (Float) nested.node(FIRST_SUM).inputSources.get(B), TOLERANCE,
                "an overridden input's source is the override");
    }

    @Test
    void aCompiledGroupsInnerNodesAreRecordsPulledAndOverriddenByPath() throws Exception {
        NodeGraphRuntime runtime = new NodeGraphRuntime(compiledGraph());
        assertEquals(7f, (Float) runtime.pull(COMPILED_SUM_PULL), TOLERANCE);
        GraphNodeContext call = runtime.node(COMPILED_CALL);
        assertTrue(call.node instanceof SumAndProductGroup, "a compiled call keeps its group");
        assertEquals(FLOAT_MATH, runtime.node(COMPILED_TOTAL).type);
        assertNotNull(runtime.node(COMPILED_TOTAL).node, "the runtime evaluated the inner node");
        assertNull(runtime.node(COMPILED_PRODUCT), "pulling sum never ran the product");

        assertEquals(-5f, (Float) runtime.pull("stats.difference"), TOLERANCE,
                "the group computes its unwired output from the wired ones");
        assertNotNull(runtime.node(COMPILED_PRODUCT));
        assertEquals(19f, (Float) runtime.pull(READER_PULL), TOLERANCE);

        assertSource(runtime.node(COMPILED_TOTAL), A, COMPILED_CALL, A);
        assertEquals(SumAndProductGroup.MULTIPLY,
                runtime.node(COMPILED_PRODUCT).inputSources.get(OPERATION));
        assertEquals(3f, (Float) call.inputSources.get(A), TOLERANCE);
        assertSource(runtime.node(READER), B, COMPILED_CALL, PRODUCT_PORT);

        NodeGraphRuntime overridden = new NodeGraphRuntime(compiledGraph());
        overridden.setOverride("stats/total.b", 5f);
        assertEquals(8f, (Float) overridden.pull(COMPILED_SUM_PULL), TOLERANCE);
        assertEquals(5f, (Float) overridden.node(COMPILED_TOTAL).inputSources.get(B), TOLERANCE);

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> new NodeGraphRuntime(compiledGraph()).setOverride("stats/totl.b", 1f));
        assertTrue(unknown.getMessage().contains("nearest: " + COMPILED_TOTAL), unknown.getMessage());
    }

    /**
     * A Java-built graph: the compiled group over two literals, and a node reading both of its
     * wired outputs.
     *
     * @return the graph's statements
     */
    private static List<PythonParser.ParsedNode> compiledGraph() {
        GroupBuilder graph = new GroupBuilder();
        PythonParser.ParsedNode stats = graph.node(COMPILED_CALL, SumAndProductGroup::new)
                .input(A, 3f).input(B, 4f);
        graph.node(READER, FloatMathNode::new).input(A, stats.port(SUM))
                .input(B, stats.port(PRODUCT_PORT));
        return graph.statements;
    }

    private static void assertSource(GraphNodeContext record, String port, String path,
            String sourcePort) {
        Object source = record.inputSources.get(port);
        assertTrue(source instanceof PythonParser.NodeReference, record.path + "." + port
                + " is wired, its source " + source);
        PythonParser.NodeReference reference = (PythonParser.NodeReference) source;
        assertEquals(path, reference.nodeId, record.path + "." + port + " source path");
        assertEquals(sourcePort, reference.portName, record.path + "." + port + " source port");
    }

    private static NodeGraphRuntime run(String source, Map<String, Object> overrides)
            throws Exception {
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(source);
        String last = runtime.root.body.get(runtime.root.body.size() - 1).id;
        runtime.executeGraphResult(runtime.root.body, last, RESULT, overrides);
        return runtime;
    }
}
