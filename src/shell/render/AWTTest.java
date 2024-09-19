package shell.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.opengl.awt.AWTGLCanvas;
import org.lwjgl.opengl.awt.GLData;
import org.lwjgl.openvr.Texture;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.net.URL;
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
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class AWTTest {

    public static int SIZE_FLOAT = 4;

    public static void main(String[] args) {
        JFrame frame = new JFrame("AWT test");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setPreferredSize(new Dimension(600, 600));
        GLData data = new GLData();
        AWTGLCanvas canvas;
        frame.add(canvas = new AWTGLCanvas(data) {
            private static final long serialVersionUID = 1L;

            float vertices[] = {
                    // positions // colors // texture coords
                    0.5f, 0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, // top right
                    0.5f, -0.5f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, // bottom right
                    -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, // bottom left
                    -0.5f, 0.5f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f // top left
            };
            int indices[] = { // note that we start from 0!
                    2, 1, 3,
                    0, 1, 3, // first triangle
            };

            IntBuffer VAO, VBO, EBO;
            Shader shader;
            int texture, texture2;

            @Override
            public void initGL() {
                System.out.println("OpenGL version: " + effective.majorVersion + "." + effective.minorVersion
                        + " (Profile: " + effective.profile + ")");
                createCapabilities();
                glClearColor(0.3f, 0.4f, 0.5f, 1);

                shader = new Shader("3.3.shader.vs", "3.3.shader.fs");

                VBO = BufferUtils.createIntBuffer(1);
                glGenBuffers(VBO);
                VAO = BufferUtils.createIntBuffer(1);
                glGenVertexArrays(VAO);

                glBindVertexArray(VAO.get(0));
                glBindBuffer(GL_ARRAY_BUFFER, VBO.get(0));
                glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

                EBO = BufferUtils.createIntBuffer(1);
                glGenBuffers(EBO);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, EBO.get(0));
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

                glVertexAttribPointer(0, 3, GL_FLOAT, false, 8 * SIZE_FLOAT, 0);
                glEnableVertexAttribArray(0);

                glVertexAttribPointer(1, 3, GL_FLOAT, false, 8 * SIZE_FLOAT, 3 * SIZE_FLOAT);
                glEnableVertexAttribArray(1);

                glVertexAttribPointer(2, 2, GL_FLOAT, false, 8 * SIZE_FLOAT, 6 * SIZE_FLOAT);
                glEnableVertexAttribArray(2);

                texture = loadTexture("decal.png");
                texture2 = loadTexture("decalSmall.png");

                shader.use(); // don't forget to activate the shader before setting uniforms!
                glUniform1i(glGetUniformLocation(shader.ID, "texture1"), 0); // set it manually
                shader.setInt("texture2", 1); // or with shader class

                // GLM trans = glm::mat4(1.0f);
                // trans = glm::translate(trans, glm::vec3(0.5f, -0.5f, 0.0f));
                // trans = glm::rotate(trans, (float)glfwGetTime(), glm::vec3(0.0f, 0.0f,
                // 1.0f));
            }

            @Override
            public void paintGL() {
                glClearColor(0.2f, 0.3f, 0.3f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);
                shader.use();

                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, texture);

                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, texture2);

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    float rads = (float) Math.abs(Math.PI / 4.0
                            * (Math.sin(5 * ((((double) System.currentTimeMillis()) / 1000.0))) + 1));
                    System.out.println(rads);
                    FloatBuffer fb = new Matrix4f()
                            .rotate(rads, new Vector3f(0.0f, 1.0f, 0.0f))
                            .get(stack.mallocFloat(16));

                    int transformLoc = glGetUniformLocation(shader.ID, "transform");
                    glUniformMatrix4fv(transformLoc, false, fb);
                }

                glBindVertexArray(VAO.get(0));
                glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
                swapBuffers();
            }

            private int loadTexture(String resourceName) {
                STBImage.stbi_set_flip_vertically_on_load(true);

                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
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
}