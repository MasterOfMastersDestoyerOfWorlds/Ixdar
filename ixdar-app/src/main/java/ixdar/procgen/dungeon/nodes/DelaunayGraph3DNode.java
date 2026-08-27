package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.procgen.dungeon.algo.DelaunayTriangulation3D;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue3D;

@MeshNodeAnnotation(id = "delaunay_graph_3d", scopes = { "dungeon" })
public class DelaunayGraph3DNode implements MeshNode {
    public static final InputPort ROOMS = new InputPort("rooms", PortType.ROOM_LIST_3D, null);
    public static final OutputPort EDGES = new OutputPort("edges", PortType.EDGE_GRAPH);

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
                ROOMS.name, "3D room list.",
                EDGES.name, "Edges between room indices, deterministic order.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        RoomListValue3D rooms = ctx.getInput(ROOMS.name, RoomListValue3D.class);
        if (rooms == null) throw new IllegalArgumentException("delaunay_graph_3d: missing 'rooms'");
        EdgeGraphValue edges = DelaunayTriangulation3D.triangulate(rooms);
        ctx.setOutput(EDGES.name, edges);
    }
}
