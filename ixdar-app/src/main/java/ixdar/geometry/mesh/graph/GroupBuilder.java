package ixdar.geometry.mesh.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.parsing.python.PythonParser;

/**
 * Builds a node graph in Java, a compiled {@link NodeGroup}'s body or a scene's graph, as the
 * statements a parsed DSL program yields, so the runtime evaluates them exactly like parsed ones.
 */
public class GroupBuilder {

    /** The statements in declaration order; a statement may only reference earlier ones. */
    public final List<PythonParser.ParsedNode> statements = new ArrayList<>();

    /** Named outputs, each a reference into {@link #statements} or to a group parameter. */
    public final Map<String, Object> outputs = new LinkedHashMap<>();

    /**
     * Adds a node statement, e.g. {@code group.node("field", NDirectionField::new)}; wire its
     * inputs with {@link PythonParser.ParsedNode#input} and read its outputs with
     * {@link PythonParser.ParsedNode#port}.
     *
     * @param id       call-site id, the node's path segment
     * @param supplier constructor of the node, called once per evaluation
     * @throws IllegalArgumentException when the id is already taken
     * @return the statement, for wiring
     */
    public PythonParser.ParsedNode node(String id, Supplier<? extends MeshNode> supplier) {
        for (PythonParser.ParsedNode statement : statements) {
            if (statement.id.equals(id)) {
                throw new IllegalArgumentException("node id '" + id + "' is declared twice");
            }
        }
        PythonParser.ParsedNode statement = new PythonParser.ParsedNode();
        statement.id = id;
        statement.supplier = supplier;
        statement.type = NodeGraphRuntime.typeOf(supplier.get());
        statements.add(statement);
        return statement;
    }

    /**
     * Adds a node statement by registered id, as a DSL call names it; code the web build reaches
     * uses this for a desktop-only node, which it must never construct directly.
     *
     * @param id   call-site id, the node's path segment
     * @param type registered node id, e.g. {@code quad_layout}
     * @throws IllegalStateException when this build lacks the node, e.g. a desktop-only one on
     *                               the web
     * @return the statement, for wiring
     */
    public PythonParser.ParsedNode node(String id, String type) {
        Supplier<? extends MeshNode> supplier = NodeGraphRuntime.supplierFor(type);
        if (supplier == null) {
            throw new IllegalStateException(NodeGraphRuntime.missingNodeMessage(type));
        }
        return node(id, supplier);
    }

    /**
     * A reference to one of the group's own input ports, as a bare parameter name is in a DSL
     * group body.
     *
     * @param name the group's input port name
     * @return the reference, for a statement's input or an output
     */
    public PythonParser.NodeReference parameter(String name) {
        return new PythonParser.NodeReference(name, NodeGraphRuntime.RESULT);
    }

    /**
     * Exposes an inner node's output as a named output of the group, as {@code return(name=ref)}
     * does in a DSL group.
     *
     * @param name   the group's output port name
     * @param source the inner output, from {@link PythonParser.ParsedNode#port}
     */
    public void output(String name, PythonParser.NodeReference source) {
        outputs.put(name, source);
    }
}
