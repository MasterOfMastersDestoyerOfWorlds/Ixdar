package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.selection.RingDslWriter;
import ixdar.parsing.python.PythonParser;

/**
 * The ring writer as pure text: what id a save mints, what it refuses, and what a rewrite leaves
 * alone. No mesh is built and no graph is executed.
 */
class RingDslWriterTest {

    /** One-statement graph a ring can chain onto. */
    private static final String CARRIER = "carrier = load_mesh(path=\"x.off\")\n";

    /** Graph whose only ring source is the automatic proposal, whose id is not a ring number. */
    private static final String CANDIDATES = CARRIER
            + "rings = ring_candidates(geometry=carrier.geometry, resolution=128)\n";

    /** Id the writer mints for the first ring of a graph that holds no ring number. */
    private static final String FIRST_RING = "ring_00";

    /** Id the writer mints once ten rings, numbered from zero, are already in the file. */
    private static final String ELEVENTH_RING = "ring_10";

    /** Label an author binds by hand, which a downstream {@code mark_edges} reads. */
    private static final String AUTHORED_LABEL = "label=\"left_thigh\"";

    /** The single point a hand-authored statement starts with, before a rewrite moves it. */
    private static final String ORIGINAL_POINTS = "0.000000,0.000000,0.000000";

    /** Rings written into the ten-ring fixture. */
    private static final int TEN_RINGS = 10;

    /** Four anchors of a square, packed xyz. */
    private static final float[] SQUARE = { 0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f };

    /** Five anchors: the square with a midpoint, so a rewrite is visible in the text. */
    private static final float[] MOVED_SQUARE =
            { 0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0f };

    /** Anchors in {@link #SQUARE}. */
    private static final int SQUARE_ANCHORS = 4;

    /** Anchors in {@link #MOVED_SQUARE}. */
    private static final int MOVED_ANCHORS = 5;

    /** Trailing blank lines a save has to survive, enough to outweigh one written statement. */
    private static final String TRAILING_BLANK_LINES = "\n".repeat(400);

    @Test
    void theMintedIdIsOnePastTheHighestNumberInTheFileAndIgnoresTheCandidateStatement() {
        assertEquals(FIRST_RING, RingDslWriter.nextRingId(CANDIDATES),
                "`rings = ring_candidates(...)` is not a ring number and must not be counted");

        StringBuilder ten = new StringBuilder(CANDIDATES);
        for (int ring = 0; ring < TEN_RINGS; ring++) {
            ten.append(String.format("ring_%02d = spline_ring(geometry=rings.geometry, "
                    + "points=\"0.000000,0.000000,0.000000\", label=\"ring_%02d\")%n", ring, ring));
        }
        String full = ten.toString();
        assertEquals(ELEVENTH_RING, RingDslWriter.nextRingId(full));

        String gapped = full.replaceAll("(?m)^ring_05 .*\\R", "");
        assertFalse(gapped.contains("ring_05 = "), "the fixture kept the deleted statement");
        assertEquals(ELEVENTH_RING, RingDslWriter.nextRingId(gapped),
                "a deleted statement must not let the next save reuse a live id");
    }

    @Test
    void theMintedIdContinuesPastLegacyRingNIdsAndLabels() {
        String legacy = CARRIER
                + "ring1 = spline_ring(geometry=carrier.geometry, points=\"0.000000,0.000000,"
                + "0.000000\", label=\"ring1\")\n"
                + "ring10 = spline_ring(geometry=ring1.geometry, points=\"0.000000,0.000000,"
                + "0.000000\", label=\"ring10\")\n";

        assertEquals("ring_11", RingDslWriter.nextRingId(legacy));
    }

    @Test
    void aGraphThatBindsAnIdTwiceIsRefusedInsteadOfWritten() {
        String shadowed = CARRIER
                + FIRST_RING + " = spline_ring(geometry=carrier.geometry, points=\"0.000000,"
                + "0.000000,0.000000\")\n"
                + FIRST_RING + " = spline_ring(geometry=carrier.geometry, points=\"1.000000,"
                + "0.000000,0.000000\")\n";

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> RingDslWriter.appendSpline(shadowed, SQUARE, SQUARE_ANCHORS));

        assertTrue(refusal.getMessage().contains(FIRST_RING),
                "the refusal does not name the shadowed id: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("nothing was written"),
                "the refusal does not say the file was left alone: " + refusal.getMessage());
        assertThrows(IllegalArgumentException.class, () -> RingDslWriter.nextRingId(shadowed),
                "every path that mints an id must refuse the same graph");
    }

    @Test
    void theWrittenStatementParsesBackUnderTheMintedRingId() {
        String appended = RingDslWriter.appendSpline(CANDIDATES, SQUARE, SQUARE_ANCHORS);

        List<PythonParser.ParsedNode> statements =
                NodeGraphRuntime.fromSource(appended).statements;
        assertEquals(FIRST_RING, statements.get(statements.size() - 1).id,
                "the DSL parser does not accept a ring_NN identifier");
        assertTrue(appended.contains("label=\"" + FIRST_RING + "\""), appended);
    }

    @Test
    void oneSaveOfSeveralRingsMintsAnIdEachAndRewritesTheOneAlreadyWritten() {
        String source = RingDslWriter.appendSpline(CANDIDATES, SQUARE, SQUARE_ANCHORS);
        String secondId = RingDslWriter.nextRingId(source);
        source = RingDslWriter.appendSpline(source, SQUARE, SQUARE_ANCHORS);
        assertEquals("ring_01", secondId,
                "a second ring saved in the same pass must not reuse the first ring's id");

        source = RingDslWriter.replaceSpline(source, FIRST_RING, MOVED_SQUARE, MOVED_ANCHORS);
        int carrierStatements = NodeGraphRuntime.fromSource(CANDIDATES).statements.size();
        List<PythonParser.ParsedNode> statements =
                NodeGraphRuntime.fromSource(source).statements;
        assertEquals(2, statements.size() - carrierStatements,
                "the save wrote a statement per ring and no more");
        assertTrue(source.contains(SurfaceWaypoints.format(MOVED_SQUARE, MOVED_ANCHORS)),
                "the ring edited since its last save was not rewritten in place: " + source);
    }

    @Test
    void aRewriteChangesThePointsAndNothingElse() {
        String authored = CARRIER
                + FIRST_RING + " = spline_ring(geometry=carrier.geometry, points=\""
                + ORIGINAL_POINTS + "\", " + AUTHORED_LABEL
                + ", tighten=false)  # the cut the marks read\n"
                + "marked = mark_edges(geometry=" + FIRST_RING + ".geometry, left_thigh=true)\n";

        String rewritten = RingDslWriter.replaceSpline(authored, FIRST_RING, MOVED_SQUARE,
                MOVED_ANCHORS);

        assertTrue(rewritten.contains(AUTHORED_LABEL),
                "the rewrite renamed the label the downstream node reads: " + rewritten);
        assertTrue(rewritten.contains("tighten=false"),
                "the rewrite dropped an argument: " + rewritten);
        assertTrue(rewritten.contains("# the cut the marks read"),
                "the rewrite dropped the trailing comment: " + rewritten);
        assertEquals(
                authored.replace(ORIGINAL_POINTS,
                        SurfaceWaypoints.format(MOVED_SQUARE, MOVED_ANCHORS)),
                rewritten, "the rewrite changed something other than the points argument");
    }

    @Test
    void aRewriteWithNoStatementOfThatIdIsRefused() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> RingDslWriter.replaceSpline(CARRIER, FIRST_RING, SQUARE, SQUARE_ANCHORS));

        assertTrue(refusal.getMessage().contains(FIRST_RING), refusal.getMessage());
    }

    @Test
    void appendingToAFileOfTrailingBlankLinesReportsTheStatementItWrote() {
        String padded = CARRIER + TRAILING_BLANK_LINES;

        String updated = RingDslWriter.append(padded, SQUARE, SQUARE_ANCHORS, true, false);

        assertTrue(updated.length() < padded.length(),
                "the fixture no longer shrinks the source, so it no longer covers the bug");
        String statement = RingDslWriter.appendedStatement(padded, updated);
        assertTrue(statement.startsWith(FIRST_RING + " = loop_through_points(geometry=carrier."),
                "the echoed statement is not the one that was appended: " + statement);
        assertTrue(statement.endsWith(")"), "the echoed statement is truncated: " + statement);
        assertEquals(2, updated.lines().count(),
                "the appended source should be the carrier and the ring: " + updated);
    }

    @Test
    void anAtomicWriteReplacesTheFileAndLeavesNoTemporaryBehind(@TempDir Path directory)
            throws IOException {
        Path graph = directory.resolve("working.dsl");
        Files.write(graph, CARRIER.getBytes(StandardCharsets.UTF_8));

        String updated = RingDslWriter.append(CARRIER, SQUARE, SQUARE_ANCHORS, true, false);
        RingDslWriter.writeAtomically(graph, updated);

        assertEquals(updated, new String(Files.readAllBytes(graph), StandardCharsets.UTF_8));
        try (Stream<Path> beside = Files.list(directory)) {
            assertEquals(List.of(graph), beside.toList(),
                    "the write left a temporary file next to the graph");
        }
    }
}
