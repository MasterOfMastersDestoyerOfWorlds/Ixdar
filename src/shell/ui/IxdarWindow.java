package shell.ui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.awt.GLData;

import shell.cameras.Camera2D;
import shell.cameras.Camera3D;
import shell.render.Clock;
import shell.ui.input.keys.KeyGuy;
import shell.ui.input.mouse.MouseTrap;

public class IxdarWindow extends JFrame {

    public static IxdarWindow frame;
    public static Camera3D camera;
    public static Camera2D camera2D;
    private static Canvas3D canvas;
    private static MouseTrap mouseTrap;

    private static boolean init = true;

    public static void main(String[] args) {

        float start = Clock.time();

        camera = new Camera3D(new Vector3f(0, 0, 3.0f), -90.0f, 0.0f);
        camera2D = new Camera2D(600, 600, 1f, 0, 0, null);
        mouseTrap = new MouseTrap(null, frame, camera, false);

        GLData context = new GLData();
        context.stencilSize = 8;
        context.majorVersion = 4;
        context.minorVersion = 3;
        context.swapInterval = 0;
        canvas = new Canvas3D(context, camera, camera2D, mouseTrap);
        IxdarWindow.frame = new IxdarWindow();
        frame.setCursor(new Cursor(Cursor.CROSSHAIR_CURSOR));
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setBackground(java.awt.Color.darkGray);
        frame.setTitle("Ixdar");
        ImageIcon img = new ImageIcon("res/decalSmall.png");
        frame.setIconImage(img.getImage());
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
                if (init) {
                    init = false;
                    System.out.println("Window Creation" + (Clock.time() - start));
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