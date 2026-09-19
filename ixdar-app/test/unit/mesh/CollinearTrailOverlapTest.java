package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.motorcycle.records.FaceSegmentIndex;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceAxis;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;

/**
 * Two hand-authored chords of one face on the same iso-line, run in opposite
 * directions: the separatrix between two cones, traced from both ends. The face
 * index has to report that as a contact, or the curve is laid twice.
 */
class CollinearTrailOverlapTest {

    /** The only face of the fixture. */
    private static final int FACE = 0;

    /** Faces the fixture's index is sized for. */
    private static final int FACE_COUNT = 1;

    /** The iso-line both traversals hold; they move along u, so v is constant. */
    private static final double LEVEL = 3.0;

    /** A second iso-line, a quarter of a quad away from {@link #LEVEL}. */
    private static final double NEARBY_LEVEL = 3.25;

    /** Where the first traversal's chord enters the face. */
    private static final double LAID_ENTRY_U = 1.0;

    /** Where it leaves. */
    private static final double LAID_EXIT_U = 5.0;

    /** Where the facing traversal enters, beyond the laid chord's exit. */
    private static final double FACING_ENTRY_U = 6.0;

    /** Where it leaves, inside the laid chord's span. */
    private static final double FACING_EXIT_U = 2.0;

    /** Parametric length both traversals carry at their chord entry. */
    private static final double LENGTH_AT_ENTRY = 1.0;

    @Test
    void afacingTraversalOfOneIsoLineIsReportedAsAContact() {
        FaceSegmentIndex index = new FaceSegmentIndex(FACE_COUNT);
        TraceSegment laid = chord(0, LAID_ENTRY_U, LAID_EXIT_U, LEVEL, 1);
        index.add(laid);

        List<FaceSegmentIndex.IntersectionHit> hits =
                index.contactsOf(chord(1, FACING_ENTRY_U, FACING_EXIT_U, LEVEL, -1));

        assertEquals(1, hits.size());
        assertSame(laid, hits.get(0).otherSegment);
        assertEquals(laid.axis, hits.get(0).otherSegment.axis);
        assertEquals(LAID_EXIT_U, hits.get(0).intersectionU);
        assertEquals(LEVEL, hits.get(0).intersectionV);
        assertEquals(FACING_ENTRY_U - LAID_EXIT_U, hits.get(0).tAlongCandidate);
    }

    @Test
    void aTraversalOfTheNextIsoLineOverIsNoContact() {
        FaceSegmentIndex index = new FaceSegmentIndex(FACE_COUNT);
        index.add(chord(0, LAID_ENTRY_U, LAID_EXIT_U, LEVEL, 1));

        assertTrue(index.contactsOf(
                chord(1, FACING_ENTRY_U, FACING_EXIT_U, NEARBY_LEVEL, -1)).isEmpty());
    }

    /**
     * One chord of the fixture's face, running along u at a fixed v.
     *
     * @param traceId trace the chord belongs to
     * @param entryU  u the chord enters the face at
     * @param exitU   u it leaves at
     * @param level   v it holds
     * @param sign    direction along u
     * @return the chord
     */
    private static TraceSegment chord(int traceId, double entryU, double exitU, double level,
            int sign) {
        return new TraceSegment(traceId, FACE, 0, entryU, level, exitU, level,
                TraceAxis.U, sign, LENGTH_AT_ENTRY);
    }
}
