package unit.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.game.trade.TradeGame;
import ixdar.game.trade.level.TradeLevelFile;
import ixdar.game.trade.map.Place;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.KeyNames;
import ixdar.platform.input.Keys;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.scenes.trade.TradeScene;
import ixdar.tools.sculpt.SculptPass;
import ixdar.tools.sculpt.StrokeSculptTool;
import unit.GlStandIn;

/**
 * Ctrl+Z, ctrl+Y and ctrl+shift+Z are every scene's, stepping the active tool's own history: the
 * stroke-sculpt passes, the trade routes, and a refusal naming a tool that keeps none.
 */
class SceneUndoTest {

    private Platform suitePlatform;

    private GL suiteGl;

    /** Put a GL stand-in under the scenes. */
    @BeforeEach
    void installGlStandIn() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platforms.init(suitePlatform, GlStandIn.create());
    }

    /** Put the suite's GL back. */
    @AfterEach
    void restoreSuiteGl() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void ctrlZStepsTheStrokeSculptPassesAndAToolWithoutHistorySaysSo() {
        MeshNodeViewerScene scene = new MeshNodeViewerScene() {
            @Override
            public void showGraph(String dslSource, Map<String, Object> overrides) {
            }

            @Override
            public int getMeshVertexCount() {
                return 0;
            }
        };
        scene.initPoints();
        scene.setTools();
        scene.switchTool(scene.defaultTool);
        scene.keys.handleSceneKeys(Keys.Z, KeyNames.MOD_CONTROL);
        assertEquals("nothing to undo in the " + scene.defaultTool.toolName(), scene.defaultTool.lastError);

        StrokeSculptTool sculpt = scene.strokeSculptTool;
        scene.switchTool(sculpt);
        List<SculptPass> drawn = List.of(new SculptPass("pass_1", "ball.mesh", "pass_1.svg", "<svg/>",
                "none", true));
        sculpt.history.push("stroke", sculpt.passes, drawn);
        sculpt.passes = drawn;

        scene.keys.handleSceneKeys(Keys.Z, KeyNames.MOD_CONTROL);
        assertEquals(List.of(), sculpt.passes, sculpt.lastError);
        assertTrue(sculpt.lastRow.startsWith("undid stroke"), sculpt.lastRow);
        scene.keys.handleSceneKeys(Keys.Y, KeyNames.MOD_CONTROL);
        assertEquals(drawn, sculpt.passes, sculpt.lastError);
        scene.keys.handleSceneKeys(Keys.Z, KeyNames.MOD_CONTROL);
        scene.keys.handleSceneKeys(Keys.Z, KeyNames.MOD_CONTROL | KeyNames.MOD_SHIFT);
        assertEquals(drawn, sculpt.passes, sculpt.lastError);
    }

    @Test
    void ctrlZStepsTheTradeRoutes() throws Exception {
        TradeScene scene = new TradeScene();
        scene.setTools();
        scene.game = new TradeGame(TradeLevelFile.read(Files.readString(Path.of(
                "src/main/resources/trade", "small_loops.ix"))));
        scene.switchTool(scene.pipeTool);
        int loops = scene.game.player.loops.size();
        assertTrue(scene.game.network.pipe(place(scene.game, "Farrow"), place(scene.game, "Elmet"), 0));
        assertEquals(loops + 1, scene.game.player.loops.size());

        scene.keys.handleSceneKeys(Keys.Z, KeyNames.MOD_CONTROL);
        assertEquals(loops, scene.game.player.loops.size());
        scene.keys.handleSceneKeys(Keys.Y, KeyNames.MOD_CONTROL);
        assertEquals(loops + 1, scene.game.player.loops.size());
    }

    /**
     * A place on a game's map by name.
     *
     * @param game the game
     * @param name the place's name
     * @return the place
     */
    private static Place place(TradeGame game, String name) {
        for (Place place : game.map.places) {
            if (place.name.equals(name)) {
                return place;
            }
        }
        throw new IllegalArgumentException("no place " + name);
    }
}
