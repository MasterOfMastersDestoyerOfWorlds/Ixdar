package ixdar.nodegraph.runtime;

import java.util.List;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;

/**
 * A node group, compiled in Java or loaded from a DSL {@code def}, or a whole program: its
 * {@link #build} declares the inner nodes and wiring, its input ports are the group's parameters.
 * The runtime, never the group, evaluates the inner nodes, so each is a tree record.
 */
public abstract class NodeGroup implements MeshNode {

    /**
     * The group's parameters; a program has none.
     *
     * @return the input ports, none by default
     */
    @Override
    public List<InputPort> inputs() {
        return List.of();
    }

    /**
     * Outputs the group computes in {@link #evaluate} beyond those {@link #build} wires; a DSL
     * group or a program declares none.
     *
     * @return the output ports, none by default
     */
    @Override
    public List<OutputPort> outputs() {
        return List.of();
    }

    /**
     * Computes the outputs {@link #build} does not wire, once every wired output has been read;
     * {@code ctx.getOutput} reads the wired ones. It never evaluates a node. The default computes
     * nothing.
     *
     * @param ctx the group call's record, its wired outputs set
     */
    @Override
    public void evaluate(NodeContext ctx) {
    }

    /**
     * Declares the inner nodes, e.g. {@code group.node("field", NDirectionField::new)
     * .input("geometry", group.scope, "geometry")}, and the outputs wired to them. The runtime
     * calls it once per call of the group on every run, building that call's body records.
     *
     * @param group the builder receiving the body
     */
    public abstract void build(GroupBuilder group);
}
