package ixdar.graphics.cameras;

import java.util.function.Consumer;

import org.joml.Vector2f;

/**
 * Axis-aligned screen-space rectangle (offset + size) used as a viewport region
 * for cameras. Carries an {@code id} for lookup and an optional
 * {@link Consumer} that re-derives the rectangle on demand (e.g. when the host
 * window resizes).
 */
public class Bounds {
    public float offsetX;
    public float offsetY;
    public float viewWidth;
    public float viewHeight;
    public Consumer<Bounds> recalculator;
    public String id;

    /**
     * Construct a bounds rectangle with no recalculator hook.
     *
     * @param x lower-left corner x in screen space
     * @param y lower-left corner y in screen space
     * @param width rectangle width
     * @param height rectangle height
     * @param id stable lookup key for this region
     */
    public Bounds(float x, float y, float width, float height, String id) {

        offsetX = x;
        offsetY = y;
        viewWidth = width;
        viewHeight = height;
        this.id = id;
    }

    /**
     * Construct a bounds rectangle with a recalculator hook invoked by
     * {@link #recalc()}.
     *
     * @param x lower-left corner x in screen space
     * @param y lower-left corner y in screen space
     * @param width rectangle width
     * @param height rectangle height
     * @param recalculator callback that mutates this rectangle when invoked
     * @param id stable lookup key for this region
     */
    public Bounds(float x, float y, float width, float height, Consumer<Bounds> recalculator, String id) {
        this(x, y, width, height, id);
        this.recalculator = recalculator;
    }

    /**
     * Overwrite offset and size in place.
     *
     * @param x new lower-left corner x
     * @param y new lower-left corner y
     * @param width new rectangle width
     * @param height new rectangle height
     */
    public void update(float x, float y, float width, float height) {
        offsetX = x;
        offsetY = y;
        viewWidth = width;
        viewHeight = height;
    }

    /**
     * Copy offset and size from another bounds (does not copy id or recalculator).
     *
     * @param viewBounds source rectangle to mirror
     */
    public void update(Bounds viewBounds) {
        offsetX = viewBounds.offsetX;
        offsetY = viewBounds.offsetY;
        viewWidth = viewBounds.viewWidth;
        viewHeight = viewBounds.viewHeight;
    }

    /**
     * Half-open rectangle containment test in screen space.
     *
     * @param x screen x to test
     * @param y screen y to test
     * @return {@code true} when {@code (x,y)} lies strictly inside this rectangle
     */
    public boolean contains(float x, float y) {
        boolean inViewRightBound = x < viewWidth + offsetX;
        boolean inViewLeftBound = x > offsetX;
        boolean inViewLowerBound = y > offsetY;
        boolean inViewUpperBound = y < viewHeight + offsetY;
        return inViewLeftBound && inViewRightBound && inViewLowerBound && inViewUpperBound;
    }

    /**
     * Vector overload of {@link #contains(float, float)}.
     *
     * @param pA screen-space point
     * @return {@code true} when {@code pA} lies strictly inside this rectangle
     */
    public boolean contains(Vector2f pA) {
        return contains(pA.x, pA.y);
    }

    /**
     * Invoke the registered recalculator (if any) so it can rewrite offset
     * and size from external state. No-op when no callback is installed.
     */
    public void recalc() {
        if (recalculator != null) {
            recalculator.accept(this);
        }
    }

    /**
     * Install or replace the recalculator hook used by {@link #recalc()}.
     *
     * @param recalculator callback that mutates this rectangle, or {@code null} to clear
     */
    public void setUpdateCallback(Consumer<Bounds> recalculator) {
        this.recalculator = recalculator;
    }
}
