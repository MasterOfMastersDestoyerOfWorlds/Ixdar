package shell.cameras;

import shell.Main;

public class Bounds {
    public float offsetX;
    public float offsetY;
    public float viewWidth;
    public float viewHeight;

    public Bounds(float x, float y, float width, float height) {

        offsetX = x;
        offsetY = y;
        viewWidth = width;
        viewHeight = height;
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
        boolean inViewLowerBound = y > Main.MAIN_VIEW_OFFSET_Y;
        boolean inViewUpperBound = y < Main.MAIN_VIEW_HEIGHT + Main.MAIN_VIEW_OFFSET_Y;
        return inViewLeftBound && inViewRightBound && inViewLowerBound
                && inViewUpperBound;
    }
}
