package shell.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.opengl.awt.AWTGLCanvas;
import org.lwjgl.opengl.awt.GLData;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import shell.cameras.Camera3D;
import shell.ui.KeyGuy;
import shell.ui.MouseTrap;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class AWTTest extends JFrame {

    public static int SIZE_FLOAT = 4;
    public static AWTTest frame;
    public static int width = 600;
    public static int height = 600;
    public static Camera3D camera;

    public static void main(String[] args) {
        frame = new AWTTest();
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setPreferredSize(new Dimension(600, 600));
        GLData data = new GLData();
        AWTGLCanvas canvas;
        camera = new Camera3D(new Vector3f(0, 0, 3.0f), -90.0f, 0.0f);
        KeyGuy keyGuy = new KeyGuy(camera);

        MouseTrap mouseTrap;
        frame.requestFocus();
        mouseTrap = new MouseTrap(null, frame, camera, true);
        mouseTrap.captureMouse(true);
        frame.add(canvas = new AWTGLCanvas(data) {

            private static final long serialVersionUID = 1L;
            float vertices[] = {
                    -0.5f, -0.5f, -0.5f, 0.0f, 0.0f,
                    0.5f, -0.5f, -0.5f, 1.0f, 0.0f,
                    0.5f, 0.5f, -0.5f, 1.0f, 1.0f,
                    0.5f, 0.5f, -0.5f, 1.0f, 1.0f,
                    -0.5f, 0.5f, -0.5f, 0.0f, 1.0f,
                    -0.5f, -0.5f, -0.5f, 0.0f, 0.0f,

                    -0.5f, -0.5f, 0.5f, 0.0f, 0.0f,
                    0.5f, -0.5f, 0.5f, 1.0f, 0.0f,
                    0.5f, 0.5f, 0.5f, 1.0f, 1.0f,
                    0.5f, 0.5f, 0.5f, 1.0f, 1.0f,
                    -0.5f, 0.5f, 0.5f, 0.0f, 1.0f,
                    -0.5f, -0.5f, 0.5f, 0.0f, 0.0f,

                    -0.5f, 0.5f, 0.5f, 1.0f, 0.0f,
                    -0.5f, 0.5f, -0.5f, 1.0f, 1.0f,
                    -0.5f, -0.5f, -0.5f, 0.0f, 1.0f,
                    -0.5f, -0.5f, -0.5f, 0.0f, 1.0f,
                    -0.5f, -0.5f, 0.5f, 0.0f, 0.0f,
                    -0.5f, 0.5f, 0.5f, 1.0f, 0.0f,

                    0.5f, 0.5f, 0.5f, 1.0f, 0.0f,
                    0.5f, 0.5f, -0.5f, 1.0f, 1.0f,
                    0.5f, -0.5f, -0.5f, 0.0f, 1.0f,
                    0.5f, -0.5f, -0.5f, 0.0f, 1.0f,
                    0.5f, -0.5f, 0.5f, 0.0f, 0.0f,
                    0.5f, 0.5f, 0.5f, 1.0f, 0.0f,

                    -0.5f, -0.5f, -0.5f, 0.0f, 1.0f,
                    0.5f, -0.5f, -0.5f, 1.0f, 1.0f,
                    0.5f, -0.5f, 0.5f, 1.0f, 0.0f,
                    0.5f, -0.5f, 0.5f, 1.0f, 0.0f,
                    -0.5f, -0.5f, 0.5f, 0.0f, 0.0f,
                    -0.5f, -0.5f, -0.5f, 0.0f, 1.0f,

                    -0.5f, 0.5f, -0.5f, 0.0f, 1.0f,
                    0.5f, 0.5f, -0.5f, 1.0f, 1.0f,
                    0.5f, 0.5f, 0.5f, 1.0f, 0.0f,
                    0.5f, 0.5f, 0.5f, 1.0f, 0.0f,
                    -0.5f, 0.5f, 0.5f, 0.0f, 0.0f,
                    -0.5f, 0.5f, -0.5f, 0.0f, 1.0f
            };

            Vector3f cubePositions[] = {
                    new Vector3f(0.0f, 0.0f, 0.0f),
                    new Vector3f(2.0f, 5.0f, -15.0f),
                    new Vector3f(-1.5f, -2.2f, -2.5f),
                    new Vector3f(-3.8f, -2.0f, -12.3f),
                    new Vector3f(2.4f, -0.4f, -3.5f),
                    new Vector3f(-1.7f, 3.0f, -7.5f),
                    new Vector3f(1.3f, -2.0f, -2.5f),
                    new Vector3f(1.5f, 2.0f, -2.5f),
                    new Vector3f(1.5f, 0.2f, -1.5f),
                    new Vector3f(-1.3f, 1.0f, -1.5f)
            };

            IntBuffer VAO, VBO;
            Shader shader;
            int texture, texture2;

            @Override
            public void initGL() {

                frame.addKeyListener(keyGuy);
                frame.addMouseListener(mouseTrap);
                this.addMouseMotionListener(mouseTrap);
                this.addMouseListener(mouseTrap);
                this.addMouseWheelListener(mouseTrap);
                System.out.println("OpenGL version: " + effective.majorVersion + "." + effective.minorVersion
                        + " (Profile: " + effective.profile + ")");
                createCapabilities();
                glClearColor(0.3f, 0.4f, 0.5f, 1);

                shader = new Shader("3.3.shader.vs", "3.3.shader.fs");
                shader = new Shader("light_shader.vs", "light_shader.fs");

                VBO = BufferUtils.createIntBuffer(1);
                glGenBuffers(VBO);
                VAO = BufferUtils.createIntBuffer(1);
                glGenVertexArrays(VAO);

                glBindVertexArray(VAO.get(0));
                glBindBuffer(GL_ARRAY_BUFFER, VBO.get(0));
                glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

                glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * SIZE_FLOAT, 0);
                glEnableVertexAttribArray(0);

                glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * SIZE_FLOAT, 3 * SIZE_FLOAT);
                glEnableVertexAttribArray(1);

                texture = loadTexture("decal.png");
                texture2 = loadTexture("decalSmall.png");

                shader.use(); // don't forget to activate the shader before setting uniforms!
                shader.setInt("texture1", 0);
                shader.setInt("texture2", 1); // or with shader class

                glEnable(GL_DEPTH_TEST);
                mouseTrap.setCanvas(this);
                mouseTrap.captureMouse(true);

            }

            @Override
            public void paintGL() {
                if (this.hasFocus()) {
                    frame.requestFocus();
                }
                keyGuy.paintUpdate(1.0);
                mouseTrap.paintUpdate(1.0);
                glClearColor(0.2f, 0.3f, 0.3f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

                shader.use();
                if (frame.getWidth() != width || frame.getHeight() != height) {
                    width = frame.getWidth();
                    height = frame.getHeight();
                    glViewport(0, 0, width, height);
                }

                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, texture);

                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, texture2);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    camera.updateViewFirstPerson();
                    FloatBuffer view = camera.view
                            .get(stack.mallocFloat(16)); // make sure to initialize matrix to identity matrix first

                    FloatBuffer projection = new Matrix4f()
                            .perspective((float) Math.toRadians(camera.fov), getAspectRatio(), 0.1f, 100.0f)
                            .get(stack.mallocFloat(16));
                    // pass transformation matrices to the shader
                    shader.setMat4("projection", projection); // note: currently we set the projection matrix each
                                                              // frame, but since the projection matrix rarely changes
                                                              // it's often best practice to set it outside the main
                                                              // loop only once.
                    shader.setMat4("view", view);

                    // render boxes
                    glBindVertexArray(VAO.get(0));
                    for (int i = 0; i < 10; i++) {

                        float rads = Clock.spin((float) (Math.PI / 4)) + (float) Math.toRadians(20.0f * i);
                        FloatBuffer model = new Matrix4f().translate(cubePositions[i])
                                .rotate(rads, new Vector3f(1.0f, 0.3f, 0.5f))
                                .get(stack.mallocFloat(16));
                        shader.setMat4("model", model);

                        glDrawArrays(GL_TRIANGLES, 0, 36);
                    }

                }
                Clock.frameRendered();
                swapBuffers();
            }

            private int loadTexture(String resourceName) {
                STBImage.stbi_set_flip_vertically_on_load(true);

                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                IntBuffer w = BufferUtils.createIntBuffer(1);
                IntBuffer h = BufferUtils.createIntBuffer(1);
                IntBuffer channels = BufferUtils.createIntBuffer(1);
                File file = new File("res/" + resourceName);
                String filePath = file.getAbsolutePath();
                ByteBuffer buffer = STBImage.stbi_load(filePath, w, h, channels, 4);
                if (buffer == null) {
                    System.out.println("Can't load file " + resourceName + " " + STBImage.stbi_failure_reason());
                }
                int width = w.get(0);
                int height = h.get(0);

                int texture = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
                glGenerateMipmap(GL_TEXTURE_2D);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                glEnable(GL_BLEND);
                STBImage.stbi_image_free(buffer);
                return texture;
            }

        }, BorderLayout.CENTER);
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