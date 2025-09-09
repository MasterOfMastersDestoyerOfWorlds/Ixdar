package shell.cameras;

import org.joml.Vector2f;

import shell.PointSet;
import shell.platform.input.MouseTrap;

public interface Camera extends MouseTrap.ScrollHandler {

    void reset();

    void move(Direction direction);

    void setShiftMod(float sHIFT_MOD);

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

    void calculateCameraTransform(PointSet ps);

    /**
     * transform from point space to screen space
     * 
     * @param normalizedPosX
     * @return pointSpaceX
     */
    float pointTransformX(float x);

    /**
     * transform from point space to screen space
     * 
     * @param normalizedPosY
     * @return pointSpaceY
     */
    float pointTransformY(float y);

    /**
     * transform from screen space to point space
     * 
     * @param normalizedPosX
     * @return pointSpaceX
     */
    float screenTransformX(float normalizedPosX);

    /**
     * transform from screen space to point space
     * 
     * @param normalizedPosY
     * @return pointSpaceY
     */
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

    float getFarZIndex();

    void decFarZIndex();

    Bounds getBounds();

    boolean contains(Vector2f pB);

    void updateView(int x, int y, int width, int height);

    void resetView();
}
