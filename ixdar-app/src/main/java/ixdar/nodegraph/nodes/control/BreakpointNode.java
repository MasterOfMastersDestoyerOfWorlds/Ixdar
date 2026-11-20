package ixdar.nodegraph.nodes.control;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.syntax.PythonParser;

/**
 * The {@code breakpoint} statement: where the graph debugger stops before stepping the rest of a
 * program node by node. Written bare, {@code breakpoint} or {@code breakpoint("before fit")}, with
 * no assignment; every other run evaluates it as a no-op.
 */
@MeshNodeAnnotation(id = PythonParser.BREAKPOINT, category = NodeCategory.CONTROL_FLOW)
public final class BreakpointNode implements MeshNode {

    public static final InputPort LABEL = new InputPort(PythonParser.BREAKPOINT_LABEL, PortType.STRING, "");

    @Override
    public List<InputPort> inputs() {
        return List.of(LABEL);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of();
    }

    @Override
    public String description() {
        return "Where the graph debugger stops; does nothing in any other run.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(LABEL.name, "What the debugger shows when it stops here, e.g. \"before fit\".");
    }

    /**
     * Does nothing: a breakpoint only marks a place in the program for the debugger.
     *
     * @param ctx unused
     */
    @Override
    public void evaluate(NodeContext ctx) {
    }
}
