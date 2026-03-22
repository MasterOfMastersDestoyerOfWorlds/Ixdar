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

    public Bounds(float x, float y, float width, float height, String id) {

        offsetX = x;
        offsetY = y;
        viewWidth = width;
        viewHeight = height;
        this.id = id;
    }

    public Bounds(float x, float y, float width, float height, Consumer<Bounds> recalculator, String id) {
        this(x, y, width, height, id);
        this.recalculator = recalculator;
    }

    public void update(float x, float y, float width, float height) {
        offsetX = x;
        offsetY = y;
        viewWidth = width;
        viewHeight = height;
    }

    public void update(Bounds viewBounds) {
        offsetX = viewBounds.offsetX;
        offsetY = viewBounds.offsetY;
        viewWidth = viewBounds.viewWidth;
        viewHeight = viewBounds.viewHeight;
    }

    public boolean contains(float x, float y) {
        boolean inViewRightBound = x < viewWidth + offsetX;
        boolean inViewLeftBound = x > offsetX;
        boolean inViewLowerBound = y > offsetY;
        boolean inViewUpperBound = y < viewHeight + offsetY;
        return inViewLeftBound && inViewRightBound && inViewLowerBound && inViewUpperBound;
    }

    public boolean contains(Vector2f pA) {
        return contains(pA.x, pA.y);
    }

    public void recalc() {
        if (recalculator != null) {
            recalculator.accept(this);
        }
    }

    public void setUpdateCallback(Consumer<Bounds> recalculator) {
        this.recalculator = recalculator;
    }
}
