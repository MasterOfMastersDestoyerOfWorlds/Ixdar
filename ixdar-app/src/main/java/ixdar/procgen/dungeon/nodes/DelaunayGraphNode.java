package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.procgen.dungeon.algo.DelaunayTriangulation2D;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;

@MeshNodeAnnotation(id = "delaunay_graph", scopes = { "dungeon" })
public class DelaunayGraphNode implements MeshNode {
    public static final String ROOMS_2 = "rooms";
    public static final String EDGES_2 = "edges";

    private static final InputPort ROOMS = new InputPort(ROOMS_2, PortType.ROOM_LIST, null);
    private static final OutputPort EDGES = new OutputPort(EDGES_2, PortType.EDGE_GRAPH);

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
                ROOMS_2, "Room list from random_rooms (or any ROOM_LIST producer).",
                EDGES_2, "Delaunay edges between room indices, sorted by (min, max) for determinism.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        RoomListValue rooms = ctx.getInput(ROOMS_2, RoomListValue.class);
        if (rooms == null) {
            throw new IllegalArgumentException("delaunay_graph: missing required input 'rooms'");
        }
        EdgeGraphValue edges = DelaunayTriangulation2D.triangulate(rooms);
        ctx.setOutput(EDGES_2, edges);
    }
}
