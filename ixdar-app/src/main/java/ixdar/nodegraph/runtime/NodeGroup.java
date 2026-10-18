package ixdar.nodegraph.runtime;

import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;

/**
 * A node group compiled in Java: its subclass declares inner nodes and wiring through
 * {@link #build}, its input ports being the group's parameters. The runtime, never the group,
 * evaluates the inner nodes, so each is a tree record.
 */
public abstract class NodeGroup implements MeshNode {

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
     * .input("geometry", group.parameter("geometry"))}, and the outputs wired to them. The runtime
     * calls it once per run and shares the body between every call of the group.
     *
     * @param group the builder receiving the body
     */
    public abstract void build(GroupBuilder group);
}
