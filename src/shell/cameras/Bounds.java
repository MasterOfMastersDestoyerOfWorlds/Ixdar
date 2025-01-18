package shell.cameras;

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
        boolean inViewLowerBound = y > offsetY;
        boolean inViewUpperBound = y < viewHeight + offsetY;
        return inViewLeftBound && inViewRightBound && inViewLowerBound && inViewUpperBound;
    }
}
