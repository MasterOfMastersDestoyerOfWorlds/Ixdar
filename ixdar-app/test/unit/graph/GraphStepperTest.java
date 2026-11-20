package unit.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.ArrangementDiagnostic;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.nodegraph.nodes.quadlayout.NetworkContractionNode;
import ixdar.nodegraph.runtime.GraphStepper;
import ixdar.nodegraph.runtime.GraphStepper.Action;
import ixdar.nodegraph.runtime.GraphStepper.Move;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.syntax.PythonParser;
import ixdar.nodegraph.syntax.PythonParser.ParsedNode;

/**
 * The {@code breakpoint} statement and the graph debugger's stepped runs: a breakpoint changes no
 * normal run, a stepped run stops after it, stepping back lands where stepping forward fewer
 * times does, and the contraction's inner steps reach what contracting it whole does.
 */
class GraphStepperTest {

    private static final String FAN_COLLAPSE = "dsl/fixtures/fan_collapse.dsl";

    private static final String TORUS_LAYOUT = "dsl/fixtures/torus_layout.dsl";

    private static final String NETWORK = "network";

    private static final String NET_PORT = "net";

    private static final int FAN_SPOKES_BESIDE_THE_ZERO_ONE = 11;

    private static final String CONTRACTION = "\nbreakpoint(\"before contraction\")\n"
            + "contracted = tmesh_contract(tmesh=network.net)\n";

    private static final String CONTRACTED = "contracted";

    private static final String CONTRACTED_PORT = NetworkContractionNode.TMESH_OUT.name;

    private static final String GROUP_CALL = "called";

    private static final String GROUP_AFTER_BREAKPOINT = GROUP_CALL + "/large";

    @Test
    void parsesBareAndLabelledBreakpoints() {
        List<ParsedNode> statements = PythonParser.parse("a = cube(size=1.0)\nbreakpoint\n"
                + "b = cube(size=2.0)\nbreakpoint(\"before fit\")\nbreakpoint = cube(size=3.0)\n");

        assertEquals(5, statements.size(), "two breakpoints among three assignments");
        assertEquals(PythonParser.BREAKPOINT, statements.get(1).type);
        assertTrue(statements.get(1).arguments.isEmpty(), "a bare breakpoint has no label");
        assertEquals(PythonParser.BREAKPOINT, statements.get(3).type);
        assertEquals("before fit", statements.get(3).arguments.get(PythonParser.BREAKPOINT_LABEL));
        assertEquals("breakpoint", statements.get(4).id,
                "an assignment to a variable named breakpoint is still an assignment");
        assertEquals("cube", statements.get(4).type);
    }

    @Test
    void aBreakpointChangesNothingInAFullRun() throws Exception {
        String fixture = resource(FAN_COLLAPSE);
        int afterNetwork = fixture.indexOf('\n', fixture.indexOf("network = arc_network")) + 1;
        String withBreakpoint = fixture.substring(0, afterNetwork) + "breakpoint\n"
                + fixture.substring(afterNetwork);

        ArcNetwork plain = runNetwork(fixture);
        ArcNetwork stopped = runNetwork(withBreakpoint);

        assertEquals(plain.nodes.size(), stopped.nodes.size());
        assertEquals(plain.arcs.size(), stopped.arcs.size());
        assertEquals(plain.patches.size(), stopped.patches.size());
    }

    @Test
    void continueStopsJustAfterTheBreakpoint() throws Exception {
        GraphStepper stepper = stepper(TORUS_LAYOUT);

        assertTrue(stepper.continueToBreakpoint());

        assertTrue(stepper.atBreakpoint);
        assertNull(stepper.failure);
        assertEquals(CONTRACTED, stepper.next().path, "the contraction is the next statement");
        assertNull(stepper.runtime.node(CONTRACTED), "nothing after the breakpoint has run");
    }

    @Test
    void steppingBackLandsWhereSteppingForwardFewerTimesDoes() throws Exception {
        GraphStepper stepper = intoContraction(TORUS_LAYOUT);
        for (int operator = 0; operator < 3; operator++) {
            move(stepper, Move.INNER, NetworkContractionNode.OPERATOR_LEVEL);
        }
        stepper.back();
        stepper.back();

        GraphStepper fresh = intoContraction(TORUS_LAYOUT);
        move(fresh, Move.INNER, NetworkContractionNode.OPERATOR_LEVEL);

        assertEquals(state(fresh), state(stepper));
        move(stepper, Move.INNER, NetworkContractionNode.OPERATOR_LEVEL);
        move(fresh, Move.INNER, NetworkContractionNode.OPERATOR_LEVEL);
        assertEquals(state(fresh), state(stepper), "forward again from the stepped-back state");
    }

    @Test
    void dragsOfOneCollapseAddUpToOneOperator() throws Exception {
        NodeGraphRuntime oneShot = NodeGraphRuntime.fromSource(resource(FAN_COLLAPSE));
        oneShot.executeGraphResult(oneShot.lastId(), NodeGraphRuntime.RESULT);
        ArcNetwork oneShotNet = (ArcNetwork) oneShot.getNodeOutput(NETWORK, NET_PORT);
        new NetworkContraction(oneShotNet).collapseArc.collapse(oneShot.intOutput("zeroSpokeArcId"));

        GraphStepper byDrag = intoContraction(FAN_COLLAPSE);
        NetworkContractionNode dragged = (NetworkContractionNode) byDrag.inner;
        move(byDrag, Move.INNER, NetworkContractionNode.DRAG_LEVEL);
        assertInstanceOf(ArrangementDiagnostic.class, dragged.stepOverlay(),
                "a collapse in flight is drawn as its drag diagnostic");
        int drags = 0;
        while (dragged.stepping.arcCollapseCount == 0) {
            move(byDrag, Move.INNER, NetworkContractionNode.DRAG_LEVEL);
            drags++;
        }

        assertEquals(FAN_SPOKES_BESIDE_THE_ZERO_ONE + 1, drags, "one drag per fan arc, then the merge");
        assertEquals(alive(oneShotNet), alive(dragged.stepping.network));
        assertEquals(oneShotNet.nodes.stream().filter(node -> node.alive).count(),
                dragged.stepping.network.nodes.stream().filter(node -> node.alive).count());
        assertTrue(byDrag.back());
        assertEquals(0, ((NetworkContractionNode) byDrag.inner).stepping.arcCollapseCount,
                "back lands before the merge");
    }

    @Test
    void innerStepsToTheEndMatchContractingWhole() throws Exception {
        String source = resource(TORUS_LAYOUT) + CONTRACTION;
        NodeGraphRuntime whole = NodeGraphRuntime.fromSource(source);
        whole.executeGraphResult(CONTRACTED, CONTRACTED_PORT);
        ArcNetwork expected = (ArcNetwork) whole.getNodeOutput(CONTRACTED, CONTRACTED_PORT);

        GraphStepper stepper = intoContraction(TORUS_LAYOUT);
        do {
            move(stepper, Move.INNER, NetworkContractionNode.OPERATOR_LEVEL);
        } while (stepper.inner != null);
        ArcNetwork stepped = (ArcNetwork) stepper.runtime.getNodeOutput(CONTRACTED, CONTRACTED_PORT);

        assertNotNull(stepped, "finishing the stepper publishes the node's output");
        assertEquals(alive(expected), alive(stepped));
        assertEquals(expected.patches.stream().filter(patch -> patch.alive).count(),
                stepped.patches.stream().filter(patch -> patch.alive).count());
        assertTrue(stepper.apply(new Action(Move.OVER, 0)));
        assertTrue(stepper.finished);
    }

    @Test
    void stepsIntoAGroupCallStatementByStatement() throws Exception {
        String source = "def boxes(size: FLOAT) -> GEOMETRY_BUNDLE:\n"
                + "    small = cube(size=size)\n"
                + "    breakpoint\n"
                + "    large = cube(size=4.0)\n"
                + "end\n"
                + "first = cube(size=1.0)\n"
                + "called = boxes(size=2.0)\n";
        GraphStepper stepper = new GraphStepper(NodeGraphRuntime.fromSource(source), Map.of());

        assertTrue(stepper.continueToBreakpoint());

        assertEquals(1, stepper.depth(), "continuing entered the group holding the breakpoint");
        assertEquals(GROUP_AFTER_BREAKPOINT, stepper.next().path);
        assertNotNull(stepper.runtime.node(GROUP_CALL + "/small"));
        assertNull(stepper.runtime.node(GROUP_AFTER_BREAKPOINT));
        assertTrue(stepper.apply(new Action(Move.OVER, 0)));
        assertTrue(stepper.apply(new Action(Move.OVER, 0)), "leaving the group computes its outputs");
        assertEquals(0, stepper.depth());
        assertSame(stepper.runtime.node(GROUP_CALL), stepper.last);
        assertNotNull(stepper.runtime.getNodeOutput(GROUP_CALL, "mesh"));
    }

    @Test
    void runUntilFailureStopsAtTheFailingStatementAndBackRestoresBeforeIt() throws Exception {
        String source = "ball = icosphere(radius=1.0, subdivisions=1)\n"
                + "bad = load_mesh(path=\"no/such/mesh.off\")\n"
                + "after = icosphere(radius=2.0, subdivisions=1)\n";
        GraphStepper stepper = new GraphStepper(NodeGraphRuntime.fromSource(source), Map.of());

        assertTrue(stepper.runUntilFailure());

        assertNotNull(stepper.failure);
        assertEquals(1, stepper.history.size(), "only the move before the failure is kept");
        assertTrue(stepper.back());
        assertNull(stepper.failure);
        assertEquals("bad", stepper.next().path, "back lands just before the failing statement");
        assertFalse(stepper.apply(new Action(Move.OVER, 0)), "stepping it again fails again");
    }

    private static GraphStepper stepper(String fixture) throws IOException {
        return new GraphStepper(NodeGraphRuntime.fromSource(resource(fixture) + CONTRACTION),
                Map.of());
    }

    private static GraphStepper intoContraction(String fixture) throws IOException {
        GraphStepper stepper = stepper(fixture);
        assertTrue(stepper.continueToBreakpoint(), () -> "run failed: " + stepper.failure);
        move(stepper, Move.INTO, 0);
        assertInstanceOf(NetworkContractionNode.class, stepper.inner);
        return stepper;
    }

    private static void move(GraphStepper stepper, Move move, int level) {
        assertTrue(stepper.apply(new Action(move, level)),
                () -> move + " " + level + " failed: " + stepper.failure);
    }

    private static String state(GraphStepper stepper) {
        NetworkContraction inner = ((NetworkContractionNode) stepper.inner).stepping;
        return "collapses=" + inner.arcCollapseCount + " splits="
                + inner.patchSplitCount + " aliveArcs=" + alive(inner.network)
                + " moves=" + stepper.history.size();
    }

    private static long alive(ArcNetwork network) {
        return network.arcs.stream().filter(arc -> arc.alive).count();
    }

    private static ArcNetwork runNetwork(String source) throws Exception {
        NodeGraphRuntime run = NodeGraphRuntime.fromSource(source);
        run.executeGraphResult(run.lastId(), NodeGraphRuntime.RESULT);
        return (ArcNetwork) run.getNodeOutput(NETWORK, NET_PORT);
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = GraphStepperTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
