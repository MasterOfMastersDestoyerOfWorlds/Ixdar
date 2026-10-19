package unit.scenes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.gui.ui.actions.LoadIxAction;
import ixdar.gui.ui.menu.Menu;
import ixdar.gui.ui.menu.MenuItem;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.file.FileManagement;
import ixdar.platform.gl.GL;
import unit.GlStandIn;

/**
 * The debug menu's Continue is disabled while the test-file cache names no file, and opens the
 * file it names once it does, read afresh each time the menu is shown.
 */
class MenuContinueTest {

    private static final String CACHED_FILE = "triforce.ix";

    @TempDir
    Path cacheDirectory;

    private String cacheLocationBefore;

    private Path cache;

    private Platform suitePlatform;

    private GL suiteGl;

    /**
     * Point the cache at an empty scratch directory so the tracked cache file is never written,
     * and pair the suite's platform with a GL that does nothing, so menu labels can load the font.
     */
    @BeforeEach
    void useScratchCache() {
        cacheLocationBefore = FileManagement.testFileCacheLocation;
        cache = cacheDirectory.resolve("cache");
        FileManagement.testFileCacheLocation = cache.toString();
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platforms.init(suitePlatform, GlStandIn.create());
    }

    /**
     * Put the cache location and the suite's GL back the way the suite had them.
     */
    @AfterEach
    void restoreCacheLocation() {
        FileManagement.testFileCacheLocation = cacheLocationBefore;
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void continueIsDisabledWithNoCachedFile() {
        assertNull(continueItem(new Menu.MainMenu()).action);
    }

    @Test
    void continueIsDisabledWithAnEmptyCache() throws IOException {
        Files.write(cache, new byte[0]);
        assertNull(continueItem(new Menu.MainMenu()).action);
    }

    @Test
    void continueOpensTheCachedFileOnceOneIsLoaded() {
        Menu.MainMenu menu = new Menu.MainMenu();
        assertNull(continueItem(menu).action);
        FileManagement.updateTestFileCache(CACHED_FILE);
        LoadIxAction resume = assertInstanceOf(LoadIxAction.class, continueItem(menu).action);
        assertEquals(CACHED_FILE, resume.fileName);
    }

    @Test
    void theDefaultCacheLocationIsTheTrackedCacheFile() {
        File tracked = new File(cacheLocationBefore);
        assertTrue(tracked.isFile(), tracked.getAbsolutePath());
    }

    /**
     * Show the menu as {@code MenuBox.load} does and take its Continue row.
     *
     * @param menu the debug main menu
     * @return the menu's first row, Continue
     */
    private static MenuItem continueItem(Menu.MainMenu menu) {
        MenuItem item = menu.loadMenu().get(0);
        assertEquals("Continue", item.getHeading());
        return item;
    }
}
