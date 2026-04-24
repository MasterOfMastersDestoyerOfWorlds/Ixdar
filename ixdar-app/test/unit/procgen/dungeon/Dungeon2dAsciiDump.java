package unit.procgen.dungeon;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D;
import ixdar.procgen.dungeon.algo.DelaunayTriangulation2D;
import ixdar.procgen.dungeon.algo.PrimMinimumSpanningTree;
import ixdar.procgen.dungeon.algo.RoomPlacer;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;
import ixdar.procgen.dungeon.values.TileGridValue;

/**
 * Diagnostic that runs dungeon_2d.dsl (via the same algo classes the DSL wrappers call) and
 * dumps the tile grid as ASCII plus the emitted mesh's stats. Not a correctness test — just a
 * sanity-check the layout looks plausible before launching the fly-cam viewer. Run with:
 *
 * <pre>mvn test -pl ixdar-app -Dtest=Dungeon2dAsciiDump</pre>
 */
public class Dungeon2dAsciiDump {

    @Test
    public void dumpDungeon() throws Exception {
        // Match the parameters in dsl/dungeon_2d.dsl.
        int gridW = 30, gridH = 30;
        RoomListValue rooms = RoomPlacer.place(42L, gridW, gridH, 15, 3, 8, 2000);
        EdgeGraphValue tri = DelaunayTriangulation2D.triangulate(rooms);
        EdgeGraphValue mst = PrimMinimumSpanningTree.build(tri, rooms, 0.125, 42L);
        TileGridValue grid = AStarCorridorPathfinder2D.carve(
                gridW, gridH, rooms, mst, AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);

        System.out.println();
        System.out.println("=== dungeon_2d.dsl (seed=42) ===");
        System.out.println("rooms: " + rooms.size() + "   tri edges: " + tri.edgeCount()
                + "   mst+extras edges: " + mst.edgeCount());
        System.out.println();
        System.out.println("Legend: . EMPTY   # ROOM   + HALLWAY");
        System.out.println();
        // Print top-down (grid y=gridH-1 at top so output reads like a map).
        for (int y = gridH - 1; y >= 0; y--) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < gridW; x++) {
                CellType c = grid.at(x, y);
                sb.append(c == CellType.ROOM ? '#' : c == CellType.HALLWAY ? '+' : '.');
            }
            System.out.println(sb);
        }

        // Also run the full DSL end-to-end and report mesh stats.
        String dsl = loadDsl();
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();
        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        ArrayMesh mesh = (ArrayMesh) runtime.executeGraphResult(ast, "dungeon", "mesh");
        System.out.println();
        System.out.println("mesh: " + mesh.vertexCount() + " verts, "
                + mesh.faceCount() + " quads");
        float[] p = mesh.copyPositions();
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i + 2 < p.length; i += 3) {
            minX = Math.min(minX, p[i]);   maxX = Math.max(maxX, p[i]);
            minY = Math.min(minY, p[i+1]); maxY = Math.max(maxY, p[i+1]);
            minZ = Math.min(minZ, p[i+2]); maxZ = Math.max(maxZ, p[i+2]);
        }
        System.out.printf("bounds: X[%.2f, %.2f]  Y[%.2f, %.2f]  Z[%.2f, %.2f]%n",
                minX, maxX, minY, maxY, minZ, maxZ);
        System.out.println();
    }

    private static String loadDsl() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("dsl/dungeon_2d.dsl")) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
