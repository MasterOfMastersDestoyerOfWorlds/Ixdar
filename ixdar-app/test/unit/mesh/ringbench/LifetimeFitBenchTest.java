package unit.mesh.ringbench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.analysis.curvature.PrincipalDirectionField;
import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceLifetime;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;
import ixdar.geometry.mesh.data.representation.TriangleSoup;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.geodesic.WidthLimitedFlow;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.query.RestingSphere;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.rings.RegionClusterFit;
import ixdar.numeric.graph.UnionFind;
import ixdar.nodegraph.nodes.data.SurfaceCreasesNode;
import ixdar.nodegraph.nodes.data.SurfaceMetricNode;
import ixdar.nodegraph.nodes.selection.RingFitNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * The bench under the walker-lifetime fit, groove bottoms absorbing: every case's score written to
 * tmp/ beside the passes, moves and what the fit ended at.
 */
class LifetimeFitBenchTest {

    private static final int TRACED_PASSES = 200;

    private static final int TORUS_SECTIONS = 160;

    private static final double FULL_TURN = 360.0;

    private static final double HALF = 0.5;

    private static final String GROOVED_RINGS_BISECTOR = "bisector_grooved_rings";

    private static final String WIDE_HORN_RING = "horn_ring_wide";

    private static final String SURFACE_NODE = "surface";

    private static final String HALF_GROOVES_RING = "ring_between_half_grooves";

    private static final String TRENCHES_RING = "slanted_ring_trenches";

    private static final String HORN_RING_ON_HORN = "horn_ring_on_horn";

    private static final String PASSED = "PASS ";

    private static final String FAILED = "fail ";

    private static final String POCK_MARKS = "one_groove_pock_marks";

    private static final String SHRUNK_BY_THINNEST = "thinnest";

    private static final String SHRUNK_BY_HALF = "half";

    private static final String SHRUNK_BY_THINNEST_ENDS = "thinnest ends";

    private static final String SMOOTH_RINGS_BISECTOR = "bisector_smooth_rings";

    private static final String POCKED_GROOVE = "the groove (depth 0.3)";

    private static final String ELSEWHERE = "everywhere else";

    private static final String CENTRE_LINE_ELSEWHERE = "centre-line vertices everywhere else";

    private static final double HORN_FOOT_HEIGHT = 0.958;

    private static final double HORN_FOOT_RADIUS = 0.2866;

    /** Whether the bench's crease strengths come from the largest sphere that rests at each vertex. */
    private static boolean sphereCreases;

    /** Whether the walls the resting sphere finds run on along a fold from where they are found. */
    private static boolean closedSphereWalls;

    /**
     * How the sphere's walls are shrunk from the whole tight band, or null where they are not:
     * "thinnest" where the band's limit is the model's thinnest half-width, "ends" where a wall's
     * loose ends are kept.
     */
    private static String shrunkSphereWalls;

    private static final List<String> BENDING_RING_CASES = List.of("slanted_ring", TRENCHES_RING,
            "tree_partly_in_groove");

    @Test
    void theBenchUnderTheLifetimeFitIsWrittenToTmp() throws IOException {
        benchUnder(fit -> fit.closedWalls = true, "tmp/craw-112-fable-bench.txt");
    }

    @Test
    void theBenchUnderGradedAirWithTheCrowdingRuleIsWrittenToTmp() throws IOException {
        benchUnder(fit -> fit.gradedWalls = true, "tmp/craw-112-fable-bench-graded-crowding-thin.txt");
        benchUnder(fit -> {
            fit.gradedWalls = true;
            fit.gradedBand = true;
        }, "tmp/craw-112-fable-bench-graded-crowding-band.txt");
    }

    @Test
    void theBenchUnderHardWallsByAmountsAloneIsWrittenToTmp() throws IOException {
        benchUnder(fit -> {
            fit.closedWalls = true;
            fit.bottleneck = false;
            fit.crowdingShares = false;
        }, "tmp/craw-112-fable-bench-walls-amounts.txt");
    }

    @Test
    void theBenchUnderWallsOfAnyStrengthByAmountsAloneIsWrittenToTmp() throws IOException {
        benchUnder(fit -> {
            fit.ungatedWalls = true;
            fit.bottleneck = false;
            fit.crowdingShares = false;
        }, "tmp/craw-112-fable-bench-ungated-amounts.txt");
    }

    @Test
    void theBenchUnderWallsOfAnyStrengthWithTheFractionFirstIsWrittenToTmp() throws IOException {
        benchUnder(fit -> fit.ungatedWalls = true, "tmp/craw-112-fable-bench-ungated-first.txt");
    }

    @Test
    void theBenchUnderGradedBandAirByAmountsAloneIsWrittenToTmp() throws IOException {
        benchUnder(fit -> {
            fit.gradedWalls = true;
            fit.gradedBand = true;
            fit.bottleneck = false;
            fit.crowdingShares = false;
        }, "tmp/craw-112-fable-bench-graded-band-amounts.txt");
    }

    @Test
    void theHornRingOnHornUnderGradedAirIsTracedPassByPassToTmp() throws IOException {
        hornOnHornTrace(false, "tmp/craw-112-fable-horn-on-horn-trace.txt");
    }

    @Test
    void theHornRingOnHornUnderHardWallsIsTracedPassByPassToTmp() throws IOException {
        hornOnHornTrace(true, "tmp/craw-112-fable-horn-on-horn-trace-walls.txt");
    }

    /**
     * Trace the ring drawn on the horn pass by pass: how far it lies from the horn's foot, and
     * which way the fit's rule and the amounts of air alone push it.
     *
     * @param hardWalls whether the grooves are walls and the fraction decides first, or air is graded
     * @param file      where the trace goes
     * @throws IOException when the file cannot be written
     */
    private static void hornOnHornTrace(boolean hardWalls, String file) throws IOException {
        NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + HORN_RING_ON_HORN + ".dsl", Map.of());
        String port = SurfaceCreasesNode.CREASES.name;
        GeometryBundle bundle = (GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY);
        SurfaceCreases creases = (SurfaceCreases) drawn.getNodeOutput(port, port);
        // The fit as run, the same seams read by amounts alone, and a run by amounts alone.
        RegionClusterFit scaled = hardWalls ? new RegionClusterFit(bundle, creases)
                : RingFitNode.fitOf(bundle, creases);
        scaled.airFlow = true;
        scaled.closedWalls = hardWalls;
        scaled.bottleneck = true;
        scaled.crowdingShares = true;
        RegionClusterFit[] amounts = new RegionClusterFit[2];
        for (int which = 0; which < amounts.length; which++) {
            amounts[which] = new RegionClusterFit(bundle, creases);
            amounts[which].airFlow = true;
            amounts[which].closedWalls = hardWalls;
            amounts[which].gradedWalls = !hardWalls;
            amounts[which].gradedBand = !hardWalls;
            amounts[which].recordPasses = true;
            amounts[which].begin();
        }
        scaled.recordPasses = true;
        scaled.begin();
        StringBuilder out = new StringBuilder("the fit as run (amount times fraction): per pass, seam vertices,"
                + " farthest and mean distance from the foot circle in edges, then of the free seam vertices how"
                + " many are pushed out onto the sphere by that rule and by amounts alone\n");
        Vector3f at = new Vector3f();
        Vector3f beside = new Vector3f();
        for (int pass = 0; pass == 0 || scaled.stepPass(); pass++) {
            amounts[0].loopsByPass.add(scaled.loopsByPass.get(pass));
            amounts[0].standAtPass(amounts[0].loopsByPass.size() - 1);
            double[][] byRule = scaled.pushesAlong(0);
            double[][] byAmount = amounts[0].pushesAlong(0);
            int free = 0;
            int outByRule = 0;
            int outByAmount = 0;
            for (int vertex = 0; vertex < byRule.length && byRule.length == byAmount.length; vertex++) {
                scaled.mesh.vertexPosition((int) byRule[vertex][0], at);
                int first = RegionClusterFit.PUSH_ROW_BEFORE_DIRECTIONS;
                beside.set((float) byRule[vertex][first], (float) byRule[vertex][first + 1],
                        (float) byRule[vertex][first + 2]).mul(0.05f).add(at);
                // Side 0 is the sphere's when a step into it leads away from the horn's axis.
                int sphere = Math.hypot(beside.x, beside.z) > Math.hypot(at.x, at.z) ? 1 : 2;
                if (byRule[vertex][3] > 0 || byRule[vertex][1] == byRule[vertex][2]) {
                    continue;
                }
                free++;
                outByRule += byRule[vertex][sphere] < byRule[vertex][3 - sphere] ? 1 : 0;
                outByAmount += byAmount[vertex][sphere] < byAmount[vertex][3 - sphere] ? 1 : 0;
            }
            int pinned = 0;
            for (double[] row : byRule) {
                pinned += row[3] > 0 ? 1 : 0;
            }
            out.append(String.format("  pass %d: %s | pinned %d, free %d, out by the rule %d, out by amounts %d%n",
                    pass, footDistances(scaled), pinned, free, outByRule, outByAmount));
        }
        out.append("\na run by amounts alone: per pass, seam vertices, farthest and mean distance in edges,"
                + " and how many are pinned\n");
        for (int pass = 0; pass == 0 || amounts[1].stepPass(); pass++) {
            int pinned = 0;
            for (double[] row : amounts[1].pushesAlong(0)) {
                pinned += row[3] > 0 ? 1 : 0;
            }
            out.append(String.format("  pass %d: %s, pinned %d%n", pass, footDistances(amounts[1]), pinned));
        }
        Files.write(Path.of(file), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theSmoothRingsBisectorUnderGradedAirIsTracedPassByPassToTmp() throws IOException {
        smoothBisectorTrace(false, "tmp/craw-112-fable-bisector-smooth-trace.txt");
    }

    @Test
    void theSmoothRingsBisectorUnderHardWallsIsTracedPassByPassToTmp() throws IOException {
        smoothBisectorTrace(true, "tmp/craw-112-fable-bisector-smooth-trace-walls.txt");
    }

    /**
     * Trace the smooth-rings bisector pass by pass: where it stands, and which way the fit's rule
     * and the amounts of air alone push it.
     *
     * @param hardWalls whether the grooves are walls and the fraction decides first, or air is graded
     * @param file      where the trace goes
     * @throws IOException when the file cannot be written
     */
    private static void smoothBisectorTrace(boolean hardWalls, String file) throws IOException {
        NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + "bisector_smooth_rings.dsl", Map.of());
        String port = SurfaceCreasesNode.CREASES.name;
        GeometryBundle bundle = (GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY);
        SurfaceCreases creases = (SurfaceCreases) drawn.getNodeOutput(port, port);
        RegionClusterFit scaled = hardWalls ? new RegionClusterFit(bundle, creases)
                : RingFitNode.fitOf(bundle, creases);
        RegionClusterFit amounts = new RegionClusterFit(bundle, creases);
        amounts.airFlow = true;
        amounts.closedWalls = hardWalls;
        amounts.gradedWalls = !hardWalls;
        amounts.gradedBand = !hardWalls;
        scaled.airFlow = true;
        scaled.closedWalls = hardWalls;
        scaled.bottleneck = true;
        scaled.crowdingShares = true;
        amounts.recordPasses = true;
        amounts.begin();
        scaled.recordPasses = true;
        scaled.begin();
        int bisector = Arrays.asList(scaled.ringLabels).indexOf("ring_02");
        StringBuilder out = new StringBuilder("the groove is at 0 degrees round the tube. Per pass: the bisector's"
                + " vertices, their nearest, mean and farthest angle from the groove, then of its free vertices how"
                + " many the rule moves toward the groove and how many amounts alone would\n");
        Vector3f at = new Vector3f();
        Vector3f beside = new Vector3f();
        for (int pass = 0; pass == 0 || scaled.stepPass(); pass++) {
            amounts.loopsByPass.add(scaled.loopsByPass.get(pass));
            amounts.standAtPass(amounts.loopsByPass.size() - 1);
            double[][] byRule = scaled.pushesAlong(bisector);
            double[][] byAmount = amounts.pushesAlong(bisector);
            int free = 0;
            int towardByRule = 0;
            int towardByAmount = 0;
            double nearest = Double.POSITIVE_INFINITY;
            double farthest = 0;
            double sum = 0;
            for (int vertex = 0; vertex < byRule.length; vertex++) {
                scaled.mesh.vertexPosition((int) byRule[vertex][0], at);
                double off = Math.abs(RingFitBench.roundTubeDegrees(at));
                nearest = Math.min(nearest, off);
                farthest = Math.max(farthest, off);
                sum += off;
                int first = RegionClusterFit.PUSH_ROW_BEFORE_DIRECTIONS;
                beside.set((float) byRule[vertex][first], (float) byRule[vertex][first + 1],
                        (float) byRule[vertex][first + 2]).mul(0.05f).add(at);
                // Side 0 is the groove's when a step into it lessens the angle from the groove.
                int groove = Math.abs(RingFitBench.roundTubeDegrees(beside)) < off ? 1 : 2;
                if (byRule[vertex][3] > 0 || byRule[vertex][1] == byRule[vertex][2]
                        || byRule.length != byAmount.length) {
                    continue;
                }
                free++;
                towardByRule += byRule[vertex][groove] < byRule[vertex][3 - groove] ? 1 : 0;
                towardByAmount += byAmount[vertex][groove] < byAmount[vertex][3 - groove] ? 1 : 0;
            }
            int held = 0;
            for (double[] row : byRule) {
                held += row[3] > 0 ? 1 : 0;
            }
            out.append(String.format(
                    "  pass %d: %d vertices, %.1f / %.1f / %.1f degrees | held %d, free %d, toward by the rule %d,"
                            + " by amounts %d%n",
                    pass, byRule.length, nearest, sum / Math.max(1, byRule.length), farthest, held, free,
                    towardByRule, towardByAmount));
            // As drawn, every vertex's push from the groove's side and from the side away from it.
            for (int vertex = 0; pass == 0 && vertex < byAmount.length; vertex++) {
                scaled.mesh.vertexPosition((int) byAmount[vertex][0], at);
                int first = RegionClusterFit.PUSH_ROW_BEFORE_DIRECTIONS;
                beside.set((float) byAmount[vertex][first], (float) byAmount[vertex][first + 1],
                        (float) byAmount[vertex][first + 2]).mul(0.05f).add(at);
                int groove = Math.abs(RingFitBench.roundTubeDegrees(beside)) < Math.abs(
                        RingFitBench.roundTubeDegrees(at)) ? 1 : 2;
                out.append(String.format(
                        "    round the torus %6.1f: amount from the groove's side %.5f, from the far side %.5f;"
                                + " the rule's %.5f and %.5f%n",
                        RingFitBench.roundTorusDegrees(at), byAmount[vertex][groove], byAmount[vertex][3 - groove],
                        byRule[vertex][groove], byRule[vertex][3 - groove]));
            }
        }
        Files.write(Path.of(file), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void thePockMarkFixturesWallsAndItsFitUnderEachWallRuleAreWrittenToTmp() throws IOException {
        String fixture = POCK_MARKS;
        NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + fixture + ".dsl", Map.of());
        String port = SurfaceCreasesNode.CREASES.name;
        SurfaceCreases creases = (SurfaceCreases) drawn.getNodeOutput(port, port);
        GeometryBundle bundle = (GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY);
        // The fixture's pits: centre on the tube, width and depth, as its graph writes them.
        double[][] pits = { { 1.767767, 0, 1.767767, 0.3, 0.5 }, { 0.938943, 0.5, 1.765895, 0.4, 0.2 },
            { 1.299038, 0, 0.75, 0.22, 0.6 }, { 0.695288, -0.433013, 2.139877, 0.3, 0.35 },
            { -1.863796, 0.25, 1.56391, 0.3, 0.5 }, { 1.664349, -0.433013, 0.54078, 0.25, 0.3 },
            { -0.662237, 0.25, 1.420173, 0.28, 0.4 }, { 1.25, 0, -2.165064, 0.35, 0.45 } };
        RegionClusterFit walled = new RegionClusterFit(bundle, creases);
        int places = pits.length + 2;
        int[] vertices = new int[places];
        int[] strong = new int[places];
        int[] closed = new int[places];
        double[] deepest = new double[places];
        Vector3f at = new Vector3f();
        for (int active = 0; active < walled.mesh.vertexCount(); active++) {
            int vertex = walled.mesh.vertexIdAt(active);
            walled.mesh.vertexPosition(vertex, at);
            // Place 0 is the groove, the last is everywhere else, and between them the pits.
            int place = Math.abs(RingFitBench.roundTorusDegrees(at)) < 6 ? 0 : places - 1;
            for (int pit = 0; pit < pits.length; pit++) {
                double off = Math.sqrt(Math.pow(at.x - pits[pit][0], 2) + Math.pow(at.y - pits[pit][1], 2)
                        + Math.pow(at.z - pits[pit][2], 2));
                place = off < pits[pit][3] + HALF * pits[pit][4] ? pit + 1 : place;
            }
            vertices[place]++;
            strong[place] += walled.grooveBottom[vertex] ? 1 : 0;
            closed[place] += walled.closedBottom[vertex] ? 1 : 0;
            deepest[place] = Math.max(deepest[place], creases.valleyStrength[vertex]);
        }
        StringBuilder out = new StringBuilder(
                "place: vertices, largest crease value, wall vertices at 0.5 or more, wall vertices after closing\n");
        for (int place = 0; place < places; place++) {
            out.append(String.format("  %s: %d, %.3f, %d, %d%n",
                    place == 0 ? POCKED_GROOVE : place == places - 1 ? ELSEWHERE
                            : "pit " + place + " (width " + pits[place - 1][3] + ", depth " + pits[place - 1][4] + ")",
                    vertices[place], deepest[place], strong[place], closed[place]));
        }
        RingFitBench.Case pocked = RingFitBench.CASES_IN_RING_ANCHOR_FIT_TEST_ORDER.stream()
                .filter(benchCase -> benchCase.fixture().equals(fixture)).findFirst().orElseThrow();
        SurfaceMetric metric = (SurfaceMetric) drawn.getNodeOutput(SURFACE_NODE, SurfaceMetricNode.METRIC.name);
        String[] rules = { "walls, amounts alone", "walls, fraction first", "walls of any strength, amounts alone",
            "graded, amount times fraction" };
        out.append("\nthe ring under each rule: passes, end state by anchors and over the whole ring\n");
        for (int rule = 0; rule < rules.length; rule++) {
            RegionClusterFit fit = new RegionClusterFit(bundle, creases);
            fit.airFlow = true;
            fit.closedWalls = rule < 2;
            fit.ungatedWalls = rule == 2;
            fit.gradedWalls = rule == 3;
            fit.gradedBand = rule == 3;
            fit.sharesScalePressure = rule == 3;
            fit.bottleneck = rule == 1 || rule == 3;
            fit.crowdingShares = rule == 1 || rule == 3;
            fit.run();
            GeometryBundle fitted = fit.fitted(bundle, metric);
            out.append(String.format("  %s: %d passes; %s; whole ring %s%n", rules[rule], fit.passes,
                    RingFitBench.scoreFitted(pocked, fitted, false).scores(),
                    RingFitBench.scoreFitted(pocked, fitted, true).scores()));
        }
        Files.write(Path.of("tmp/craw-112-fable-pock-marks.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theTurnAcrossEachGrooveBesideItsCreaseStrengthIsWrittenToTmp() throws IOException {
        StringBuilder out = new StringBuilder("centre-line vertices by place: count; crease strength least / mean /"
                + " most; degrees the surface turns across the valley least / mean / most; edges across, mean\n");
        String port = SurfaceCreasesNode.CREASES.name;
        Vector3f at = new Vector3f();
        // The pock-mark torus: the groove, each pit, and everywhere else.
        NodeGraphRuntime pocked = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + "one_groove_pock_marks.dsl", Map.of());
        SurfaceCreases creases = (SurfaceCreases) pocked.getNodeOutput(port, port);
        RegionClusterFit fit = new RegionClusterFit((GeometryBundle) pocked.lastOutput(NodeGraphRuntime.GEOMETRY),
                creases);
        double[][] turns = turnAcross(fit.mesh, creases);
        double[][] pits = { { 1.767767, 0, 1.767767, 0.3, 0.5 }, { 0.938943, 0.5, 1.765895, 0.4, 0.2 },
            { 1.299038, 0, 0.75, 0.22, 0.6 }, { 0.695288, -0.433013, 2.139877, 0.3, 0.35 },
            { -1.863796, 0.25, 1.56391, 0.3, 0.5 }, { 1.664349, -0.433013, 0.54078, 0.25, 0.3 },
            { -0.662237, 0.25, 1.420173, 0.28, 0.4 }, { 1.25, 0, -2.165064, 0.35, 0.45 } };
        List<List<double[]>> places = new ArrayList<>();
        for (int place = 0; place < pits.length + 2; place++) {
            places.add(new ArrayList<>());
        }
        for (int active = 0; active < fit.mesh.vertexCount(); active++) {
            int vertex = fit.mesh.vertexIdAt(active);
            if (!fit.valleyLine[vertex]) {
                continue;
            }
            fit.mesh.vertexPosition(vertex, at);
            int place = Math.abs(RingFitBench.roundTorusDegrees(at)) < 6 ? 0 : pits.length + 1;
            for (int pit = 0; pit < pits.length; pit++) {
                double off = Math.sqrt(Math.pow(at.x - pits[pit][0], 2) + Math.pow(at.y - pits[pit][1], 2)
                        + Math.pow(at.z - pits[pit][2], 2));
                place = off < pits[pit][3] + HALF * pits[pit][4] ? pit + 1 : place;
            }
            places.get(place).add(new double[] { creases.valleyStrength[vertex], turns[vertex][0], turns[vertex][1] });
        }
        out.append("one_groove_pock_marks\n");
        for (int place = 0; place < places.size(); place++) {
            out.append(placeTold(place == 0 ? POCKED_GROOVE : place == places.size() - 1 ? ELSEWHERE
                    : "pit " + place + " (width " + pits[place - 1][3] + ", depth " + pits[place - 1][4] + ")",
                    places.get(place)));
        }
        // The horn: the foot circle, and everywhere else, with the walls that stand off the foot.
        NodeGraphRuntime horn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + HORN_RING_ON_HORN + ".dsl", Map.of());
        creases = (SurfaceCreases) horn.getNodeOutput(port, port);
        fit = new RegionClusterFit((GeometryBundle) horn.lastOutput(NodeGraphRuntime.GEOMETRY), creases);
        turns = turnAcross(fit.mesh, creases);
        List<double[]> foot = new ArrayList<>();
        List<double[]> footLine = new ArrayList<>();
        List<double[]> offFoot = new ArrayList<>();
        StringBuilder strays = new StringBuilder();
        for (int active = 0; active < fit.mesh.vertexCount(); active++) {
            int vertex = fit.mesh.vertexIdAt(active);
            fit.mesh.vertexPosition(vertex, at);
            double[] row = { creases.valleyStrength[vertex], turns[vertex][0], turns[vertex][1] };
            double off = Math.hypot(at.y - HORN_FOOT_HEIGHT, Math.hypot(at.x, at.z) - HORN_FOOT_RADIUS)
                    / fit.mesh.meanEdgeLength();
            if (off <= HALF) {
                foot.add(row);
                (fit.valleyLine[vertex] ? footLine : new ArrayList<double[]>()).add(row);
            } else if (fit.valleyLine[vertex]) {
                offFoot.add(row);
                if (fit.closedBottom[vertex]) {
                    strays.append(String.format("    a wall %.1f edges off the foot: strength %.3f, turns %.1f%n", off,
                            row[0], row[1]));
                }
            }
        }
        out.append(HORN_RING_ON_HORN).append('\n').append(placeTold("every vertex on the foot circle", foot))
                .append(placeTold("the centre-line vertices among them", footLine))
                .append(placeTold(CENTRE_LINE_ELSEWHERE, offFoot)).append(strays);
        // The bisector torus: the long groove's bottom between the rings, and everywhere else.
        NodeGraphRuntime torus = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + "bisector_smooth_rings.dsl", Map.of());
        creases = (SurfaceCreases) torus.getNodeOutput(port, port);
        fit = new RegionClusterFit((GeometryBundle) torus.lastOutput(NodeGraphRuntime.GEOMETRY), creases);
        turns = turnAcross(fit.mesh, creases);
        List<double[]> bottom = new ArrayList<>();
        List<double[]> offBottom = new ArrayList<>();
        for (int active = 0; active < fit.mesh.vertexCount(); active++) {
            int vertex = fit.mesh.vertexIdAt(active);
            if (!fit.valleyLine[vertex]) {
                continue;
            }
            fit.mesh.vertexPosition(vertex, at);
            double round = RingFitBench.roundTorusDegrees(at);
            (Math.abs(RingFitBench.roundTubeDegrees(at)) < 4.5 && round > 110 && round < 160 ? bottom : offBottom)
                    .add(new double[] { creases.valleyStrength[vertex], turns[vertex][0], turns[vertex][1] });
        }
        out.append("bisector_smooth_rings\n").append(placeTold("the long groove's bottom between the rings", bottom))
                .append(placeTold(CENTRE_LINE_ELSEWHERE, offBottom));
        Files.write(Path.of("tmp/craw-112-fable-turn-across.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theAirReachingEachRingAndHowLongItTookWithCreasesAndWithoutIsWrittenToTmp() throws IOException {
        String report = "per ring and side: air arriving with no creases -> with graded creases; the mean time that"
                + " air took -> with graded creases; the air over the time it took, summed -> with graded creases\n"
                + airByAge(SMOOTH_RINGS_BISECTOR, 0, at -> Math.abs(RingFitBench.roundTubeDegrees(at)))
                + airByAge(POCK_MARKS, 0, at -> Math.abs(RingFitBench.roundTorusDegrees(at)))
                + airByAge(HORN_RING_ON_HORN, 0, at -> -Math.hypot(at.x, at.z))
                + airByAge(HORN_RING_ON_HORN, 5, at -> -Math.hypot(at.x, at.z));
        Files.write(Path.of("tmp/craw-112-fable-air-by-age.txt"), report.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The air reaching each side of every ring of a fixture and how long it took, with no creases
     * and with graded ones, after some passes of the graded fit.
     *
     * @param fixture the fixture
     * @param passes  the passes of the graded fit run first
     * @param height  what is less on the side of a ring called "low": the groove's side, or the sphere's
     * @return the report's lines for the fixture
     */
    private static String airByAge(String fixture, int passes, ToDoubleFunction<Vector3f> height) {
        NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + fixture + ".dsl", Map.of());
        String port = SurfaceCreasesNode.CREASES.name;
        SurfaceCreases creases = (SurfaceCreases) drawn.getNodeOutput(port, port);
        RegionClusterFit fit = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                creases);
        fit.airFlow = true;
        fit.gradedWalls = true;
        fit.gradedBand = true;
        fit.sharesScalePressure = true;
        fit.bottleneck = true;
        fit.crowdingShares = true;
        fit.begin();
        int stepped = 0;
        while (stepped < passes && fit.stepPass()) {
            stepped++;
        }
        MeshTopology mesh = fit.mesh;
        SurfaceLifetime walker = new SurfaceLifetime(mesh);
        boolean[] seam = new boolean[mesh.vertexIdBound()];
        for (int[] loop : fit.loops) {
            for (int edge : loop) {
                seam[mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge))] = true;
                seam[mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge))] = true;
            }
        }
        double[] openness = new double[seam.length];
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            openness[vertex] = seam[vertex] ? 1 : Math.max(0, 1 - creases.valleyStrength[vertex]);
        }
        // Run 0 has no creases; run 1 grades the air by them.
        double[][] steep = new double[2][];
        double[][] late = new double[2][];
        for (int run = 0; run < steep.length; run++) {
            boolean[] blocking = new boolean[seam.length];
            for (int vertex = 0; vertex < seam.length; vertex++) {
                blocking[vertex] = run == 1 && openness[vertex] <= 0;
            }
            double[] life = walker.lifetimeRound(seam, blocking, run == 0 ? null : openness, null);
            steep[run] = walker.steepness(life);
            late[run] = walker.lateness(seam, blocking, run == 0 ? null : openness, life, steep[run]);
        }
        StringBuilder out = new StringBuilder(String.format("%s after %d passes%n", fixture, stepped));
        Vector3f at = new Vector3f();
        Vector3f other = new Vector3f();
        for (int ring = 0; ring < fit.loops.length; ring++) {
            // By run, then side (0 the low side), then: air, air times its time, air over its time.
            double[][][] sums = new double[2][2][3];
            for (int edge : fit.loops[ring]) {
                int halfEdge = mesh.edgeHalfEdge(edge);
                double length = mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), at)
                        .distance(mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), other));
                double edgeHeight = height.applyAsDouble(at.add(other).mul(0.5f));
                for (int side = 0; side < 2; side++) {
                    int face = mesh.edgeFace(edge, side);
                    if (face < 0) {
                        continue;
                    }
                    double open = 1;
                    at.zero();
                    for (int corner = 0; corner < mesh.faceEdgeCount(face); corner++) {
                        int vertex = mesh.halfEdgeVertex(mesh.edgeHalfEdge(mesh.faceEdgeAt(face, corner)));
                        open = Math.min(open, openness[vertex]);
                        at.add(mesh.vertexPosition(vertex, other));
                        vertex = mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(mesh.faceEdgeAt(face, corner)));
                        open = Math.min(open, openness[vertex]);
                        at.add(mesh.vertexPosition(vertex, other));
                    }
                    int low = height.applyAsDouble(at.div(2 * mesh.faceEdgeCount(face))) < edgeHeight ? 0 : 1;
                    for (int run = 0; run < steep.length; run++) {
                        double air = length * (run == 0 ? 1 : open) * Math.sqrt(steep[run][face]);
                        if (late[run][face] > 0) {
                            sums[run][low][0] += air;
                            sums[run][low][1] += air * late[run][face];
                            sums[run][low][2] += air / late[run][face];
                        }
                    }
                }
            }
            for (int low = 0; low < 2; low++) {
                out.append(String.format("  %s, %s side: air %.4f -> %.4f; time %.4f -> %.4f; air over time %.3f"
                        + " -> %.3f%n", fit.ringLabels[ring], low == 0 ? "low" : "high", sums[0][low][0],
                        sums[1][low][0], sums[0][low][1] / Math.max(1e-30, sums[0][low][0]),
                        sums[1][low][1] / Math.max(1e-30, sums[1][low][0]), sums[0][low][2], sums[1][low][2]));
            }
        }
        return out.toString();
    }

    @Test
    void theCreaseValuesRoundTheHornFootBesideItsMeshAreWrittenToTmp() throws IOException {
        NodeGraphRuntime horn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + HORN_RING_ON_HORN + ".dsl", Map.of());
        String port = SurfaceCreasesNode.CREASES.name;
        SurfaceCreases creases = (SurfaceCreases) horn.getNodeOutput(port, port);
        MeshTopology mesh = creases.sourceMesh;
        SurfaceLifetime measured = new SurfaceLifetime(mesh);
        double edge = mesh.meanEdgeLength();
        int bins = 36;
        // By bin round the axis: foot vertices, most raw curvature, most smoothed, most strength, the
        // raw and the smoothed curvature summed over the strip by area, mean edge across the foot.
        double[][] byBin = new double[bins][7];
        List<double[]> footRows = new ArrayList<>();
        Vector3f at = new Vector3f();
        Vector3f other = new Vector3f();
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            mesh.vertexPosition(vertex, at);
            double off = Math.hypot(at.y - HORN_FOOT_HEIGHT, Math.hypot(at.x, at.z) - HORN_FOOT_RADIUS) / edge;
            int bin = (int) Math.floor((Math.atan2(at.z, at.x) + Math.PI) / (2 * Math.PI) * bins) % bins;
            if (off <= 4) {
                byBin[bin][4] += Math.max(0, creases.valleySignal[vertex]) * measured.massByVertexId[vertex];
                byBin[bin][5] += creases.valleyCurvature[vertex] * measured.massByVertexId[vertex];
            }
            if (off > HALF) {
                continue;
            }
            // Its edges: how many, how many run on along the foot, and the mean length of the rest.
            int along = 0;
            double acrossLength = 0;
            int spokes = mesh.vertexEdgeCount(vertex);
            for (int spoke = 0; spoke < spokes; spoke++) {
                mesh.vertexPosition(mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertex, spoke), vertex), other);
                boolean onFoot = Math.hypot(other.y - HORN_FOOT_HEIGHT,
                        Math.hypot(other.x, other.z) - HORN_FOOT_RADIUS) / edge <= HALF;
                along += onFoot ? 1 : 0;
                acrossLength += onFoot ? 0 : at.distance(other);
            }
            double across = spokes > along ? acrossLength / (spokes - along) / edge : 0;
            footRows.add(new double[] { creases.valleySignal[vertex], creases.valleyCurvature[vertex],
                creases.valleyStrength[vertex], spokes, (double) along / spokes, across });
            byBin[bin][0]++;
            byBin[bin][1] = Math.max(byBin[bin][1], creases.valleySignal[vertex]);
            byBin[bin][2] = Math.max(byBin[bin][2], creases.valleyCurvature[vertex]);
            byBin[bin][3] = Math.max(byBin[bin][3], creases.valleyStrength[vertex]);
            byBin[bin][6] += across;
        }
        StringBuilder out = new StringBuilder(String.format(
                "%s: %d vertices on the foot circle; mean edge %.4f; the surface truly turns 55.7 degrees there%n"
                        + "by 10 degrees round the axis: foot vertices; most raw curvature; most smoothed; most"
                        + " strength; degrees turned by the raw curvature summed across; by the smoothed; mean"
                        + " edge across the foot in mean edges%n",
                HORN_RING_ON_HORN, footRows.size(), edge));
        double arc = 2 * Math.PI * HORN_FOOT_RADIUS / bins;
        for (int bin = 0; bin < bins; bin++) {
            out.append(String.format("  %3d: %2.0f; %7.2f; %6.2f; %.3f; %5.1f; %5.1f; %.2f%n", 10 * bin, byBin[bin][0],
                    byBin[bin][1], byBin[bin][2], byBin[bin][3], Math.toDegrees(byBin[bin][4] / arc),
                    Math.toDegrees(byBin[bin][5] / arc), byBin[bin][6] / Math.max(1, byBin[bin][0])));
        }
        // How each foot vertex's values go with its mesh: Pearson's r.
        String[] names = { "raw curvature", "smoothed curvature", "strength" };
        String[] causes = { "its edge count", "the share of its edges that run along the foot",
            "the mean length of its edges across the foot" };
        out.append("across the foot vertices, how a value goes with the mesh (Pearson r)\n");
        for (int value = 0; value < names.length; value++) {
            for (int cause = 0; cause < causes.length; cause++) {
                double[] sums = new double[5];
                for (double[] row : footRows) {
                    sums[0] += row[value];
                    sums[1] += row[3 + cause];
                    sums[2] += row[value] * row[value];
                    sums[3] += row[3 + cause] * row[3 + cause];
                    sums[4] += row[value] * row[3 + cause];
                }
                int count = footRows.size();
                double r = (count * sums[4] - sums[0] * sums[1]) / Math.sqrt(
                        (count * sums[2] - sums[0] * sums[0]) * (count * sums[3] - sums[1] * sums[1]));
                out.append(String.format("  %s with %s: %.2f%n", names[value], causes[cause], r));
            }
        }
        // The fold read off the faces themselves: at each vertex, the widest angle between the
        // normals of any two triangles that meet there, whatever their size.
        List<List<Vector3f>> normals = new ArrayList<>();
        for (int vertex = 0; vertex < mesh.vertexIdBound(); vertex++) {
            normals.add(new ArrayList<>());
        }
        for (int corner = 0; corner < measured.triangles.length; corner += 3) {
            mesh.vertexPosition(measured.triangles[corner], at);
            Vector3f normal = mesh.vertexPosition(measured.triangles[corner + 1], new Vector3f()).sub(at)
                    .cross(mesh.vertexPosition(measured.triangles[corner + 2], other).sub(at));
            if (normal.lengthSquared() > 0) {
                normal.normalize();
                for (int of = 0; of < 3; of++) {
                    normals.get(measured.triangles[corner + of]).add(normal);
                }
            }
        }
        double[] onFoot = { Double.POSITIVE_INFINITY, 0, 0, 0 };
        double[] offFoot = { Double.POSITIVE_INFINITY, 0, 0, 0 };
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            double widest = 0;
            for (Vector3f one : normals.get(vertex)) {
                for (Vector3f two : normals.get(vertex)) {
                    widest = Math.max(widest, Math.toDegrees(one.angle(two)));
                }
            }
            mesh.vertexPosition(vertex, at);
            double[] tally = Math.hypot(at.y - HORN_FOOT_HEIGHT, Math.hypot(at.x, at.z) - HORN_FOOT_RADIUS)
                    / edge <= HALF ? onFoot : offFoot;
            tally[0] = Math.min(tally[0], widest);
            tally[1] += widest;
            tally[2] = Math.max(tally[2], widest);
            tally[3]++;
        }
        out.append(String.format(
                "widest angle between the triangles meeting at a vertex, least / mean / most: on the foot %.1f /"
                        + " %.1f / %.1f over %.0f; everywhere else %.1f / %.1f / %.1f over %.0f%n",
                onFoot[0], onFoot[1] / onFoot[3], onFoot[2], onFoot[3], offFoot[0], offFoot[1] / offFoot[3], offFoot[2],
                offFoot[3]));
        Files.write(Path.of("tmp/craw-112-fable-horn-foot-creases.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theLargestSphereThatRestsOnEachVertexIsWrittenToTmp() throws IOException {
        StringBuilder out = new StringBuilder("the radius of the largest sphere that rests on the outside of the"
                + " surface at a vertex and holds no other vertex, in mean edges: vertices; least / median / most;"
                + " how many have no limit\n");
        String port = SurfaceCreasesNode.CREASES.name;
        Vector3f at = new Vector3f();
        // The horn: on the foot circle, within three edges of it, and everywhere else.
        NodeGraphRuntime horn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + HORN_RING_ON_HORN + ".dsl", Map.of());
        MeshTopology mesh = ((SurfaceCreases) horn.getNodeOutput(port, port)).sourceMesh;
        double[] radius = restingSphere(mesh);
        double edge = mesh.meanEdgeLength();
        List<List<Double>> places = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            mesh.vertexPosition(vertex, at);
            double off = Math.hypot(at.y - HORN_FOOT_HEIGHT, Math.hypot(at.x, at.z) - HORN_FOOT_RADIUS) / edge;
            places.get(off <= HALF ? 0 : off <= 3 ? 1 : 2).add(radius[vertex] / edge);
        }
        String edgeTold = " (mean edge %.4f)%n";
        out.append(HORN_RING_ON_HORN).append(String.format(edgeTold, edge))
                .append(sphereTold("on the foot circle", places.get(0)))
                .append(sphereTold("within three edges of it", places.get(1)))
                .append(sphereTold(ELSEWHERE, places.get(2)));
        // The pock-mark torus: the groove, each pit, and everywhere else.
        NodeGraphRuntime pocked = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + POCK_MARKS + ".dsl", Map.of());
        mesh = ((SurfaceCreases) pocked.getNodeOutput(port, port)).sourceMesh;
        radius = restingSphere(mesh);
        edge = mesh.meanEdgeLength();
        double[][] pits = { { 1.767767, 0, 1.767767, 0.3, 0.5 }, { 0.938943, 0.5, 1.765895, 0.4, 0.2 },
            { 1.299038, 0, 0.75, 0.22, 0.6 }, { 0.695288, -0.433013, 2.139877, 0.3, 0.35 },
            { -1.863796, 0.25, 1.56391, 0.3, 0.5 }, { 1.664349, -0.433013, 0.54078, 0.25, 0.3 },
            { -0.662237, 0.25, 1.420173, 0.28, 0.4 }, { 1.25, 0, -2.165064, 0.35, 0.45 } };
        List<List<Double>> pocks = new ArrayList<>();
        for (int place = 0; place < pits.length + 4; place++) {
            pocks.add(new ArrayList<>());
        }
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            mesh.vertexPosition(vertex, at);
            // The groove's bottom row, the groove's flanks, then the pits, then the tube's inside and outside.
            double round = Math.abs(RingFitBench.roundTorusDegrees(at));
            int place = round < 1.2 ? 0 : round < 6 ? 1
                    : Math.abs(RingFitBench.roundTubeDegrees(at)) > 90 ? pits.length + 2 : pits.length + 3;
            for (int pit = 0; pit < pits.length; pit++) {
                double off = Math.sqrt(Math.pow(at.x - pits[pit][0], 2) + Math.pow(at.y - pits[pit][1], 2)
                        + Math.pow(at.z - pits[pit][2], 2));
                place = off < pits[pit][3] + HALF * pits[pit][4] ? pit + 2 : place;
            }
            pocks.get(place).add(radius[vertex] / edge);
        }
        out.append(POCK_MARKS).append(String.format(edgeTold, edge))
                .append(sphereTold("the groove's bottom row", pocks.get(0)))
                .append(sphereTold("the groove's flanks", pocks.get(1)));
        for (int pit = 0; pit < pits.length; pit++) {
            out.append(sphereTold("pit " + (pit + 1) + " (width " + pits[pit][3] + ", depth " + pits[pit][4]
                    + ")", pocks.get(pit + 2)));
        }
        out.append(sphereTold("the rest of one half of the tube", pocks.get(pits.length + 2)))
                .append(sphereTold("the rest of the other half", pocks.get(pits.length + 3)));
        Files.write(Path.of("tmp/craw-112-fable-resting-sphere.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theBenchUnderCurvatureWallsWithNoPinIsWrittenToTmp() throws IOException {
        benchUnder(fit -> walled(fit, false, true), false, "tmp/craw-112-fable-bench-walls-unpinned.txt");
    }

    @Test
    void theBenchUnderSphereWallsWithNoPinIsWrittenToTmp() throws IOException {
        benchUnder(fit -> walled(fit, true, true), false, "tmp/craw-112-fable-bench-sphere-walls-unpinned.txt");
    }

    @Test
    void theBenchUnderSphereWallsWithThePinIsWrittenToTmp() throws IOException {
        benchUnder(fit -> walled(fit, true, false), false, "tmp/craw-112-fable-bench-sphere-walls-pinned.txt");
    }

    /**
     * Sets a fit to walls air cannot cross, each side of a ring pushing by the air arriving there.
     *
     * @param fit      the fit
     * @param bySphere whether the walls are found by the resting sphere, not by crease strength
     * @param unpinned whether a seam lying along a wall is free to move
     */
    private static void walled(RegionClusterFit fit, boolean bySphere, boolean unpinned) {
        fit.bottleneck = false;
        fit.crowdingShares = false;
        fit.closedWalls = true;
        fit.wallsUnpinned = unpinned;
        if (bySphere) {
            boolean[] walls = sphereWalls(fit.mesh);
            System.arraycopy(walls, 0, fit.grooveBottom, 0, walls.length);
            System.arraycopy(walls, 0, fit.closedBottom, 0, walls.length);
        }
    }

    /**
     * The walls the resting sphere finds: a vertex where the sphere is no bigger than half the
     * model, and no smaller at the neighbour lying opposite the one where it grows most. That is
     * the bottom of a valley of the sphere's radius, one vertex wide.
     *
     * @param mesh the surface
     * @return whether each vertex is a wall, by vertex id
     */
    private static boolean[] sphereWalls(MeshTopology mesh) {
        return shrunkSphereWalls == null ? sphereWalls(mesh, closedSphereWalls)
                : shrunkWalls(mesh, shrunkSphereWalls.contains(SHRUNK_BY_THINNEST),
                        shrunkSphereWalls.contains("ends"));
    }

    /**
     * Walls with no gaps: the band of small resting spheres, shrunk widest sphere first without
     * tearing or piercing it, so a band round a fold leaves one unbroken loop.
     *
     * @param mesh       the surface
     * @param byThinnest whether "small enough" is the model's thinnest half-width, not half its size
     * @param keepsEnds  whether a wall's loose end is kept, so a groove that stops keeps its wall
     * @return whether each vertex is a wall, by vertex id
     */
    private static boolean[] shrunkWalls(MeshTopology mesh, boolean byThinnest, boolean keepsEnds) {
        double[] radius = restingSphere(mesh);
        Vector3f least = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f most = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f at = new Vector3f();
        for (int active = 0; active < mesh.vertexCount(); active++) {
            mesh.vertexPosition(mesh.vertexIdAt(active), at);
            least.min(at);
            most.max(at);
        }
        double limit = byThinnest
                ? HALF * Math.min(most.x - least.x, Math.min(most.y - least.y, most.z - least.z))
                : HALF * HALF * least.distance(most);
        boolean[] walls = new boolean[mesh.vertexIdBound()];
        // Round each vertex, the pairs of neighbours that share a triangle with it.
        List<List<int[]>> around = new ArrayList<>();
        for (int vertex = 0; vertex < walls.length; vertex++) {
            around.add(new ArrayList<>());
        }
        int[] triangles = mesh.triangulate(TriangulationRule.FAN, null);
        for (int corner = 0; corner < triangles.length; corner += 3) {
            for (int of = 0; of < 3; of++) {
                around.get(triangles[corner + of]).add(
                        new int[] { triangles[corner + (of + 1) % 3], triangles[corner + (of + 2) % 3] });
            }
        }
        PriorityQueue<Integer> widest = new PriorityQueue<>(
                (one, two) -> Double.compare(radius[two], radius[one]));
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            walls[vertex] = radius[vertex] <= limit;
            if (walls[vertex]) {
                widest.add(vertex);
            }
        }
        while (!widest.isEmpty()) {
            int vertex = widest.poll();
            if (!walls[vertex]) {
                continue;
            }
            // Its wall neighbours lie in one unbroken run round it, and not all the way round, when
            // the ring of its neighbours changes between wall and not exactly twice.
            int changes = 0;
            for (int[] pair : around.get(vertex)) {
                changes += walls[pair[0]] != walls[pair[1]] ? 1 : 0;
            }
            int beside = 0;
            for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertex); spoke++) {
                beside += walls[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertex, spoke), vertex)] ? 1 : 0;
            }
            if (beside == 0 || changes == 2 && !(keepsEnds && beside == 1)) {
                walls[vertex] = false;
                for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertex); spoke++) {
                    int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertex, spoke), vertex);
                    if (walls[other]) {
                        widest.add(other);
                    }
                }
            }
        }
        return walls;
    }

    /**
     * {@link #sphereWalls(MeshTopology)}, run on where asked: from every wall the wall takes in
     * each neighbour whose sphere is no bigger than half the model and is stopped by one of that
     * neighbour's own neighbours, as it is along a fold and is not on a fold's flank.
     *
     * @param mesh   the surface
     * @param closed whether the walls run on so
     * @return whether each vertex is a wall, by vertex id
     */
    private static boolean[] sphereWalls(MeshTopology mesh, boolean closed) {
        int[] stopper = new int[mesh.vertexIdBound()];
        double[] radius = restingSphere(mesh, stopper);
        Vector3f least = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f most = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f at = new Vector3f();
        Vector3f toward = new Vector3f();
        Vector3f spoke = new Vector3f();
        for (int active = 0; active < mesh.vertexCount(); active++) {
            mesh.vertexPosition(mesh.vertexIdAt(active), at);
            least.min(at);
            most.max(at);
        }
        double model = HALF * least.distance(most);
        boolean[] walls = new boolean[mesh.vertexIdBound()];
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            if (radius[vertex] > HALF * model) {
                continue;
            }
            int growing = -1;
            for (int at2 = 0; at2 < mesh.vertexEdgeCount(vertex); at2++) {
                int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertex, at2), vertex);
                growing = growing < 0 || radius[other] > radius[growing] ? other : growing;
            }
            if (growing < 0 || radius[growing] <= radius[vertex]) {
                continue;
            }
            mesh.vertexPosition(vertex, at);
            mesh.vertexPosition(growing, toward).sub(at).normalize();
            int opposite = -1;
            float mostOpposite = Float.POSITIVE_INFINITY;
            for (int at2 = 0; at2 < mesh.vertexEdgeCount(vertex); at2++) {
                int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertex, at2), vertex);
                float along = mesh.vertexPosition(other, spoke).sub(at).normalize().dot(toward);
                if (along < mostOpposite) {
                    mostOpposite = along;
                    opposite = other;
                }
            }
            walls[vertex] = radius[opposite] >= radius[vertex];
        }
        List<Integer> reached = new ArrayList<>();
        for (int active = 0; closed && active < mesh.vertexCount(); active++) {
            if (walls[mesh.vertexIdAt(active)]) {
                reached.add(mesh.vertexIdAt(active));
            }
        }
        for (int next = 0; next < reached.size(); next++) {
            int wall = reached.get(next);
            for (int at2 = 0; at2 < mesh.vertexEdgeCount(wall); at2++) {
                int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(wall, at2), wall);
                boolean byNeighbour = false;
                for (int at3 = 0; at3 < mesh.vertexEdgeCount(other); at3++) {
                    byNeighbour |= mesh.edgeOtherVertex(mesh.vertexEdgeAt(other, at3), other) == stopper[other];
                }
                if (!walls[other] && byNeighbour && radius[other] <= HALF * model) {
                    walls[other] = true;
                    reached.add(other);
                }
            }
        }
        return walls;
    }

    @Test
    void theBenchUnderSphereWallsRunOnWithNoPinIsWrittenToTmp() throws IOException {
        closedSphereWalls = true;
        benchUnder(fit -> walled(fit, true, true), false, "tmp/craw-112-fable-bench-sphere-walls-closed-unpinned.txt");
        closedSphereWalls = false;
    }

    @Test
    void theBenchUnderSphereWallsRunOnWithThePinIsWrittenToTmp() throws IOException {
        closedSphereWalls = true;
        benchUnder(fit -> walled(fit, true, false), false, "tmp/craw-112-fable-bench-sphere-walls-closed-pinned.txt");
        closedSphereWalls = false;
    }

    @Test
    void theBenchUnderTheWallsTheAppFindsWithNoPinIsWrittenToTmp() throws IOException {
        benchUnder(fit -> {
            walled(fit, false, true);
            boolean[] walls = RestingSphere.walls(fit.mesh, false);
            System.arraycopy(walls, 0, fit.grooveBottom, 0, walls.length);
            System.arraycopy(walls, 0, fit.closedBottom, 0, walls.length);
        }, false, "tmp/craw-112-fable-bench-app-walls.txt");
    }

    @Test
    void theShrunkSphereWallsAreWrittenToTmp() throws IOException {
        for (String how : List.of(SHRUNK_BY_HALF, "half ends", SHRUNK_BY_THINNEST, SHRUNK_BY_THINNEST_ENDS)) {
            shrunkSphereWalls = how;
            theWallsTheRestingSphereFindsAreWrittenToTmp();
        }
        shrunkSphereWalls = null;
    }

    @Test
    void theBenchUnderShrunkSphereWallsWithNoPinIsWrittenToTmp() throws IOException {
        shrunkSphereWalls = SHRUNK_BY_THINNEST;
        benchUnder(fit -> walled(fit, true, true), false, "tmp/craw-112-fable-bench-shrunk-walls-unpinned.txt");
        shrunkSphereWalls = null;
    }

    @Test
    void theBenchUnderShrunkSphereWallsKeepingEndsWithNoPinIsWrittenToTmp() throws IOException {
        shrunkSphereWalls = SHRUNK_BY_THINNEST_ENDS;
        benchUnder(fit -> walled(fit, true, true), false, "tmp/craw-112-fable-bench-shrunk-walls-ends-unpinned.txt");
        shrunkSphereWalls = null;
    }

    @Test
    void theBenchUnderShrunkSphereWallsOfHalfTheModelWithNoPinIsWrittenToTmp() throws IOException {
        shrunkSphereWalls = SHRUNK_BY_HALF;
        benchUnder(fit -> walled(fit, true, true), false, "tmp/craw-112-fable-bench-shrunk-walls-half-unpinned.txt");
        shrunkSphereWalls = null;
    }

    @Test
    void theWallsTheRestingSphereFindsRunOnAreWrittenToTmp() throws IOException {
        closedSphereWalls = true;
        theWallsTheRestingSphereFindsAreWrittenToTmp();
        closedSphereWalls = false;
    }

    @Test
    void theWallsTheRestingSphereFindsAreWrittenToTmp() throws IOException {
        StringBuilder out = new StringBuilder();
        String port = SurfaceCreasesNode.CREASES.name;
        Vector3f at = new Vector3f();
        // The horn: walls on and off the foot, and the pieces the surface falls into without them.
        NodeGraphRuntime horn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + HORN_RING_ON_HORN + ".dsl", Map.of());
        MeshTopology mesh = ((SurfaceCreases) horn.getNodeOutput(port, port)).sourceMesh;
        boolean[] walls = sphereWalls(mesh);
        double edge = mesh.meanEdgeLength();
        int onFoot = 0;
        int offFoot = 0;
        double farthest = 0;
        int[] piece = UnionFind.singletons(mesh.vertexIdBound());
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            mesh.vertexPosition(vertex, at);
            double off = Math.hypot(at.y - HORN_FOOT_HEIGHT, Math.hypot(at.x, at.z) - HORN_FOOT_RADIUS) / edge;
            onFoot += walls[vertex] && off <= HALF ? 1 : 0;
            offFoot += walls[vertex] && off > HALF ? 1 : 0;
            farthest = walls[vertex] ? Math.max(farthest, off) : farthest;
            for (int spoke = 0; !walls[vertex] && spoke < mesh.vertexEdgeCount(vertex); spoke++) {
                int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertex, spoke), vertex);
                if (!walls[other]) {
                    UnionFind.union(piece, vertex, other);
                }
            }
        }
        Map<Integer, Integer> sizes = new HashMap<>();
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            if (!walls[vertex]) {
                sizes.merge(UnionFind.find(piece, vertex), 1, Integer::sum);
            }
        }
        out.append(String.format(
                "%s: walls on the foot circle %d of its 220 vertices, off it %d, the farthest %.1f edges off;"
                        + " without its walls the surface falls into pieces of %s vertices%n",
                HORN_RING_ON_HORN, onFoot, offFoot, farthest,
                sizes.values().stream().sorted((one, two) -> two - one).limit(6).toList()));
        // The pock-mark torus: walls on the groove's bottom row, in the pits and elsewhere.
        NodeGraphRuntime pocked = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + POCK_MARKS + ".dsl", Map.of());
        mesh = ((SurfaceCreases) pocked.getNodeOutput(port, port)).sourceMesh;
        walls = sphereWalls(mesh);
        double[][] pits = { { 1.767767, 0, 1.767767, 0.3, 0.5 }, { 0.938943, 0.5, 1.765895, 0.4, 0.2 },
            { 1.299038, 0, 0.75, 0.22, 0.6 }, { 0.695288, -0.433013, 2.139877, 0.3, 0.35 },
            { -1.863796, 0.25, 1.56391, 0.3, 0.5 }, { 1.664349, -0.433013, 0.54078, 0.25, 0.3 },
            { -0.662237, 0.25, 1.420173, 0.28, 0.4 }, { 1.25, 0, -2.165064, 0.35, 0.45 } };
        int[] counts = new int[pits.length + 3];
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            if (!walls[vertex]) {
                continue;
            }
            mesh.vertexPosition(vertex, at);
            double round = Math.abs(RingFitBench.roundTorusDegrees(at));
            int place = round < 1.2 ? 0 : round < 6 ? 1 : pits.length + 2;
            for (int pit = 0; pit < pits.length; pit++) {
                double off = Math.sqrt(Math.pow(at.x - pits[pit][0], 2) + Math.pow(at.y - pits[pit][1], 2)
                        + Math.pow(at.z - pits[pit][2], 2));
                place = off < pits[pit][3] + HALF * pits[pit][4] ? pit + 2 : place;
            }
            counts[place]++;
        }
        out.append(String.format(
                "%s: walls on the groove's bottom row %d of its 40, on its flanks %d, in the eight pits %s,"
                        + " everywhere else %d%n",
                POCK_MARKS, counts[0], counts[1], Arrays.toString(Arrays.copyOfRange(counts, 2, pits.length + 2)),
                counts[pits.length + 2]));
        Files.write(Path.of(shrunkSphereWalls != null
                ? "tmp/craw-112-fable-sphere-walls-shrunk-" + shrunkSphereWalls.replace(' ', '-') + ".txt"
                : closedSphereWalls ? "tmp/craw-112-fable-sphere-walls-closed.txt"
                        : "tmp/craw-112-fable-sphere-walls.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One place's line of the resting-sphere report.
     *
     * @param name  the place
     * @param radii the radius at each of its vertices, infinite where nothing limits it
     * @return the line, ending in a line break
     */
    private static String sphereTold(String name, List<Double> radii) {
        double[] sorted = radii.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        long unlimited = radii.stream().filter(radius -> radius.isInfinite()).count();
        return sorted.length == 0 ? String.format("  %s: none%n", name)
                : String.format("  %s: %d; %.2f / %.2f / %.2f; %d%n", name, sorted.length, sorted[0],
                        sorted[sorted.length / 2], sorted[sorted.length - 1], unlimited);
    }

    /**
     * The largest sphere that rests on the outside of a surface at each vertex, touching it there,
     * and holds no other vertex: small in a crease, without limit on flat or bulging surface.
     *
     * @param mesh the surface
     * @return the sphere's radius by vertex id, infinite where no vertex limits it
     */
    private static double[] restingSphere(MeshTopology mesh) {
        return restingSphere(mesh, new int[mesh.vertexIdBound()]);
    }

    /**
     * {@link #restingSphere(MeshTopology)}, also telling which vertex stops each sphere growing.
     *
     * @param mesh    the surface
     * @param stopper filled by vertex id with the vertex on the sphere's skin, or -1 for none
     * @return the sphere's radius by vertex id, infinite where no vertex limits it
     */
    private static double[] restingSphere(MeshTopology mesh, int[] stopper) {
        int[] triangles = mesh.triangulate(TriangulationRule.FAN, null);
        int bound = mesh.vertexIdBound();
        double[] normal = new double[3 * bound];
        double[] position = new double[3 * bound];
        Vector3f first = new Vector3f();
        Vector3f second = new Vector3f();
        Vector3f third = new Vector3f();
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            mesh.vertexPosition(vertex, first);
            position[3 * vertex] = first.x;
            position[3 * vertex + 1] = first.y;
            position[3 * vertex + 2] = first.z;
        }
        double outward = mesh.signedVolume() >= 0 ? 1 : -1;
        for (int corner = 0; corner < triangles.length; corner += 3) {
            mesh.vertexPosition(triangles[corner], first);
            mesh.vertexPosition(triangles[corner + 1], second).sub(first);
            mesh.vertexPosition(triangles[corner + 2], third).sub(first);
            second.cross(third);
            for (int of = 0; of < 3; of++) {
                normal[3 * triangles[corner + of]] += outward * second.x;
                normal[3 * triangles[corner + of] + 1] += outward * second.y;
                normal[3 * triangles[corner + of] + 2] += outward * second.z;
            }
        }
        double[] radius = new double[bound];
        IntStream.range(0, mesh.vertexCount()).parallel().forEach(active -> {
            int vertex = mesh.vertexIdAt(active);
            double length = Math.sqrt(normal[3 * vertex] * normal[3 * vertex]
                    + normal[3 * vertex + 1] * normal[3 * vertex + 1] + normal[3 * vertex + 2] * normal[3 * vertex + 2]);
            double least = Double.POSITIVE_INFINITY;
            stopper[vertex] = -1;
            for (int other = 0; length > 0 && other < mesh.vertexCount(); other++) {
                int against = mesh.vertexIdAt(other);
                double x = position[3 * against] - position[3 * vertex];
                double y = position[3 * against + 1] - position[3 * vertex + 1];
                double z = position[3 * against + 2] - position[3 * vertex + 2];
                double above = (x * normal[3 * vertex] + y * normal[3 * vertex + 1] + z * normal[3 * vertex + 2])
                        / length;
                // A sphere of this radius resting here has the other vertex just on its skin.
                if (above > 0 && (x * x + y * y + z * z) / (2 * above) < least) {
                    least = (x * x + y * y + z * z) / (2 * above);
                    stopper[vertex] = against;
                }
            }
            radius[vertex] = least;
        });
        return radius;
    }

    /**
     * One place's line of the turn-across report.
     *
     * @param name the place
     * @param rows each vertex's crease strength, turn in degrees and edges walked across
     * @return the line, ending in a line break
     */
    private static String placeTold(String name, List<double[]> rows) {
        double[] least = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };
        double[] most = new double[2];
        double[] sum = new double[3];
        for (double[] row : rows) {
            for (int column = 0; column < sum.length; column++) {
                sum[column] += row[column];
                if (column < least.length) {
                    least[column] = Math.min(least[column], row[column]);
                    most[column] = Math.max(most[column], row[column]);
                }
            }
        }
        int count = Math.max(1, rows.size());
        return String.format("  %s: %d; strength %.3f / %.3f / %.3f; turns %.1f / %.1f / %.1f; %.1f edges across%n", name,
                rows.size(), least[0], sum[0] / count, most[0], least[1], sum[1] / count, most[1], sum[2] / count);
    }

    /**
     * How far the surface turns across the valley at every vertex: from the vertex, walk each way
     * across the valley for as long as the surface still bends inward and the smoothed valley
     * curvature keeps falling, and take the angle between the surface's normals where the two
     * walks end.
     *
     * @param mesh    the surface
     * @param creases its creases, with their across directions
     * @return by vertex id, the turn in degrees and the edges the two walks took together
     */
    private static double[][] turnAcross(MeshTopology mesh, SurfaceCreases creases) {
        int[] triangles = mesh.triangulate(TriangulationRule.FAN, null);
        Vector3f[] normal = new Vector3f[mesh.vertexIdBound()];
        Vector3f first = new Vector3f();
        Vector3f second = new Vector3f();
        Vector3f third = new Vector3f();
        for (int corner = 0; corner < triangles.length; corner += 3) {
            mesh.vertexPosition(triangles[corner], first);
            mesh.vertexPosition(triangles[corner + 1], second).sub(first);
            mesh.vertexPosition(triangles[corner + 2], third).sub(first);
            second.cross(third);
            for (int of = 0; of < 3; of++) {
                int vertex = triangles[corner + of];
                normal[vertex] = (normal[vertex] == null ? new Vector3f() : normal[vertex]).add(second);
            }
        }
        double[][] turns = new double[mesh.vertexIdBound()][2];
        Vector3f heading = new Vector3f();
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            if (normal[vertex] == null) {
                continue;
            }
            int[] ends = new int[2];
            for (int way = 0; way < ends.length; way++) {
                heading.set(creases.acrossDirection[3 * vertex], creases.acrossDirection[3 * vertex + 1],
                        creases.acrossDirection[3 * vertex + 2]).mul(way == 0 ? 1 : -1);
                int stands = vertex;
                for (int step = 0; step < mesh.vertexCount(); step++) {
                    // Keep the heading in the surface as it bends.
                    first.set(normal[stands]).normalize();
                    heading.sub(first.mul(heading.dot(first)));
                    if (heading.lengthSquared() <= 0) {
                        break;
                    }
                    heading.normalize();
                    mesh.vertexPosition(stands, first);
                    int next = -1;
                    float best = 0;
                    for (int spoke = 0; spoke < mesh.vertexEdgeCount(stands); spoke++) {
                        int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(stands, spoke), stands);
                        float along = mesh.vertexPosition(other, second).sub(first).normalize().dot(heading);
                        if (along > best) {
                            best = along;
                            next = other;
                        }
                    }
                    if (next < 0 || creases.valleySignal[next] <= 0
                            || creases.valleyCurvature[next] >= creases.valleyCurvature[stands]) {
                        break;
                    }
                    stands = next;
                    turns[vertex][1]++;
                }
                ends[way] = stands;
            }
            turns[vertex][0] = Math.toDegrees(first.set(normal[ends[0]]).normalize()
                    .angle(second.set(normal[ends[1]]).normalize()));
        }
        return turns;
    }

    @Test
    void theCreaseStrengthAcrossTheBisectorGrooveAndRoundTheHornFootIsWrittenToTmp() throws IOException {
        StringBuilder out = new StringBuilder();
        Vector3f at = new Vector3f();
        String port = SurfaceCreasesNode.CREASES.name;
        // The long groove: every row of vertices round the tube, by its angle from the groove's bottom.
        NodeGraphRuntime torus = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + "bisector_smooth_rings.dsl", Map.of());
        SurfaceCreases grooved = (SurfaceCreases) torus.getNodeOutput(port, port);
        int rows = 9;
        double[][] byRow = new double[rows][4];
        for (double[] row : byRow) {
            row[1] = Double.POSITIVE_INFINITY;
        }
        for (int active = 0; active < grooved.sourceMesh.vertexCount(); active++) {
            int vertex = grooved.sourceMesh.vertexIdAt(active);
            grooved.sourceMesh.vertexPosition(vertex, at);
            // Away from the two rings' pinches, which are grooves of their own.
            double round = RingFitBench.roundTorusDegrees(at);
            int row = (int) Math.round(Math.abs(RingFitBench.roundTubeDegrees(at)) / 9.0);
            if (row >= rows || round < 110 || round > 160) {
                continue;
            }
            double strength = grooved.valleyStrength[vertex];
            byRow[row][0]++;
            byRow[row][1] = Math.min(byRow[row][1], strength);
            byRow[row][2] += strength;
            byRow[row][3] = Math.max(byRow[row][3], strength);
        }
        out.append("bisector_smooth_rings, between the rings: crease strength by degrees round the tube from"
                + " the groove's bottom (count, least, mean, most); graded air lets by one minus it\n");
        for (int row = 0; row < rows; row++) {
            out.append(String.format("  %3d degrees: %4.0f vertices, %.3f / %.3f / %.3f%n", 9 * row, byRow[row][0],
                    byRow[row][1], byRow[row][2] / Math.max(1, byRow[row][0]), byRow[row][3]));
        }
        // What the strengths were divided by, and where the mesh curves most.
        float[] curved = grooved.valleyCurvature;
        double[] positive = new double[grooved.sourceMesh.vertexCount()];
        int positiveCount = 0;
        int most = -1;
        int full = 0;
        double bottomSmoothed = 0;
        double bottomRaw = 0;
        for (int active = 0; active < grooved.sourceMesh.vertexCount(); active++) {
            int vertex = grooved.sourceMesh.vertexIdAt(active);
            if (curved[vertex] > 0) {
                positive[positiveCount++] = curved[vertex];
            }
            most = most < 0 || curved[vertex] > curved[most] ? vertex : most;
            full += grooved.valleyStrength[vertex] >= 1 ? 1 : 0;
            grooved.sourceMesh.vertexPosition(vertex, at);
            if (Math.abs(RingFitBench.roundTubeDegrees(at)) < 1 && Math.abs(RingFitBench.roundTorusDegrees(at) - 135) < 1) {
                bottomSmoothed = curved[vertex];
                bottomRaw = grooved.valleySignal[vertex];
            }
        }
        Arrays.sort(positive, 0, positiveCount);
        grooved.sourceMesh.vertexPosition(most, at);
        out.append(String.format(
                "  divided by %.3f; the 95th percentile of the smoothed valley curvature is %.3f over %d vertices"
                        + " of %d with any%n  groove bottom: %.3f as measured, %.3f smoothed%n"
                        + "  most curved vertex: %.3f smoothed, at %.1f round the tube, %.1f round the torus;"
                        + " vertices at full strength %d%n",
                grooved.saturation, positive[(int) Math.min(positiveCount - 1, Math.floor(0.95 * positiveCount))],
                positiveCount, grooved.sourceMesh.vertexCount(), bottomRaw, bottomSmoothed, curved[most],
                RingFitBench.roundTubeDegrees(at), RingFitBench.roundTorusDegrees(at), full));
        // The horn's foot: the vertices on its circle.
        NodeGraphRuntime horn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + HORN_RING_ON_HORN + ".dsl", Map.of());
        SurfaceCreases foot = (SurfaceCreases) horn.getNodeOutput(port, port);
        double edge = foot.sourceMesh.meanEdgeLength();
        int[] counts = new int[5];
        double least = Double.POSITIVE_INFINITY;
        double sum = 0;
        for (int active = 0; active < foot.sourceMesh.vertexCount(); active++) {
            int vertex = foot.sourceMesh.vertexIdAt(active);
            foot.sourceMesh.vertexPosition(vertex, at);
            if (Math.hypot(at.y - HORN_FOOT_HEIGHT, Math.hypot(at.x, at.z) - HORN_FOOT_RADIUS) > HALF * edge) {
                continue;
            }
            double strength = foot.valleyStrength[vertex];
            counts[0]++;
            counts[1] += strength >= 1 ? 1 : 0;
            counts[2] += strength >= 0.9 && strength < 1 ? 1 : 0;
            counts[3] += strength >= HALF && strength < 0.9 ? 1 : 0;
            counts[4] += strength < HALF ? 1 : 0;
            least = Math.min(least, strength);
            sum += strength;
        }
        out.append(String.format(
                "horn_ring_on_horn, vertices on the foot circle: %d; at full strength %d, 0.9 to 1 %d, 0.5 to 0.9 %d,"
                        + " under 0.5 %d; least %.3f, mean %.3f%n",
                counts[0], counts[1], counts[2], counts[3], counts[4], least, sum / Math.max(1, counts[0])));
        Files.write(Path.of("tmp/craw-112-fable-crease-strength.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * How far a fit's first ring lies from the horn's foot circle as it stands.
     *
     * @param fit a fit of a horn fixture
     * @return its seam vertex count, then the farthest and the mean vertex's distance in mean edges
     */
    private static String footDistances(RegionClusterFit fit) {
        Vector3f at = new Vector3f();
        boolean[] seen = new boolean[fit.mesh.vertexIdBound()];
        double farthest = 0;
        double sum = 0;
        int count = 0;
        for (int edge : fit.loops[0]) {
            int halfEdge = fit.mesh.edgeHalfEdge(edge);
            for (int vertex : new int[] { fit.mesh.halfEdgeVertex(halfEdge), fit.mesh.halfEdgeEndVertex(halfEdge) }) {
                if (seen[vertex]) {
                    continue;
                }
                seen[vertex] = true;
                fit.mesh.vertexPosition(vertex, at);
                double off = Math.hypot(at.y - HORN_FOOT_HEIGHT, Math.hypot(at.x, at.z) - HORN_FOOT_RADIUS)
                        / fit.mesh.meanEdgeLength();
                farthest = Math.max(farthest, off);
                sum += off;
                count++;
            }
        }
        return String.format("%d vertices, farthest %.2f, mean %.2f", count, farthest, sum / Math.max(1, count));
    }

    @Test
    void theBenchUnderGradedLineAirWithSharesScalingPressureIsWrittenToTmp() throws IOException {
        benchUnder(fit -> {
            fit.gradedWalls = true;
            fit.sharesScalePressure = true;
        }, "tmp/craw-112-fable-bench-graded-crowding-thin-scale.txt");
    }

    @Test
    void theBenchUnderGradedBandAirWithSharesScalingPressureIsWrittenToTmp() throws IOException {
        benchUnder(fit -> {
            fit.gradedWalls = true;
            fit.gradedBand = true;
            fit.sharesScalePressure = true;
        }, "tmp/craw-112-fable-bench-graded-crowding-band-scale.txt");
    }

    @Test
    void theBenchUnderBandAirGradedBySquaredStrengthIsWrittenToTmp() throws IOException {
        gradedCurveBench(2);
    }

    @Test
    void theBenchUnderBandAirGradedByFourthPowerStrengthIsWrittenToTmp() throws IOException {
        gradedCurveBench(4);
    }

    @Test
    void theBenchUnderBandAirGradedByEighthPowerStrengthIsWrittenToTmp() throws IOException {
        gradedCurveBench(8);
    }

    @Test
    void theBenchUnderBandAirGradedBySixteenthPowerStrengthIsWrittenToTmp() throws IOException {
        gradedCurveBench(16);
    }

    /**
     * Run the bench with air graded over the whole band by one minus a power of the crease
     * strength, once with the shares scaling the pressure and once with them deciding first.
     *
     * @param power the power the strength is raised to
     * @throws IOException when a file cannot be written
     */
    private static void gradedCurveBench(int power) throws IOException {
        for (boolean scale : new boolean[] { true, false }) {
            benchUnder(fit -> {
                fit.gradedWalls = true;
                fit.gradedBand = true;
                fit.gradedSharpness = power;
                fit.sharesScalePressure = scale;
            }, "tmp/craw-112-fable-bench-graded-power" + power + (scale ? "-scale" : "-first") + ".txt");
        }
    }

    @Test
    void theBenchUnderGradedAirWithSeamsHeldAndTheCrowdingRuleIsWrittenToTmp() throws IOException {
        benchUnder(fit -> {
            fit.gradedWalls = true;
            fit.gradedHeld = true;
        }, "tmp/craw-112-fable-bench-graded-crowding-thin-held.txt");
        benchUnder(fit -> {
            fit.gradedWalls = true;
            fit.gradedBand = true;
            fit.gradedHeld = true;
        }, "tmp/craw-112-fable-bench-graded-crowding-band-held.txt");
    }

    /**
     * Run the bench under the air-flow fit with the crowding rule and write each case's score and
     * run beside it.
     *
     * @param walls sets how the fit's air meets the grooves: closed walls, or a graded form
     * @param file  where the scores go
     * @throws IOException when the file cannot be written
     */
    private static void benchUnder(Consumer<RegionClusterFit> walls, String file) throws IOException {
        benchUnder(walls, false, file);
    }

    @Test
    void theBenchUnderTravelWeightedGradedAirIsWrittenToTmp() throws IOException {
        benchUnder(fit -> travelWeighted(fit, false), false, "tmp/craw-112-fable-bench-travel.txt");
    }

    @Test
    void theBenchUnderTravelWeightedGradedAirWithTheOldCreaseScaleIsWrittenToTmp() throws IOException {
        benchUnder(fit -> travelWeighted(fit, false), true, "tmp/craw-112-fable-bench-travel-old-scale.txt");
    }

    @Test
    void theBenchUnderTravelWeightedGradedAirKeepingPressureIsWrittenToTmp() throws IOException {
        benchUnder(fit -> travelWeighted(fit, true), false, "tmp/craw-112-fable-bench-travel-pressure.txt");
    }

    @Test
    void theBenchUnderTravelWeightedGradedAirKeepingPressureWithTheOldCreaseScaleIsWrittenToTmp()
            throws IOException {
        benchUnder(fit -> travelWeighted(fit, true), true, "tmp/craw-112-fable-bench-travel-pressure-old-scale.txt");
    }

    @Test
    void theBenchUnderAirRisingFromTheCreasesIsWrittenToTmp() throws IOException {
        benchUnder(fit -> creaseAir(fit, false), false, "tmp/craw-112-fable-bench-crease-air.txt");
    }

    @Test
    void theBenchUnderAirRisingFromTheCreasesWithTheOldCreaseScaleIsWrittenToTmp() throws IOException {
        benchUnder(fit -> creaseAir(fit, false), true, "tmp/craw-112-fable-bench-crease-air-old-scale.txt");
    }

    @Test
    void theBenchUnderAirRisingFromTheValleyLinesIsWrittenToTmp() throws IOException {
        benchUnder(fit -> creaseAir(fit, true), false, "tmp/craw-112-fable-bench-crease-air-line.txt");
    }

    @Test
    void theBenchUnderAirRisingFromTheValleyLinesWithTheOldCreaseScaleIsWrittenToTmp() throws IOException {
        benchUnder(fit -> creaseAir(fit, true), true, "tmp/craw-112-fable-bench-crease-air-line-old-scale.txt");
    }

    @Test
    void theBenchUnderTravelWeightedAirFromTheCreasesIsWrittenToTmp() throws IOException {
        benchUnder(fit -> {
            creaseAir(fit, false);
            fit.travelWeighted = true;
        }, false, "tmp/craw-112-fable-bench-crease-travel.txt");
    }

    @Test
    void theBenchUnderTravelWeightedAirFromTheCreasesWithTheOldCreaseScaleIsWrittenToTmp() throws IOException {
        benchUnder(fit -> {
            creaseAir(fit, false);
            fit.travelWeighted = true;
        }, true, "tmp/craw-112-fable-bench-crease-travel-old-scale.txt");
    }

    @Test
    void theBenchUnderTravelWeightedAirFromTheValleyLinesIsWrittenToTmp() throws IOException {
        benchUnder(fit -> {
            creaseAir(fit, true);
            fit.travelWeighted = true;
        }, false, "tmp/craw-112-fable-bench-crease-travel-line.txt");
    }

    @Test
    void theBenchUnderTravelWeightedAirFromTheValleyLinesWithTheOldCreaseScaleIsWrittenToTmp() throws IOException {
        benchUnder(fit -> {
            creaseAir(fit, true);
            fit.travelWeighted = true;
        }, true, "tmp/craw-112-fable-bench-crease-travel-line-old-scale.txt");
    }

    @Test
    void theBenchUnderRingAirSwallowedByTheCreasesIsWrittenToTmp() throws IOException {
        benchUnder(fit -> ringAir(fit, false, false), false, "tmp/craw-112-fable-bench-ring-air.txt");
    }

    @Test
    void theBenchUnderRingAirSwallowedByTheCreasesWithTheOldCreaseScaleIsWrittenToTmp() throws IOException {
        benchUnder(fit -> ringAir(fit, false, false), true, "tmp/craw-112-fable-bench-ring-air-old-scale.txt");
    }

    @Test
    void theBenchUnderRingAirSwallowedByTheValleyLinesIsWrittenToTmp() throws IOException {
        benchUnder(fit -> ringAir(fit, true, false), false, "tmp/craw-112-fable-bench-ring-air-line.txt");
    }

    @Test
    void theBenchUnderRingAirSwallowedByTheValleyLinesWithTheOldCreaseScaleIsWrittenToTmp() throws IOException {
        benchUnder(fit -> ringAir(fit, true, false), true, "tmp/craw-112-fable-bench-ring-air-line-old-scale.txt");
    }

    @Test
    void theBenchUnderRingAirSwallowedByCurvatureIsWrittenToTmp() throws IOException {
        benchUnder(fit -> ringAir(fit, false, true), false, "tmp/craw-112-fable-bench-ring-air-curvature.txt");
    }

    @Test
    void theBenchUnderTravelWeightedAirGradedByTheRestingSphereIsWrittenToTmp() throws IOException {
        sphereCreases = true;
        benchUnder(fit -> travelWeighted(fit, true), false, "tmp/craw-112-fable-bench-sphere-travel.txt");
        sphereCreases = false;
    }

    @Test
    void theBenchUnderAirGradedByTheRestingSphereIsWrittenToTmp() throws IOException {
        sphereCreases = true;
        benchUnder(fit -> {
            fit.gradedWalls = true;
            fit.gradedBand = true;
            fit.bottleneck = false;
            fit.crowdingShares = false;
        }, false, "tmp/craw-112-fable-bench-sphere-graded.txt");
        sphereCreases = false;
    }

    @Test
    void theBenchUnderEvenAirSwallowedByTheRestingSphereIsWrittenToTmp() throws IOException {
        sphereCreases = true;
        benchUnder(fit -> swallowedAir(fit, false, false), false, "tmp/craw-112-fable-bench-sphere-swallow.txt");
        sphereCreases = false;
    }

    @Test
    void theBenchUnderEvenAirSwallowedByTheCreasesIsWrittenToTmp() throws IOException {
        benchUnder(fit -> swallowedAir(fit, false, false), false, "tmp/craw-112-fable-bench-swallow.txt");
    }

    @Test
    void theBenchUnderEvenAirSwallowedByTheCreasesWithTheOldCreaseScaleIsWrittenToTmp() throws IOException {
        benchUnder(fit -> swallowedAir(fit, false, false), true, "tmp/craw-112-fable-bench-swallow-old-scale.txt");
    }

    @Test
    void theBenchUnderEvenAirSwallowedByTheValleyLinesIsWrittenToTmp() throws IOException {
        benchUnder(fit -> swallowedAir(fit, true, false), false, "tmp/craw-112-fable-bench-swallow-line.txt");
    }

    @Test
    void theBenchUnderEvenAirSwallowedByTheValleyLinesWithTheOldCreaseScaleIsWrittenToTmp() throws IOException {
        benchUnder(fit -> swallowedAir(fit, true, false), true, "tmp/craw-112-fable-bench-swallow-line-old-scale.txt");
    }

    @Test
    void theBenchUnderEvenAirSwallowedByCurvatureIsWrittenToTmp() throws IOException {
        benchUnder(fit -> swallowedAir(fit, false, true), false, "tmp/craw-112-fable-bench-swallow-curvature.txt");
    }

    /**
     * Sets a fit to air that rises evenly, as in the bouncy castle, and is swallowed crossing a crease.
     *
     * @param fit         the fit
     * @param lineOnly    whether only a valley's low line swallows
     * @param byCurvature whether a vertex swallows by its curvature squared, not its crease strength
     */
    private static void swallowedAir(RegionClusterFit fit, boolean lineOnly, boolean byCurvature) {
        fit.bottleneck = false;
        fit.crowdingShares = false;
        fit.creasesSwallow = true;
        fit.ringSourceLineOnly = lineOnly;
        fit.ringSourceByCurvature = byCurvature;
    }

    /**
     * Sets a fit to air the seams give off and the creases swallow.
     *
     * @param fit         the fit
     * @param lineOnly    whether only a valley's low line swallows
     * @param byCurvature whether a vertex swallows by its curvature squared, not its crease strength
     */
    private static void ringAir(RegionClusterFit fit, boolean lineOnly, boolean byCurvature) {
        fit.bottleneck = false;
        fit.crowdingShares = false;
        fit.ringSource = true;
        fit.ringSourceLineOnly = lineOnly;
        fit.ringSourceByCurvature = byCurvature;
    }

    /**
     * Sets a fit to air that rises only from the creases and draws each seam toward it.
     *
     * @param fit      the fit
     * @param lineOnly whether only a valley's low line gives off air
     */
    private static void creaseAir(RegionClusterFit fit, boolean lineOnly) {
        fit.bottleneck = false;
        fit.crowdingShares = false;
        fit.creaseSource = true;
        fit.creaseSourceLineOnly = lineOnly;
    }

    /**
     * Sets a fit to graded air whose push is weighted by how far each bit of air travelled.
     *
     * @param fit           the fit
     * @param keepsPressure whether a region's area still divides its push
     */
    private static void travelWeighted(RegionClusterFit fit, boolean keepsPressure) {
        fit.gradedWalls = true;
        fit.gradedBand = true;
        fit.bottleneck = false;
        fit.crowdingShares = false;
        fit.travelWeighted = true;
        fit.travelKeepsPressure = keepsPressure;
    }

    /**
     * Run the bench under the air-flow fit with some switches set, and write every case's score.
     *
     * @param walls         sets the fit's switches
     * @param oldCreaseScale whether crease strengths are divided as they were before the largest
     *                      value on the surface was made to read one
     * @param file          where the scores go
     * @throws IOException when the file cannot be written
     */
    private static void benchUnder(Consumer<RegionClusterFit> walls, boolean oldCreaseScale, String file)
            throws IOException {
        StringBuilder runs = new StringBuilder();
        List<GeometryBundle> fitted = new ArrayList<>();
        RingFitBench.RingFit fit = drawn -> {
            GeometryBundle bundle = (GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY);
            String port = SurfaceCreasesNode.CREASES.name;
            SurfaceCreases graded = (SurfaceCreases) drawn.getNodeOutput(port, port);
            if (sphereCreases) {
                // A crease by the largest sphere that rests there: one that needs no more room than
                // the model itself is flat, and the smaller the sphere the stronger the crease.
                MeshTopology mesh = graded.sourceMesh;
                double[] radius = restingSphere(mesh);
                Vector3f least = new Vector3f(Float.POSITIVE_INFINITY);
                Vector3f most = new Vector3f(Float.NEGATIVE_INFINITY);
                Vector3f at = new Vector3f();
                for (int active = 0; active < mesh.vertexCount(); active++) {
                    mesh.vertexPosition(mesh.vertexIdAt(active), at);
                    least.min(at);
                    most.max(at);
                }
                double model = HALF * least.distance(most);
                for (int active = 0; active < mesh.vertexCount(); active++) {
                    int vertex = mesh.vertexIdAt(active);
                    graded.valleyStrength[vertex] = (float) (1 - Math.min(1.0, radius[vertex] / model));
                }
            }
            if (oldCreaseScale) {
                // The scale of before: the 95th percentile of the smoothed valley curvature, or the
                // surface's median curvature of any kind when that is larger.
                MeshTopology mesh = graded.sourceMesh;
                int[] triangles = mesh.triangulate(TriangulationRule.FAN, null);
                PackedPoints positions = SemanticPatchDecomposer.positionsById(mesh);
                PrincipalDirectionField curvature = PrincipalDirectionField.compute(mesh, positions, triangles,
                        TriangleGeometry.normals(positions, triangles),
                        TriangleSoup.oneRings(triangles, mesh.vertexIdBound()));
                double[] magnitude = new double[mesh.vertexCount()];
                double[] positive = new double[mesh.vertexCount()];
                int positiveCount = 0;
                for (int active = 0; active < magnitude.length; active++) {
                    int vertex = mesh.vertexIdAt(active);
                    magnitude[active] = Math.max(Math.abs(curvature.kappaMax(vertex)),
                            Math.abs(curvature.kappaMin(vertex)));
                    if (graded.valleyCurvature[vertex] > 0) {
                        positive[positiveCount++] = graded.valleyCurvature[vertex];
                    }
                }
                Arrays.sort(magnitude);
                Arrays.sort(positive, 0, positiveCount);
                double scale = Math.max(magnitude.length == 0 ? 0 : magnitude[magnitude.length / 2],
                        positiveCount == 0 ? 0
                                : positive[(int) Math.min(positiveCount - 1, Math.floor(0.95 * positiveCount))]);
                for (int active = 0; active < magnitude.length; active++) {
                    int vertex = mesh.vertexIdAt(active);
                    graded.valleyStrength[vertex] = (float) Math.min(1.0,
                            scale > 0 ? graded.valleyCurvature[vertex] / scale : 0.0);
                }
            }
            RegionClusterFit lifetimeFit = new RegionClusterFit(bundle, graded);
            lifetimeFit.airFlow = true;
            lifetimeFit.bottleneck = true;
            lifetimeFit.crowdingShares = true;
            walls.accept(lifetimeFit);
            long began = System.nanoTime();
            lifetimeFit.run();
            runs.append("passes ").append(lifetimeFit.passes).append(" moves ").append(lifetimeFit.moves)
                    .append(" refusals ").append(lifetimeFit.refusals).append(" waste ").append(lifetimeFit.waste)
                    .append(" deathRate ").append(lifetimeFit.deathRate).append(" seconds ")
                    .append((System.nanoTime() - began) / 1e9).append('\n');
            fitted.add(lifetimeFit.fitted(bundle,
                    (SurfaceMetric) drawn.getNodeOutput(SURFACE_NODE, SurfaceMetricNode.METRIC.name)));
            return fitted.get(fitted.size() - 1);
        };
        List<RingFitBench.Result> results = RingFitBench.run(fit);
        // The bench reads each ring's drawn anchors; the same places measured over every vertex of
        // the fitted ring tell a ring that bulges between its anchors from one that lies in its groove.
        List<RingFitBench.Result> whole = new ArrayList<>();
        for (int at = 0; at < results.size(); at++) {
            whole.add(RingFitBench.scoreFitted(RingFitBench.CASES_IN_RING_ANCHOR_FIT_TEST_ORDER.get(at),
                    fitted.get(at), true));
        }
        String[] runLines = runs.toString().split("\n");
        StringBuilder out = new StringBuilder();
        out.append(results.stream().filter(RingFitBench.Result::passes).count()).append(" of ")
                .append(results.size()).append(" by anchors, ")
                .append(whole.stream().filter(RingFitBench.Result::passes).count()).append(" by the whole ring\n");
        for (int at = 0; at < results.size(); at++) {
            out.append(results.get(at).passes() ? PASSED : FAILED).append(results.get(at)).append("\n     ")
                    .append(runLines[at]).append("\n     whole ring ")
                    .append(whole.get(at).passes() ? PASSED : FAILED).append(whole.get(at).scores()).append('\n');
        }
        Files.createDirectories(Path.of("tmp"));
        Files.write(Path.of(file), out.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(RingFitBench.CASES_IN_RING_ANCHOR_FIT_TEST_ORDER.size(), results.size());
    }

    @Test
    void theFailingCasesAreTracedPassByPassToTmp() throws IOException {
        StringBuilder out = new StringBuilder();
        Vector3f at = new Vector3f();
        for (String fixture : Stream.concat(BENDING_RING_CASES.stream(), Stream.of(GROOVED_RINGS_BISECTOR)).toList()) {
            NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                    RingFitBench.FIXTURE_RESOURCE_DIRECTORY + fixture + ".dsl", Map.of());
            String port = SurfaceCreasesNode.CREASES.name;
            RegionClusterFit fit = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                    (SurfaceCreases) drawn.getNodeOutput(port, port));
            fit.airFlow = true;
            fit.maxPasses = 1;
            int bottoms = 0;
            for (boolean bottom : fit.grooveBottom) {
                bottoms += bottom ? 1 : 0;
            }
            out.append("== ").append(fixture).append(" vertices ").append(fit.mesh.vertexCount())
                    .append(" groove bottoms ").append(bottoms).append(" regions ").append(fit.regionCount).append('\n');
            for (int pass = 0; pass < TRACED_PASSES; pass++) {
                fit.run();
                out.append(pass).append(" moves ").append(fit.moves).append(" refusals ").append(fit.refusals)
                        .append(" waste ").append(fit.waste).append(" rate ").append(fit.deathRate);
                for (int ring = 0; ring < fit.loops.length; ring++) {
                    double[] torus = { Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY };
                    double[] tube = { Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY };
                    int onBottom = 0;
                    for (int edge : fit.loops[ring]) {
                        int vertex = fit.mesh.halfEdgeVertex(fit.mesh.edgeHalfEdge(edge));
                        fit.mesh.vertexPosition(vertex, at);
                        onBottom += fit.grooveBottom[vertex] ? 1 : 0;
                        torus[0] = Math.min(torus[0], RingFitBench.roundTorusDegrees(at));
                        torus[1] = Math.max(torus[1], RingFitBench.roundTorusDegrees(at));
                        tube[0] = Math.min(tube[0], RingFitBench.roundTubeDegrees(at));
                        tube[1] = Math.max(tube[1], RingFitBench.roundTubeDegrees(at));
                    }
                    out.append(String.format(" | %s torus %.1f..%.1f tube %.1f..%.1f edges %d onBottom %d",
                            fit.ringLabels[ring], torus[0], torus[1], tube[0], tube[1], fit.loops[ring].length,
                            onBottom));
                }
                out.append(" | ").append(fit.lastRefusal).append('\n');
                if (fit.moves == 0) {
                    break;
                }
            }
        }
        Files.write(Path.of("tmp/craw-112-fable-trace.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theAirFlowFailuresAreExplainedToTmp() throws IOException {
        StringBuilder out = new StringBuilder();
        Vector3f at = new Vector3f();
        for (String fixture : Stream.concat(BENDING_RING_CASES.stream(), Stream.of(GROOVED_RINGS_BISECTOR,
                "bisector_rings_off_grooves", "two_grooves_one_ring_nearest_both", WIDE_HORN_RING)).toList()) {
            NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                    RingFitBench.FIXTURE_RESOURCE_DIRECTORY + fixture + ".dsl", Map.of());
            String port = SurfaceCreasesNode.CREASES.name;
            RegionClusterFit fit = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                    (SurfaceCreases) drawn.getNodeOutput(port, port));
            fit.airFlow = true;
            fit.closedWalls = true;
            fit.bottleneck = true;
            Map<Integer, Integer> passOfState = new HashMap<>();
            out.append("== ").append(fixture).append('\n');
            fit.maxPasses = 0;
            fit.run();
            int[] drawnFaces = new int[fit.regionCount];
            for (int active = 0; active < fit.mesh.faceCount(); active++) {
                drawnFaces[fit.regionOfFace[fit.mesh.faceIdAt(active)]]++;
            }
            out.append("   as drawn region faces ").append(Arrays.toString(drawnFaces));
            for (int ring = 0; ring < fit.loops.length; ring++) {
                double[] mean = new double[2];
                int bottoms = 0;
                double[][] rows = fit.pushesAlong(ring);
                for (double[] row : rows) {
                    mean[0] += row[1] / rows.length;
                    mean[1] += row[2] / rows.length;
                    bottoms += (int) row[3];
                }
                out.append(String.format(" | %s mean push %.4e %.4e, %d of %d held", fit.ringLabels[ring], mean[0],
                        mean[1], bottoms, rows.length));
            }
            out.append('\n');
            fit.maxPasses = 1;
            SurfaceCreases creases = (SurfaceCreases) drawn.getNodeOutput(port, port);
            int[] walls = new int[2];
            StringBuilder added = new StringBuilder();
            for (int vertex = 0; vertex < fit.grooveBottom.length; vertex++) {
                walls[0] += fit.grooveBottom[vertex] ? 1 : 0;
                walls[1] += fit.closedBottom[vertex] ? 1 : 0;
                if (fit.closedBottom[vertex] && !fit.grooveBottom[vertex]) {
                    fit.mesh.vertexPosition(vertex, at);
                    added.append(String.format(" (%.1f,%.0f s%.2f)", RingFitBench.roundTorusDegrees(at),
                            RingFitBench.roundTubeDegrees(at), creases.valleyStrength[vertex]));
                }
            }
            out.append("   walls gated ").append(walls[0]).append(" closed ").append(walls[1]).append(" added")
                    .append(added).append('\n');
            for (int tube = 54; tube >= -54; tube -= 9) {
                StringBuilder row = new StringBuilder(String.format("   walls tube %4d from torus 81: ", tube));
                for (double torus = 81; torus <= 112.5; torus += 2.25) {
                    char mark = '?';
                    for (int active = 0; active < fit.mesh.vertexCount(); active++) {
                        int vertex = fit.mesh.vertexIdAt(active);
                        fit.mesh.vertexPosition(vertex, at);
                        if (Math.abs(RingFitBench.roundTorusDegrees(at) - torus) < HALF
                                && Math.abs(RingFitBench.roundTubeDegrees(at) - tube) < HALF) {
                            mark = fit.grooveBottom[vertex] ? '#'
                                    : creases.valleyStrength[vertex] >= SurfaceCreases.CREASE_STRENGTH ? 'o' : '.';
                        }
                    }
                    row.append(mark);
                }
                out.append(row).append('\n');
            }
            Map<Long, Integer> bottomsAtTorus = new HashMap<>();
            for (int vertex = 0; vertex < fit.grooveBottom.length; vertex++) {
                if (fit.grooveBottom[vertex]) {
                    fit.mesh.vertexPosition(vertex, at);
                    bottomsAtTorus.merge(Math.round(RingFitBench.roundTorusDegrees(at) * TORUS_SECTIONS), 1,
                            Integer::sum);
                }
            }
            for (int active = 0; active < fit.mesh.vertexCount(); active++) {
                int vertex = fit.mesh.vertexIdAt(active);
                fit.mesh.vertexPosition(vertex, at);
                long torus = Math.round(RingFitBench.roundTorusDegrees(at) * TORUS_SECTIONS);
                if (!fit.grooveBottom[vertex] && bottomsAtTorus.getOrDefault(torus, 0) > TORUS_SECTIONS / 16) {
                    out.append(String.format("   gap torus %.2f tube %.0f strength %.3f curvature %.3f%n",
                            RingFitBench.roundTorusDegrees(at), RingFitBench.roundTubeDegrees(at),
                            creases.valleyStrength[vertex], creases.valleyCurvature[vertex]));
                }
            }
            for (int pass = 0; pass < 2 * TRACED_PASSES; pass++) {
                fit.run();
                Integer before = passOfState.put(Arrays.deepHashCode(fit.loops), pass);
                if (fit.moves == 0 || before != null) {
                    out.append(fit.moves == 0 ? "   still after pass " + pass
                            : "   pass " + pass + " repeats the seams of pass " + before).append(" | ")
                            .append(fit.lastRefusal).append('\n');
                    break;
                }
            }
            double[] regionArea = new double[fit.regionCount];
            for (int active = 0; active < fit.mesh.faceCount(); active++) {
                int face = fit.mesh.faceIdAt(active);
                regionArea[fit.regionOfFace[face]]++;
            }
            out.append("   region areas ").append(Arrays.toString(regionArea)).append('\n');
            for (int ring = 0; ring < fit.loops.length; ring++) {
                out.append("   ").append(fit.ringLabels[ring]).append('\n');
                for (double[] row : fit.pushesAlong(ring)) {
                    fit.mesh.vertexPosition((int) row[0], at);
                    out.append(String.format("      xyz %6.3f %6.3f %6.3f torus %7.2f tube %7.2f push %.4e %.4e%s%n", at.x,
                            at.y, at.z, RingFitBench.roundTorusDegrees(at), RingFitBench.roundTubeDegrees(at), row[1],
                            row[2], row[3] > 0 ? " bottom" : ""));
                }
            }
        }
        Files.write(Path.of("tmp/craw-112-fable-air.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theWideHornRingIsTracedPassByPassToTmp() throws IOException {
        hornTrace(WIDE_HORN_RING, false);
    }

    @Test
    void theHornRingsAreTracedShelterFirstToTmp() throws IOException {
        hornTrace(WIDE_HORN_RING, true);
        hornTrace(HORN_RING_ON_HORN, true);
    }

    private static void hornTrace(String fixture, boolean shelterFirst) throws IOException {
        NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + fixture + ".dsl", Map.of());
        String port = SurfaceCreasesNode.CREASES.name;
        RegionClusterFit fit = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                (SurfaceCreases) drawn.getNodeOutput(port, port));
        fit.airFlow = true;
        fit.closedWalls = true;
        fit.shelterFirst = shelterFirst;
        SurfaceLifetime measure = new SurfaceLifetime(fit.mesh);
        double[] faceArea = new double[fit.mesh.faceIdBound()];
        for (int triangle = 0; triangle < measure.doubleArea.length; triangle++) {
            faceArea[measure.faceOfTriangle[triangle]] += measure.doubleArea[triangle] / 2;
        }
        Vector3f at = new Vector3f();
        double[] wallHeight = { Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY };
        int walls = 0;
        for (int vertex = 0; vertex < fit.closedBottom.length; vertex++) {
            if (fit.closedBottom[vertex]) {
                fit.mesh.vertexPosition(vertex, at);
                wallHeight[0] = Math.min(wallHeight[0], at.y);
                wallHeight[1] = Math.max(wallHeight[1], at.y);
                walls++;
            }
        }
        double above = 0;
        double total = 0;
        for (int active = 0; active < fit.mesh.faceCount(); active++) {
            int face = fit.mesh.faceIdAt(active);
            at.set(fit.mesh.faceCentroid(face));
            above += at.y > wallHeight[1] ? faceArea[face] : 0;
            total += faceArea[face];
        }
        StringBuilder out = new StringBuilder(String.format(
                "walls %d at height %.3f..%.3f; surface area %.4f, of it above the walls %.4f%n", walls,
                wallHeight[0], wallHeight[1], total, above));
        int tip = -1;
        for (int active = 0; active < fit.mesh.vertexCount(); active++) {
            int vertex = fit.mesh.vertexIdAt(active);
            fit.mesh.vertexPosition(vertex, at);
            float height = at.y;
            tip = tip < 0 || height > fit.mesh.vertexPosition(tip, at).y ? vertex : tip;
        }
        boolean[] reached = new boolean[fit.mesh.vertexIdBound()];
        int[] queue = new int[fit.mesh.vertexCount()];
        int queued = 0;
        queue[queued++] = tip;
        reached[tip] = true;
        double lowest = Double.POSITIVE_INFINITY;
        for (int next = 0; next < queued; next++) {
            lowest = Math.min(lowest, fit.mesh.vertexPosition(queue[next], at).y);
            for (int spoke = 0; spoke < fit.mesh.vertexEdgeCount(queue[next]); spoke++) {
                int other = fit.mesh.edgeOtherVertex(fit.mesh.vertexEdgeAt(queue[next], spoke), queue[next]);
                if (!reached[other] && !fit.closedBottom[other]) {
                    reached[other] = true;
                    queue[queued++] = other;
                }
            }
        }
        double[] turn = new double[walls];
        int turns = 0;
        for (int vertex = 0; vertex < fit.closedBottom.length; vertex++) {
            if (fit.closedBottom[vertex]) {
                fit.mesh.vertexPosition(vertex, at);
                turn[turns++] = Math.toDegrees(Math.atan2(at.z, at.x));
            }
        }
        Arrays.sort(turn);
        double widest = turn[0] + FULL_TURN - turn[walls - 1];
        for (int wall = 1; wall < walls; wall++) {
            widest = Math.max(widest, turn[wall] - turn[wall - 1]);
        }
        out.append(String.format(
                "from the horn's tip, not passing a wall vertex: %d of %d vertices reached, down to height %.3f; "
                        + "widest gap between wall vertices round the horn %.1f degrees%n",
                queued, fit.mesh.vertexCount(), lowest, widest));
        SurfaceCreases hornCreases = (SurfaceCreases) drawn.getNodeOutput(port, port);
        for (int active = 0; active < fit.mesh.vertexCount(); active++) {
            int vertex = fit.mesh.vertexIdAt(active);
            fit.mesh.vertexPosition(vertex, at);
            if (at.y >= wallHeight[0] - 0.03 && at.y <= wallHeight[1] + 0.03) {
                out.append(String.format("   band vertex turn %7.1f height %.3f radius %.3f %s strength %.2f raw %.2f%n",
                        Math.toDegrees(Math.atan2(at.z, at.x)), at.y, Math.hypot(at.x, at.z),
                        fit.closedBottom[vertex] ? "WALL" : "open", hornCreases.valleyStrength[vertex],
                        hornCreases.valleySignal[vertex]));
            }
        }
        for (int pass = -1; pass < TRACED_PASSES; pass++) {
            fit.maxPasses = pass < 0 ? 0 : 1;
            fit.run();
            double[] area = new double[fit.regionCount];
            for (int active = 0; active < fit.mesh.faceCount(); active++) {
                area[fit.regionOfFace[fit.mesh.faceIdAt(active)]] += faceArea[fit.mesh.faceIdAt(active)];
            }
            double[] height = { Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY };
            double[] push = new double[2];
            int held = 0;
            double[][] rows = fit.pushesAlong(0);
            for (double[] row : rows) {
                fit.mesh.vertexPosition((int) row[0], at);
                height[0] = Math.min(height[0], at.y);
                height[1] = Math.max(height[1], at.y);
                push[0] += row[1] / rows.length;
                push[1] += row[2] / rows.length;
                held += (int) row[3];
            }
            out.append(String.format(
                    "%d moves %d region area %.4f %.4f ring height %.3f..%.3f edges %d held %d push %.4e %.4e%n", pass,
                    fit.moves, area[0], area[1], height[0], height[1], rows.length, held, push[0], push[1]));
            if (pass >= 0 && fit.moves == 0) {
                break;
            }
        }
        Files.write(Path.of("tmp/craw-112-fable-" + fixture + (shelterFirst ? "-shelter-first" : "") + ".txt"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theWidthLimitedFlowOnTheHornRingsIsWrittenToTmp() throws IOException {
        StringBuilder out = new StringBuilder();
        for (String fixture : List.of(WIDE_HORN_RING, HORN_RING_ON_HORN)) {
            NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                    RingFitBench.FIXTURE_RESOURCE_DIRECTORY + fixture + ".dsl", Map.of());
            String port = SurfaceCreasesNode.CREASES.name;
            RegionClusterFit fit = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                    (SurfaceCreases) drawn.getNodeOutput(port, port));
            SurfaceLifetime measure = new SurfaceLifetime(fit.mesh);
            double[] faceArea = new double[fit.mesh.faceIdBound()];
            for (int triangle = 0; triangle < measure.doubleArea.length; triangle++) {
                faceArea[measure.faceOfTriangle[triangle]] += measure.doubleArea[triangle] / 2;
            }
            out.append("== ").append(fixture).append('\n');
            for (int region = 0; region < fit.regionCount; region++) {
                double[] open = widthLimitedFlow(fit, faceArea, region, false);
                double[] walled = widthLimitedFlow(fit, faceArea, region, true);
                out.append(String.format(
                        "   region %d area %.4f seam length %.4f: reaches the seam with no walls %.4f (%.0f%%), "
                                + "with the walls %.4f (%.0f%%)%n",
                        region, open[1], open[2], open[0], 100 * open[0] / open[1], walled[0],
                        100 * walled[0] / walled[1]));
            }
        }
        Files.write(Path.of("tmp/craw-112-fable-width-flow.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The most of a region's air that reaches its seams when every face supplies its area and every
     * edge carries at most its length times the region's area over its seam length, less where an
     * end of the edge is a wall vertex.
     */
    private static double[] widthLimitedFlow(RegionClusterFit fit, double[] faceArea, int region, boolean walls) {
        MeshTopology mesh = fit.mesh;
        boolean[] seamEdge = new boolean[mesh.edgeIdBound()];
        boolean[] seamVertex = new boolean[mesh.vertexIdBound()];
        for (int[] loop : fit.loops) {
            for (int edge : loop) {
                seamEdge[edge] = true;
                seamVertex[mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge))] = true;
                seamVertex[mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge))] = true;
            }
        }
        int[] nodeOfFace = new int[mesh.faceIdBound()];
        Arrays.fill(nodeOfFace, -1);
        int nodes = 0;
        double area = 0;
        for (int active = 0; active < mesh.faceCount(); active++) {
            int face = mesh.faceIdAt(active);
            if (fit.regionOfFace[face] == region) {
                nodeOfFace[face] = nodes++;
                area += faceArea[face];
            }
        }
        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        double seamLength = 0;
        double[] length = new double[mesh.edgeIdBound()];
        for (int active = 0; active < mesh.edgeCount(); active++) {
            int edge = mesh.edgeIdAt(active);
            int halfEdge = mesh.edgeHalfEdge(edge);
            length[edge] = mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), from)
                    .distance(mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), to));
            for (int side = 0; seamEdge[edge] && side < 2; side++) {
                int face = mesh.edgeFace(edge, side);
                seamLength += face >= 0 && nodeOfFace[face] >= 0 ? length[edge] : 0;
            }
        }
        double carried = area / seamLength;
        int source = nodes;
        int sink = nodes + 1;
        List<double[]> arcs = new ArrayList<>();
        for (int active = 0; active < mesh.faceCount(); active++) {
            int face = mesh.faceIdAt(active);
            if (nodeOfFace[face] >= 0) {
                arcs.add(new double[] { source, nodeOfFace[face], faceArea[face], 0 });
            }
        }
        for (int active = 0; active < mesh.edgeCount(); active++) {
            int edge = mesh.edgeIdAt(active);
            int halfEdge = mesh.edgeHalfEdge(edge);
            int one = mesh.edgeFace(edge, 0);
            int other = mesh.edgeFace(edge, 1);
            int oneNode = one < 0 ? -1 : nodeOfFace[one];
            int otherNode = other < 0 ? -1 : nodeOfFace[other];
            if (seamEdge[edge]) {
                for (int node : new int[] { oneNode, otherNode }) {
                    if (node >= 0) {
                        arcs.add(new double[] { node, sink, length[edge] * carried, 0 });
                    }
                }
            } else if (oneNode >= 0 && otherNode >= 0) {
                int openEnds = 0;
                for (int vertex : new int[] { mesh.halfEdgeVertex(halfEdge), mesh.halfEdgeEndVertex(halfEdge) }) {
                    openEnds += walls && fit.closedBottom[vertex] && !seamVertex[vertex] ? 0 : 1;
                }
                double capacity = length[edge] * carried * openEnds / 2;
                arcs.add(new double[] { oneNode, otherNode, capacity, capacity });
            }
        }
        int[] head = new int[nodes + 2];
        Arrays.fill(head, -1);
        int[] next = new int[2 * arcs.size()];
        int[] target = new int[2 * arcs.size()];
        double[] room = new double[2 * arcs.size()];
        for (int arc = 0; arc < arcs.size(); arc++) {
            double[] spec = arcs.get(arc);
            for (int way = 0; way < 2; way++) {
                int tail = (int) spec[way];
                target[2 * arc + way] = (int) spec[1 - way];
                room[2 * arc + way] = spec[2 + way];
                next[2 * arc + way] = head[tail];
                head[tail] = 2 * arc + way;
            }
        }
        double flow = 0;
        int[] level = new int[nodes + 2];
        int[] queue = new int[nodes + 2];
        int[] at = new int[nodes + 2];
        int[] pathArc = new int[nodes + 2];
        double tiny = 1e-12 * area;
        while (true) {
            Arrays.fill(level, -1);
            level[source] = 0;
            int queued = 0;
            queue[queued++] = source;
            for (int read = 0; read < queued; read++) {
                for (int arc = head[queue[read]]; arc >= 0; arc = next[arc]) {
                    if (room[arc] > tiny && level[target[arc]] < 0) {
                        level[target[arc]] = level[queue[read]] + 1;
                        queue[queued++] = target[arc];
                    }
                }
            }
            if (level[sink] < 0) {
                return new double[] { flow, area, seamLength };
            }
            System.arraycopy(head, 0, at, 0, head.length);
            int depth = 0;
            int node = source;
            while (true) {
                if (node == sink) {
                    double push = Double.POSITIVE_INFINITY;
                    for (int step = 0; step < depth; step++) {
                        push = Math.min(push, room[pathArc[step]]);
                    }
                    for (int step = 0; step < depth; step++) {
                        room[pathArc[step]] -= push;
                        room[pathArc[step] ^ 1] += push;
                    }
                    flow += push;
                    depth = 0;
                    node = source;
                    continue;
                }
                while (at[node] >= 0 && !(room[at[node]] > tiny && level[target[at[node]]] == level[node] + 1)) {
                    at[node] = next[at[node]];
                }
                if (at[node] >= 0) {
                    pathArc[depth++] = at[node];
                    node = target[at[node]];
                } else if (depth == 0) {
                    break;
                } else {
                    level[node] = -1;
                    depth--;
                    node = target[pathArc[depth] ^ 1];
                }
            }
        }
    }

    @Test
    void theTopologyOfEveryCaseBeforeAndAfterDirectRoutesIsWrittenToTmp() throws IOException {
        StringBuilder out = new StringBuilder();
        for (RingFitBench.Case benchCase : RingFitBench.CASES_IN_RING_ANCHOR_FIT_TEST_ORDER) {
            NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                    RingFitBench.FIXTURE_RESOURCE_DIRECTORY + benchCase.fixture() + ".dsl", Map.of());
            String port = SurfaceCreasesNode.CREASES.name;
            RegionClusterFit fit = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                    (SurfaceCreases) drawn.getNodeOutput(port, port));
            fit.recordPasses = true;
            fit.airFlow = true;
            fit.closedWalls = true;
            fit.bottleneck = true;
            fit.crowdingShares = true;
            fit.run();
            out.append("== ").append(benchCase.fixture()).append('\n');
            for (int pass : new int[] { 0, fit.loopsByPass.size() - 1 }) {
                int[][] snapshot = fit.loopsByPass.get(pass);
                boolean[] seam = new boolean[fit.mesh.edgeIdBound()];
                out.append("  stand ").append(pass);
                for (int ring = 0; ring < snapshot.length - 1; ring++) {
                    int[] degree = new int[fit.mesh.vertexIdBound()];
                    int[] piece = UnionFind.singletons(fit.mesh.vertexIdBound());
                    for (int edge : snapshot[ring]) {
                        seam[edge] = true;
                        int halfEdge = fit.mesh.edgeHalfEdge(edge);
                        degree[fit.mesh.halfEdgeVertex(halfEdge)]++;
                        degree[fit.mesh.halfEdgeEndVertex(halfEdge)]++;
                        UnionFind.union(piece, fit.mesh.halfEdgeVertex(halfEdge), fit.mesh.halfEdgeEndVertex(halfEdge));
                    }
                    int ends = 0;
                    int crossings = 0;
                    int pieces = 0;
                    for (int vertex = 0; vertex < degree.length; vertex++) {
                        ends += degree[vertex] == 1 ? 1 : 0;
                        crossings += degree[vertex] > 2 ? 1 : 0;
                        pieces += degree[vertex] > 0 && UnionFind.find(piece, vertex) == vertex ? 1 : 0;
                    }
                    out.append(" | ring ").append(ring).append(" edges ").append(snapshot[ring].length)
                            .append(" pieces ").append(pieces).append(" ends ").append(ends)
                            .append(" touches itself at ").append(crossings);
                }
                // The parts the seams cut the surface into, and the faces in the smallest.
                int[] part = UnionFind.singletons(fit.mesh.faceIdBound());
                for (int active = 0; active < fit.mesh.edgeCount(); active++) {
                    int edge = fit.mesh.edgeIdAt(active);
                    if (!seam[edge] && fit.mesh.edgeFace(edge, 0) >= 0 && fit.mesh.edgeFace(edge, 1) >= 0) {
                        UnionFind.union(part, fit.mesh.edgeFace(edge, 0), fit.mesh.edgeFace(edge, 1));
                    }
                }
                int[] size = new int[part.length];
                for (int active = 0; active < fit.mesh.faceCount(); active++) {
                    size[UnionFind.find(part, fit.mesh.faceIdAt(active))]++;
                }
                int parts = 0;
                int smallest = Integer.MAX_VALUE;
                for (int faces : size) {
                    parts += faces > 0 ? 1 : 0;
                    smallest = faces > 0 ? Math.min(smallest, faces) : smallest;
                }
                out.append(" | surface parts ").append(parts).append(" smallest ").append(smallest).append(" faces\n");
            }
            Files.write(Path.of("tmp/craw-112-fable-topology.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void theOneWayRingCasesUnderFairSharesAreWrittenToTmp() throws IOException {
        StringBuilder out = new StringBuilder();
        for (RingFitBench.Case benchCase : RingFitBench.CASES_IN_RING_ANCHOR_FIT_TEST_ORDER) {
            if (!benchCase.fixture().equals(HALF_GROOVES_RING)
                    && !benchCase.fixture().equals(TRENCHES_RING)) {
                continue;
            }
            long began = System.nanoTime();
            RingFitBench.Result result = RingFitBench.score(benchCase, drawn -> {
                GeometryBundle bundle = (GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY);
                String port = SurfaceCreasesNode.CREASES.name;
                RegionClusterFit fit = new RegionClusterFit(bundle, (SurfaceCreases) drawn.getNodeOutput(port, port));
                fit.airFlow = true;
                fit.closedWalls = true;
                fit.bottleneck = true;
                fit.directRoutes = true;
                fit.run();
                out.append("passes ").append(fit.passes).append(" moves ").append(fit.moves).append(" refusals ")
                        .append(fit.refusals).append(" flows ").append(WidthLimitedFlow.flowsRun).append('\n');
                GeometryBundle fitted = fit.fitted(bundle,
                        (SurfaceMetric) drawn.getNodeOutput(SURFACE_NODE, SurfaceMetricNode.METRIC.name));
                SurfaceSpline spline = SurfaceSpline.inBundle(fitted, "ring_00");
                Vector3f at = new Vector3f();
                for (int anchor = 0; anchor < spline.anchorCount; anchor++) {
                    fitted.mesh().vertexPosition(spline.anchorVertexId[anchor], at);
                    out.append(String.format("  anchor authored %b tube %7.1f torus %6.2f%n",
                            spline.anchorAuthored[anchor], RingFitBench.roundTubeDegrees(at),
                            RingFitBench.roundTorusDegrees(at)));
                }
                return fitted;
            });
            out.append("passes ").append(result.passes()).append(' ').append(result).append(" seconds ")
                    .append((System.nanoTime() - began) / 1e9).append('\n');
            Files.write(Path.of("tmp/craw-112-fable-fair-cases.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void theCostOfOneFairSharesMeasureIsWrittenToTmp() throws IOException {
        StringBuilder out = new StringBuilder();
        for (String fixture : List.of("one_groove", HALF_GROOVES_RING, WIDE_HORN_RING)) {
            NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                    RingFitBench.FIXTURE_RESOURCE_DIRECTORY + fixture + ".dsl", Map.of());
            String port = SurfaceCreasesNode.CREASES.name;
            RegionClusterFit fit = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                    (SurfaceCreases) drawn.getNodeOutput(port, port));
            fit.recordPasses = true;
            fit.airFlow = true;
            fit.closedWalls = true;
            fit.bottleneck = true;
            fit.run();
            for (boolean fair : new boolean[] { false, true }) {
                fit.directRoutes = fair;
                fit.arrivalShares = fair;
                WidthLimitedFlow.flowsRun = 0;
                WidthLimitedFlow.levelsSettled = 0;
                long began = System.nanoTime();
                fit.standAtPass(0);
                out.append(fixture).append(" fair ").append(fair).append(" seconds ")
                        .append((System.nanoTime() - began) / 1e9).append(" flows ").append(WidthLimitedFlow.flowsRun)
                        .append(" levels ").append(WidthLimitedFlow.levelsSettled).append('\n');
                Vector3f at = new Vector3f();
                for (double[] row : fair ? fit.pushesAlong(0) : new double[0][]) {
                    fit.mesh.vertexPosition((int) row[0], at);
                    out.append(String.format("  tube %7.1f torus %6.2f side0 %.6f side1 %.6f held %.0f shares %.0f%n",
                            RingFitBench.roundTubeDegrees(at), RingFitBench.roundTorusDegrees(at), row[1], row[2],
                            row[3], row[4]));
                }
                if (fair && fixture.equals(HALF_GROOVES_RING)) {
                    RegionClusterFit whole = new RegionClusterFit(
                            (GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                            (SurfaceCreases) drawn.getNodeOutput(port, port));
                    whole.recordPasses = true;
                    whole.airFlow = true;
                    whole.closedWalls = true;
                    whole.bottleneck = true;
                    whole.directRoutes = true;
                    whole.run();
                    whole.standAtPass(whole.loopsByPass.size() - 1);
                    out.append("where the fit stops, after ").append(whole.loopsByPass.size()).append(" stands, ")
                            .append(whole.lastRefusal).append('\n');
                    for (double[] row : whole.pushesAlong(0)) {
                        whole.mesh.vertexPosition((int) row[0], at);
                        out.append(String.format(
                                "  tube %7.1f torus %6.2f side0 %.6f side1 %.6f held %.0f shares %.0f wall %b%n",
                                RingFitBench.roundTubeDegrees(at), RingFitBench.roundTorusDegrees(at), row[1], row[2],
                                row[3], row[4], whole.closedBottom[(int) row[0]]));
                    }
                }
                Files.write(Path.of("tmp/craw-112-fable-fair-cost.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void theWideHornPushesUnderEachRuleAreWrittenToTmp() throws IOException {
        StringBuilder out = new StringBuilder();
        NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + WIDE_HORN_RING + ".dsl", Map.of());
        String port = SurfaceCreasesNode.CREASES.name;
        RegionClusterFit fit = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                (SurfaceCreases) drawn.getNodeOutput(port, port));
        fit.recordPasses = true;
        fit.airFlow = true;
        fit.closedWalls = true;
        fit.run();
        String[] rules = { "pressure only", "stretch shares", "direct shares replace", "direct shares scale",
                "crowding shares", "delay shares" };
        for (int rule = 0; rule < rules.length; rule++) {
            fit.bottleneck = rule > 0;
            fit.directRoutes = rule == 2 || rule == 3;
            fit.sharesScalePressure = rule == 3;
            fit.crowdingShares = rule == 4;
            fit.delayShares = rule == 5;
            fit.standAtPass(0);
            out.append(rules[rule]).append('\n');
            Vector3f at = new Vector3f();
            for (double[] row : fit.pushesAlong(0)) {
                fit.mesh.vertexPosition((int) row[0], at);
                out.append(String.format("  at %6.2f %6.2f %6.2f side0 %.6f side1 %.6f held %.0f shares %.0f wall %b%n",
                        at.x, at.y, at.z, row[1], row[2], row[3], row[4], fit.closedBottom[(int) row[0]]));
            }
        }
        Files.write(Path.of("tmp/craw-112-fable-horn-pushes.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theSlantedRingSharesAsDrawnAreWrittenToTmp() throws IOException {
        StringBuilder out = new StringBuilder();
        for (String fixture : List.of(WIDE_HORN_RING, BENDING_RING_CASES.get(2), TRENCHES_RING, HALF_GROOVES_RING)) {
            NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                    RingFitBench.FIXTURE_RESOURCE_DIRECTORY + fixture + ".dsl", Map.of());
            String port = SurfaceCreasesNode.CREASES.name;
            RegionClusterFit fit = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                    (SurfaceCreases) drawn.getNodeOutput(port, port));
            fit.recordPasses = true;
            fit.airFlow = true;
            fit.closedWalls = true;
            fit.bottleneck = true;
            fit.crowdingShares = true;
            fit.run();
            Vector3f at = new Vector3f();
            int last = fit.loopsByPass.size() - 1;
            for (int pass : new int[] { 0, last / 2, last }) {
                fit.standAtPass(pass);
                out.append("== ").append(fixture).append(" pass ").append(pass).append(" of ").append(last).append(' ')
                        .append(fit.lastRefusal).append('\n');
                for (double[] row : fit.pushesAlong(0)) {
                    fit.mesh.vertexPosition((int) row[0], at);
                    out.append(String.format(
                            "  at %6.2f %6.2f %6.2f tube %7.1f torus %6.2f side0 %.4f side1 %.4f held %.0f shares %.0f wall %b%n",
                            at.x, at.y, at.z, RingFitBench.roundTubeDegrees(at), RingFitBench.roundTorusDegrees(at),
                            row[1], row[2], row[3], row[4], fit.closedBottom[(int) row[0]]));
                }
            }
        }
        Files.write(Path.of("tmp/craw-112-fable-slanted-shares.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theHalfGrooveRingSharesBesideItsPressuresAreWrittenToTmp() throws IOException {
        NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + HALF_GROOVES_RING + ".dsl", Map.of());
        String port = SurfaceCreasesNode.CREASES.name;
        StringBuilder out = new StringBuilder(
                "per seam vertex, the side toward +torus then the side toward -torus; the seam moves into the lower\n");
        // The same drawn seam under the crowding rule and under pressure alone.
        double[][][] rows = new double[2][][];
        RegionClusterFit[] fits = new RegionClusterFit[2];
        for (int rule = 0; rule < rows.length; rule++) {
            fits[rule] = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                    (SurfaceCreases) drawn.getNodeOutput(port, port));
            fits[rule].recordPasses = true;
            fits[rule].airFlow = true;
            fits[rule].closedWalls = true;
            fits[rule].bottleneck = rule == 0;
            fits[rule].crowdingShares = rule == 0;
            fits[rule].begin();
            rows[rule] = fits[rule].pushesAlong(0);
        }
        Vector3f at = new Vector3f();
        Vector3f beside = new Vector3f();
        for (int vertex = 0; vertex < rows[0].length; vertex++) {
            double[] shared = rows[0][vertex];
            double[] pressed = rows[1][vertex];
            fits[0].mesh.vertexPosition((int) shared[0], at);
            int first = RegionClusterFit.PUSH_ROW_BEFORE_DIRECTIONS;
            beside.set((float) shared[first], (float) shared[first + 1], (float) shared[first + 2]).mul(0.05f).add(at);
            // Side 0 lies toward +torus when a step into it raises the angle round the torus.
            int plus = RingFitBench.roundTorusDegrees(beside) > RingFitBench.roundTorusDegrees(at) ? 1 : 2;
            out.append(String.format(
                    "  tube %7.1f torus %5.2f | crowding rule %.4f vs %.4f, %s decide%s | pressure alone %.4f vs %.4f%n",
                    RingFitBench.roundTubeDegrees(at), RingFitBench.roundTorusDegrees(at), shared[plus],
                    shared[3 - plus], shared[4] > 0 ? "shares" : "pressures", shared[3] > 0 ? ", held" : "",
                    pressed[plus], pressed[3 - plus]));
        }
        // Pass by pass: where the shares send the seam into the side whose air presses harder.
        RegionClusterFit stepped = fits[0];
        RegionClusterFit pressure = fits[1];
        out.append("\nper pass: seam vertices, of them moved by shares, of those into the side pressing harder\n");
        for (int pass = 0; pass == 0 || stepped.stepPass(); pass++) {
            pressure.loopsByPass.add(stepped.loopsByPass.get(pass));
            pressure.standAtPass(pressure.loopsByPass.size() - 1);
            double[][] shares = stepped.pushesAlong(0);
            double[][] pressures = pressure.pushesAlong(0);
            int byShares = 0;
            int against = 0;
            StringBuilder examples = new StringBuilder();
            for (int vertex = 0; vertex < shares.length && shares.length == pressures.length; vertex++) {
                double[] shared = shares[vertex];
                double[] pressed = pressures[vertex];
                if (shared[4] <= 0 || shared[3] > 0 || shared[1] == shared[2]) {
                    continue;
                }
                byShares++;
                int into = shared[1] < shared[2] ? 1 : 2;
                if (pressed[into] > pressed[3 - into]) {
                    against++;
                    stepped.mesh.vertexPosition((int) shared[0], at);
                    examples.append(String.format(
                            "    tube %7.1f torus %6.2f: moves into the side with share %.4f against %.4f,"
                                    + " whose pressure is %.5f against %.5f%n",
                            RingFitBench.roundTubeDegrees(at), RingFitBench.roundTorusDegrees(at), shared[into],
                            shared[3 - into], pressed[into], pressed[3 - into]));
                }
            }
            out.append(String.format("  pass %d: %d, %d, %d%n", pass, shares.length, byShares, against));
            out.append(examples);
        }
        Files.write(Path.of("tmp/craw-112-fable-half-grooves-arrows.txt"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theRecordedPassesStartFromTheRingAsDrawn() {
        NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + "one_groove.dsl", Map.of());
        String port = SurfaceCreasesNode.CREASES.name;
        RegionClusterFit fit = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                (SurfaceCreases) drawn.getNodeOutput(port, port));
        fit.recordPasses = true;
        fit.airFlow = true;
        fit.closedWalls = true;
        fit.bottleneck = true;
        fit.run();
        Vector3f at = new Vector3f();
        double[] torus = new double[fit.loopsByPass.size()];
        for (int pass = 0; pass < torus.length; pass++) {
            int edge = fit.loopsByPass.get(pass)[0][0];
            torus[pass] = RingFitBench.roundTorusDegrees(
                    fit.mesh.vertexPosition(fit.mesh.halfEdgeVertex(fit.mesh.edgeHalfEdge(edge)), at));
        }
        assertEquals(90.0, Math.abs(torus[0]), 2.25, "passes " + Arrays.toString(torus));
        assertEquals(0.0, torus[torus.length - 1], 2.25, "passes " + Arrays.toString(torus));
        fit.standAtPass(0);
        int edge = fit.loops[0][0];
        assertEquals(90.0, Math.abs(RingFitBench.roundTorusDegrees(
                fit.mesh.vertexPosition(fit.mesh.halfEdgeVertex(fit.mesh.edgeHalfEdge(edge)), at))), 2.25);
    }

    @Test
    void theCreaseSignalRoundACrossingIsWrittenToTmp() throws IOException {
        NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + GROOVED_RINGS_BISECTOR + ".dsl", Map.of());
        String port = SurfaceCreasesNode.CREASES.name;
        SurfaceCreases creases = (SurfaceCreases) drawn.getNodeOutput(port, port);
        MeshTopology mesh = creases.sourceMesh;
        int[] triangles = mesh.triangulate(TriangulationRule.FAN, null);
        PackedPoints positions = SemanticPatchDecomposer.positionsById(mesh);
        PrincipalDirectionField curvature = PrincipalDirectionField.compute(mesh, positions, triangles,
                TriangleGeometry.normals(positions, triangles),
                TriangleSoup.oneRings(triangles, mesh.vertexIdBound()));
        boolean outward = mesh.signedVolume() >= 0.0;
        Map<Long, Integer> vertexAt = new HashMap<>();
        Vector3f at = new Vector3f();
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            mesh.vertexPosition(vertex, at);
            vertexAt.put(Math.round(RingFitBench.roundTorusDegrees(at) * 4) * 1000L
                    + Math.round(RingFitBench.roundTubeDegrees(at)), vertex);
        }
        StringBuilder out = new StringBuilder();
        List<String> grids = List.of("raw", "smoothed", "radius", "valence");
        for (String what : grids) {
            out.append(what).append(", columns torus 81 to 112.5 by 2.25, rows tube 54 to -54 by 9\n");
            for (int tube = 54; tube >= -54; tube -= 9) {
                for (int column = 0; column < 15; column++) {
                    Integer vertex = vertexAt.get(Math.round((81 + 2.25 * column) * 4) * 1000L + tube);
                    if (vertex == null) {
                        out.append("      ?");
                        continue;
                    }
                    mesh.vertexPosition(vertex, at);
                    int grid = grids.indexOf(what);
                    out.append(String.format(" %6.3f", grid == 0
                            ? (outward ? curvature.kappaMax(vertex) : -curvature.kappaMin(vertex))
                            : grid == 1 ? creases.valleyCurvature[vertex]
                                    : grid == 2 ? Math.hypot(Math.hypot(at.x, at.z) - 2, at.y)
                                            : mesh.vertexEdgeCount(vertex)));
                }
                out.append('\n');
            }
        }
        Files.write(Path.of("tmp/craw-112-fable-crossing.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theBisectorCasesTopologyIsTracedPassByPassToTmp() throws IOException {
        StringBuilder out = new StringBuilder();
        Vector3f at = new Vector3f();
        for (String fixture : List.of(GROOVED_RINGS_BISECTOR, SMOOTH_RINGS_BISECTOR)) {
            NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                    RingFitBench.FIXTURE_RESOURCE_DIRECTORY + fixture + ".dsl", Map.of());
            String port = SurfaceCreasesNode.CREASES.name;
            RegionClusterFit fit = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                    (SurfaceCreases) drawn.getNodeOutput(port, port));
            fit.airFlow = true;
            out.append("== ").append(fixture).append(" regions ").append(fit.regionCount).append('\n');
            SurfaceCreases creases = (SurfaceCreases) drawn.getNodeOutput(port, port);
            for (int ring = 0; ring < fit.loops.length; ring++) {
                float[] strength = { Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY };
                int onBottom = 0;
                for (int edge : fit.loops[ring]) {
                    int vertex = fit.mesh.halfEdgeVertex(fit.mesh.edgeHalfEdge(edge));
                    strength[0] = Math.min(strength[0], creases.valleyStrength[vertex]);
                    strength[1] = Math.max(strength[1], creases.valleyStrength[vertex]);
                    onBottom += fit.grooveBottom[vertex] ? 1 : 0;
                }
                out.append(String.format("   %s as drawn: valley strength %.3f..%.3f, %d of %d vertices on a groove bottom%n",
                        fit.ringLabels[ring], strength[0], strength[1], onBottom, fit.loops[ring].length));
            }
            int[] bottomsByTorusSection = new int[TORUS_SECTIONS];
            for (int vertex = 0; vertex < fit.grooveBottom.length; vertex++) {
                if (fit.grooveBottom[vertex]) {
                    fit.mesh.vertexPosition(vertex, at);
                    bottomsByTorusSection[(int) Math.floor((RingFitBench.roundTorusDegrees(at) + FULL_TURN)
                            / FULL_TURN * TORUS_SECTIONS + HALF) % TORUS_SECTIONS]++;
                }
            }
            out.append("   groove bottoms per torus section, from 0 degrees: ")
                    .append(Arrays.toString(bottomsByTorusSection)).append('\n');
            for (int pass = -1; pass < TRACED_PASSES; pass++) {
                fit.maxPasses = pass < 0 ? 0 : 1;
                fit.run();
                int[] ringsAtVertex = new int[fit.mesh.vertexIdBound()];
                int[][] degree = new int[fit.loops.length][fit.mesh.vertexIdBound()];
                for (int ring = 0; ring < fit.loops.length; ring++) {
                    for (int edge : fit.loops[ring]) {
                        int halfEdge = fit.mesh.edgeHalfEdge(edge);
                        degree[ring][fit.mesh.halfEdgeVertex(halfEdge)]++;
                        degree[ring][fit.mesh.halfEdgeEndVertex(halfEdge)]++;
                    }
                    for (int vertex = 0; vertex < ringsAtVertex.length; vertex++) {
                        ringsAtVertex[vertex] += degree[ring][vertex] > 0 ? 1 : 0;
                    }
                }
                int[] regionFaces = new int[fit.regionCount];
                for (int active = 0; active < fit.mesh.faceCount(); active++) {
                    regionFaces[fit.regionOfFace[fit.mesh.faceIdAt(active)]]++;
                }
                out.append(pass).append(" moves ").append(fit.moves).append(" waste ").append(fit.waste)
                        .append(" regionFaces ").append(Arrays.toString(regionFaces));
                for (int ring = 0; ring < fit.loops.length; ring++) {
                    int shared = 0;
                    int branching = 0;
                    double sine = 0;
                    double cosine = 0;
                    StringBuilder ends = new StringBuilder();
                    for (int vertex = 0; vertex < ringsAtVertex.length; vertex++) {
                        if (degree[ring][vertex] == 0) {
                            continue;
                        }
                        fit.mesh.vertexPosition(vertex, at);
                        sine += Math.sin(Math.toRadians(RingFitBench.roundTorusDegrees(at)));
                        cosine += Math.cos(Math.toRadians(RingFitBench.roundTorusDegrees(at)));
                        shared += ringsAtVertex[vertex] > 1 ? 1 : 0;
                        branching += degree[ring][vertex] > 2 ? 1 : 0;
                        if (degree[ring][vertex] == 1) {
                            StringBuilder on = new StringBuilder();
                            for (int other = 0; other < fit.loops.length; other++) {
                                on.append(other != ring && degree[other][vertex] > 0 ? fit.ringLabels[other] : "");
                            }
                            ends.append(String.format(" end(torus %.1f tube %.1f on %s)",
                                    RingFitBench.roundTorusDegrees(at), RingFitBench.roundTubeDegrees(at), on));
                        }
                    }
                    out.append(String.format(" | %s mean torus %.1f edges %d shared %d branching %d%s",
                            fit.ringLabels[ring], Math.toDegrees(Math.atan2(sine, cosine)), fit.loops[ring].length,
                            shared, branching, ends));
                }
                out.append(" | ").append(fit.lastRefusal).append('\n');
                if (pass >= 0 && fit.moves == 0) {
                    break;
                }
            }
        }
        Files.write(Path.of("tmp/craw-112-fable-bisector.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theHornWallVerticesAndHowFarFromTheGrooveTheyLieAreWrittenToTmp() throws IOException {
        NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                RingFitBench.FIXTURE_RESOURCE_DIRECTORY + HORN_RING_ON_HORN + ".dsl", Map.of());
        String port = SurfaceCreasesNode.CREASES.name;
        SurfaceCreases creases = (SurfaceCreases) drawn.getNodeOutput(port, port);
        RegionClusterFit fit = new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                creases);
        // The groove is the circle where the cone (radius 0.4 at y 0.6, a twentieth of that at y 1.8)
        // leaves the unit sphere.
        double low = 0.9;
        double high = 0.999;
        for (int halving = 0; halving < 60; halving++) {
            double middle = HALF * (low + high);
            if (Math.sqrt(1 - middle * middle) > 0.4 * (1 - 0.95 * (middle - 0.6) / 1.2)) {
                low = middle;
            } else {
                high = middle;
            }
        }
        double grooveHeight = low;
        double grooveRadius = Math.sqrt(1 - low * low);
        double edge = fit.mesh.meanEdgeLength();
        Vector3f at = new Vector3f();
        int[] strongByRows = new int[4];
        int[] closedByRows = new int[4];
        StringBuilder far = new StringBuilder();
        for (int vertex = 0; vertex < fit.closedBottom.length; vertex++) {
            if (!fit.closedBottom[vertex]) {
                continue;
            }
            fit.mesh.vertexPosition(vertex, at);
            double radius = Math.hypot(at.x, at.z);
            double rows = Math.hypot(radius - grooveRadius, at.y - grooveHeight) / edge;
            int bucket = rows < HALF ? 0 : rows < 1.5 ? 1 : rows < 2.5 ? 2 : 3;
            (fit.grooveBottom[vertex] ? strongByRows : closedByRows)[bucket]++;
            if (bucket >= 2) {
                far.append(String.format(
                        "  vertex %d %s on the %s: %.2f edges from the groove, strength %.3f, valley curvature %.4f%n",
                        vertex, fit.grooveBottom[vertex] ? "strong" : "added by closing",
                        Math.abs(at.length() - 1) < 1e-3 ? "sphere" : "cone", rows, creases.valleyStrength[vertex],
                        creases.valleyCurvature[vertex]));
            }
        }
        float deepest = 0;
        for (float curvature : creases.valleyCurvature) {
            deepest = Math.max(deepest, curvature);
        }
        StringBuilder out = new StringBuilder(String.format(
                "groove circle radius %.4f height %.4f; mean edge %.4f; deepest valley curvature %.4f%n",
                grooveRadius, grooveHeight, edge, deepest));
        out.append("walls by distance from the groove in edges: under 0.5, 0.5 to 1.5, 1.5 to 2.5, more\n");
        out.append("  strong (valley line and crease strength 0.5 or more): ").append(Arrays.toString(strongByRows))
                .append('\n');
        out.append("  added by closing (valley line of any strength beside a wall): ")
                .append(Arrays.toString(closedByRows)).append('\n');
        out.append(far);
        Files.write(Path.of("tmp/craw-112-fable-horn-walls.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
