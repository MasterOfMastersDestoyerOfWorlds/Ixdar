package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.procgen.dungeon.algo.DelaunayTriangulation2D;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;

@MeshNodeAnnotation(id = "delaunay_graph", scopes = { "dungeon" })
public class DelaunayGraphNode implements MeshNode {

    private static final InputPort ROOMS = new InputPort("rooms", PortType.ROOM_LIST, null);
    private static final OutputPort EDGES = new OutputPort("edges", PortType.EDGE_GRAPH);

    @Override
    public List<InputPort> inputs() {
        return List.of(ROOMS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(EDGES);
    }

    @Override
    public String description() {
        return "Delaunay triangulation over room centers via Bowyer-Watson. Stage 2 of the dungeon "
                + "pipeline — produces the candidate edge set the MST stage filters.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                "rooms", "Room list from random_rooms (or any ROOM_LIST producer).",
                "edges", "Delaunay edges between room indices, sorted by (min, max) for determinism.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        RoomListValue rooms = ctx.getInput("rooms", RoomListValue.class);
        if (rooms == null) {
            throw new IllegalArgumentException("delaunay_graph: missing required input 'rooms'");
        }
        EdgeGraphValue edges = DelaunayTriangulation2D.triangulate(rooms);
        ctx.setOutput("edges", edges);
    }
}
