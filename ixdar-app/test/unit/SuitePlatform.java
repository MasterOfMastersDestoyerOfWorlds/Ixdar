package unit;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import ixdar.platform.Platforms;
import ixdar.platform.automation.AutomationBackend;
import ixdar.platform.gl.lwjgl.LwjglGL;
import ixdar.platform.gl.lwjgl.LwjglPlatform;

/**
 * Installs the desktop platform, registered through {@code META-INF/services}, before the first
 * test class loads, so tests take the desktop backends that ship. It opens no window, so it has no
 * automation server: there is no input to record, and a test must not bind a port.
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
    }
}
