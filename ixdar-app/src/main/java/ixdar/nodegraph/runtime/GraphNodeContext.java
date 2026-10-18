package ixdar.nodegraph.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ixdar.nodegraph.field.FieldContext;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;

/**
 * One evaluated node of a {@link NodeGraphRuntime} run and the {@link NodeContext} it evaluated
 * through: its path, type, instance, input and output bags, wall time, peak heap and, for a group
 * call, the body nodes it evaluated. The records form the run's node tree.
 */
public class GraphNodeContext implements NodeContext {

    public static final String PATH_SEPARATOR = "/";

    /** The running graph's failure list {@link #reportFailure} adds to, or null to throw. */
    public List<String> deferredFailures;

    /** Call-site ids from the top level down, joined with {@link #PATH_SEPARATOR}; empty for the root. */
    public String path = "";

    /** Registered node id or group name this statement called; {@code "graph"} for the root. */
    public String type = "graph";

    /**
     * The node this record evaluates, built with the record: a {@link NodeGroup} for a group
     * call, DSL or compiled; {@code null} for the root.
     */
    public MeshNode node;

    /** The record whose scope holds this one; {@code null} for the root. */
    public GraphNodeContext parent;

    /**
     * The records of the scope this record opens, a group's body or the program for the root, in
     * declaration order and evaluated or not; {@link #children} holds the evaluated ones.
     */
    public final List<GraphNodeContext> declared = new ArrayList<>();

    /**
     * Each input's source by port: the source record when wired, its port in
     * {@link #inputSourcePorts} (a group parameter: the enclosing call and the parameter's name),
     * else the literal value. Set when the graph is built; an override replaces it on evaluation.
     */
    public final Map<String, Object> inputSources = new HashMap<>();

    /** The source port of each wired input in {@link #inputSources}. */
    public final Map<String, String> inputSourcePorts = new HashMap<>();

    /**
     * For a group call, each output its body wires, keyed and valued as {@link #inputSources}:
     * a body record, the call itself for a parameter, or a literal.
     */
    public final Map<String, Object> outputWires = new LinkedHashMap<>();

    /** The source port of each wired output in {@link #outputWires}. */
    public final Map<String, String> outputWirePorts = new LinkedHashMap<>();

    /**
     * For a group call, where each output read so far came from, valued as {@link #inputSources};
     * an output a compiled group's evaluate computed holds the list of the wired outputs' sources.
     */
    public final Map<String, Object> outputSources = new LinkedHashMap<>();

    /**
     * The source port of each wired output in {@link #outputSources}; a computed output holds
     * the list of its sources' ports, {@code null} for a literal.
     */
    public final Map<String, Object> outputSourcePorts = new LinkedHashMap<>();

    /** Body records in evaluation order. */
    public final List<GraphNodeContext> children = new ArrayList<>();

    /** Evaluated body records by id. */
    public final Map<String, GraphNodeContext> childrenById = new HashMap<>();

    /** Wall time spent evaluating this node, children included, in nanoseconds. */
    public long elapsedNanos;

    /** Peak used heap observed while this node or any of its children ran. */
    public long peakHeapBytes;

    /** Values the graph's host bound for scene-bound input nodes, shared by every record of a run. */
    public Map<String, Object> graphInputs = Map.of();

    private final Map<String, Object> inputs = new LinkedHashMap<>();
    private final Map<String, Object> outputs = new HashMap<>();
    private FieldContext fieldContext;
    private String nodeAssignmentId;

    /**
     * Adds the failure to the running graph's list, which the runtime throws once every
     * statement has run.
     *
     * @param failure what failed, naming the statement and the offending input
     * @throws IllegalArgumentException when no graph run collects failures for this context
     */
    @Override
    public void reportFailure(String failure) {
        if (deferredFailures == null) {
            throw new IllegalArgumentException(failure);
        }
        deferredFailures.add(failure);
    }

    /**
     * Wires an input of this record to a literal, as {@code port=value} does in DSL source.
     *
     * @param port    input port name
     * @param literal the value the port takes
     * @return this record, for chaining
     */
    public GraphNodeContext input(String port, Object literal) {
        inputSources.put(port, literal);
        inputSourcePorts.remove(port);
        return this;
    }

    /**
     * Wires an input of this record to an output of an earlier record, or to a parameter of a
     * group call enclosing it, as {@code port=source.sourcePort} does in DSL source.
     *
     * @param port       input port name
     * @param source     the earlier record, or the enclosing call whose parameter is read
     * @param sourcePort output port of the source, or the parameter's name
     * @return this record, for chaining
     */
    public GraphNodeContext input(String port, GraphNodeContext source, String sourcePort) {
        inputSources.put(port, source);
        inputSourcePorts.put(port, sourcePort);
        return this;
    }

    /**
     * Sets the DSL left-hand-side variable name of the node about to evaluate.
     *
     * @param id LHS identifier (e.g. {@code "thumb_attach"}); may be null
     */
    public void setNodeAssignmentId(String id) {
        this.nodeAssignmentId = id;
    }

    /**
     * {@inheritDoc}.
     *
     * @return DSL left-hand-side variable name, or null
     */
    @Override
    public String nodeAssignmentId() {
        return nodeAssignmentId;
    }

    /**
     * Stores a resolved input value for the named input port. Called by the
     * runtime before {@code evaluate}.
     *
     * @param portName input port name
     * @param value    resolved value (literal, upstream output, or override)
     */
    public void setInputValue(String portName, Object value) {
        inputs.put(portName, value);
    }

    /**
     * Untyped read of the input bag.
     *
     * @param name input port name
     * @return raw value, or null if not present
     */
    @Override
    public Object getInputValue(String name) {
        return inputs.get(name);
    }

    /**
     * Read-only view of the resolved inputs, in the order they were set.
     *
     * @return the input bag
     */
    public Map<String, Object> getInputsSnapshot() {
        return Collections.unmodifiableMap(inputs);
    }

    /**
     * Sets the field context (vertex domain) that nodes can read from
     * {@link #fieldContext()}.
     *
     * @param ctx active field context, or null
     */
    public void setFieldContext(FieldContext ctx) {
        this.fieldContext = ctx;
    }

    /**
     * {@inheritDoc}.
     *
     * @return field context most recently set on this node, or null
     */
    @Override
    public FieldContext fieldContext() {
        return fieldContext;
    }

    /**
     * {@inheritDoc}.
     *
     * @param name binding name
     * @return the value the runtime's host bound, or {@code null}
     */
    @Override
    public Object graphInput(String name) {
        return graphInputs.get(name);
    }

    /**
     * Type-safe read of the input bag.
     *
     * @param <T>      requested type
     * @param portName input port name
     * @param type     class object for the requested type
     * @return value cast to {@code T}, or null when absent or incompatible
     */
    @Override
    public <T> T getInput(String portName, Class<T> type) {
        Object val = inputs.get(portName);
        if (val != null && type.isAssignableFrom(val.getClass())) {
            return type.cast(val);
        }
        return null;
    }

    /**
     * Writes an output value for the named output port.
     *
     * @param portName output port name
     * @param value    value to publish (may be null)
     */
    @Override
    public void setOutput(String portName, Object value) {
        outputs.put(portName, value);
    }

    /**
     * Untyped read of the output bag (used by the runtime when wiring downstream
     * nodes).
     *
     * @param portName output port name
     * @return raw value, or null if not present
     */
    public Object getOutput(String portName) {
        return outputs.get(portName);
    }

    /**
     * Whether the output bag holds the port, even with a {@code null} value.
     *
     * @param portName output port name
     * @return true once the port was written
     */
    public boolean hasOutput(String portName) {
        return outputs.containsKey(portName);
    }

    /**
     * For runtime introspection (e.g. implicit field context after evaluate).
     *
     * @return read-only view of the output bag
     */
    public Map<String, Object> getOutputsSnapshot() {
        return Collections.unmodifiableMap(outputs);
    }

    /**
     * Type-safe read of the output bag.
     *
     * @param <T>  requested type
     * @param name output port name
     * @param type class object for the requested type
     * @return value cast to {@code T}, or null when absent or incompatible
     */
    @Override
    public <T> T getOutput(String name, Class<T> type) {
        Object val = outputs.get(name);
        if (val != null && type.isAssignableFrom(val.getClass())) {
            return type.cast(val);
        }
        return null;
    }

    /**
     * Wall time of this node in whole milliseconds.
     *
     * @return elapsed milliseconds, children included
     */
    public long elapsedMs() {
        return elapsedNanos / NodeGraphRuntime.NANOS_PER_MILLI;
    }

    /**
     * This record and every record below it, depth first in evaluation order.
     *
     * @return the flattened subtree, this record first
     */
    public List<GraphNodeContext> subtree() {
        List<GraphNodeContext> out = new ArrayList<>();
        List<GraphNodeContext> stack = new ArrayList<>();
        stack.add(this);
        while (!stack.isEmpty()) {
            GraphNodeContext record = stack.remove(stack.size() - 1);
            out.add(record);
            for (int index = record.children.size() - 1; index >= 0; index--) {
                stack.add(record.children.get(index));
            }
        }
        return out;
    }
}
