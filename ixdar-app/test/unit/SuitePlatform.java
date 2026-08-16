package unit;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import ixdar.platform.Platforms;
import ixdar.platform.gl.lwjgl.LwjglGL;
import ixdar.platform.gl.lwjgl.LwjglPlatform;

/**
 * Installs the desktop platform once for the whole test run.
 *
 * <p>Capabilities that differ between desktop and browser — the Assimp model importer, the PARDISO
 * Cholesky backend — are reached through {@link ixdar.platform.gl.Platform}, so a suite with no
 * platform installed would silently take every fallback path and stop testing what ships.
 *
 * <p>Registered through {@code META-INF/services}, so it runs before the first test class loads.
 * Neither the platform nor the GL adapter opens a window here: {@code LwjglPlatform} only stores the
 * window handle, and nothing in the unit suite renders.
 */
public final class SuitePlatform implements LauncherSessionListener {

    /**
     * Install the desktop platform as the test run opens.
     *
     * @param session the launcher session being opened
     */
    @Override
    public void launcherSessionOpened(LauncherSession session) {
        Platforms.init(new LwjglPlatform(0L), new LwjglGL());
    }
}
