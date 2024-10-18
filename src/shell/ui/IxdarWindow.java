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
    public static Camera3D camera = new Camera3D(new Vector3f(0, 0, 3.0f), -90.0f, 0.0f);
    public static Camera2D camera2D;
    private static Canvas3D canvas;
    private static MouseTrap mouseTrap = new MouseTrap(null, frame, camera, false);
    private static boolean init = true;
    public static float startTime;

    public static void main(String[] args) {

        startTime = Clock.time();

        GLData context = new GLData();
        context.stencilSize = 8;
        context.majorVersion = 4;
        context.minorVersion = 3;
        context.swapInterval = 0;
        canvas = new Canvas3D(context, camera, mouseTrap);

        System.out.println("Canvas Setup: " + (Clock.time() - startTime));
        IxdarWindow.frame = new IxdarWindow();
        frame.setLayout(new BorderLayout());
        frame.setBackground(java.awt.Color.darkGray);

        frame.add(canvas, BorderLayout.CENTER);
        frame.getContentPane().setPreferredSize(new Dimension(750, 750));
        // mouseTrap.captureMouse(false);
        frame.setVisible(true);
        frame.pack();
        Runnable renderLoop = new Runnable() {
            @Override
            public void run() {
                if (!canvas.isValid()) {
                    GL.setCapabilities(null);
                    return;
                }
                if (init) {
                    init = false;
                    System.out.println("Window Creation: " + (Clock.time() - startTime));
                    Thread t1 = new Thread(new Runnable() {
                        @Override
                        public void run() {

                            frame.setCursor(new Cursor(Cursor.CROSSHAIR_CURSOR));
                            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                            
                            frame.setTitle("Ixdar");
                            ImageIcon img = new ImageIcon("res/decalSmall.png");
                            frame.setIconImage(img.getImage());
                            KeyGuy keyGuy = new KeyGuy(camera, canvas);
                            canvas.setKeys(keyGuy);
                            frame.transferFocus();
                            frame.addKeyListener(keyGuy);
                            frame.addMouseListener(mouseTrap);
                            frame.addMouseMotionListener(mouseTrap);
                            frame.addMouseWheelListener(mouseTrap);
                            canvas.addMouseMotionListener(mouseTrap);
                            canvas.addMouseListener(mouseTrap);
                            canvas.addMouseWheelListener(mouseTrap);
                        }
                    });
                    t1.start();
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