package shell.cameras;

public interface Camera {

    void reset();

    void move(Direction direction);

    void setShiftMod(float sHIFT_MOD);

    void zoom(boolean b);

    void drag(float d, float e);

    void mouseMove(float lastX, float lastY, float x, float y);

    public enum Direction {
        FORWARD, BACKWARD, LEFT, RIGHT

    }

    void incZIndex();

    float getZIndex();

    void resetZIndex();

    void addZIndex(float diff);

    void setZIndex(Camera camera);

    void calculateCameraTransform();

    float screenTransformX(float normalizedPosX);

    float screenTransformY(float normalizedPosY);

    float getWidth();

    float getHeight();

    float getScreenOffsetX();

    float getScreenOffsetY();

    float getScreenWidthRatio();

    float getScreenHeightRatio();
    float getScaleFactor();

    float getNormalizePosX(float xPos);
    float getNormalizePosY(float yPos);
}
