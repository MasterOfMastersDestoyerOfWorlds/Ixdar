package unit.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperWord;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuScrollBar;
import ixdar.gui.ui.menu.MenuScrollBox;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.Platform;
import unit.GlStandIn;

/**
 * A scroll bar, vertical or horizontal: the thumb follows the content, view and scroll; arrows move
 * a step, the track a page, the thumb in proportion.
 */
class MenuScrollBarTest {

    private static final String BOX_ID = "TEST_BOX";

    private static final float BOX_WIDTH = 200f;

    private static final float ROW = Drawing.FONT_HEIGHT_PIXELS;

    private static final int BOX_ROWS = 10;

    private static final int CONTENT_ROWS = 40;

    private static final float HEIGHT = BOX_ROWS * ROW;

    private static final float BAR = MenuScrollBar.WIDTH_PIXELS;

    private static final float TRACK = HEIGHT - 2 * BAR;

    private static final float THUMB = TRACK * BOX_ROWS / CONTENT_ROWS;

    private static final float BAR_X = BOX_WIDTH - BAR / 2;

    private static final float EPSILON = 1e-3f;

    private Platform suitePlatform;

    private GL suiteGl;

    /**
     * Pair the suite's platform with a GL that does nothing, so the drawing font loads.
     */
    @BeforeEach
    void installGlStandIn() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platforms.init(suitePlatform, GlStandIn.create());
    }

    /**
     * Put the suite's GL back.
     */
    @AfterEach
    void restoreSuiteGl() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void thumbLengthIsTheVisibleShareOfTheTrack() {
        MenuScrollBox box = overflowingBox();
        assertTrue(box.scrollBar.isNeeded());
        assertEquals(TRACK, box.scrollBar.trackLength(), EPSILON);
        assertEquals(THUMB, box.scrollBar.thumbLength(), EPSILON);
    }

    @Test
    void thumbRunsFromTheTopArrowToTheBottomArrowAsTheBoxScrolls() {
        MenuScrollBox box = overflowingBox();
        MenuScrollBar bar = box.scrollBar;
        float limit = bar.maximumScrollOffset();
        assertEquals((CONTENT_ROWS - BOX_ROWS) * ROW, limit, EPSILON);

        bar.scrollOffset = 0;
        assertEquals(BAR, bar.thumbStart(), EPSILON);
        bar.scrollOffset = limit / 2;
        assertEquals(BAR + (TRACK - THUMB) / 2, bar.thumbStart(), EPSILON);
        bar.scrollOffset = limit;
        assertEquals(HEIGHT - BAR, bar.thumbStart() + bar.thumbLength(), EPSILON);
    }

    @Test
    void thumbKeepsAGrabbableLengthOverVeryManyRows() {
        MenuScrollBox box = overflowingBox();
        MenuScrollBar bar = box.scrollBar;
        bar.contentLength = 1000 * ROW;
        assertEquals(MenuScrollBar.MINIMUM_THUMB_PIXELS, bar.thumbLength(), EPSILON);
        bar.scrollOffset = bar.maximumScrollOffset();
        assertEquals(HEIGHT - BAR, bar.thumbStart() + bar.thumbLength(), EPSILON);
    }

    @Test
    void noBarAndNoPressTakenWhenTheRowsFit() {
        MenuScrollBox box = overflowingBox();
        box.scrollBar.contentLength = HEIGHT;
        assertFalse(box.scrollBar.isNeeded());
        assertEquals(TRACK, box.scrollBar.thumbLength(), EPSILON, "a bar with nothing to scroll is all thumb");
        assertFalse(box.scrollBar.press(BAR_X, HEIGHT / 2));
    }

    @Test
    void arrowsMoveOneRowAndTheTrackAPage() {
        MenuScrollBox box = overflowingBox();
        MenuScrollBar bar = box.scrollBar;
        assertTrue(bar.press(BAR_X, HEIGHT - BAR / 2));
        assertEquals(0, bar.scrollOffset, EPSILON, "the top arrow stops at the first row");
        assertTrue(bar.press(BAR_X, BAR / 2));
        assertEquals(ROW, bar.scrollOffset, EPSILON);
        assertTrue(bar.press(BAR_X, BAR / 2));
        assertEquals(2 * ROW, bar.scrollOffset, EPSILON);
        assertTrue(bar.press(BAR_X, HEIGHT - BAR / 2));
        assertEquals(ROW, bar.scrollOffset, EPSILON);

        float page = (BOX_ROWS - 1) * ROW;
        float belowThumb = (BAR + HEIGHT - bar.thumbStart() - bar.thumbLength()) / 2;
        assertTrue(bar.press(BAR_X, belowThumb));
        assertEquals(ROW + page, bar.scrollOffset, EPSILON);
        float aboveThumb = (HEIGHT - bar.thumbStart() + HEIGHT - BAR) / 2;
        assertTrue(bar.press(BAR_X, aboveThumb));
        assertEquals(ROW, bar.scrollOffset, EPSILON);
        bar.scrollOffset = bar.maximumScrollOffset();
        assertTrue(bar.press(BAR_X, BAR / 2));
        assertEquals(bar.maximumScrollOffset(), bar.scrollOffset, EPSILON, "the bottom arrow stops at the last row");
    }

    @Test
    void draggingTheThumbThroughTheBoxRegionScrollsInProportion() {
        MenuScrollBox box = overflowingBox();
        MenuScrollBar bar = box.scrollBar;
        float grabY = HEIGHT - bar.thumbStart() - bar.thumbLength() / 2;
        assertTrue(box.claimsPointer());
        box.onPress(BAR_X, grabY);
        assertEquals(0, bar.scrollOffset, EPSILON, "grabbing the thumb does not scroll");
        box.onDrag(BAR_X, grabY - (TRACK - THUMB) / 2);
        assertEquals(bar.maximumScrollOffset() / 2, bar.scrollOffset, EPSILON);
        box.onDrag(BAR_X, -HEIGHT);
        assertEquals(bar.maximumScrollOffset(), bar.scrollOffset, EPSILON);
        box.onDrag(BAR_X, 2 * HEIGHT);
        assertEquals(0, bar.scrollOffset, EPSILON);
        box.onRelease(BAR_X, 2 * HEIGHT, false);
        box.onDrag(BAR_X, grabY - TRACK);
        assertEquals(0, bar.scrollOffset, EPSILON, "a released thumb no longer follows");
    }

    @Test
    void pressesLeftOfTheBarOrOnAHiddenMenuAreNotTaken() {
        MenuScrollBox box = overflowingBox();
        assertFalse(box.scrollBar.press(BOX_WIDTH - BAR - 1, HEIGHT / 2));
        MenuScrollBox hidden = new MenuScrollBox("HIDDEN_BOX", () -> false);
        hidden.bounds.update(0, 0, BOX_WIDTH, HEIGHT);
        hidden.scrollBar.contentLength = CONTENT_ROWS * ROW;
        hidden.scrollBar.visibleLength = HEIGHT;
        assertFalse(hidden.claimsPointer());
    }

    @Test
    void overflowingRowsWrapLeftOfTheBar() {
        Camera2D camera = new Camera2D(1, 1, 1f, 0, 0, null);
        MenuScrollBox box = new MenuScrollBox(BOX_ID, () -> true);
        box.bounds.update(0, 0, BOX_WIDTH, 4 * ROW);
        box.clearRows();
        for (int row = 0; row < 8; row++) {
            box.addRow("model row " + row + " with a name long enough to reach the bar",
                    Color.TEAL_DARK, null);
        }
        box.layout(camera);
        assertTrue(box.scrollBar.isNeeded());
        assertEquals(box.rowsUsed * ROW, box.scrollBar.contentLength, EPSILON);
        for (HyperWord word : box.text.words) {
            if (!word.newLine) {
                float right = word.x + ROW / Drawing.getDrawing().font.fontHeight * word.width;
                assertTrue(right <= BOX_WIDTH - BAR + EPSILON, word + " runs under the bar");
            }
        }
    }

    @Test
    void aHorizontalBarRunsLeftToRightAlongTheBottomEdge() {
        float width = HEIGHT;
        Bounds bounds = new Bounds(0, 0, width, BOX_WIDTH, BOX_ID);
        MenuScrollBar bar = new MenuScrollBar(bounds, true, ROW);
        bar.contentLength = CONTENT_ROWS * ROW;
        bar.visibleLength = width;
        float barY = BAR / 2;
        assertTrue(bar.contains(width / 2, barY));
        assertFalse(bar.contains(width / 2, BAR + 1), "above the bottom strip is not the bar");
        assertEquals(BAR, bar.thumbStart(), EPSILON);
        assertEquals(THUMB, bar.thumbLength(), EPSILON);

        assertTrue(bar.press(width - BAR / 2, barY));
        assertEquals(ROW, bar.scrollOffset, EPSILON, "the right arrow scrolls right");
        assertTrue(bar.press(BAR / 2, barY));
        assertEquals(0, bar.scrollOffset, EPSILON, "the left arrow scrolls back");

        float grabX = bar.thumbStart() + bar.thumbLength() / 2;
        assertTrue(bar.press(grabX, barY));
        bar.dragTo(grabX + (TRACK - THUMB) / 2, barY);
        assertEquals(bar.maximumScrollOffset() / 2, bar.scrollOffset, EPSILON);
        assertEquals(BAR + (TRACK - THUMB) / 2, bar.thumbStart(), EPSILON);
    }

    private static MenuScrollBox overflowingBox() {
        MenuScrollBox box = new MenuScrollBox(BOX_ID, () -> true);
        box.bounds.update(0, 0, BOX_WIDTH, HEIGHT);
        box.rowsUsed = CONTENT_ROWS;
        box.scrollBar.contentLength = CONTENT_ROWS * ROW;
        box.scrollBar.visibleLength = HEIGHT;
        return box;
    }
}
