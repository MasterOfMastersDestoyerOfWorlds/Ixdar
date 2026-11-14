package unit.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.graphics.cameras.Camera2D;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.text.HyperString;
import ixdar.gui.ui.text.HyperWord;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import unit.GlStandIn;

/**
 * Words appended with {@link HyperString#joinWord} wrap with the run they join: they share a row
 * whenever the run fits the view, and a run wider than the view still wraps word by word.
 */
class HyperStringJoinWordTest {

    private static final float ROW = Drawing.FONT_HEIGHT_PIXELS;

    private static final int VIEW_HEIGHT = 1000;

    private static final int PAIRS = 12;

    private static final int WORDS_PER_PAIR = 4;

    private static final float EXACT = 0f;

    private Platform suitePlatform;

    private GL suiteGl;

    /**
     * A GL stand-in so the font loads.
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
    void aRunThatFitsMovesToTheNextRowWhole() {
        HyperString bar = new HyperString();
        for (int pair = 0; pair < PAIRS; pair++) {
            HyperWord key = bar.addWord("double-click " + pair + ":", bar.defaultColor);
            bar.joinWord(key, "does it", bar.defaultColor);
        }
        bar.wrap();
        int rows = layOut(bar, widthOf(bar, 0, WORDS_PER_PAIR) * 3);
        assertTrue(rows > 1, "twelve pairs wrap in a view three pairs wide");
        for (int index = 1; index < bar.words.size(); index++) {
            HyperWord word = bar.words.get(index);
            if (word.joinsPrevious) {
                assertEquals(bar.words.get(index - 1).y, word.y, EXACT,
                        "a wrap fell inside a run at word " + index + " '" + word.charSequence + "'");
            }
        }
    }

    @Test
    void aRunWiderThanTheViewWrapsWordByWord() {
        HyperString bar = new HyperString();
        HyperWord first = bar.addWord("one", bar.defaultColor);
        bar.joinWord(first, "two three four five six seven eight", bar.defaultColor);
        bar.wrap();
        int rows = layOut(bar, widthOf(bar, 0, 2));
        assertTrue(rows > 1, "the run spread over rows instead of running off the view");
    }

    /**
     * Lay {@code text} out from the top of a view {@code width} pixels wide.
     *
     * @param text  the string
     * @param width view width in pixels
     * @return rows the text took
     */
    private static int layOut(HyperString text, float width) {
        Camera2D camera = new Camera2D(1, 1, 1f, 0, 0, null);
        camera.updateView(0, 0, (int) Math.ceil(width), VIEW_HEIGHT);
        return text.setLineOffsetFromTopRow(camera, 0, 0f, ROW);
    }

    /**
     * Drawn width of words {@code from} up to {@code to} at the row height.
     *
     * @param text the string
     * @param from first word
     * @param to   word after the last
     * @return width in pixels
     */
    private static float widthOf(HyperString text, int from, int to) {
        float scale = ROW / Drawing.getDrawing().font.fontHeight;
        float width = 0f;
        for (int index = from; index < to; index++) {
            width += scale * text.words.get(index).width;
        }
        return width;
    }
}
