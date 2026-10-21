package unit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.models.ModelChoice;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.scenes.EmbeddedTMeshScene;
import ixdar.scenes.dungeon.DungeonViewerScene;
import ixdar.scenes.model.ModelScene;
import unit.GlStandIn;

/**
 * The T-mesh and dungeon scenes register their graphs as {@link ModelChoice} entries of kind DSL,
 * each with its literal overrides, and an override set before the first run lands on the scene's
 * collection after being checked against the graph it is meant for.
 */
class RegisteredGraphTest {

    private static final int STAND_IN_PLATFORM_ID = 7719;

    private static final String SCALED_TORUS = "Scaled torus x4";

    private static final String MAJOR_SEGMENTS = "carrier.major_segments";

    private Platform suitePlatform;

    private GL suiteGl;

    /**
     * A platform whose window takes no callbacks, paired with a GL that does nothing.
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
    void theTMeshFixturesAreGraphChoicesCarryingTheirOverrides() {
        EmbeddedTMeshScene scene = new EmbeddedTMeshScene();

        for (ModelChoice graph : scene.graphs) {
            assertEquals(ModelChoice.Kind.DSL, graph.kind, graph.displayName + " is a graph");
            assertTrue(graph.path.startsWith("dsl/fixtures/"), graph.displayName
                    + " names its fixture resource");
        }
        ModelChoice scaled = registered(scene, SCALED_TORUS);
        assertEquals(Map.of(MAJOR_SEGMENTS, 48, "carrier.minor_segments", 32), scaled.overrides,
                "the dense fixture carries the scale it runs at");
        assertEquals(Map.of(), registered(scene, "Plane layout").overrides,
                "a fixture without overrides carries none");
    }

    @Test
    void theDungeonGraphsAreGraphChoicesAndThePlanarOneOpensFirst() {
        DungeonViewerScene scene = new DungeonViewerScene();

        assertEquals(2, scene.graphs.size(), "both dungeon graphs are registered");
        assertEquals(ModelChoice.Kind.DSL, registered(scene, DungeonViewerScene.LAYERED_DUNGEON).kind,
                "the layered dungeon is a graph");
        assertEquals(DungeonViewerScene.PLANAR_DUNGEON, scene.resolveInitialModel().displayName,
                "with no model named, the planar dungeon opens first");
    }

    @Test
    void anOverrideSetBeforeTheFirstRunIsCheckedAndKeptOnTheCollection() {
        EmbeddedTMeshScene scene = new EmbeddedTMeshScene();
        ModelChoice scaled = registered(scene, SCALED_TORUS);
        assertNull(scene.currentModel(), "nothing has been opened yet");

        scene.setOverride(MAJOR_SEGMENTS, 24f, scaled);
        assertThrows(IllegalArgumentException.class,
                () -> scene.setOverride("carrier.no_such_port", 1f, scaled),
                "a key the graph has no port for is refused");

        assertEquals(Map.of(MAJOR_SEGMENTS, 24f), scene.modelCollection.overrides,
                "the override waits on the collection for the first run");
        scene.modelCollection = scene.modelCollection.open(scaled);
        assertEquals(24f, scene.modelCollection.overridesFor(scaled).get(MAJOR_SEGMENTS),
                "the scene's override replaces the fixture's own when it runs");
        scene.modelCollection = scene.modelCollection.open(registered(scene, "Torus layout"));
        assertEquals(Map.of(MAJOR_SEGMENTS, 24f), scene.modelCollection.overrides,
                "the override survives switching to another graph");
    }

    /**
     * The graph a scene registered under a display name.
     *
     * @param scene scene holding the registrations
     * @param displayName name the model menu lists it under
     * @return the registered choice
     */
    private static ModelChoice registered(ModelScene scene, String displayName) {
        for (ModelChoice graph : scene.graphs) {
            if (graph.displayName.equals(displayName)) {
                return graph;
            }
        }
        throw new AssertionError("no graph registered as " + displayName);
    }
}
