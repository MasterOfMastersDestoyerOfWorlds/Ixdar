package unit.mesh.ringbench;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * Scores any ring fit on the {@code ring_bench} fixtures: grooved tori and a horned sphere, rings
 * drawn off the grooves.
 *
 * <p>The expected places are CRAW-106's RingAnchorFitTest's: degrees round the torus from +x or
 * round the tube from its outside, or for the horn, mesh units from its foot circle.
 */
public final class RingFitBench {

    /** Takes a fixture's graph, run with the rings as drawn, and returns the fitted geometry. */
    @FunctionalInterface
    public interface RingFit {

        /**
         * Fit the drawn rings.
         *
         * @param drawn the fixture's graph, its geometry the rings as drawn
         * @return the geometry with the fitted rings, under the same labels
         */
        GeometryBundle fit(NodeGraphRuntime drawn);
    }

    public static final RingFit NO_FIT_RINGS_AS_DRAWN =drawn -> (GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY);

    /**
     * One place an anchor may end in.
     *
     * @param name   what the place is, for reports
     * @param off    how far an anchor at a position is from the place, NaN where the place sets no
     *               bound on it
     * @param within how far off still counts as in the place
     */
    public record Place(String name, ToDoubleFunction<Vector3f> off, double within) {
    }

    /**
     * Where one ring's anchors should end.
     *
     * @param ring      the ring's label
     * @param places    every anchor must end in one of these
     * @param mustVisit each of these must hold at least one anchor
     */
    public record RingExpectation(String ring, List<Place> places, List<Place> mustVisit) {

        /**
         * How far this ring's authored anchors ended from its places in fitted geometry.
         *
         * @param fitted the fitted geometry
         * @return the ring's score
         * @throws IllegalStateException when the geometry carries no ring of this label
         */
        public Score score(GeometryBundle fitted) {
            SurfaceSpline spline = SurfaceSpline.inBundle(fitted, ring);
            if (spline == null) {
                throw new IllegalStateException("the fitted geometry carries no " + ring);
            }
            Vector3f at = new Vector3f();
            double worst = 0;
            double worstWithin = places.get(0).within();
            boolean[] visited = new boolean[mustVisit.size()];
            for (int anchor = 0; anchor < spline.anchorCount; anchor++) {
                if (!spline.anchorAuthored[anchor]) {
                    continue;
                }
                fitted.mesh().vertexPosition(spline.anchorVertexId[anchor], at);
                double nearestOff = Double.POSITIVE_INFINITY;
                double nearestWithin = worstWithin;
                for (Place place : places) {
                    double off = place.off().applyAsDouble(at);
                    off = Double.isNaN(off) ? 0 : off;
                    if (off / place.within() < nearestOff / nearestWithin) {
                        nearestOff = off;
                        nearestWithin = place.within();
                    }
                }
                if (nearestOff / nearestWithin > worst / worstWithin) {
                    worst = nearestOff;
                    worstWithin = nearestWithin;
                }
                for (int place = 0; place < visited.length; place++) {
                    Place visit = mustVisit.get(place);
                    visited[place] |= visit.off().applyAsDouble(at) <= visit.within() + TOLERANCE_SLACK;
                }
            }
            List<String> missing = new ArrayList<>();
            for (int place = 0; place < visited.length; place++) {
                if (!visited[place]) {
                    missing.add(mustVisit.get(place).name());
                }
            }
            return new Score(ring, worst, worstWithin, missing);
        }
    }

    /**
     * One fixture and what a fit should do to it.
     *
     * @param fixture      the fixture's name under {@link #FIXTURE_RESOURCE_DIRECTORY}
     * @param alternatives ways the rings may end, any one of which passes, such as two rings
     *                     taking two grooves in either order
     */
    public record Case(String fixture, List<List<RingExpectation>> alternatives) {
    }

    /**
     * How far one ring ended from where it should.
     *
     * @param ring    the ring's label
     * @param worst   the worst anchor's distance from the nearest place it may end in
     * @param within  that place's tolerance
     * @param missing the places in {@link RingExpectation#mustVisit} no anchor reached
     */
    public record Score(String ring, double worst, double within, List<String> missing) {

        /**
         * Whether every anchor ended in a place and every must-visit place was reached.
         *
         * @return true when the ring passes
         */
        public boolean passes() {
            return worst <= within + TOLERANCE_SLACK && missing.isEmpty();
        }
    }

    /**
     * One case's result: the scores of the alternative that came nearest.
     *
     * @param fixture the fixture
     * @param scores  one per ring
     */
    public record Result(String fixture, List<Score> scores) {

        /**
         * Whether every ring passed.
         *
         * @return true when the case passes
         */
        public boolean passes() {
            return scores.stream().allMatch(Score::passes);
        }
    }

    public static final String FIXTURE_RESOURCE_DIRECTORY ="dsl/fixtures/ring_bench/";

    private static final double TOLERANCE_SLACK = 1e-3;

    private static final String FIRST_RING = "ring_00";

    private static final String SECOND_RING = "ring_01";

    private static final String BISECTOR = "ring_02";

    private static final double MAJOR_RADIUS = 2.0;

    private static final double HALF_TURN = 180.0;

    private static final double FIRST_GROOVE = 0.0;

    private static final double QUARTER_GROOVE = 90.0;

    private static final double SIXTH_GROOVE = 60.0;

    private static final double SLANTED_GROOVE = 15.0;

    private static final double NEAR_BRANCH_LEAN = 0.20;

    private static final double BRANCH_FULL_DEPTH_INWARD = 0.75;

    private static final double SECTION = 2.25;

    private static final double SIDE = 9.0;

    private static final double OPPOSITE = 4 * SECTION;

    private static final double HORN_FOOT_HEIGHT = 0.96;

    private static final double HORN_FOOT_RADIUS = 0.285;

    private static final double HORN_FOOT_WITHIN = 0.08;

    public static final List<Case> CASES_IN_RING_ANCHOR_FIT_TEST_ORDER =List.of(
            single("one_groove", ring(FIRST_RING, roundTorus(FIRST_GROOVE, SECTION))),
            single("one_groove_crease", ring(FIRST_RING, roundTorus(FIRST_GROOVE, SECTION))),
            single("one_groove_far", ring(FIRST_RING, roundTorus(FIRST_GROOVE, SECTION))),
            single("two_grooves_nearer_second", ring(FIRST_RING, roundTorus(HALF_TURN, SECTION))),
            single("two_grooves_nearer_first", ring(FIRST_RING, roundTorus(FIRST_GROOVE, SECTION))),
            single("two_grooves_crease", ring(FIRST_RING, roundTorus(FIRST_GROOVE, SECTION))),
            single("two_grooves_two_rings_near_one", ring(FIRST_RING, roundTorus(FIRST_GROOVE, SECTION)),
                    ring(SECOND_RING, roundTorus(HALF_TURN, SECTION))),
            single("two_grooves_two_rings_each_own", ring(FIRST_RING, roundTorus(FIRST_GROOVE, SECTION)),
                    ring(SECOND_RING, roundTorus(HALF_TURN, SECTION))),
            single("one_groove_two_rings", ring(FIRST_RING, roundTorus(FIRST_GROOVE, SECTION)),
                    ring(SECOND_RING, roundTorus(HALF_TURN, OPPOSITE))),
            single("bisector_grooved_rings", ring(BISECTOR, roundTube(0.0, SIDE)),
                    ring(FIRST_RING, roundTorus(QUARTER_GROOVE, SECTION)),
                    ring(SECOND_RING, roundTorus(HALF_TURN, SECTION))),
            single("bisector_smooth_rings", ring(BISECTOR, roundTube(0.0, SIDE))),
            new Case("two_grooves_one_ring_nearest_both", List.of(
                    List.of(ring(FIRST_RING, roundTorus(SIXTH_GROOVE, SECTION)),
                            ring(SECOND_RING, roundTorus(FIRST_GROOVE, SECTION))),
                    List.of(ring(FIRST_RING, roundTorus(FIRST_GROOVE, SECTION)),
                            ring(SECOND_RING, roundTorus(SIXTH_GROOVE, SECTION))))),
            single("slanted_ring", ring(FIRST_RING, roundTorus(SLANTED_GROOVE, SECTION),
                    roundTorus(-SLANTED_GROOVE, SECTION))),
            single("slanted_ring_trenches", new RingExpectation(FIRST_RING,
                    List.of(roundTorus(SLANTED_GROOVE, SECTION), roundTorus(-SLANTED_GROOVE, SECTION), trench()),
                    List.of(roundTorus(SLANTED_GROOVE, SECTION), roundTorus(-SLANTED_GROOVE, SECTION)))),
            single("tree_partly_in_groove", ring(FIRST_RING, nearerBranch())),
            single("horn_ring_on_horn", ring(FIRST_RING, hornFoot())),
            single("horn_ring_wide", ring(FIRST_RING, hornFoot())));

    private RingFitBench() {
    }

    /**
     * Run every case through a fit.
     *
     * @param fit the fit
     * @return one result per case, in {@link #CASES_IN_RING_ANCHOR_FIT_TEST_ORDER} order
     */
    public static List<Result> run(RingFit fit) {
        List<Result> results = new ArrayList<>();
        for (Case benchCase : CASES_IN_RING_ANCHOR_FIT_TEST_ORDER) {
            results.add(score(benchCase, fit));
        }
        return results;
    }

    /**
     * Run one case through a fit, keeping the alternative that passes or, failing that, the one
     * whose worst ring is nearest its tolerance.
     *
     * @param benchCase the case
     * @param fit       the fit
     * @return its result
     */
    public static Result score(Case benchCase, RingFit fit) {
        NodeGraphRuntime drawn = NodeGraphRuntime.executeResource(
                FIXTURE_RESOURCE_DIRECTORY + benchCase.fixture() + ".dsl", Map.of());
        GeometryBundle fitted = fit.fit(drawn);
        Result best = null;
        double bestRatio = Double.POSITIVE_INFINITY;
        for (List<RingExpectation> alternative : benchCase.alternatives()) {
            List<Score> scores = new ArrayList<>();
            double ratio = 0;
            for (RingExpectation expectation : alternative) {
                Score ringScore = expectation.score(fitted);
                scores.add(ringScore);
                ratio = Math.max(ratio, ringScore.worst() / ringScore.within() + ringScore.missing().size());
            }
            if (ratio < bestRatio) {
                bestRatio = ratio;
                best = new Result(benchCase.fixture(), scores);
            }
        }
        return best;
    }

    private static Case single(String fixture, RingExpectation... rings) {
        return new Case(fixture, List.of(List.of(rings)));
    }

    private static RingExpectation ring(String label, Place... places) {
        return new RingExpectation(label, List.of(places), List.of());
    }

    /**
     * Degrees round the torus from +x.
     *
     * @param at a position
     * @return its angle round the torus
     */
    public static double roundTorusDegrees(Vector3f at) {
        return Math.toDegrees(Math.atan2(at.z, at.x));
    }

    /**
     * Degrees round the tube from its outside.
     *
     * @param at a position
     * @return its angle round the tube
     */
    public static double roundTubeDegrees(Vector3f at) {
        return Math.toDegrees(Math.atan2(at.y, Math.hypot(at.x, at.z) - MAJOR_RADIUS));
    }

    /**
     * The angle between two directions, in degrees, 0 to 180.
     *
     * @param degrees one angle
     * @param other   the other
     * @return how far apart they are
     */
    public static double apart(double degrees, double other) {
        return Math.abs(((degrees - other) % (2 * HALF_TURN) + 3 * HALF_TURN) % (2 * HALF_TURN) - HALF_TURN);
    }

    private static Place roundTorus(double degrees, double within) {
        return new Place(degrees + " degrees round the torus", at -> apart(roundTorusDegrees(at), degrees), within);
    }

    private static Place roundTube(double degrees, double within) {
        return new Place(degrees + " degrees round the tube", at -> apart(roundTubeDegrees(at), degrees), within);
    }

    private static Place trench() {
        return new Place("a trench", at -> {
            double between = Math.max(0, Math.abs(roundTorusDegrees(at)) - SLANTED_GROOVE - SECTION);
            double tube = roundTubeDegrees(at);
            double across = Math.max(0, Math.min(apart(tube, 0), apart(tube, HALF_TURN)) - SIDE);
            return between > 0 ? SECTION + between : across > 0 ? SECTION + across : 0;
        }, SECTION);
    }

    private static Place nearerBranch() {
        return new Place("the trunk or the nearer branch", at -> {
            double inward = Math.max(0, -Math.cos(Math.toRadians(roundTubeDegrees(at))));
            if (inward > BRANCH_FULL_DEPTH_INWARD) {
                return Double.NaN;
            }
            return apart(roundTorusDegrees(at), Math.toDegrees(Math.asin(NEAR_BRANCH_LEAN * inward)));
        }, SECTION);
    }

    private static Place hornFoot() {
        return new Place("the horn's foot", at -> Math.hypot(at.y - HORN_FOOT_HEIGHT,
                Math.hypot(at.x, at.z) - HORN_FOOT_RADIUS), HORN_FOOT_WITHIN);
    }
}
