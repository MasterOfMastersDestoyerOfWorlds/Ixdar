package unit.scenes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

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

    private static final String APP_CACHE_DIRECTORY = "appCacheDirectory";

    @TempDir
    Path cacheDirectory;

    private Platform suitePlatform;

    private GL suiteGl;

    private String platformCacheDirectory;

    /**
     * Pair the suite's platform, its cache directory moved into the test's temp folder, with a GL
     * that does nothing, so menu labels can load the font.
     */
    @BeforeEach
    void useStandInPlatform() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        platformCacheDirectory = cacheDirectory.toString();
        Platform cachedInTemp = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    if (APP_CACHE_DIRECTORY.equals(method.getName())) {
                        return platformCacheDirectory;
                    }
                    try {
                        return method.invoke(suitePlatform, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        Platforms.init(cachedInTemp, GlStandIn.create());
    }

    /**
     * Put the suite's platform and GL back the way the suite had them.
     */
    @AfterEach
    void restoreSuitePlatform() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void continueIsDisabledWithNoCachedFile() {
        assertNull(continueItem(new Menu.MainMenu()).action);
    }

    @Test
    void continueIsDisabledWithAnEmptyCache() throws IOException {
        Files.write(cacheDirectory.resolve(FileManagement.CACHE_FILE), new byte[0]);
        assertNull(continueItem(new Menu.MainMenu()).action);
    }

    @Test
    void continueIsDisabledWhenThePlatformHasNoCacheDirectory() {
        platformCacheDirectory = null;
        assertNull(continueItem(new Menu.MainMenu()).action);
    }

    @Test
    void continueOpensTheCachedFileOnceOneIsLoaded() {
        platformCacheDirectory = cacheDirectory.resolve("not-yet-created").toString();
        Menu.MainMenu menu = new Menu.MainMenu();
        assertNull(continueItem(menu).action);
        FileManagement.updateTestFileCache(CACHED_FILE);
        LoadIxAction resume = assertInstanceOf(LoadIxAction.class, continueItem(menu).action);
        assertEquals(CACHED_FILE, resume.fileName);
    }

    @Test
    void thePlatformCacheDirectoryIsOutsideTheSourceTree() {
        Path sourceTree = Path.of("").toAbsolutePath();
        Path resolved = Path.of(suitePlatform.appCacheDirectory()).toAbsolutePath();
        assertFalse(resolved.startsWith(sourceTree), resolved + " is under " + sourceTree);
        assertTrue(resolved.endsWith("ixdar") || resolved.endsWith("Ixdar"), resolved.toString());
    }

    @Test
    void loadingAFileWritesOnlyInsideTheCacheDirectory() throws IOException {
        long writesBefore = newestSourceModification();
        FileManagement.updateTestFileCache(CACHED_FILE);
        assertEquals(writesBefore, newestSourceModification());
        assertEquals(CACHED_FILE, FileManagement.getTestFileCache());
    }

    /**
     * Latest modification time of any file under the module's {@code src} and {@code test}
     * folders.
     *
     * @return the newest last-modified time in epoch milliseconds
     * @throws IOException if a folder cannot be walked
     */
    private static long newestSourceModification() throws IOException {
        long newest = 0L;
        for (String folder : new String[] {"src", "test"}) {
            try (Stream<Path> files = Files.walk(Path.of(folder))) {
                newest = Math.max(newest, files.mapToLong(path -> path.toFile().lastModified()).max().orElse(0L));
            }
        }
        return newest;
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
