package unit.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.ArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.game.City;
import ixdar.game.CityNetwork;
import ixdar.geometry.grid2d.Grid.CartesianGrid;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.scenes.trade.TradeScene;
import ixdar.tools.trade.RoutePlanningTool;
import unit.GlStandIn;

/**
 * Undoing a route operation by operation and redoing every one brings back the route the tool
 * reports, with as many segments as before the first undo.
 */
class RoutePlanningUndoRedoTest {

    private static final int CITY_POPULATION = 1000;

    private static final int STAND_IN_PLATFORM_ID = 7719;

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
    void undoAllThenRedoAllRestoresTheRouteAndItsSegmentCount() {
        ArrayList<City> cities = new ArrayList<>();
        cities.add(new City("hq", "Headquarters", 100f, 100f, CITY_POPULATION));
        cities.add(new City("east", "East", 200f, 110f, CITY_POPULATION));
        cities.add(new City("north", "North", 150f, 200f, CITY_POPULATION));
        cities.add(new City("west", "West", 50f, 160f, CITY_POPULATION));
        CityNetwork network = new CityNetwork(cities, new CartesianGrid());
        network.setHeadquarters(cities.get(0));
        RoutePlanningTool tool = new TradeScene(network, null).routePlanningTool;

        tool.onCityClick(cities.get(0));
        tool.onCityClick(cities.get(1));
        tool.confirm();
        int[] segmentsAfterOperation = new int[cities.size() - 1];
        segmentsAfterOperation[0] = tool.getCurrentRoute().manifoldSegments.size();
        for (int city = 2; city < cities.size(); city++) {
            tool.onCityClick(cities.get(city));
            tool.confirm();
            segmentsAfterOperation[city - 1] = tool.getCurrentRoute().manifoldSegments.size();
        }
        assertEquals(cities.size(), segmentsAfterOperation[segmentsAfterOperation.length - 1]);

        for (int operation = segmentsAfterOperation.length - 1; operation > 0; operation--) {
            tool.undo();
            assertEquals(segmentsAfterOperation[operation - 1], tool.getCurrentRoute().manifoldSegments.size());
        }
        tool.undo();
        assertNull(tool.getCurrentRoute());

        for (int operation = 0; operation < segmentsAfterOperation.length; operation++) {
            tool.redo();
            assertNotNull(tool.getCurrentRoute());
            assertEquals(segmentsAfterOperation[operation], tool.getCurrentRoute().manifoldSegments.size());
        }
    }
}
