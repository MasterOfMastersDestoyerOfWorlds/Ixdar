package ixdar.platform.input;

/**
 * A screen rectangle subscribed to the scene's pointer dispatcher, asked before the mouse handler:
 * while claimed, the wheel or a left press, drag and release over it are its alone. Positions are
 * framebuffer pixels, y up.
 */
public interface PointerRegion {

    /**
     * Called once per frame while a queued scroll delta lands inside the region and the region
     * does not {@link #claimsWheel claim the raw delta}.
     *
     * @param scrollUp true when the wheel rolled "up" (negative delta)
     * @param deltaSeconds frame delta in seconds, scaled by the controllers' scroll speed scale
     */
    void onScroll(boolean scrollUp, double deltaSeconds);

    /**
     * A click of any button inside bounds the region is listed under among the dispatcher's click
     * regions; the first such region under the click takes it.
     *
     * @param button button index
     */
    default void onClick(int button) {
    }

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
     * Scroll by the raw platform deltas summed since the last frame, each delta delivered once and
     * never rounded; only called while {@link #claimsWheel} holds.
     *
     * @param deltaX summed horizontal delta, positive toward the left, from a tilting wheel or a
     *               two-finger sideways swipe
     * @param deltaY summed vertical delta, 1.0 per wheel notch, negative when the wheel rolled
     *               toward the user
     * @param x      framebuffer x of the cursor
     * @param y      framebuffer y of the cursor, y up
     * @param mods   modifier-key bitmask held, as {@link KeyNames} spells the bits
     */
    default void onScrollDelta(double deltaX, double deltaY, float x, float y, int mods) {
    }

    /**
     * Whether a left press at a point over the region is its own right now, so the press, its drag
     * and its release reach only {@link #onPress}, {@link #onDrag} and {@link #onRelease}.
     *
     * @param x framebuffer x of the press
     * @param y framebuffer y of the press, y up
     * @return false unless the region opts in
     */
    default boolean claimsPointer(float x, float y) {
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
     * @param click true when the cursor stayed within the mouse handler's click threshold
     *              of the press, so the press and release make a click
     */
    default void onRelease(float x, float y, boolean click) {
    }
}
