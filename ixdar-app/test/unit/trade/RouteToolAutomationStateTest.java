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
import ixdar.game.CityNetwork;
import ixdar.geometry.grid2d.Grid.CartesianGrid;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.Keys;
import ixdar.scenes.trade.TradeScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Draft;
import ixdar.tools.EditHistory;
import ixdar.tools.Item;
import ixdar.tools.Tool;
import ixdar.tools.trade.RoutePlanningTool.Mode;
import unit.GlStandIn;

/**
 * Read through the plain {@link Tool} base, the route tool reports in the shape every tool shares:
 * its picked cities as the draft, the route's segments as items, its undo history and its mode.
 */
class RouteToolAutomationStateTest {

    private static final int CITY_POPULATION = 1000;

    private static final int STAND_IN_PLATFORM_ID = 7721;

    private static final String PIPE_MODE = Mode.PIPE.name();

    private static final String GROW_MODE = Mode.GROW.name();

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
        ArrayList<City> cities = new ArrayList<>();
        cities.add(new City("hq", "Headquarters", 100f, 100f, CITY_POPULATION));
        cities.add(new City("east", "East", 200f, 110f, CITY_POPULATION));
        cities.add(new City("north", "North", 150f, 200f, CITY_POPULATION));
        CityNetwork network = new CityNetwork(cities, new CartesianGrid());
        network.setHeadquarters(cities.get(0));
        TradeScene scene = new TradeScene(network, null);
        Tool tool = scene.routePlanningTool;
        assertNull(tool.draft(), "a draft before any city was picked");
        assertTrue(tool.items().isEmpty());

        scene.routePlanningTool.onCityClick(cities.get(0));
        Draft picked = tool.draft();
        assertArrayEquals(new float[] { cities.get(0).getX(), cities.get(0).getY(), 0f }, picked.points);
        assertFalse(picked.valid, "one city is not an operation yet");
        scene.routePlanningTool.onCityClick(cities.get(1));
        assertTrue(tool.draft().valid, "two picked cities are not previewed");
        assertEquals("PREVIEW", tool.draft().label);
        assertArrayEquals(new String[] { cities.get(0).name, cities.get(1).name },
                tool.draft().pointLabels);
        scene.routePlanningTool.confirm();

        EditHistory<?> history = tool.history();
        assertEquals(1, history.undoDepth);
        assertFalse(history.nextUndoName().isEmpty(), "the pipe was not recorded");
        assertEquals(2, tool.items().size(), "one item per route segment");
        Item segment = tool.items().get(0);
        assertTrue(segment.saved);
        assertFalse(segment.deleted);
        assertEquals(PIPE_MODE, tool.modes[tool.mode]);

        List<ControlHint> controls = new ArrayList<>();
        tool.addControls(controls);
        for (ControlHint hint : controls) {
            if (hint.keyCode == Keys.G) {
                hint.action.perform();
            }
        }
        assertEquals(GROW_MODE, tool.modes[tool.mode]);
        scene.routePlanningTool.undo();
        assertEquals(0, history.undoDepth);
        assertEquals(1, history.redoDepth());
        assertTrue(tool.items().isEmpty(), "undo left the route's segments");
        assertArrayEquals(new String[] { PIPE_MODE, GROW_MODE, Mode.COLLAPSE.name() }, tool.modes);
    }
}
