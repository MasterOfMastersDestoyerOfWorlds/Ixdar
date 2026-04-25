package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.procgen.dungeon.algo.DelaunayTriangulation3D;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue3D;

@MeshNodeAnnotation(id = "delaunay_graph_3d", scopes = { "dungeon" })
public class DelaunayGraph3DNode implements MeshNode {

    private static final InputPort ROOMS = new InputPort("rooms", PortType.ROOM_LIST_3D, null);
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
        return "3D Delaunay tetrahedralization (Bowyer-Watson) over room centers. Stage 2 of the 3D pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                "rooms", "3D room list.",
                "edges", "Edges between room indices, deterministic order.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        RoomListValue3D rooms = ctx.getInput("rooms", RoomListValue3D.class);
        if (rooms == null) throw new IllegalArgumentException("delaunay_graph_3d: missing 'rooms'");
        EdgeGraphValue edges = DelaunayTriangulation3D.triangulate(rooms);
        ctx.setOutput("edges", edges);
    }
}
