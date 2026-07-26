package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;

/**
 * Resumable carve state of one trace: its growing vertex chain, the chain
 * position of each reached node, the current head, and the trace-parametric
 * position of the next carve event, by which the global interleave orders all
 * traces.
 */
public final class TraceCursor implements Comparable<TraceCursor> {

    public final Trace trace;

    /** Copy vertices the trace's lane has reached so far, in carve order. */
    public final List<Integer> chain = new ArrayList<>();

    /** Chain index of each reached chain node, for cutting arcs out. */
    public final int[] chainPositionByNode;

    /** Copy vertex the carve currently stands on. */
    public int head;

    /** Index of the segment holding the pending event. */
    public int segmentIndex;

    /** Next chain node to reach; the arc being carved is {@code nodeIndex - 1}. */
    public int nodeIndex = 1;

    /** Trace-parametric position of the pending event, the interleave key. */
    public double nextEventParameter;

    /** Whether the pending event targets a chain node (else an edge crossing). */
    public boolean nextEventIsNode;

    /** Whether every chain node has been reached and all arcs emitted. */
    public boolean finished;

    /**
     * Starts a cursor at a trace's first chain node.
     *
     * @param trace       trace to carve
     * @param startVertex copy vertex of the trace's first chain node
     */
    public TraceCursor(Trace trace, int startVertex) {
        this.trace = trace;
        this.chainPositionByNode = new int[trace.arcNodeIds.size()];
        this.head = startVertex;
        chain.add(startVertex);
    }

    /**
     * Orders cursors by pending event parameter, ties by trace id, so the global
     * carve replays the motorcycle simulation's own clock.
     *
     * @param other cursor to compare against
     * @return the comparison result
     */
    @Override
    public int compareTo(TraceCursor other) {
        int byParameter = Double.compare(nextEventParameter, other.nextEventParameter);
        return byParameter != 0 ? byParameter
                : Integer.compare(trace.traceId, other.trace.traceId);
    }
}
