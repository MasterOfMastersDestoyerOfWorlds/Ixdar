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
import ixdar.procgen.dungeon.values.RoomListValue3D;

@MeshNodeAnnotation(id = "minimum_spanning_tree_3d", scopes = { "dungeon" })
public class MinimumSpanningTree3DNode implements MeshNode {
    public static final InputPort EDGES_IN = new InputPort("edges", PortType.EDGE_GRAPH, null);
    public static final InputPort ROOMS = new InputPort("rooms", PortType.ROOM_LIST_3D, null);
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
        return "3D MST over input edges weighted by Euclidean distance between 3D room centers, "
                + "plus a probabilistic extra-edge pass. Stage 3 of the 3D dungeon pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                EDGES_IN.name, "Candidate edges (typically delaunay_graph_3d output).",
                ROOMS.name, "3D rooms used for distance weighting.",
                EXTRA_EDGE_PROB.name, "Probability per non-MST edge to keep as a loop (default 0.125).",
                SEED.name, "Seed for the extra-edge RNG stream.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        EdgeGraphValue edges = ctx.getInput(EDGES_IN.name, EdgeGraphValue.class);
        RoomListValue3D rooms = ctx.getInput(ROOMS.name, RoomListValue3D.class);
        Number extraProb = ctx.getInput(EXTRA_EDGE_PROB.name, Number.class);
        Number seed = ctx.getInput(SEED.name, Number.class);
        if (edges == null) throw new IllegalArgumentException("minimum_spanning_tree_3d: missing 'edges'");
        if (rooms == null) throw new IllegalArgumentException("minimum_spanning_tree_3d: missing 'rooms'");
        double prob = extraProb == null ? PrimMinimumSpanningTree.DEFAULT_EXTRA_EDGE_PROB : extraProb.doubleValue();
        long seedLong = seed == null ? 0L : seed.longValue();
        EdgeGraphValue result = PrimMinimumSpanningTree.build3D(edges, rooms, prob, seedLong);
        ctx.setOutput(EDGES_OUT.name, result);
    }
}
