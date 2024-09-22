package shell.render;

import org.joml.Vector3f;
import org.lwjgl.opengl.*;

import shell.cameras.Camera3D;
import shell.ui.input.keys.KeyGuy;
import shell.ui.input.mouse.MouseTrap;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class AWTTest extends JFrame {

    public static AWTTest frame;
    public static Camera3D camera;

    public static void main(String[] args) {
        frame = new AWTTest();
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setPreferredSize(new Dimension(800, 800));
        camera = new Camera3D(new Vector3f(0, 0, 3.0f), -90.0f, 0.0f);
        MouseTrap mouseTrap = new MouseTrap(null, frame, camera, true);
        Canvas3D canvas = new Canvas3D(camera, mouseTrap, frame);
        KeyGuy keyGuy = new KeyGuy(camera, canvas);
        canvas.setKeyGuy(keyGuy);
        frame.requestFocus();
        mouseTrap.captureMouse(true);
        frame.add(canvas, BorderLayout.CENTER);
        frame.addKeyListener(keyGuy);
        frame.addMouseListener(mouseTrap);
        frame.addMouseMotionListener(mouseTrap);
        frame.addMouseWheelListener(mouseTrap);
        frame.pack();
        frame.setVisible(true);
        frame.transferFocus();
        Runnable renderLoop = new Runnable() {
            @Override
            public void run() {
                if (!canvas.isValid()) {
                    GL.setCapabilities(null);
                    return;
                }
                canvas.render();
                SwingUtilities.invokeLater(this);
            }
        };
        SwingUtilities.invokeLater(renderLoop);
    }

    public static float getAspectRatio() {
        return ((float) frame.getWidth()) / ((float) frame.getHeight());
    }

}