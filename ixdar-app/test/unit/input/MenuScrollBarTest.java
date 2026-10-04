package unit.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperWord;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuScrollBar;
import ixdar.gui.ui.menu.MenuScrollBox;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.Platform;

/**
 * A menu box's scroll bar: the thumb's length and place follow the rows, the box height and the
 * scroll; the arrows move a row, the track a page and the thumb in proportion; and there is no
 * bar, and no press taken, when the rows fit.
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
        Platforms.init(suitePlatform, FontGlStandIn.create());
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
        float limit = box.maximumScrollOffsetY();
        assertEquals((CONTENT_ROWS - BOX_ROWS) * ROW, limit, EPSILON);

        box.scrollOffsetY = 0;
        assertEquals(HEIGHT - BAR, box.scrollBar.thumbTop(), EPSILON);
        box.scrollOffsetY = limit / 2;
        assertEquals(HEIGHT - BAR - (TRACK - THUMB) / 2, box.scrollBar.thumbTop(), EPSILON);
        box.scrollOffsetY = limit;
        assertEquals(BAR, box.scrollBar.thumbTop() - box.scrollBar.thumbLength(), EPSILON);
    }

    @Test
    void thumbKeepsAGrabbableLengthOverVeryManyRows() {
        MenuScrollBox box = overflowingBox();
        box.rowsUsed = 1000;
        assertEquals(MenuScrollBar.MINIMUM_THUMB_PIXELS, box.scrollBar.thumbLength(), EPSILON);
        box.scrollOffsetY = box.maximumScrollOffsetY();
        assertEquals(BAR, box.scrollBar.thumbTop() - box.scrollBar.thumbLength(), EPSILON);
    }

    @Test
    void noBarAndNoPressTakenWhenTheRowsFit() {
        MenuScrollBox box = overflowingBox();
        box.rowsUsed = BOX_ROWS;
        assertFalse(box.scrollBar.isNeeded());
        assertEquals(BOX_WIDTH, box.scrollBar.textWidth(), EPSILON);
        assertFalse(box.scrollBar.press(BAR_X, HEIGHT / 2));
    }

    @Test
    void arrowsMoveOneRowAndTheTrackAPage() {
        MenuScrollBox box = overflowingBox();
        MenuScrollBar bar = box.scrollBar;
        assertTrue(bar.press(BAR_X, HEIGHT - BAR / 2));
        assertEquals(0, box.scrollOffsetY, EPSILON, "the top arrow stops at the first row");
        assertTrue(bar.press(BAR_X, BAR / 2));
        assertEquals(ROW, box.scrollOffsetY, EPSILON);
        assertTrue(bar.press(BAR_X, BAR / 2));
        assertEquals(2 * ROW, box.scrollOffsetY, EPSILON);
        assertTrue(bar.press(BAR_X, HEIGHT - BAR / 2));
        assertEquals(ROW, box.scrollOffsetY, EPSILON);

        float page = (BOX_ROWS - 1) * ROW;
        float belowThumb = (BAR + bar.thumbTop() - bar.thumbLength()) / 2;
        assertTrue(bar.press(BAR_X, belowThumb));
        assertEquals(ROW + page, box.scrollOffsetY, EPSILON);
        float aboveThumb = (bar.thumbTop() + HEIGHT - BAR) / 2;
        assertTrue(bar.press(BAR_X, aboveThumb));
        assertEquals(ROW, box.scrollOffsetY, EPSILON);
        box.scrollOffsetY = box.maximumScrollOffsetY();
        assertTrue(bar.press(BAR_X, BAR / 2));
        assertEquals(box.maximumScrollOffsetY(), box.scrollOffsetY, EPSILON,
                "the bottom arrow stops at the last row");
    }

    @Test
    void draggingTheThumbThroughTheBoxRegionScrollsInProportion() {
        MenuScrollBox box = overflowingBox();
        MenuScrollBar bar = box.scrollBar;
        float grabY = bar.thumbTop() - bar.thumbLength() / 2;
        assertTrue(box.claimsPointer());
        box.onPress(BAR_X, grabY);
        assertEquals(0, box.scrollOffsetY, EPSILON, "grabbing the thumb does not scroll");
        box.onDrag(BAR_X, grabY - (TRACK - THUMB) / 2);
        assertEquals(box.maximumScrollOffsetY() / 2, box.scrollOffsetY, EPSILON);
        box.onDrag(BAR_X, -HEIGHT);
        assertEquals(box.maximumScrollOffsetY(), box.scrollOffsetY, EPSILON);
        box.onDrag(BAR_X, 2 * HEIGHT);
        assertEquals(0, box.scrollOffsetY, EPSILON);
        box.onRelease(BAR_X, 2 * HEIGHT, false);
        box.onDrag(BAR_X, grabY - TRACK);
        assertEquals(0, box.scrollOffsetY, EPSILON, "a released thumb no longer follows");
    }

    @Test
    void pressesLeftOfTheBarOrOnAHiddenMenuAreNotTaken() {
        MenuScrollBox box = overflowingBox();
        assertFalse(box.scrollBar.press(BOX_WIDTH - BAR - 1, HEIGHT / 2));
        MenuScrollBox hidden = new MenuScrollBox("HIDDEN_BOX", () -> false);
        hidden.bounds.update(0, 0, BOX_WIDTH, HEIGHT);
        hidden.rowsUsed = CONTENT_ROWS;
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
                    Color.COMMAND, null);
        }
        box.layout(camera);
        assertTrue(box.scrollBar.isNeeded());
        for (HyperWord word : box.text.words) {
            if (!word.newLine) {
                float right = word.x + ROW / Drawing.getDrawing().font.fontHeight * word.width;
                assertTrue(right <= BOX_WIDTH - BAR + EPSILON, word + " runs under the bar");
            }
        }
    }

    private static MenuScrollBox overflowingBox() {
        MenuScrollBox box = new MenuScrollBox(BOX_ID, () -> true);
        box.bounds.update(0, 0, BOX_WIDTH, HEIGHT);
        box.rowsUsed = CONTENT_ROWS;
        return box;
    }
}
