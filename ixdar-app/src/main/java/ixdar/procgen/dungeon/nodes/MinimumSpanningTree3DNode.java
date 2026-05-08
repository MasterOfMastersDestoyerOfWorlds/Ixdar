package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.procgen.dungeon.algo.PrimMinimumSpanningTree;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue3D;

@MeshNodeAnnotation(id = "minimum_spanning_tree_3d", scopes = { "dungeon" })
public class MinimumSpanningTree3DNode implements MeshNode {
    public static final String EDGES = "edges";
    public static final String ROOMS_2 = "rooms";
    public static final String EXTRA_EDGE_PROB_2 = "extra_edge_prob";
    public static final String SEED_2 = "seed";

    private static final InputPort EDGES_IN = new InputPort(EDGES, PortType.EDGE_GRAPH, null);
    private static final InputPort ROOMS = new InputPort(ROOMS_2, PortType.ROOM_LIST_3D, null);
    private static final InputPort EXTRA_EDGE_PROB = new InputPort(
            EXTRA_EDGE_PROB_2, PortType.FLOAT, (float) PrimMinimumSpanningTree.DEFAULT_EXTRA_EDGE_PROB, 0f, 1f);
    private static final InputPort SEED = new InputPort(SEED_2, PortType.INT, 0, 0f, 1_000_000f);
    private static final OutputPort EDGES_OUT = new OutputPort(EDGES, PortType.EDGE_GRAPH);

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
        return "3D MST over input edges weighted by Euclidean distance between 3D room centers, "
                + "plus a probabilistic extra-edge pass. Stage 3 of the 3D dungeon pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                EDGES, "Candidate edges (typically delaunay_graph_3d output).",
                ROOMS_2, "3D rooms used for distance weighting.",
                EXTRA_EDGE_PROB_2, "Probability per non-MST edge to keep as a loop (default 0.125).",
                SEED_2, "Seed for the extra-edge RNG stream.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        EdgeGraphValue edges = ctx.getInput(EDGES, EdgeGraphValue.class);
        RoomListValue3D rooms = ctx.getInput(ROOMS_2, RoomListValue3D.class);
        Number extraProb = ctx.getInput(EXTRA_EDGE_PROB_2, Number.class);
        Number seed = ctx.getInput(SEED_2, Number.class);
        if (edges == null) throw new IllegalArgumentException("minimum_spanning_tree_3d: missing 'edges'");
        if (rooms == null) throw new IllegalArgumentException("minimum_spanning_tree_3d: missing 'rooms'");
        double prob = extraProb == null ? PrimMinimumSpanningTree.DEFAULT_EXTRA_EDGE_PROB : extraProb.doubleValue();
        long seedLong = seed == null ? 0L : seed.longValue();
        EdgeGraphValue result = PrimMinimumSpanningTree.build3D(edges, rooms, prob, seedLong);
        ctx.setOutput(EDGES, result);
    }
}
