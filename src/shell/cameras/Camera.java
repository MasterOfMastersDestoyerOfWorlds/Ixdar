package shell.cameras;

import java.awt.event.MouseEvent;

public interface Camera {

    void reset();

    void move(Direction direction);

    void setShiftMod(float sHIFT_MOD);

    void zoom(boolean b);

    void drag(float d, float e);

    void mouseMove(float lastX, float lastY, MouseEvent e);

    public enum Direction {
        FORWARD, BACKWARD, LEFT, RIGHT

    }

    void incZIndex();

    float getZIndex();

    void resetZIndex();

    void addZIndex(float diff);

    void setZIndex(Camera camera);
}
