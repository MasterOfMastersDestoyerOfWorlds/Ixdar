package ixdar.platform.input;

/**
 * A screen rectangle subscribed to a {@link PointerDispatcher}, asked before the trap: while claimed, the wheel
 * or a left press, drag and release over it are its alone. Positions are framebuffer pixels, y up.
 */
public interface PointerRegion {

    /**
     * Called once per frame while a queued scroll delta lands inside the region and the region
     * does not {@link #claimsWheel claim the raw delta}.
     *
     * @param scrollUp true when the wheel rolled "up" (negative delta)
     * @param deltaSeconds frame delta in seconds, scaled by {@link MouseTrap#SCROLL_SPEED_SCALE}
     */
    void onScroll(boolean scrollUp, double deltaSeconds);

    /**
     * Whether the region takes the raw scroll delta through {@link #onScrollDelta} right now, so
     * the wheel over it scrolls it rather than doing what the trap does with the wheel.
     *
     * @return false unless the region opts in
     */
    default boolean claimsWheel() {
        return false;
    }

    /**
     * Scroll by the raw platform delta summed since the last frame, each delta delivered once and
     * never rounded; only called while {@link #claimsWheel} holds.
     *
     * @param delta summed scroll delta, 1.0 per wheel notch, negative when the wheel rolled toward
     *              the user
     */
    default void onScrollDelta(double delta) {
    }

    /**
     * Whether a left press over the region is its own right now, so the press, its drag and its
     * release reach only {@link #onPress}, {@link #onDrag} and {@link #onRelease}.
     *
     * @return false unless the region opts in
     */
    default boolean claimsPointer() {
        return false;
    }

    /**
     * A left press the region claimed.
     *
     * @param x framebuffer x of the press
     * @param y framebuffer y of the press, y up
     */
    default void onPress(float x, float y) {
    }

    /**
     * The cursor moved with the claimed press still held, wherever it now is.
     *
     * @param x framebuffer x of the cursor
     * @param y framebuffer y of the cursor, y up
     */
    default void onDrag(float x, float y) {
    }

    /**
     * The claimed press was released.
     *
     * @param x framebuffer x of the release
     * @param y framebuffer y of the release, y up
     * @param click true when the cursor stayed within {@link MouseTrap#CLICK_DRAG_THRESHOLD_PX}
     *              of the press, so the press and release make a click
     */
    default void onRelease(float x, float y, boolean click) {
    }
}
