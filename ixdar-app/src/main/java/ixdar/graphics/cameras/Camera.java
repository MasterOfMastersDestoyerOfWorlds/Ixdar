package ixdar.graphics.cameras;

import org.joml.Vector2f;

import ixdar.geometry.point.PointSet;
import ixdar.platform.input.MouseTrap;

public interface Camera extends MouseTrap.ScrollHandler {

    /**
     * TODO: document {@code reset}.
     */
    void reset();

    /**
     * TODO: document {@code move}.
     *
     * @param direction TODO: describe
     */
    void move(Direction direction);

    /**
     * TODO: document {@code setShiftMod}.
     *
     * @param sHIFT_MOD TODO: describe
     */
    void setShiftMod(float sHIFT_MOD);

    /**
     * TODO: document {@code drag}.
     *
     * @param d TODO: describe
     * @param e TODO: describe
     */
    void drag(float d, float e);

    /**
     * TODO: document {@code mouseMove}.
     *
     * @param lastX TODO: describe
     * @param lastY TODO: describe
     * @param x TODO: describe
     * @param y TODO: describe
     */
    void mouseMove(float lastX, float lastY, float x, float y);

    /**
     * TODO: document {@code incZIndex}.
     */
    void incZIndex();

    /**
     * TODO: document {@code getZIndex}.
     *
     * @return TODO: describe
     */
    float getZIndex();

    /**
     * TODO: document {@code resetZIndex}.
     */
    void resetZIndex();

    /**
     * TODO: document {@code addZIndex}.
     *
     * @param diff TODO: describe
     */
    void addZIndex(float diff);

    /**
     * TODO: document {@code setZIndex}.
     *
     * @param camera TODO: describe
     */
    void setZIndex(Camera camera);

    /**
     * TODO: document {@code calculateCameraTransform}.
     *
     * @param ps TODO: describe
     */
    void calculateCameraTransform(PointSet ps);

    /**
     * transform from point space to screen space.
     *
     * @param x TODO: describe
     * @return pointSpaceX
     */
    float pointTransformX(float x);

    /**
     * transform from point space to screen space.
     *
     * @param y TODO: describe
     * @return pointSpaceY
     */
    float pointTransformY(float y);

    /**
     * transform from screen space to point space.
     *
     * @param normalizedPosX TODO: describe
     * @return pointSpaceX
     */
    float screenTransformX(float normalizedPosX);

    /**
     * transform from screen space to point space.
     *
     * @param normalizedPosY TODO: describe
     * @return pointSpaceY
     */
    float screenTransformY(float normalizedPosY);

    /**
     * TODO: document {@code getWidth}.
     *
     * @return TODO: describe
     */
    float getWidth();

    /**
     * TODO: document {@code getHeight}.
     *
     * @return TODO: describe
     */
    float getHeight();

    /**
     * TODO: document {@code getScreenOffsetX}.
     *
     * @return TODO: describe
     */
    float getScreenOffsetX();

    /**
     * TODO: document {@code getScreenOffsetY}.
     *
     * @return TODO: describe
     */
    float getScreenOffsetY();

    /**
     * TODO: document {@code getScreenWidthRatio}.
     *
     * @return TODO: describe
     */
    float getScreenWidthRatio();

    /**
     * TODO: document {@code getScreenHeightRatio}.
     *
     * @return TODO: describe
     */
    float getScreenHeightRatio();

    /**
     * TODO: document {@code getScaleFactor}.
     *
     * @return TODO: describe
     */
    float getScaleFactor();

    /**
     * TODO: document {@code getNormalizePosX}.
     *
     * @param xPos TODO: describe
     * @return TODO: describe
     */
    float getNormalizePosX(float xPos);

    /**
     * TODO: document {@code getNormalizePosY}.
     *
     * @param yPos TODO: describe
     * @return TODO: describe
     */
    float getNormalizePosY(float yPos);

    /**
     * TODO: document {@code getFarZIndex}.
     *
     * @return TODO: describe
     */
    float getFarZIndex();

    /**
     * TODO: document {@code decFarZIndex}.
     */
    void decFarZIndex();

    /**
     * TODO: document {@code getBounds}.
     *
     * @return TODO: describe
     */
    Bounds getBounds();

    /**
     * TODO: document {@code contains}.
     *
     * @param pB TODO: describe
     * @return TODO: describe
     */
    boolean contains(Vector2f pB);

    /**
     * TODO: document {@code updateView}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     */
    void updateView(int x, int y, int width, int height);

    /**
     * TODO: document {@code resetView}.
     */
    void resetView();

    public enum Direction {
        FORWARD, BACKWARD, LEFT, RIGHT

    }
}
