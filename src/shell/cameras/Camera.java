package shell.cameras;

import java.awt.event.MouseEvent;

public interface Camera {

    void reset();

    void move(CameraMoveDirection direction);

    void setShiftMod(double sHIFT_MOD);

    void zoom(boolean b);

    void drag(double d, double e);

    void mouseMove(float lastX, float lastY,MouseEvent e);
}
