package unit.trade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.game.City;
import ixdar.game.TradeLevel;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.Keys;
import ixdar.scenes.trade.TradeScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Draft;
import ixdar.tools.Item;
import ixdar.tools.Tool;
import ixdar.tools.trade.RoutePlanningTool;
import unit.GlStandIn;

/**
 * Read through the plain {@link Tool} base, the route tool reports in the shape every tool shares:
 * its picked city as the draft, the loops' legs as items, and its mode.
 */
class RouteToolAutomationStateTest {

    private static final int STAND_IN_PLATFORM_ID = 7721;

    private static final String PIPE_MODE = "PIPE";

    private static final String GROW_MODE = "GROW";

    private Platform suitePlatform;

    private GL suiteGl;

    /**
     * A platform whose window takes no callbacks, paired with a GL that does nothing, since the
     * cities and the scene upload their overlays as they are built.
     */
    @BeforeEach
    void installStandIns() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platform window = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getPlatformID")) {
                        return STAND_IN_PLATFORM_ID;
                    }
                    Class<?> type = method.getReturnType();
                    return type == int.class ? 0 : type == float.class ? 0f
                            : type == boolean.class ? false : null;
                });
        Platforms.init(window, GlStandIn.create());
    }

    /**
     * Put the suite's platform back.
     */
    @AfterEach
    void restoreSuitePlatform() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void routeToolReportsThroughTheCommonShape() {
        TradeScene scene = new TradeScene(TradeLevel.campaign(), 0, null);
        scene.setTools();
        scene.loadLevel(0);
        RoutePlanningTool route = scene.routePlanningTool;
        Tool tool = route;
        assertNull(tool.draft(), "a draft before any city was picked");
        assertTrue(tool.items().isEmpty(), "the first tutorial starts with no loop");

        City home = scene.network.cities.get(0);
        route.firstCity = 0;
        Draft picked = tool.draft();
        assertArrayEquals(new float[] { home.getX(), home.getY(), 0f }, picked.points);
        assertArrayEquals(new String[] { home.name }, picked.pointLabels);

        assertTrue(scene.game.pipe(0, 1), scene.game.lastRefusal);
        assertEquals(2, tool.items().size(), "one item per leg of the out-and-back loop");
        Item leg = tool.items().get(0);
        assertTrue(leg.saved);
        assertEquals(scene.game.legCost[0][1], leg.length, 0.0);
        assertEquals(PIPE_MODE, tool.modes[tool.mode]);

        List<ControlHint> controls = new ArrayList<>();
        tool.addControls(controls);
        for (ControlHint hint : controls) {
            if (hint.keyCode == Keys.G) {
                hint.action.perform();
            }
        }
        assertEquals(GROW_MODE, tool.modes[tool.mode]);
        route.undo();
        assertTrue(tool.items().isEmpty(), "undo left the loop's legs");
        assertFalse(scene.game.undone.isEmpty(), "undo keeps the operation for redo");
        assertArrayEquals(new String[] { PIPE_MODE, GROW_MODE, "COLLAPSE" }, tool.modes);
    }
}
