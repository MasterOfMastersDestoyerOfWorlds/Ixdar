package ixdar.nodegraph.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.api.OutputPort;

/**
 * Releases a run's intermediate values once their last reader has run, keeping what the host
 * reads afterwards.
 */
final class ValueRelease {

    /** One node output's outstanding reads and the inputs holding it. */
    private static final class Slot {
        private int pendingReads;
        private boolean kept;
        private final List<GraphNodeContext> readers = new ArrayList<>();
        private final List<String> readerPorts = new ArrayList<>();
    }

    /** References the host reads after the run, or {@code null} to keep every top-level output. */
    private final List<String> keptReferences;

    /** The tree the plan was made for; a reset builds a new one, which needs a new plan. */
    private GraphNodeContext plannedRoot;

    private final Map<GraphNodeContext, Map<String, Slot>> slots = new IdentityHashMap<>();

    private final Set<GraphNodeContext> keptRecords = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Map<GraphNodeContext, Set<String>> neededGroupOutputs = new IdentityHashMap<>();

    /**
     * A release keeping the host's references.
     *
     * @param keptReferences {@code path.port} references the host reads after the run, or
     *                       {@code null} to keep every top-level statement's outputs
     */
    ValueRelease(List<String> keptReferences) {
        this.keptReferences = keptReferences == null ? null : List.copyOf(keptReferences);
    }

    /**
     * Plans the release over a tree once, as evaluation starts. With no kept references, a
     * top-level group keeps its own computed outputs, else its wired ones.
     *
     * @param runtime the runtime whose tree is planned, to resolve kept references
     * @throws IllegalArgumentException when a kept reference names no node
     */
    public void planFor(NodeGraphRuntime runtime) {
        GraphNodeContext root = runtime.root;
        if (plannedRoot == root) {
            return;
        }
        plannedRoot = root;
        slots.clear();
        keptRecords.clear();
        neededGroupOutputs.clear();
        countReaders(root);
        if (keptReferences == null) {
            for (GraphNodeContext statement : root.declared) {
                if (!(statement.node instanceof NodeGroup group)) {
                    keptRecords.add(statement);
                    continue;
                }
                boolean computesOwn = false;
                for (OutputPort output : group.outputs()) {
                    if (!statement.outputWires.containsKey(output.name)) {
                        needGroupOutput(statement, output.name);
                        computesOwn = true;
                    }
                }
                if (computesOwn) {
                    continue;
                }
                if (statement.outputWires.isEmpty()) {
                    needGroupOutput(statement, NodeGraphRuntime.RESULT);
                    continue;
                }
                for (String wired : List.copyOf(statement.outputWires.keySet())) {
                    needGroupOutput(statement, wired);
                }
            }
            return;
        }
        for (String reference : keptReferences) {
            int dot = reference.lastIndexOf(NodeGraphRuntime.PORT_SEPARATOR);
            GraphNodeContext record = runtime.statementAt(reference.substring(0, dot));
            if (record == null) {
                throw new IllegalArgumentException("kept reference " + reference + ": "
                        + runtime.noNodeAt(reference.substring(0, dot)));
            }
            String port = reference.substring(dot + 1);
            if (record.node instanceof NodeGroup) {
                needGroupOutput(record, port);
            } else {
                slot(record, port).kept = true;
            }
        }
    }

    /**
     * Whether a group output is one the plan computes; an unplanned one is computed on demand
     * only while its sources still hold their values.
     *
     * @param group the group call
     * @param port  the output
     * @return true when the plan counted the output's reads
     */
    public boolean isNeeded(GraphNodeContext group, String port) {
        Set<String> needed = neededGroupOutputs.get(group);
        return needed != null && needed.contains(port);
    }

    /**
     * Counts a record's finished reads, releasing every output whose last read this was, then
     * releases the record's own outputs nothing reads, such as a solver's DOF system.
     *
     * @param record a node record that evaluated
     */
    public void readsFinished(GraphNodeContext record) {
        for (Map.Entry<String, Object> wire : record.inputSources.entrySet()) {
            if (wire.getValue() instanceof GraphNodeContext source && isNodeOutput(record, source)) {
                readFinished(source, record.inputSourcePorts.get(wire.getKey()));
            }
        }
        if (keptRecords.contains(record)) {
            return;
        }
        Map<String, Slot> bySource = slots.get(record);
        for (String port : List.copyOf(record.getOutputsSnapshot().keySet())) {
            if ((bySource == null || !bySource.containsKey(port)) && isReleasable(record.getOutput(port))) {
                record.releaseOutput(port);
            }
        }
    }

    /**
     * Whether a value may be released: anything but a mesh bundle, which a later statement's field
     * nodes may still take as their domain from the nearest earlier statement holding one.
     *
     * @param value the output's value
     * @return false for a {@link GeometryBundle}
     */
    private static boolean isReleasable(Object value) {
        return !(value instanceof GeometryBundle);
    }

    /**
     * Counts the read a planned group output made of its wired source when it was computed.
     *
     * @param group  the group call
     * @param port   the group output just computed
     * @param source the output's wire source
     * @param sourcePort the source's port
     */
    public void groupOutputRead(GraphNodeContext group, String port, Object source, String sourcePort) {
        if (isNeeded(group, port) && source instanceof GraphNodeContext record && isNodeOutput(group, record)) {
            readFinished(record, sourcePort);
        }
    }

    /**
     * Walks every record below a scope, counting the outputs each one's wires read.
     *
     * @param scope the scope whose declared records are counted
     */
    private void countReaders(GraphNodeContext scope) {
        for (GraphNodeContext record : scope.declared) {
            for (Map.Entry<String, Object> wire : record.inputSources.entrySet()) {
                countRead(record, wire.getKey(), wire.getValue(), record.inputSourcePorts.get(wire.getKey()));
            }
            if (record.node instanceof NodeGroup) {
                countReaders(record);
            }
        }
    }

    /**
     * Counts one wire's read: a node output gains a reader, a group output becomes needed, and a
     * parameter or literal counts nothing. A value a group call binds as a parameter is kept,
     * since its body may read it at any time.
     *
     * @param reader     the reading record
     * @param inputPort  the reader's input the value lands in, or {@code null} for a group output's wire
     * @param source     the wire's source record, or a literal
     * @param sourcePort the source's port
     */
    private void countRead(GraphNodeContext reader, String inputPort, Object source, String sourcePort) {
        if (!(source instanceof GraphNodeContext record) || !isOutsideOf(record, reader)) {
            return;
        }
        if (record.node instanceof NodeGroup) {
            needGroupOutput(record, sourcePort);
            return;
        }
        Slot slot = slot(record, sourcePort);
        if (reader.node instanceof NodeGroup && inputPort != null) {
            slot.kept = true;
            return;
        }
        slot.pendingReads++;
        if (inputPort != null) {
            slot.readers.add(reader);
            slot.readerPorts.add(inputPort);
        }
    }

    /**
     * Marks a group output as computed, counting the reads it makes of its sources: its wire, the
     * outputs {@link NodeGroup#evaluate} reads for a computed one, or the last body node's outputs
     * for a group wiring none.
     *
     * @param group the group call
     * @param port  the output
     */
    private void needGroupOutput(GraphNodeContext group, String port) {
        if (!neededGroupOutputs.computeIfAbsent(group, unused -> new HashSet<>()).add(port)) {
            return;
        }
        if (group.outputWires.isEmpty()) {
            if (!group.declared.isEmpty()) {
                keepWhole(group.declared.get(group.declared.size() - 1));
            }
            return;
        }
        if (group.outputWires.containsKey(port)) {
            countRead(group, null, group.outputWires.get(port), group.outputWirePorts.get(port));
            return;
        }
        List<OutputPort> reads = ((NodeGroup) group.node).evaluateReads();
        for (String wired : List.copyOf(group.outputWires.keySet())) {
            if (reads == null || reads.stream().anyMatch(read -> read.name.equals(wired))) {
                needGroupOutput(group, wired);
            }
        }
    }

    /**
     * Keeps every output of a record, or of a group call's last body node when it wires none.
     *
     * @param record the record
     */
    private void keepWhole(GraphNodeContext record) {
        keptRecords.add(record);
        if (record.node instanceof NodeGroup && record.outputWires.isEmpty() && !record.declared.isEmpty()) {
            keepWhole(record.declared.get(record.declared.size() - 1));
        }
    }

    /**
     * Counts one finished read of a node output, releasing it from its record and its readers'
     * inputs when it was the last and the output is not kept.
     *
     * @param source the node record whose output was read
     * @param port   the output
     */
    private void readFinished(GraphNodeContext source, String port) {
        Map<String, Slot> bySource = slots.get(source);
        Slot slot = bySource == null ? null : bySource.get(port);
        if (slot == null || --slot.pendingReads > 0 || slot.kept || keptRecords.contains(source)
                || !source.hasOutput(port) || !isReleasable(source.getOutput(port))) {
            return;
        }
        Object value = source.getOutput(port);
        source.releaseOutput(port);
        for (int reader = 0; reader < slot.readers.size(); reader++) {
            slot.readers.get(reader).forgetInput(slot.readerPorts.get(reader), value);
        }
    }

    /**
     * The slot of a node output, made on first use.
     *
     * @param record the node record
     * @param port   the output
     * @return the slot
     */
    private Slot slot(GraphNodeContext record, String port) {
        return slots.computeIfAbsent(record, unused -> new HashMap<>())
                .computeIfAbsent(port, unused -> new Slot());
    }

    /**
     * Whether a wire's source is a node output rather than a parameter of the reader or a call
     * enclosing it.
     *
     * @param reader the reading record
     * @param source the wire's source record
     * @return true for a node record outside the reader's enclosing calls
     */
    private static boolean isNodeOutput(GraphNodeContext reader, GraphNodeContext source) {
        return !(source.node instanceof NodeGroup) && isOutsideOf(source, reader);
    }

    /**
     * Whether a source record is neither the reader nor a call enclosing it.
     *
     * @param source the wire's source record
     * @param reader the reading record
     * @return true when the wire reads an output, not a parameter
     */
    private static boolean isOutsideOf(GraphNodeContext source, GraphNodeContext reader) {
        for (GraphNodeContext level = reader; level != null; level = level.parent) {
            if (level == source) {
                return false;
            }
        }
        return true;
    }
}
