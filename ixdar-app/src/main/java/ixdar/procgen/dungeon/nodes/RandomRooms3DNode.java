package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.procgen.dungeon.algo.RoomPlacer3D;
import ixdar.procgen.dungeon.values.RoomListValue3D;

@MeshNodeAnnotation(id = "random_rooms_3d", scopes = { "dungeon" })
public class RandomRooms3DNode implements MeshNode {

    private static final InputPort SEED = new InputPort("seed", PortType.INT, 0, 0f, 1_000_000f);
    private static final InputPort GRID_W = new InputPort("grid_w", PortType.INT, 30, 1f, 1000f);
    private static final InputPort GRID_H = new InputPort("grid_h", PortType.INT, 5, 1f, 100f);
    private static final InputPort GRID_D = new InputPort("grid_d", PortType.INT, 30, 1f, 1000f);
    private static final InputPort COUNT = new InputPort("count", PortType.INT, 15, 1f, 500f);
    private static final InputPort MIN_SIZE = new InputPort("min_size", PortType.INT, 3, 1f, 100f);
    private static final InputPort MAX_SIZE = new InputPort("max_size", PortType.INT, 8, 1f, 100f);
    private static final InputPort MAX_ATTEMPTS = new InputPort("max_attempts", PortType.INT, 2000, 1f, 100_000f);
    private static final OutputPort ROOMS = new OutputPort("rooms", PortType.ROOM_LIST_3D);

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
                "seed", "PRNG seed.",
                "grid_w", "Grid width (X) in cells.",
                "grid_h", "Grid height (Y) in floors. Default 5 per vazgriz.",
                "grid_d", "Grid depth (Z) in cells.",
                "count", "Target room count.",
                "min_size", "Minimum horizontal edge length in cells.",
                "max_size", "Maximum horizontal edge length in cells.",
                "max_attempts", "Cap on placement attempts; result may be smaller than count if exceeded.",
                "rooms", "List of 3D rooms with ids, centers, and half-extents.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        int seed = intInput(ctx, "seed");
        int gridW = intInput(ctx, "grid_w");
        int gridH = intInput(ctx, "grid_h");
        int gridD = intInput(ctx, "grid_d");
        int count = intInput(ctx, "count");
        int minSize = intInput(ctx, "min_size");
        int maxSize = intInput(ctx, "max_size");
        int maxAttempts = intInput(ctx, "max_attempts");
        RoomListValue3D rooms = RoomPlacer3D.place(seed, gridW, gridH, gridD, count,
                minSize, maxSize, maxAttempts);
        ctx.setOutput("rooms", rooms);
    }

    private static int intInput(NodeContext ctx, String name) {
        Number n = ctx.getInput(name, Number.class);
        if (n == null) throw new IllegalArgumentException("random_rooms_3d: missing '" + name + "'");
        return n.intValue();
    }
}
