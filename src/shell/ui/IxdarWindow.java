package shell.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.awt.GLData;

import shell.cameras.Camera2D;
import shell.cameras.Camera3D;
import shell.ui.input.keys.KeyGuy;
import shell.ui.input.mouse.MouseTrap;

public class IxdarWindow extends JFrame {

    public static IxdarWindow frame;
    public static Camera3D camera;
    public static Camera2D camera2D;

    public static void main(String[] args) {
        frame = new IxdarWindow();
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setBackground(java.awt.Color.darkGray);
        frame.setName("Ixdar");

        ImageIcon img = new ImageIcon("res/decalSmall.png");
        frame.setIconImage(img.getImage());
        camera = new Camera3D(new Vector3f(0, 0, 3.0f), -90.0f, 0.0f);
        camera2D = new Camera2D(600, 600, 0.9f, 0, 0, null);
        MouseTrap mouseTrap = new MouseTrap(null, frame, camera, false);
        GLData context = new GLData();
        context.stencilSize = 8;
        Canvas3D canvas = new Canvas3D(context, camera, camera2D, mouseTrap, frame);
        frame.add(canvas, BorderLayout.CENTER);
        frame.getContentPane().setPreferredSize(new Dimension(750, 750));

        KeyGuy keyGuy = new KeyGuy(camera, canvas);
        canvas.setKeys(keyGuy);
        frame.requestFocus();
        // mouseTrap.captureMouse(false);
        frame.addKeyListener(keyGuy);
        frame.addMouseListener(mouseTrap);
        frame.addMouseMotionListener(mouseTrap);
        frame.addMouseWheelListener(mouseTrap);
        frame.setVisible(true);
        frame.transferFocus();
        frame.pack();
        frame.repaint();
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