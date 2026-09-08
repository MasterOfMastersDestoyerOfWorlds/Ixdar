package ixdar.platform.automation;

import ixdar.graphics.render.Clock;

/**
 * Blocks an automation input route until the scene has drawn the frames its effect needs, so a
 * caller can screenshot straight after a click without guessing a sleep.
 */
public final class InputSettle {

    /** Frames waited for when a request does not ask for a different number. */
    public static final int DEFAULT_FRAMES = 2;

    /** Longest a settle waits before giving up on a scene that has stopped drawing. */
    public static final long TIMEOUT_MILLIS = 5000L;

    private static final long POLL_MILLIS = 1L;

    private InputSettle() {
    }

    /**
     * Wait until the renderer has completed {@code frames} frames after the one the input was
     * applied during. The applying frame itself does not count: it was drawn before the input ran.
     *
     * @param appliedDuringFrame {@link Clock#framesRendered()} sampled on the render thread while
     *        the input was being delivered
     * @param frames how many drawn frames past the input to wait for; zero or less returns at once
     * @return {@code true} when the frames arrived, {@code false} on the
     *         {@value #TIMEOUT_MILLIS}ms timeout
     */
    public static boolean awaitFrames(long appliedDuringFrame, int frames) {
        if (frames <= 0) {
            return true;
        }
        long target = appliedDuringFrame + 1 + frames;
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (Clock.framesRendered() < target) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
