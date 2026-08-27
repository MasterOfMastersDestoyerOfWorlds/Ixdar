package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.procgen.dungeon.algo.PrimMinimumSpanningTree;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;

@MeshNodeAnnotation(id = "minimum_spanning_tree", scopes = { "dungeon" })
public class MinimumSpanningTreeNode implements MeshNode {
    public static final InputPort EDGES_IN = new InputPort("edges", PortType.EDGE_GRAPH, null);
    public static final InputPort ROOMS = new InputPort("rooms", PortType.ROOM_LIST, null);
    public static final InputPort EXTRA_EDGE_PROB = new InputPort(
            "extra_edge_prob", PortType.FLOAT, (float) PrimMinimumSpanningTree.DEFAULT_EXTRA_EDGE_PROB, 0f, 1f);
    public static final InputPort SEED = new InputPort("seed", PortType.INT, 0, 0f, 1_000_000f);
    public static final OutputPort EDGES_OUT = new OutputPort(EDGES_IN.name, PortType.EDGE_GRAPH);

    @Override
    public List<InputPort> inputs() {
        return List.of(EDGES_IN, ROOMS, EXTRA_EDGE_PROB, SEED);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(EDGES_OUT);
    }

    @Override
    public String description() {
        return "Minimum spanning tree over input edges (Prim's, weighted by Euclidean distance between "
                + "room centers), plus a probabilistic extra-edge pass on a separate RNG stream. Stage 3 "
                + "of the dungeon pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                EDGES_IN.name, "Candidate edges (typically from delaunay_graph).",
                ROOMS.name, "Room list used to weight edges by center-to-center distance.",
                EXTRA_EDGE_PROB.name, "Probability [0,1] of keeping each non-MST edge as a loop (default 0.125 per vazgriz).",
                SEED.name, "Seed for the extra-edge RNG stream (separate from upstream room placement)."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        EdgeGraphValue edges = ctx.getInput(EDGES_IN.name, EdgeGraphValue.class);
        RoomListValue rooms = ctx.getInput(ROOMS.name, RoomListValue.class);
        Number extraProb = ctx.getInput(EXTRA_EDGE_PROB.name, Number.class);
        Number seed = ctx.getInput(SEED.name, Number.class);
        if (edges == null) throw new IllegalArgumentException("minimum_spanning_tree: missing 'edges'");
        if (rooms == null) throw new IllegalArgumentException("minimum_spanning_tree: missing 'rooms'");
        double prob = extraProb == null ? PrimMinimumSpanningTree.DEFAULT_EXTRA_EDGE_PROB : extraProb.doubleValue();
        long seedLong = seed == null ? 0L : seed.longValue();
        EdgeGraphValue result = PrimMinimumSpanningTree.build(edges, rooms, prob, seedLong);
        ctx.setOutput(EDGES_OUT.name, result);
    }
}
