package unit.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperWord;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuScrollBox;
import ixdar.gui.ui.menu.SceneModelMenu;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.Platform;

/**
 * A menu box's rows wrap through HyperString, and its clicks, scroll limit and centring count the
 * rows HyperString reports. The real font atlas loads over a GL stand-in.
 */
class MenuScrollBoxWrapTest {

    private static final float BOX_WIDTH = 200f;

    private static final float ROW = Drawing.FONT_HEIGHT_PIXELS;

    private static final String LONG_ROW =
            "a model row whose name is far too long for the strip and has to wrap";

    private Platform suitePlatform;

    private GL suiteGl;

    private Camera2D camera;

    /**
     * Pair the suite's platform with a GL that does nothing, so the drawing font loads.
     */
    @BeforeEach
    void installGlStandIn() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platforms.init(suitePlatform, FontGlStandIn.create());
        camera = new Camera2D(1, 1, 1f, 0, 0, null);
    }

    /**
     * Put the suite's GL back.
     */
    @AfterEach
    void restoreSuiteGl() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void rowsThatFitTakeOneBoxRowEach() {
        MenuScrollBox box = box(10);
        box.addRow("ctrl+S  save", Color.COMMAND, null);
        box.addRow("R  rings", Color.COMMAND, null);
        box.layout(camera);
        assertEquals(2, box.rowsUsed);
        assertEquals(1, rowsOf(box, 0).size());
    }

    @Test
    void longRowWrapsAndEveryRowOfItClicksThatRow() {
        MenuScrollBox box = box(20);
        int[] clicks = new int[3];
        box.addRow("short", Color.COMMAND, () -> clicks[0]++);
        box.addRow(LONG_ROW, Color.COMMAND, () -> clicks[1]++);
        box.addRow("not clickable", Color.LIGHT_GRAY, null);
        box.layout(camera);

        TreeSet<Float> wrappedRows = rowsOf(box, 1);
        assertTrue(wrappedRows.size() >= 3, "rows of the long row: " + wrappedRows);
        assertEquals(1 + wrappedRows.size() + 1, box.rowsUsed);
        for (HyperWord word : wordsOf(box, 1)) {
            assertTrue(word.x + scaledWidth(word) <= BOX_WIDTH + 1e-3f, word + " sticks out");
        }
        for (float rowY : wrappedRows) {
            HyperWord last = null;
            for (HyperWord word : wordsOf(box, 1)) {
                if (word.y == rowY) {
                    last = word;
                }
            }
            int before = clicks[1];
            box.text.click(last.xScreenOffset + 1, rowY + ROW / 2);
            assertTrue(clicks[1] > before, "click on the row at y " + rowY);
        }
        int wrappedClicks = clicks[1];
        HyperWord plain = wordsOf(box, 2).get(0);
        box.text.click(plain.xScreenOffset + 1, plain.yScreenOffset + ROW / 2);
        assertEquals(0, clicks[0]);
        assertEquals(wrappedClicks, clicks[1]);
    }

    @Test
    void oneClickOnAModelRowRunsItsActionOnce() {
        MenuScrollBox box = box(10);
        int[] loads = new int[2];
        box.addRow(SceneModelMenu.OTHER_MARKER + "botijo in tri", Color.COMMAND, () -> loads[0]++);
        box.addRow(SceneModelMenu.CURRENT_MARKER + "kitten", Color.BRIGHT_GREEN, () -> loads[1]++);
        box.layout(camera);

        List<HyperWord> words = wordsOf(box, 0);
        assertTrue(words.size() >= 3, "words: " + words);
        for (HyperWord word : words) {
            int before = loads[0];
            box.text.click(word.xScreenOffset + word.drawnWidth / 2, word.yScreenOffset + ROW / 2);
            assertEquals(before + 1, loads[0], "one click on " + word);
        }
        assertEquals(0, loads[1]);
        HyperWord last = words.get(words.size() - 1);
        box.text.click(last.xScreenOffset + last.drawnWidth + 1, last.yScreenOffset + ROW / 2);
        assertEquals(words.size(), loads[0], "a click right of the text runs nothing");
    }

    @Test
    void unbrokenTokenIsCutToFitWithNoBlankRowBeforeIt() {
        MenuScrollBox box = box(20);
        String token = "abcdefghijklmnopqrstuvwxyz".repeat(3) + ".dsl";
        box.addRow(token, Color.COMMAND, null);
        box.layout(camera);

        List<HyperWord> pieces = wordsOf(box, 0);
        assertTrue(pieces.size() >= 2, "pieces: " + pieces);
        StringBuilder joined = new StringBuilder();
        for (HyperWord piece : pieces) {
            joined.append(piece.charSequence);
            assertTrue(piece.x + scaledWidth(piece) <= BOX_WIDTH + 1e-3f, piece + " sticks out");
        }
        assertEquals(token + " ", joined.toString());
        assertEquals(pieces.size(), box.rowsUsed);
        assertEquals(box.bounds.viewHeight - ROW, pieces.get(0).y, "first piece on the top row");
    }

    @Test
    void scrollLimitAndCentringCountWrappedRows() {
        MenuScrollBox box = box(4);
        for (int row = 0; row < 6; row++) {
            box.addRow(row == 2 ? LONG_ROW : "row " + row, Color.COMMAND, null);
        }
        box.layout(camera);
        int wrappedRows = rowsOf(box, 2).size();
        assertEquals(5 + wrappedRows, box.rowsUsed);
        assertEquals((box.rowsUsed - 4) * ROW, box.maximumScrollOffsetY());

        box.centreRow(2);
        box.layout(camera);
        assertEquals((2 + wrappedRows / 2f) * ROW - 2 * ROW, box.scrollOffsetY, 1e-3f);

        box.centreRow(5);
        box.layout(camera);
        assertEquals(box.maximumScrollOffsetY(), box.scrollOffsetY);
        box.centreRow(0);
        box.layout(camera);
        assertEquals(0, box.scrollOffsetY);
    }

    @Test
    void rowsAreDrawnAtWholeRowOffsetsSoNoneIsCutUnderTheTitle() {
        MenuScrollBox box = box(4);
        for (int row = 0; row < 10; row++) {
            box.addRow("row " + row, Color.COMMAND, null);
        }
        box.layout(camera);
        box.scrollOffsetY = 1.4f * ROW;
        assertEquals(ROW, box.drawnScrollOffsetY());
        box.scrollOffsetY = 1.6f * ROW;
        assertEquals(2 * ROW, box.drawnScrollOffsetY());
        box.scrollOffsetY = box.maximumScrollOffsetY();
        assertEquals(6 * ROW, box.drawnScrollOffsetY());
    }

    private static MenuScrollBox box(int rowsTall) {
        MenuScrollBox box = new MenuScrollBox("TEST_BOX", () -> true);
        box.bounds.update(0, 0, BOX_WIDTH, rowsTall * ROW);
        box.clearRows();
        return box;
    }

    private static List<HyperWord> wordsOf(MenuScrollBox box, int row) {
        List<HyperWord> words = new ArrayList<>();
        for (HyperWord word : box.text.getLine(row)) {
            if (!word.newLine) {
                words.add(word);
            }
        }
        return words;
    }

    private static TreeSet<Float> rowsOf(MenuScrollBox box, int row) {
        TreeSet<Float> rowYs = new TreeSet<>();
        for (HyperWord word : wordsOf(box, row)) {
            rowYs.add(word.y);
        }
        return rowYs;
    }

    private static float scaledWidth(HyperWord word) {
        return ROW / Drawing.getDrawing().font.fontHeight * word.width;
    }
}
