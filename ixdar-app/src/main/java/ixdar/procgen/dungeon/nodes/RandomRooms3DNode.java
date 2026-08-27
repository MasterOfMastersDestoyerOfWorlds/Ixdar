package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.procgen.dungeon.algo.RoomPlacer3D;
import ixdar.procgen.dungeon.values.RoomListValue3D;

@MeshNodeAnnotation(id = "random_rooms_3d", scopes = { "dungeon" })
public class RandomRooms3DNode implements MeshNode {
    public static final InputPort SEED = new InputPort("seed", PortType.INT, 0, 0f, 1_000_000f);
    public static final InputPort GRID_W = new InputPort("grid_w", PortType.INT, 30, 1f, 1000f);
    public static final InputPort GRID_H = new InputPort("grid_h", PortType.INT, 5, 1f, 100f);
    public static final InputPort GRID_D = new InputPort("grid_d", PortType.INT, 30, 1f, 1000f);
    public static final InputPort COUNT = new InputPort("count", PortType.INT, 15, 1f, 500f);
    public static final InputPort MIN_SIZE = new InputPort("min_size", PortType.INT, 3, 1f, 100f);
    public static final InputPort MAX_SIZE = new InputPort("max_size", PortType.INT, 8, 1f, 100f);
    public static final InputPort MAX_ATTEMPTS = new InputPort("max_attempts", PortType.INT, 2000, 1f, 100_000f);
    public static final OutputPort ROOMS = new OutputPort("rooms", PortType.ROOM_LIST_3D);

    @Override
    public List<InputPort> inputs() {
        return List.of(SEED, GRID_W, GRID_H, GRID_D, COUNT, MIN_SIZE, MAX_SIZE, MAX_ATTEMPTS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(ROOMS);
    }

    @Override
    public String description() {
        return "Places non-overlapping 3D rooms across multiple floors with a 1-unit buffer. "
                + "Stage 1 of the 3D dungeon pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                SEED.name, "PRNG seed.",
                GRID_W.name, "Grid width (X) in cells.",
                GRID_H.name, "Grid height (Y) in floors. Default 5 per vazgriz.",
                GRID_D.name, "Grid depth (Z) in cells.",
                COUNT.name, "Target room count.",
                MIN_SIZE.name, "Minimum horizontal edge length in cells.",
                MAX_SIZE.name, "Maximum horizontal edge length in cells.",
                MAX_ATTEMPTS.name, "Cap on placement attempts; result may be smaller than count if exceeded.",
                ROOMS.name, "List of 3D rooms with ids, centers, and half-extents.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        int seed = intInput(ctx, SEED.name);
        int gridW = intInput(ctx, GRID_W.name);
        int gridH = intInput(ctx, GRID_H.name);
        int gridD = intInput(ctx, GRID_D.name);
        int count = intInput(ctx, COUNT.name);
        int minSize = intInput(ctx, MIN_SIZE.name);
        int maxSize = intInput(ctx, MAX_SIZE.name);
        int maxAttempts = intInput(ctx, MAX_ATTEMPTS.name);
        RoomListValue3D rooms = RoomPlacer3D.place(seed, gridW, gridH, gridD, count,
                minSize, maxSize, maxAttempts);
        ctx.setOutput(ROOMS.name, rooms);
    }

    private static int intInput(NodeContext ctx, String name) {
        Number n = ctx.getInput(name, Number.class);
        if (n == null) throw new IllegalArgumentException("random_rooms_3d: missing '" + name + "'");
        return n.intValue();
    }
}
