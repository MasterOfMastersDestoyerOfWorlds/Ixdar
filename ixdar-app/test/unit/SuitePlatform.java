package unit;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import ixdar.numeric.sparse.chol.CholeskyBackend;
import ixdar.platform.AutomationBackend;
import ixdar.platform.Platforms;
import ixdar.platform.gl.lwjgl.LwjglGL;
import ixdar.platform.gl.lwjgl.LwjglPlatform;

/**
 * Before the first test class loads, installs the windowless desktop platform, without an
 * automation server, and starts the native solver loading in the background as the window does.
 */
public final class SuitePlatform implements LauncherSessionListener {

    /**
     * Install the desktop platform, less its automation server, as the test run opens.
     *
     * @param session the launcher session being opened
     */
    @Override
    public void launcherSessionOpened(LauncherSession session) {
        Platforms.init(new LwjglPlatform(0L) {
            @Override
            public AutomationBackend automation() {
                return null;
            }
        }, new LwjglGL());
        CholeskyBackend.preloadAsync();
    }
}
