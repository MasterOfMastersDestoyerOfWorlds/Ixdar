package benchmark;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.game.trade.level.TradeLevelFile;
import ixdar.game.trade.level.TradeMapGenerator;
import ixdar.game.trade.map.GridPath;
import ixdar.game.trade.map.TradeMap;
import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer.DecompositionDiagnostics;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonComparator;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonComparator.DetailedBranchMatch;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonComparator.JointDelta;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonBranch;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonJoint;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonResult;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.GeometryBundles;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.procgen.dungeon.algo.DungeonGrids;
import ixdar.procgen.dungeon.values.CellType;

/**
 * The outputs MESH-92's reuse items change, one file per case; given a compare directory, each
 * case is compared with the same file there, one row per case in {@code summary.tsv}.
 *
 * <pre>
 * mvn test -Dtest=ResultChangingReuseBenchmark -Dbenchmark.out=tmp/mesh-92/before
 * mvn test -Dtest=ResultChangingReuseBenchmark -Dbenchmark.out=tmp/mesh-92/after \
 *     -Dbenchmark.compare=tmp/mesh-92/before
 * </pre>
 */
public final class ResultChangingReuseBenchmark {

    private static final String OUT_PROPERTY = "benchmark.out";

    private static final String COMPARE_PROPERTY = "benchmark.compare";

    private static final String DEFAULT_OUT = "tmp/mesh-92/run";

    private static final String MESH_ROOT = "test/resources/quadlayout/";

    private static final String BOLT = "figure_10/bolt_in_tri.off";

    private static final String FANDISK = "figure_10/fandisk_in_tri.off";

    private static final String HAND = "figure_6/hand_in_tri.off";

    private static final String ROCKER_ARM = "rocker-arm/rocker-arm.obj";

    private static final String GEOMETRY_PORT = "geometry";

    private static final String DSL_SUFFIX = ".dsl";

    private static final String[] DECOMPOSED_MESHES = { BOLT, FANDISK, "figure_8/block_in_tri.off", HAND, ROCKER_ARM };

    private static final String[] LOADED_MESHES = { ROCKER_ARM, "baseline-hand/hand-quad.obj",
        "baseline-hand/Hand-tri-30k.obj", FANDISK, "figure_1/botijo_out_quad_20.off" };

    private static final String[] SKELETON_MESHES = { HAND, ROCKER_ARM, BOLT };

    private static final String[] DUNGEON_GRAPHS = { "dsl/dungeon_2d.dsl", "dsl/dungeon_3d.dsl" };

    private static final String[] MESH_GRAPHS = { "dsl/birail_loft_ribbon.dsl", "dsl/petal.dsl", "dsl/neck_armor.dsl",
        "dsl/tool_quilt.dsl", "dsl/test_function.dsl", "dsl/stroke_sculpt_sphere.dsl", "dsl/stroke_crease_sphere.dsl",
        "dsl/stroke_sculpt_head.dsl", "dsl/silhouette_hull_skull.dsl", "dsl/silhouette_hull_skull.dsl#smooth" };

    private static final String[] TRADE_LEVELS = { "generated.ix", "small_loops.ix", "far_bases.ix", "blood_war.ix",
        "first_blood.ix", "fuel.ix" };

    private static final int DECOMPOSITION_RESOLUTION = 128;

    private static final int SKELETON_RESOLUTION = 64;

    private static final int DUNGEON_SEEDS = 12;

    private static final String VALUES = ".values";

    private static final String SET = ".set";

    private static final String LABELS = ".labels";

    private static final String NORMALS = ".normals";

    private static final String SORTED_POINTS = ".points";

    private static final double VALUE_TOLERANCE = 1e-6;

    private static final int XYZ = 3;

    /**
     * Each decomposed mesh's patch label per fan triangle and its feature edge sets, from the
     * principal, crest, saddle and union detectors, plus patch and Morse-Smale critical counts.
     *
     * @throws IOException when a mesh cannot be read or a result cannot be written
     */
    @Test
    public void patchDecompositions() throws IOException {
        for (String name : DECOMPOSED_MESHES) {
            MeshTopology mesh = MeshLoader.load(MESH_ROOT + name);
            DecompositionDiagnostics diagnostics = SemanticPatchDecomposer.decomposeWithDiagnostics(mesh,
                    DECOMPOSITION_RESOLUTION);
            String base = "curvature/" + caseName(name);
            write(base + LABELS, diagnostics.facePatchId());
            write(base + "-principal" + SET, diagnostics.principalFeatureEdges());
            write(base + "-crest" + SET, diagnostics.crestEdges());
            write(base + "-saddle" + SET, diagnostics.saddleSeparatorEdges());
            write(base + "-cut" + SET, diagnostics.unionFeatureEdges());
            write(base + "-counts" + VALUES, new double[] { diagnostics.decomposition().patches().size(),
                diagnostics.morseSmale().critical().size(), diagnostics.morseSmale().arcs().size() });
        }
        compare("curvature");
    }

    /**
     * The vertex normals each loaded OBJ and OFF file comes out of {@link MeshLoader#load} with.
     *
     * @throws IOException when a mesh cannot be read or a result cannot be written
     */
    @Test
    public void loaderNormals() throws IOException {
        for (String name : LOADED_MESHES) {
            MeshTopology mesh = MeshLoader.load(MESH_ROOT + name);
            double[] normals = new double[mesh.vertexCount() * XYZ];
            Vector3f normal = new Vector3f();
            for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
                mesh.vertexNormal(mesh.vertexIdAt(activeVertex), normal);
                normals[activeVertex * XYZ] = normal.x;
                normals[activeVertex * XYZ + 1] = normal.y;
                normals[activeVertex * XYZ + 2] = normal.z;
            }
            write("normals/" + caseName(name) + NORMALS, normals);
        }
        compare("normals");
    }

    /**
     * Every cell type of each dungeon graph's carved grid over a run of seeds.
     *
     * @throws IOException when a result cannot be written
     */
    @Test
    public void seededDungeons() throws IOException {
        for (String graph : DUNGEON_GRAPHS) {
            for (int seed = 1; seed <= DUNGEON_SEEDS; seed++) {
                NodeGraphRuntime run = NodeGraphRuntime.executeResource(graph,
                        Map.of("rooms.seed", seed, "mst.seed", seed));
                GeometryBundle tiles = (GeometryBundle) run.getNodeOutput("tiles", GEOMETRY_PORT);
                CellType[] cells;
                if (DungeonGrids.isLattice(tiles)) {
                    int[] size = DungeonGrids.latticeDims(tiles);
                    cells = DungeonGrids.latticeCells(tiles, size[0], size[1], size[2]);
                } else {
                    int[] size = DungeonGrids.gridDims(tiles);
                    cells = DungeonGrids.gridCells(tiles, size[0], size[1]);
                }
                double[] types = new double[cells.length];
                for (int cell = 0; cell < cells.length; cell++) {
                    types[cell] = cells[cell].ordinal();
                }
                write("dungeon/" + caseName(graph) + "-seed" + seed + LABELS, types);
            }
        }
        compare("dungeon");
    }

    /**
     * The grid cells of every road and river each trade level generates.
     *
     * @throws IOException when a level cannot be read or a result cannot be written
     */
    @Test
    public void tradeRoads() throws IOException {
        for (String level : TRADE_LEVELS) {
            TradeMap map = TradeMapGenerator.build(TradeLevelFile.read(
                    new String(Files.readAllBytes(Path.of("src/main/resources/trade", level)), StandardCharsets.UTF_8)));
            List<Double> cells = new ArrayList<>();
            List<GridPath> paths = new ArrayList<>(map.roads);
            paths.addAll(map.rivers);
            for (GridPath path : paths) {
                cells.add(-1.0);
                for (int node : path.nodes) {
                    cells.add((double) node);
                }
            }
            write("trade/" + caseName(level) + VALUES, cells.stream().mapToDouble(Double::doubleValue).toArray());
        }
        compare("trade");
    }

    /**
     * The final mesh of each loft and subdivision graph: positions in vertex order, and sorted so
     * a reordering alone compares equal.
     *
     * @throws IOException when a result cannot be written
     */
    @Test
    public void graphMeshes() throws IOException {
        for (String entry : MESH_GRAPHS) {
            // "graph.dsl#node" reads that statement's mesh instead of the graph's last.
            String[] graphAndNode = entry.split("#");
            NodeGraphRuntime run = NodeGraphRuntime.executeResource(graphAndNode[0], Map.of());
            MeshTopology mesh = GeometryBundles.meshPart((GeometryBundle) (graphAndNode.length > 1
                    ? run.getNodeOutput(graphAndNode[1], GEOMETRY_PORT) : run.lastOutput(GEOMETRY_PORT)));
            String graph = graphAndNode.length > 1
                    ? graphAndNode[0].replace(DSL_SUFFIX, "-" + graphAndNode[1] + DSL_SUFFIX) : entry;
            assertNotNull(mesh, graph + " ends in a mesh");
            double[] positions = new double[mesh.vertexCount() * XYZ];
            Vector3f position = new Vector3f();
            for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
                mesh.vertexPosition(mesh.vertexIdAt(activeVertex), position);
                positions[activeVertex * XYZ] = position.x;
                positions[activeVertex * XYZ + 1] = position.y;
                positions[activeVertex * XYZ + 2] = position.z;
            }
            write("graphs/" + caseName(graph) + VALUES, positions);
            write("graphs/" + caseName(graph) + SORTED_POINTS, positions);
            write("graphs/" + caseName(graph) + "-faces" + VALUES, new double[] { mesh.faceCount() });
        }
        compare("graphs");
    }

    /**
     * Each mesh's skeleton joints and radii, and the per-joint deltas between the first mesh's
     * skeleton and every other's.
     *
     * @throws IOException when a mesh cannot be read or a result cannot be written
     */
    @Test
    public void skeletons() throws IOException {
        List<SkeletonResult> skeletons = new ArrayList<>();
        for (String name : SKELETON_MESHES) {
            SkeletonResult skeleton = MeshSkeletonExtractor.extract(MeshLoader.load(MESH_ROOT + name),
                    SKELETON_RESOLUTION);
            skeletons.add(skeleton);
            List<Double> joints = new ArrayList<>();
            for (SkeletonBranch branch : skeleton.branches()) {
                joints.add((double) branch.joints().size());
                for (SkeletonJoint joint : branch.joints()) {
                    for (float coordinate : joint.position()) {
                        joints.add((double) coordinate);
                    }
                    joints.add((double) joint.radius());
                }
            }
            write("skeleton/" + caseName(name) + VALUES, joints.stream().mapToDouble(Double::doubleValue).toArray());
        }
        for (int other = 1; other < skeletons.size(); other++) {
            List<Double> deltas = new ArrayList<>();
            for (DetailedBranchMatch match : MeshSkeletonComparator.compareDetailed(skeletons.get(other),
                    skeletons.get(0)).detailedMatches()) {
                for (JointDelta delta : match.jointDeltas()) {
                    deltas.add((double) delta.distance());
                }
            }
            write("skeleton/deltas-" + caseName(SKELETON_MESHES[other]) + VALUES,
                    deltas.stream().mapToDouble(Double::doubleValue).toArray());
        }
        compare("skeleton");
    }

    private static String caseName(String path) {
        String file = Path.of(path).getFileName().toString();
        return file.substring(0, file.lastIndexOf('.'));
    }

    private static Path outDirectory() {
        return MeshLoader.resolve(System.getProperty(OUT_PROPERTY, DEFAULT_OUT));
    }

    private static void write(String relative, int[] values) throws IOException {
        write(relative, Arrays.stream(values).asDoubleStream().toArray());
    }

    private static void write(String relative, Set<Long> keys) throws IOException {
        StringBuilder text = new StringBuilder();
        for (long key : new TreeSet<>(keys)) {
            text.append(key).append('\n');
        }
        writeText(relative, text.toString());
    }

    private static void write(String relative, double[] values) throws IOException {
        StringBuilder text = new StringBuilder();
        for (double value : values) {
            text.append(String.format(Locale.ROOT, "%.9g", value)).append('\n');
        }
        writeText(relative, text.toString());
    }

    private static void writeText(String relative, String text) throws IOException {
        Path file = outDirectory().resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Compare every file of one item's directory with the same file under the compare directory,
     * when one is given, and append one row per file to {@code summary.tsv}: line counts, then
     * how many entries differ and by how much, by the file's kind.
     *
     * @param item the item's subdirectory
     * @throws IOException when a file cannot be read or the summary cannot be written
     */
    private static void compare(String item) throws IOException {
        String compareRoot = System.getProperty(COMPARE_PROPERTY);
        if (compareRoot == null) {
            return;
        }
        Path after = outDirectory().resolve(item);
        Path before = MeshLoader.resolve(compareRoot).resolve(item);
        StringBuilder rows = new StringBuilder();
        List<Path> files;
        try (var listing = Files.list(after)) {
            files = listing.sorted().toList();
        }
        for (Path afterFile : files) {
            Path beforeFile = before.resolve(afterFile.getFileName());
            if (!Files.exists(beforeFile)) {
                continue;
            }
            List<String> beforeLines = Files.readAllLines(beforeFile);
            List<String> afterLines = Files.readAllLines(afterFile);
            String name = afterFile.getFileName().toString();
            String verdict;
            double[] left = name.endsWith(SET) ? new double[0]
                    : beforeLines.stream().mapToDouble(Double::parseDouble).toArray();
            double[] right = name.endsWith(SET) ? new double[0]
                    : afterLines.stream().mapToDouble(Double::parseDouble).toArray();
            if (name.endsWith(SET)) {
                Set<String> union = new HashSet<>(beforeLines);
                union.addAll(afterLines);
                Set<String> common = new HashSet<>(beforeLines);
                common.retainAll(afterLines);
                verdict = String.format(Locale.ROOT, "jaccard=%.4f",
                        union.isEmpty() ? 1.0 : common.size() / (double) union.size());
            } else if (left.length != right.length) {
                verdict = "length differs";
            } else if (name.endsWith(LABELS)) {
                int differing = 0;
                for (int index = 0; index < left.length; index++) {
                    differing += left[index] == right[index] ? 0 : 1;
                }
                verdict = String.format(Locale.ROOT, "differing=%d purity=%.4f", differing,
                        Math.min(purity(left, right), purity(right, left)));
            } else if (name.endsWith(NORMALS)) {
                double worst = 0.0;
                double total = 0.0;
                int over = 0;
                int count = left.length / XYZ;
                for (int vertex = 0; vertex < count; vertex++) {
                    double dot = 0.0;
                    for (int axis = 0; axis < XYZ; axis++) {
                        dot += left[vertex * XYZ + axis] * right[vertex * XYZ + axis];
                    }
                    double degrees = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
                    worst = Math.max(worst, degrees);
                    total += degrees;
                    over += degrees > 1.0 ? 1 : 0;
                }
                verdict = String.format(Locale.ROOT, "meanDeg=%.4f maxDeg=%.4f over1Deg=%d",
                        total / Math.max(1, count), worst, over);
            } else {
                if (name.endsWith(SORTED_POINTS)) {
                    left = sortedPoints(left);
                    right = sortedPoints(right);
                }
                int differing = 0;
                double worst = 0.0;
                for (int index = 0; index < left.length; index++) {
                    double gap = Math.abs(left[index] - right[index]);
                    differing += gap > VALUE_TOLERANCE ? 1 : 0;
                    worst = Math.max(worst, gap);
                }
                verdict = String.format(Locale.ROOT, "differing=%d maxAbs=%.3g", differing, worst);
            }
            rows.append(item).append('\t').append(name).append('\t').append(beforeLines.size()).append('\t')
                    .append(afterLines.size()).append('\t').append(verdict).append('\n');
        }
        Path summary = outDirectory().resolve("summary.tsv");
        Files.write(summary, rows.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /**
     * The share of entries whose label is the most common {@code right} label among the entries
     * sharing their {@code left} label: 1 when {@code right} refines nothing {@code left} joins.
     */
    private static double purity(double[] left, double[] right) {
        Map<Double, Map<Double, Integer>> counts = new HashMap<>();
        for (int index = 0; index < left.length; index++) {
            counts.computeIfAbsent(left[index], key -> new HashMap<>()).merge(right[index], 1, Integer::sum);
        }
        int agreeing = 0;
        for (Map<Double, Integer> byRight : counts.values()) {
            agreeing += byRight.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }
        return left.length == 0 ? 1.0 : agreeing / (double) left.length;
    }

    private static double[] sortedPoints(double[] packed) {
        int count = packed.length / XYZ;
        Integer[] order = new Integer[count];
        for (int point = 0; point < count; point++) {
            order[point] = point;
        }
        Arrays.sort(order, (first, second) -> {
            for (int axis = 0; axis < XYZ; axis++) {
                int byAxis = Double.compare(packed[first * XYZ + axis], packed[second * XYZ + axis]);
                if (byAxis != 0) {
                    return byAxis;
                }
            }
            return 0;
        });
        double[] sorted = new double[packed.length];
        for (int point = 0; point < count; point++) {
            System.arraycopy(packed, order[point] * XYZ, sorted, point * XYZ, XYZ);
        }
        return sorted;
    }
}
