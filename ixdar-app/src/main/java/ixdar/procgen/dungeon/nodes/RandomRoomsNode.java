package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.procgen.dungeon.algo.RoomPlacer;
import ixdar.procgen.dungeon.values.RoomListValue;

@MeshNodeAnnotation(id = "random_rooms", scopes = { "dungeon" })
public class RandomRoomsNode implements MeshNode {

    private static final InputPort SEED = new InputPort("seed", PortType.INT, 0, 0f, 1_000_000f);
    private static final InputPort GRID_W = new InputPort("grid_w", PortType.INT, 30, 1f, 1000f);
    private static final InputPort GRID_H = new InputPort("grid_h", PortType.INT, 30, 1f, 1000f);
    private static final InputPort COUNT = new InputPort("count", PortType.INT, 15, 1f, 500f);
    private static final InputPort MIN_SIZE = new InputPort("min_size", PortType.INT, 3, 1f, 100f);
    private static final InputPort MAX_SIZE = new InputPort("max_size", PortType.INT, 8, 1f, 100f);
    private static final InputPort MAX_ATTEMPTS = new InputPort("max_attempts", PortType.INT, 2000, 1f, 100_000f);
    private static final OutputPort ROOMS = new OutputPort("rooms", PortType.ROOM_LIST);

    @Override
    public List<InputPort> inputs() {
        return List.of(SEED, GRID_W, GRID_H, COUNT, MIN_SIZE, MAX_SIZE, MAX_ATTEMPTS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(ROOMS);
    }

    @Override
    public String description() {
        return "Places non-overlapping axis-aligned rooms on an integer grid with a 1-unit buffer. "
                + "Deterministic for a given seed. Stage 1 of the dungeon pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                "seed", "PRNG seed; same seed + same parameters -> same placement.",
                "grid_w", "Grid width in cells. Rooms fit inside [0, grid_w].",
                "grid_h", "Grid height in cells.",
                "count", "Target number of rooms to place.",
                "min_size", "Minimum room edge length in cells (inclusive).",
                "max_size", "Maximum room edge length in cells (inclusive).",
                "max_attempts", "Cap on total placement attempts. Result may contain fewer than 'count' rooms if exceeded.",
                "rooms", "List of placed rooms with ids, centers, and half-extents.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        int seed = intInput(ctx, "seed");
        int gridW = intInput(ctx, "grid_w");
        int gridH = intInput(ctx, "grid_h");
        int count = intInput(ctx, "count");
        int minSize = intInput(ctx, "min_size");
        int maxSize = intInput(ctx, "max_size");
        int maxAttempts = intInput(ctx, "max_attempts");
        RoomListValue rooms = RoomPlacer.place(seed, gridW, gridH, count, minSize, maxSize, maxAttempts);
        ctx.setOutput("rooms", rooms);
    }

    private static int intInput(NodeContext ctx, String name) {
        Number n = ctx.getInput(name, Number.class);
        if (n == null) {
            throw new IllegalArgumentException("random_rooms: missing required input '" + name + "'");
        }
        return n.intValue();
    }
}
