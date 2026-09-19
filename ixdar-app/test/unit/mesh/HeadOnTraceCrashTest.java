package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;

/**
 * Two hand-authored traversals of one iso-line, by the numbers the tracer sees:
 * position on the line, travelled length there, direction. A motorcycle stops
 * where it first reaches a point another reached no later, so the two stop on one
 * point and neither lays an arc over the other's.
 */
class HeadOnTraceCrashTest {

    /** Where the traversal under test stands on the shared line. */
    private static final double OUR_START = 0.0;

    /** Where the other traversal stands, ahead of it. */
    private static final double THEIR_START = 4.0;

    /** Travelled length of the traversal under test at {@link #OUR_START}. */
    private static final double OUR_LENGTH = 10.0;

    /** Travelled length of the other traversal at {@link #THEIR_START}. */
    private static final double THEIR_LENGTH = 12.0;

    /** A travelled length far beyond both, for the arrived-long-ago cases. */
    private static final double LATE_LENGTH = 20.0;

    /** A travelled length far below both, for the arrived-first cases. */
    private static final double EARLY_LENGTH = 2.0;

    @Test
    void facingTraversalsStopWhereTheirTravelledLengthsAgree() {
        double ours = Trace.collinearCrashDistance(OUR_START, OUR_LENGTH, 1,
                THEIR_START, THEIR_LENGTH, -1);
        double theirs = Trace.collinearCrashDistance(THEIR_START, THEIR_LENGTH, -1,
                OUR_START, OUR_LENGTH, 1);

        assertEquals(3.0, ours, "we stop 3 along, where both have travelled 13");
        assertEquals(OUR_START + ours, THEIR_START - theirs,
                "both stop on the same point of the line");
        assertEquals(OUR_LENGTH + ours, THEIR_LENGTH + theirs,
                "and reach it having travelled the same distance");
    }

    @Test
    void aTrailAlreadyLaidOverOurPositionStopsUsWhereWeStand() {
        assertEquals(0.0, Trace.collinearCrashDistance(OUR_START, LATE_LENGTH, 1,
                THEIR_START, EARLY_LENGTH, -1));
    }

    @Test
    void theTraversalThatGetsThereFirstDrivesOn() {
        assertEquals(Double.POSITIVE_INFINITY,
                Trace.collinearCrashDistance(OUR_START, EARLY_LENGTH, 1,
                        THEIR_START, LATE_LENGTH, -1));
    }

    @Test
    void aFollowerStopsAtTheLeadersPosition() {
        assertEquals(THEIR_START - OUR_START,
                Trace.collinearCrashDistance(OUR_START, OUR_LENGTH, 1,
                        THEIR_START, EARLY_LENGTH, 1));
    }

    @Test
    void aLeaderIsNotStoppedByTheFollowerBehindIt() {
        assertEquals(Double.POSITIVE_INFINITY,
                Trace.collinearCrashDistance(OUR_START, EARLY_LENGTH, 1,
                        THEIR_START, LATE_LENGTH, 1));
        assertEquals(Double.POSITIVE_INFINITY,
                Trace.collinearCrashDistance(THEIR_START, OUR_LENGTH, 1,
                        OUR_START, EARLY_LENGTH, 1));
    }

    @Test
    void aTraversalDrivingAwayBehindUsIsNoCrash() {
        assertEquals(Double.POSITIVE_INFINITY,
                Trace.collinearCrashDistance(THEIR_START, OUR_LENGTH, 1,
                        OUR_START, EARLY_LENGTH, -1));
    }
}
