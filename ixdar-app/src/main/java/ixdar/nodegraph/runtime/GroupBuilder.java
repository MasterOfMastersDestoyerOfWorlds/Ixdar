package ixdar.nodegraph.runtime;

import java.util.function.Supplier;

import ixdar.nodegraph.api.MeshNode;

/**
 * Builds the records of one scope of a run's node tree, a program's top level or a group call's
 * body, each a node wired to earlier records, so Java-built and DSL graphs evaluate alike.
 */
public class GroupBuilder {

    public static final String IN_FUNCTION = "In function '";

    /** The runtime whose group table and registry resolve node types by name. */
    public final NodeGraphRuntime runtime;

    /**
     * The record whose body this builds: the run's root or a group call. A body reads a group
     * parameter by wiring to it, e.g. {@code .input("geometry", group.scope, "geometry")}.
     */
    public final GraphNodeContext scope;

    /**
     * A builder adding records under a scope of a runtime's tree.
     *
     * @param runtime the runtime the tree belongs to
     * @param scope   the record whose body the builder declares
     */
    public GroupBuilder(NodeGraphRuntime runtime, GraphNodeContext scope) {
        this.runtime = runtime;
        this.scope = scope;
    }

    /**
     * Declares a node, e.g. {@code group.node("field", NDirectionField::new)}; wire its inputs
     * with {@link GraphNodeContext#input}. A group's body is built under it at once.
     *
     * @param id       call-site id, the node's path segment
     * @param supplier constructor of the node, called once per run
     * @throws IllegalArgumentException when the id is already taken or groups nest too deep
     * @return the node's record, for wiring
     */
    public GraphNodeContext node(String id, Supplier<? extends MeshNode> supplier) {
        MeshNode node = supplier.get();
        return declare(id, NodeGraphRuntime.typeOf(node), node);
    }

    /**
     * Declares a node by name, as a DSL call names it: a group of the runtime, a registered node
     * or a library group. Code the web build reaches uses this for a desktop-only node, which it
     * must never construct directly.
     *
     * @param id   call-site id, the node's path segment
     * @param type group name or registered node id, e.g. {@code quad_layout}
     * @throws IllegalArgumentException when nothing goes by that name in this build, e.g. a
     *                                  desktop-only node on the web
     * @return the node's record, for wiring
     */
    public GraphNodeContext node(String id, String type) {
        Supplier<? extends MeshNode> supplier = runtime.resolve(type);
        if (supplier == null) {
            String where = scope.parent == null ? "" : IN_FUNCTION + scope.type + "': ";
            throw new IllegalArgumentException(where + NodeGraphRuntime.missingNodeMessage(type));
        }
        return declare(id, type, supplier.get());
    }

    /**
     * Exposes an output of a body record, or a parameter of the group, as a named output of the
     * group, as {@code return(name=source.port)} does in a DSL group.
     *
     * @param name   the group's output port name
     * @param source a record of the body, or {@link #scope} for a parameter
     * @param port   the source's output port, or the parameter's name
     */
    public void output(String name, GraphNodeContext source, String port) {
        scope.outputWires.put(name, source);
        scope.outputWirePorts.put(name, port);
    }

    /**
     * Exposes a literal as a named output of the group.
     *
     * @param name    the group's output port name
     * @param literal the value the output takes
     */
    public void output(String name, Object literal) {
        scope.outputWires.put(name, literal);
        scope.outputWirePorts.remove(name);
    }

    /**
     * Adds a record under the scope and, for a group, builds its body below it.
     *
     * @param id   call-site id
     * @param type the record's type
     * @param node the node instance
     * @throws IllegalArgumentException when the id is already taken or groups nest deeper than
     *                                  {@link NodeGraphRuntime#MAX_GROUP_DEPTH}
     * @return the record
     */
    private GraphNodeContext declare(String id, String type, MeshNode node) {
        for (GraphNodeContext sibling : scope.declared) {
            if (sibling.nodeAssignmentId().equals(id)) {
                throw new IllegalArgumentException("node id '" + id + "' is declared twice");
            }
        }
        GraphNodeContext record = new GraphNodeContext();
        record.path = scope.parent == null ? id : scope.path + GraphNodeContext.PATH_SEPARATOR + id;
        record.type = type;
        record.node = node;
        record.parent = scope;
        record.graphInputs = runtime.graphInputs;
        record.deferredFailures = runtime.deferredFailures;
        record.setNodeAssignmentId(id);
        scope.declared.add(record);
        if (node instanceof NodeGroup group) {
            int depth = record.path.split(GraphNodeContext.PATH_SEPARATOR).length;
            if (depth > NodeGraphRuntime.MAX_GROUP_DEPTH) {
                throw new IllegalArgumentException("groups nest deeper than "
                        + NodeGraphRuntime.MAX_GROUP_DEPTH + " at " + record.path);
            }
            group.build(new GroupBuilder(runtime, record));
        }
        return record;
    }
}
