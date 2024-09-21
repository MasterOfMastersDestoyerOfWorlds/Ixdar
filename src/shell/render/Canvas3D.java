package shell.render;

import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import javax.imageio.ImageIO;
import javax.swing.JFrame;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.awt.AWTGLCanvas;
import org.lwjgl.stb.STBImage;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import shell.cameras.Camera3D;
import shell.render.lights.DirectionalLight;
import shell.render.lights.PointLight;
import shell.render.lights.SpotLight;
import shell.ui.input.keys.KeyGuy;
import shell.ui.input.mouse.MouseTrap;

import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGR;
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

public class Canvas3D extends AWTGLCanvas {

    private static final long serialVersionUID = 1L;
    float vertices[] = {
            // positions // normals // texture coords
            -0.5f, -0.5f, -0.5f, 0.0f, 0.0f, -1.0f, 0.0f, 0.0f,
            0.5f, -0.5f, -0.5f, 0.0f, 0.0f, -1.0f, 1.0f, 0.0f,
            0.5f, 0.5f, -0.5f, 0.0f, 0.0f, -1.0f, 1.0f, 1.0f,
            0.5f, 0.5f, -0.5f, 0.0f, 0.0f, -1.0f, 1.0f, 1.0f,
            -0.5f, 0.5f, -0.5f, 0.0f, 0.0f, -1.0f, 0.0f, 1.0f,
            -0.5f, -0.5f, -0.5f, 0.0f, 0.0f, -1.0f, 0.0f, 0.0f,

            -0.5f, -0.5f, 0.5f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f,
            0.5f, -0.5f, 0.5f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f,
            0.5f, 0.5f, 0.5f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            0.5f, 0.5f, 0.5f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            -0.5f, 0.5f, 0.5f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f,
            -0.5f, -0.5f, 0.5f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f,

            -0.5f, 0.5f, 0.5f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f,
            -0.5f, 0.5f, -0.5f, -1.0f, 0.0f, 0.0f, 1.0f, 1.0f,
            -0.5f, -0.5f, -0.5f, -1.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            -0.5f, -0.5f, -0.5f, -1.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            -0.5f, -0.5f, 0.5f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            -0.5f, 0.5f, 0.5f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f,

            0.5f, 0.5f, 0.5f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f,
            0.5f, 0.5f, -0.5f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f,
            0.5f, -0.5f, -0.5f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            0.5f, -0.5f, -0.5f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            0.5f, -0.5f, 0.5f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.5f, 0.5f, 0.5f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f,

            -0.5f, -0.5f, -0.5f, 0.0f, -1.0f, 0.0f, 0.0f, 1.0f,
            0.5f, -0.5f, -0.5f, 0.0f, -1.0f, 0.0f, 1.0f, 1.0f,
            0.5f, -0.5f, 0.5f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f,
            0.5f, -0.5f, 0.5f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f,
            -0.5f, -0.5f, 0.5f, 0.0f, -1.0f, 0.0f, 0.0f, 0.0f,
            -0.5f, -0.5f, -0.5f, 0.0f, -1.0f, 0.0f, 0.0f, 1.0f,

            -0.5f, 0.5f, -0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f,
            0.5f, 0.5f, -0.5f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f,
            0.5f, 0.5f, 0.5f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f,
            0.5f, 0.5f, 0.5f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f,
            -0.5f, 0.5f, 0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f,
            -0.5f, 0.5f, -0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f
    };

    Vector3f[] cubePositions = {
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

    public static int width = 600;
    public static int height = 600;
    Vector3f lightColor = new Vector3f(1, 1, 1);
    DirectionalLight dirLight = new DirectionalLight(new Vector3f(-0.2f, -1.0f, -0.3f),
            new Vector3f(0.5f, 0.5f, 0.5f));

    SpotLight spotLight;

    PointLight pointLights[] = {
            new PointLight(new Vector3f(0.7f, 0.2f, 2.0f), lightColor, 50f),
            new PointLight(new Vector3f(2.3f, -3.3f, -4.0f), lightColor, 50f),
            new PointLight(new Vector3f(-4.0f, 2.0f, -12.0f), lightColor, 50f),
            new PointLight(new Vector3f(0.0f, 0.0f, -3.0f), lightColor, 50f)
    };

    IntBuffer VAO, VBO, lightVAO;
    Shader shader, lightingShader;
    int diffuseMap, specularMap;
    private Camera3D camera;
    private MouseTrap mouseTrap;
    public static int SIZE_FLOAT = 4;
    private JFrame frame;
    private KeyGuy keyGuy;
    private boolean printScreen = false;
    private File screenShotFile;
    protected int framebufferWidth;
    protected int framebufferHeight;

    public Canvas3D(Camera3D camera, MouseTrap mouseTrap, JFrame frame) {
        super();
        this.camera = camera;
        this.mouseTrap = mouseTrap;
        this.frame = frame;
        spotLight = new SpotLight(camera.position, camera.front,
                new Vector3f(1.0f, 1.0f, 1.0f), 12.5f, 15f, 50f);
    }

    public void setKeyGuy(KeyGuy keyGuy) {
        this.keyGuy = keyGuy;
    }

    @Override
    public void initGL() {

        this.addMouseMotionListener(mouseTrap);
        this.addMouseListener(mouseTrap);
        this.addMouseWheelListener(mouseTrap);
        System.out.println("OpenGL version: " + effective.majorVersion + "." + effective.minorVersion
                + " (Profile: " + effective.profile + ")");
        createCapabilities();
        glClearColor(0.3f, 0.4f, 0.5f, 1);

        shader = new Shader("shader.vs", "shader.fs");
        lightingShader = new Shader("light_shader.vs", "light_shader.fs");

        VBO = BufferUtils.createIntBuffer(1);
        glGenBuffers(VBO);
        VAO = BufferUtils.createIntBuffer(1);
        glGenVertexArrays(VAO);

        glBindVertexArray(VAO.get(0));
        glBindBuffer(GL_ARRAY_BUFFER, VBO.get(0));
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 8 * SIZE_FLOAT, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 8 * SIZE_FLOAT, 3 * SIZE_FLOAT);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 8 * SIZE_FLOAT, 6 * SIZE_FLOAT);
        glEnableVertexAttribArray(2);

        diffuseMap = loadTexture("container2.png");

        specularMap = loadTexture("container2_specular.png");

        lightVAO = BufferUtils.createIntBuffer(1);
        glGenVertexArrays(lightVAO);
        glBindVertexArray(lightVAO.get(0));
        // we only need to bind to the VBO, the container's VBO's data already contains
        // the data.
        glBindBuffer(GL_ARRAY_BUFFER, VBO.get(0));
        // set the vertex attribute
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 8 * SIZE_FLOAT, 0);
        glEnableVertexAttribArray(0);

        glEnable(GL_DEPTH_TEST);

        java.awt.geom.AffineTransform t = this.getGraphicsConfiguration().getDefaultTransform();
        float sx = (float) t.getScaleX(), sy = (float) t.getScaleY();
        this.framebufferWidth = (int) (getWidth() * sx);
        this.framebufferHeight = (int) (getHeight() * sy);
        width = this.framebufferWidth;
        height = this.framebufferHeight;
        this.getSize();
        glViewport(0, 0, (int) width, (int) height);
        mouseTrap.setCanvas(this);
        mouseTrap.captureMouse(true);

    }

    private final ComponentAdapter listener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            java.awt.geom.AffineTransform t = Canvas3D.this.getGraphicsConfiguration().getDefaultTransform();
            float sx = (float) t.getScaleX(), sy = (float) t.getScaleY();
            Canvas3D.this.framebufferWidth = (int) (getWidth() * sx);
            Canvas3D.this.framebufferHeight = (int) (getHeight() * sy);
        }
    };

    @Override
    public void paintGL() {
        if (this.hasFocus()) {
            frame.requestFocus();
        }
        keyGuy.paintUpdate(1.0);
        mouseTrap.paintUpdate(1.0);

        glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        shader.use();

        camera.updateViewFirstPerson();

        Vector3f lightPos = new Vector3f(1.2f, 1.0f, 2.0f);

        // be sure to activate shader when setting uniforms/drawing objects
        shader.use();

        shader.setInt("material.diffuse", 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, diffuseMap);
        shader.setInt("material.specular", 1);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, specularMap);
        shader.setFloat("material.shininess", 32.0f);

        shader.setVec3("lightColor", lightColor);
        shader.setVec3("", lightPos);
        shader.setVec3("viewPos", camera.position);

        for (int i = 0; i < 4; i++) {
            pointLights[i].setShaderInfo(shader, i);
        }
        dirLight.setShaderInfo(shader, 0);
        spotLight.setShaderInfo(shader, 0);
        // view/projection transformations
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(camera.fov),
                ((float) width) / ((float) height), 0.1f, 100.0f);
        shader.setMat4("projection", projection);
        shader.setMat4("view", camera.view);

        glBindVertexArray(VAO.get(0));
        for (int i = 0; i < 10; i++) {
            Matrix4f model = new Matrix4f();
            model.translate(cubePositions[i]);
            float angle = 20.0f * i;
            model.rotate((float) Math.toRadians(angle), new Vector3f(1.0f, 0.3f, 0.5f));
            shader.setMat4("model", model);

            glDrawArrays(GL_TRIANGLES, 0, 36);
        }

        // also draw the lamp object
        lightingShader.use();
        lightingShader.setMat4("projection", projection);
        lightingShader.setMat4("view", camera.view);
        for (int i = 0; i < pointLights.length; i++) {
            Matrix4f model = new Matrix4f().translate(pointLights[i].position).scale(0.2f);
            lightingShader.setVec3("lightColor", pointLights[i].diffuse);
            lightingShader.setMat4("model", model);

            glBindVertexArray(lightVAO.get(0));
            glDrawArrays(GL_TRIANGLES, 0, 36);

        }
        Clock.frameRendered();
        if (printScreen) {
            printScreen = false;
            printScreen(screenShotFile);
        }
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

    public void printScreen(String fileName) {
        printScreen = true;
        screenShotFile = new File(fileName);
    }

    public void printScreen(File outputfile) {
        int width = framebufferWidth;
        int height = framebufferHeight;
        int[] pixels = new int[width * height];
        int bindex;
        // allocate space for RBG pixels

        ByteBuffer fb = MemoryUtil.memAlloc(width * height * 4);

        // grab a copy of the current frame contents as RGBA
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, fb);
        MemoryUtil.memFree(fb);

        BufferedImage imageIn = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        // convert RGB data in ByteBuffer to integer array
        for (int i = 0; i < pixels.length; i++) {
            bindex = i * 4;
            pixels[i] = ((fb.get(bindex) << 16)) +
                    ((fb.get(bindex + 1) << 8)) +
                    ((fb.get(bindex + 2) << 0));
        }
        // Allocate colored pixel to buffered Image
        imageIn.setRGB(0, 0, width, height, pixels, 0, width);

        // Creating the transformation direction (horizontal)
        AffineTransform at = AffineTransform.getScaleInstance(1, -1);
        at.translate(0, -imageIn.getHeight(null));

        // Applying transformation
        AffineTransformOp opRotated = new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
        BufferedImage imageOut = opRotated.filter(imageIn, null);

        try {
            ImageIO.write(imageOut, "png", outputfile);
        } catch (Exception e) {
            System.out.println("ScreenShot() exception: " + e);
        }
    }

}