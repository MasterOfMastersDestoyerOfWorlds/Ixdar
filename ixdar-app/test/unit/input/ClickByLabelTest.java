package unit.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ixdar.automation.endpoints.ui.DrawnWords;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuScrollBox;
import ixdar.gui.ui.text.HyperString;
import ixdar.input.MouseTrap;
import ixdar.input.PointerDispatcher;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import unit.GlStandIn;

/**
 * At the headless 1.25 display scale a label found by its drawn text is clicked on a window pixel
 * that lands on it, and a label that is absent, out of view, inert or ambiguous is refused with
 * its candidates.
 */
class ClickByLabelTest {

    private static final int WINDOW_WIDTH = 1024;

    private static final int WINDOW_HEIGHT = 768;

    private static final int FRAMEBUFFER_WIDTH = 1280;

    private static final int FRAMEBUFFER_HEIGHT = 960;

    private static final float ROW = Drawing.FONT_HEIGHT_PIXELS;

    private static final int BOX_ROWS = 4;

    private static final float BOX_X = 200f;

    private static final float BOX_Y = 300f;

    private static final float BOX_WIDTH = 600f;

    private static final int EXTRA_ROWS = 10;

    private static final String BOX_VIEW = "LABEL_TEST_BOX";

    private static final String FIRST_ROW = "Alpha one";

    private static final String TWICE = "Beta";

    private static final String INERT = "Gamma";

    private static final String LAST_ROW = "Row 9";

    private static final String OK = "ok";

    private static final String ERROR = "error";

    private final int[] clicks = new int[3];

    private Platform suitePlatform;

    private GL suiteGl;

    private Camera2D camera;

    private MenuScrollBox box;

    private MouseTrap trap;

    private PointerDispatcher pointer;

    /**
     * Install a 1.25-scaled platform with a GL stand-in so the font loads, and a four-row box,
     * subscribed for input, holding more rows than it shows.
     */
    @BeforeEach
    void installScaledBox() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platform scaled = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getWindowWidth":
                            return WINDOW_WIDTH;
                        case "getWindowHeight":
                            return WINDOW_HEIGHT;
                        case "getFrameBufferWidth":
                            return FRAMEBUFFER_WIDTH;
                        case "getFrameBufferHeight":
                            return FRAMEBUFFER_HEIGHT;
                        default:
                            return method.invoke(suitePlatform, arguments);
                    }
                });
        Platforms.init(scaled, GlStandIn.create());
        camera = new Camera2D(1, 1, 1f, 0, 0, null);
        box = new MenuScrollBox(BOX_VIEW, () -> true);
        box.bounds.setUpdateCallback(bounds -> bounds.update(BOX_X, BOX_Y, BOX_WIDTH, BOX_ROWS * ROW));
        box.clearRows();
        box.addRow(FIRST_ROW, Color.WHITE, () -> clicks[0]++);
        box.addRow(TWICE, Color.WHITE, () -> clicks[1]++);
        box.addRow(INERT, Color.WHITE, null);
        box.addRow(TWICE, Color.WHITE, () -> clicks[2]++);
        for (int row = 0; row < EXTRA_ROWS; row++) {
            box.addRow("Row " + row, Color.WHITE, () -> {
            });
        }
        pointer = PointerDispatcher.current();
        pointer.subscribe(box.bounds, box);
        trap = new MouseTrap(new Camera3D(new Vector3f(), 0f, 0f), null);
        trap.active = true;
        drawFrame();
    }

    /**
     * Drop the subscription, the frame's text and put the suite's platform back.
     */
    @AfterEach
    void restoreSuitePlatform() {
        pointer.unsubscribe(box);
        MouseTrap.hyperStrings = new ArrayList<>();
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void aLabelIsClickedOnAWindowPixelThatLandsOnIt() {
        JsonObject target = DrawnWords.labelTarget(FIRST_ROW, "", DrawnWords.NO_INDEX);
        assertTrue(target.get(OK).getAsBoolean(), target.toString());
        assertEquals(BOX_VIEW, target.get(DrawnWords.VIEW).getAsString());
        assertEquals("exact", target.get(DrawnWords.MATCH).getAsString());
        click(target);
        assertEquals(1, clicks[0]);
        assertEquals(0, clicks[1] + clicks[2]);
    }

    @Test
    void aPartOfALabelIgnoringSpacesFindsIt() {
        JsonObject target = DrawnWords.labelTarget("Alphaone", BOX_VIEW, DrawnWords.NO_INDEX);
        assertTrue(target.get(OK).getAsBoolean(), target.toString());
        assertEquals(FIRST_ROW, target.get(DrawnWords.LABEL).getAsString());
        assertEquals("contains", target.get(DrawnWords.MATCH).getAsString());
    }

    @Test
    void aRowScrolledOutOfTheBoxIsRefusedUntilItIsScrolledIn() {
        JsonObject refusal = DrawnWords.labelTarget(LAST_ROW, "", DrawnWords.NO_INDEX);
        assertFalse(refusal.get(OK).getAsBoolean());
        assertTrue(refusal.get(ERROR).getAsString().contains("out of its view"), refusal.toString());
        assertEquals(1, refusal.getAsJsonArray(DrawnWords.CANDIDATES).size());
        box.scrollToEndPending = true;
        drawFrame();
        assertTrue(DrawnWords.labelTarget(LAST_ROW, "", DrawnWords.NO_INDEX).get(OK).getAsBoolean());
        assertFalse(DrawnWords.labelTarget(FIRST_ROW, "", DrawnWords.NO_INDEX).get(OK).getAsBoolean(),
                "the first row has scrolled out");
    }

    @Test
    void anAbsentLabelIsRefusedWithTheVisibleClickableLabels() {
        JsonObject refusal = DrawnWords.labelTarget("Delta", "", DrawnWords.NO_INDEX);
        assertFalse(refusal.get(OK).getAsBoolean());
        JsonArray candidates = refusal.getAsJsonArray(DrawnWords.CANDIDATES);
        assertEquals(3, candidates.size(), "Alpha one and both Betas; Gamma does nothing");
    }

    @Test
    void aLabelAClickLeavesAloneIsRefused() {
        JsonObject refusal = DrawnWords.labelTarget(INERT, "", DrawnWords.NO_INDEX);
        assertFalse(refusal.get(OK).getAsBoolean());
        assertTrue(refusal.get(ERROR).getAsString().contains("does nothing"), refusal.toString());
    }

    @Test
    void anAmbiguousLabelIsRefusedAndAnIndexPicksOne() {
        JsonObject refusal = DrawnWords.labelTarget(TWICE, "", DrawnWords.NO_INDEX);
        assertFalse(refusal.get(OK).getAsBoolean());
        assertEquals(2, refusal.getAsJsonArray(DrawnWords.CANDIDATES).size());
        click(DrawnWords.labelTarget(TWICE, "", 1));
        assertEquals(1, clicks[2], "index 1 is the second Beta drawn");
        assertEquals(0, clicks[1]);
        assertFalse(DrawnWords.labelTarget(TWICE, "ELSEWHERE", DrawnWords.NO_INDEX).get(OK).getAsBoolean(),
                "no Beta is drawn in another view");
    }

    @Test
    void theListingNamesEachWordsViewAndWindowRectangle() {
        JsonObject listing = DrawnWords.drawnText("");
        JsonObject first = null;
        for (JsonElement element : listing.getAsJsonArray(DrawnWords.WORDS)) {
            if (first == null && "Alpha".equals(element.getAsJsonObject().get(DrawnWords.TEXT).getAsString())) {
                first = element.getAsJsonObject();
            }
        }
        assertTrue(first != null, listing.toString());
        assertEquals(BOX_VIEW, first.get(DrawnWords.VIEW).getAsString());
        assertTrue(first.get(DrawnWords.VISIBLE).getAsBoolean());
        JsonObject framebuffer = first.getAsJsonObject(DrawnWords.FRAMEBUFFER);
        JsonObject window = first.getAsJsonObject(DrawnWords.WINDOW);
        float scale = (float) FRAMEBUFFER_WIDTH / WINDOW_WIDTH;
        assertEquals(framebuffer.get(DrawnWords.X).getAsFloat() / scale, window.get(DrawnWords.X).getAsFloat(), 1e-3f);
        float framebufferTop = framebuffer.get(DrawnWords.Y).getAsFloat()
                + framebuffer.get(DrawnWords.HEIGHT).getAsFloat();
        assertEquals((FRAMEBUFFER_HEIGHT - framebufferTop) / scale, window.get(DrawnWords.Y).getAsFloat(), 1e-3f,
                "window rectangles are y down");
    }

    @Test
    void aStringDrawnTwiceInAFrameRegistersOnce() {
        HyperString twice = new HyperString();
        twice.addWordClick(FIRST_ROW, Color.WHITE, () -> clicks[0]++);
        int registered = MouseTrap.hyperStrings.size();
        twice.draw();
        twice.draw();
        assertEquals(registered + 1, MouseTrap.hyperStrings.size());
    }

    private void drawFrame() {
        MouseTrap.hyperStrings = new ArrayList<>();
        box.draw(camera);
    }

    private void click(JsonObject target) {
        pointer.moveOrDrag(trap, 0L, target.get(DrawnWords.X).getAsFloat(), target.get(DrawnWords.Y).getAsFloat());
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_PRESS, 0);
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_RELEASE, 0);
    }
}
