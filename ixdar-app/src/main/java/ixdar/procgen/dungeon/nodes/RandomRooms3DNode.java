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
    public static final String SEED_2 = "seed";
    public static final String GRID_W_2 = "grid_w";
    public static final String GRID_H_2 = "grid_h";
    public static final String GRID_D_2 = "grid_d";
    public static final String COUNT_2 = "count";
    public static final String MIN_SIZE_2 = "min_size";
    public static final String MAX_SIZE_2 = "max_size";
    public static final String MAX_ATTEMPTS_2 = "max_attempts";
    public static final String ROOMS_2 = "rooms";

    private static final InputPort SEED = new InputPort(SEED_2, PortType.INT, 0, 0f, 1_000_000f);
    private static final InputPort GRID_W = new InputPort(GRID_W_2, PortType.INT, 30, 1f, 1000f);
    private static final InputPort GRID_H = new InputPort(GRID_H_2, PortType.INT, 5, 1f, 100f);
    private static final InputPort GRID_D = new InputPort(GRID_D_2, PortType.INT, 30, 1f, 1000f);
    private static final InputPort COUNT = new InputPort(COUNT_2, PortType.INT, 15, 1f, 500f);
    private static final InputPort MIN_SIZE = new InputPort(MIN_SIZE_2, PortType.INT, 3, 1f, 100f);
    private static final InputPort MAX_SIZE = new InputPort(MAX_SIZE_2, PortType.INT, 8, 1f, 100f);
    private static final InputPort MAX_ATTEMPTS = new InputPort(MAX_ATTEMPTS_2, PortType.INT, 2000, 1f, 100_000f);
    private static final OutputPort ROOMS = new OutputPort(ROOMS_2, PortType.ROOM_LIST_3D);

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
                SEED_2, "PRNG seed.",
                GRID_W_2, "Grid width (X) in cells.",
                GRID_H_2, "Grid height (Y) in floors. Default 5 per vazgriz.",
                GRID_D_2, "Grid depth (Z) in cells.",
                COUNT_2, "Target room count.",
                MIN_SIZE_2, "Minimum horizontal edge length in cells.",
                MAX_SIZE_2, "Maximum horizontal edge length in cells.",
                MAX_ATTEMPTS_2, "Cap on placement attempts; result may be smaller than count if exceeded.",
                ROOMS_2, "List of 3D rooms with ids, centers, and half-extents.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        int seed = intInput(ctx, SEED_2);
        int gridW = intInput(ctx, GRID_W_2);
        int gridH = intInput(ctx, GRID_H_2);
        int gridD = intInput(ctx, GRID_D_2);
        int count = intInput(ctx, COUNT_2);
        int minSize = intInput(ctx, MIN_SIZE_2);
        int maxSize = intInput(ctx, MAX_SIZE_2);
        int maxAttempts = intInput(ctx, MAX_ATTEMPTS_2);
        RoomListValue3D rooms = RoomPlacer3D.place(seed, gridW, gridH, gridD, count,
                minSize, maxSize, maxAttempts);
        ctx.setOutput(ROOMS_2, rooms);
    }

    private static int intInput(NodeContext ctx, String name) {
        Number n = ctx.getInput(name, Number.class);
        if (n == null) throw new IllegalArgumentException("random_rooms_3d: missing '" + name + "'");
        return n.intValue();
    }
}
