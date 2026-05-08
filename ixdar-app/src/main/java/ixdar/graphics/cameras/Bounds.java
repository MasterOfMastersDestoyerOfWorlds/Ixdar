package ixdar.graphics.cameras;

import java.util.function.Consumer;

import org.joml.Vector2f;

public class Bounds {
    public float offsetX;
    public float offsetY;
    public float viewWidth;
    public float viewHeight;
    public Consumer<Bounds> recalculator;
    public String id;

    /**
     * TODO: document {@code Bounds}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     * @param id TODO: describe
     */
    public Bounds(float x, float y, float width, float height, String id) {

        offsetX = x;
        offsetY = y;
        viewWidth = width;
        viewHeight = height;
        this.id = id;
    }

    /**
     * TODO: document {@code Bounds}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     * @param recalculator TODO: describe
     * @param id TODO: describe
     */
    public Bounds(float x, float y, float width, float height, Consumer<Bounds> recalculator, String id) {
        this(x, y, width, height, id);
        this.recalculator = recalculator;
    }

    /**
     * TODO: document {@code update}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     */
    public void update(float x, float y, float width, float height) {
        offsetX = x;
        offsetY = y;
        viewWidth = width;
        viewHeight = height;
    }

    /**
     * TODO: document {@code update}.
     *
     * @param viewBounds TODO: describe
     */
    public void update(Bounds viewBounds) {
        offsetX = viewBounds.offsetX;
        offsetY = viewBounds.offsetY;
        viewWidth = viewBounds.viewWidth;
        viewHeight = viewBounds.viewHeight;
    }

    /**
     * TODO: document {@code contains}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     * @return TODO: describe
     */
    public boolean contains(float x, float y) {
        boolean inViewRightBound = x < viewWidth + offsetX;
        boolean inViewLeftBound = x > offsetX;
        boolean inViewLowerBound = y > offsetY;
        boolean inViewUpperBound = y < viewHeight + offsetY;
        return inViewLeftBound && inViewRightBound && inViewLowerBound && inViewUpperBound;
    }

    /**
     * TODO: document {@code contains}.
     *
     * @param pA TODO: describe
     * @return TODO: describe
     */
    public boolean contains(Vector2f pA) {
        return contains(pA.x, pA.y);
    }

    /**
     * TODO: document {@code recalc}.
     */
    public void recalc() {
        if (recalculator != null) {
            recalculator.accept(this);
        }
    }

    /**
     * TODO: document {@code setUpdateCallback}.
     *
     * @param recalculator TODO: describe
     */
    public void setUpdateCallback(Consumer<Bounds> recalculator) {
        this.recalculator = recalculator;
    }
}
