package unit.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.Font;
import ixdar.graphics.render.text.HyperString;
import ixdar.graphics.render.text.HyperWord;
import ixdar.gui.ui.Drawing;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.Platform;

/**
 * A word's click and hover rectangle is the rectangle it is drawn in: as wide as its glyph advance
 * scaled to the drawn height, abutting its neighbours, so a point is inside at most one word.
 */
class HyperStringBoundsTest {

    private static final float VIEW_WIDTH = 800f;

    private static final float VIEW_HEIGHT = 300f;

    private static final float ROW = Drawing.FONT_HEIGHT_PIXELS;

    private static final float LABEL_HEIGHT = 12f;

    private static final float LABEL_CENTRE_X = 400f;

    private static final float LABEL_CENTRE_Y = 150f;

    private static final float TOLERANCE = 1e-3f;

    private Platform suitePlatform;

    private GL suiteGl;

    private Camera2D camera;

    private Font font;

    /**
     * Pair the suite's platform with a GL that does nothing, so the drawing font loads.
     */
    @BeforeEach
    void installGlStandIn() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platforms.init(suitePlatform, FontGlStandIn.create());
        camera = new Camera2D((int) VIEW_WIDTH, (int) VIEW_HEIGHT, 1f, 0, 0, null);
        camera.updateView(0, 0, (int) VIEW_WIDTH, (int) VIEW_HEIGHT);
        font = Drawing.getDrawing().font;
    }

    /**
     * Put the suite's GL back.
     */
    @AfterEach
    void restoreSuiteGl() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void rowBoundsAreTheDrawnWidthAndAbut() {
        HyperString text = new HyperString();
        text.addWordClick("Knot segments wide", Color.COMMAND, () -> {
        });
        text.setLineOffsetFromTopRow(camera, 0, 0, ROW);

        List<HyperWord> words = drawnWords(text);
        assertEquals(3, words.size(), "words: " + words);
        for (int index = 0; index < words.size(); index++) {
            HyperWord word = words.get(index);
            float drawn = ROW / font.fontHeight * font.getWidth(word.charSequence);
            assertTrue(drawn > 0, word + " is drawn");
            assertEquals(drawn, word.drawnWidth, TOLERANCE, word + " bounds width");
            assertEquals(ROW, word.rowHeight, TOLERANCE, word + " bounds height");
            if (index + 1 < words.size()) {
                assertEquals(word.xScreenOffset + word.drawnWidth, words.get(index + 1).xScreenOffset,
                        TOLERANCE, word + " ends where the next word starts");
            }
        }
    }

    @Test
    void aPointInsideAWordHoversAndClicksThatWordAlone() {
        HyperString text = new HyperString();
        int[] hovers = new int[3];
        int[] clicks = new int[3];
        String[] labels = {"alpha", "beta", "gamma"};
        for (int index = 0; index < labels.length; index++) {
            int counter = index;
            text.addWord(labels[index], Color.COMMAND, () -> hovers[counter]++, () -> {
            }, () -> clicks[counter]++);
        }
        text.setLineOffsetFromTopRow(camera, 0, 0, ROW);

        List<HyperWord> words = drawnWords(text);
        for (int index = 0; index < words.size(); index++) {
            HyperWord word = words.get(index);
            float x = word.xScreenOffset + word.drawnWidth / 2;
            float y = word.yScreenOffset + ROW / 2;
            text.calculateHover(x, y);
            text.click(x, y);
            for (int other = 0; other < labels.length; other++) {
                int expected = other <= index ? 1 : 0;
                assertEquals(expected, hovers[other], "hover of " + labels[other] + " after " + word);
                assertEquals(expected, clicks[other], "click of " + labels[other] + " after " + word);
            }
        }
        HyperWord last = words.get(words.size() - 1);
        text.click(last.xScreenOffset + last.drawnWidth + 1, last.yScreenOffset + ROW / 2);
        assertEquals(1, clicks[labels.length - 1], "a click right of the text runs nothing");
    }

    @Test
    void centredBoundsAreScaledToTheDrawnHeight() {
        HyperString label = new HyperString();
        label.addWord("1234");
        label.setLineOffsetCentered(camera, LABEL_CENTRE_X, LABEL_CENTRE_Y, font, LABEL_HEIGHT, 0);

        HyperWord word = drawnWords(label).get(0);
        float scale = LABEL_HEIGHT / font.fontHeight;
        assertEquals(scale * font.getWidth(word.charSequence), word.drawnWidth, TOLERANCE);
        assertEquals(scale * font.getHeight(word.charSequence), word.rowHeight, TOLERANCE);
        assertEquals(LABEL_CENTRE_X, word.xScreenOffset + word.drawnWidth / 2, TOLERANCE, "centred in x");
        assertEquals(LABEL_CENTRE_Y, word.yScreenOffset + word.rowHeight / 2, TOLERANCE, "centred in y");
    }

    private static List<HyperWord> drawnWords(HyperString text) {
        List<HyperWord> words = new ArrayList<>();
        for (HyperWord word : text.words) {
            if (!word.newLine) {
                words.add(word);
            }
        }
        return words;
    }
}
