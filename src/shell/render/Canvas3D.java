package shell.render;

import static org.lwjgl.opengl.GL.createCapabilities;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;

import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import javax.swing.JFrame;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.awt.AWTGLCanvas;
import org.lwjgl.system.MemoryUtil;

import shell.cameras.Camera3D;
import shell.render.lights.DirectionalLight;
import shell.render.lights.PointLight;
import shell.render.lights.SpotLight;
import shell.ui.input.keys.KeyGuy;
import shell.ui.input.mouse.MouseTrap;
import shell.utils.Utils;
import shell.render.shaders.*;
import shell.render.text.*;

public class Canvas3D extends AWTGLCanvas {

    public static final long serialVersionUID = 1L;
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
    DiffuseShader shader;
    LightShader lightingShader;
    Texture diffuseMap;
    Texture specularMap;
    public Camera3D camera;
    public MouseTrap mouseTrap;
    public static int SIZE_FLOAT = 4;
    public JFrame frame;
    public KeyGuy keyGuy;
    public boolean printScreen = false;
    public File screenShotFile;
    protected int framebufferWidth;
    protected int framebufferHeight;
    public Font font;

    public VertexArrayObject vao;
    public VertexBufferObject vbo;
    public FontShader program;

    public int numVertices;
    public boolean drawing;
    public FloatBuffer verteciesBuff;
    public Font debugFont;
    public ShaderProgram fontShader;

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
        java.awt.geom.AffineTransform t = this.getGraphicsConfiguration().getDefaultTransform();
        float sx = (float) t.getScaleX(), sy = (float) t.getScaleY();
        this.framebufferWidth = (int) (getWidth() * sx);
        this.framebufferHeight = (int) (getHeight() * sy);
        width = this.framebufferWidth;
        height = this.framebufferHeight;
        this.addMouseMotionListener(mouseTrap);
        this.addMouseListener(mouseTrap);
        this.addMouseWheelListener(mouseTrap);
        System.out.println("OpenGL version: " + effective.majorVersion + "." + effective.minorVersion
                + " (Profile: " + effective.profile + ")");
        createCapabilities();
        glClearColor(0.3f, 0.4f, 0.5f, 1);
        VertexArrayObject vao = new VertexArrayObject();
        VertexBufferObject vbo = new VertexBufferObject();
        vao.bind();
        vbo.bind(GL_ARRAY_BUFFER);
        vbo.uploadData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        shader = new DiffuseShader("shader.vs", "shader.fs", vao, vbo);

        diffuseMap = Texture.loadTexture("container2.png");
        specularMap = Texture.loadTexture("container2_specular.png");

        VertexArrayObject lvao = new VertexArrayObject();
        lightingShader = new LightShader("light_shader.vs", "light_shader.fs", lvao, vbo);

        setupShaderProgram();
        // set the vertex attribute

        glEnable(GL_DEPTH_TEST);

        this.getSize();
        glViewport(0, 0, (int) width, (int) height);
        mouseTrap.setCanvas(this);
        mouseTrap.captureMouse(true);
        font = new Font();
        debugFont = new Font(12, false);

        verteciesBuff = MemoryUtil.memAllocFloat(4096);
    }

    public final ComponentAdapter listener = new ComponentAdapter() {
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
        shader.setTexture("material.diffuse", diffuseMap, GL_TEXTURE0, 0);
        shader.setTexture("material.specular", specularMap, GL_TEXTURE1, 1);
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
        shader.vao.bind();
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
        lightingShader.vao.bind();
        for (int i = 0; i < pointLights.length; i++) {
            Matrix4f model = new Matrix4f().translate(pointLights[i].position).scale(0.2f);
            lightingShader.setVec3("lightColor", pointLights[i].diffuse);
            lightingShader.setMat4("model", model);

            glDrawArrays(GL_TRIANGLES, 0, 36);

        }
        program.use();
        program.setTexture("texImage", debugFont.texture, GL_TEXTURE0, 0);
        debugFont.drawTextCentered(this, "FPS: " + (1 / Clock.deltaTime()), framebufferWidth / 2,
                framebufferHeight / 2, Color.CYAN);

        Clock.frameRendered();
        if (printScreen) {
            printScreen = false;
            printScreen(screenShotFile);
        }

        swapBuffers();
    }

    /** Setups the default shader program. */
    public void setupShaderProgram() {
        VertexArrayObject vao = new VertexArrayObject();
        vao.bind();

        /* Generate Vertex Buffer Object */
        VertexBufferObject vbo = new VertexBufferObject();
        vbo.bind(GL_ARRAY_BUFFER);

        /* Create FloatBuffer */
        verteciesBuff = MemoryUtil.memAllocFloat(4096);

        /* Upload null data to allocate storage for the VBO */
        long size = verteciesBuff.capacity() * Float.BYTES;
        vbo.uploadData(GL_ARRAY_BUFFER, size, GL_DYNAMIC_DRAW);

        /* Initialize variables */
        numVertices = 0;
        drawing = false;

        /* Create shader program */
        program = new FontShader("font.vs", "font.fs", vao, vbo, framebufferWidth, framebufferHeight);

    }

    public void printScreen(String fileName) {
        printScreen = true;
        screenShotFile = new File(fileName);
    }

    public void printScreen(File outputfile) {
        // allocate space for RBG pixels

        ByteBuffer fb = MemoryUtil.memAlloc(width * height * 4);
        // grab a copy of the current frame contents as RGBA
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, fb);
        Utils.snapByteBuffer(framebufferWidth, framebufferHeight, fb);
        MemoryUtil.memFree(fb);

    }

    /**
     * Begin rendering.
     */
    public void begin() {
        if (drawing) {
            throw new IllegalStateException("Renderer is already drawing!");
        }
        drawing = true;
        numVertices = 0;
    }

    /**
     * End rendering.
     */
    public void end() {
        if (!drawing) {
            throw new IllegalStateException("Renderer isn't drawing!");
        }
        drawing = false;
        flush();
    }

    public void flush() {
        if (numVertices > 0) {
            verteciesBuff.flip();

            if (program.vao != null) {
                program.vao.bind();
            } else {
                program.vbo.bind(GL_ARRAY_BUFFER);
            }
            program.use();

            /* Upload the new vertex data */
            program.vbo.bind(GL_ARRAY_BUFFER);
            program.vbo.uploadSubData(GL_ARRAY_BUFFER, 0, verteciesBuff);

            /* Draw batch */
            glDrawArrays(GL_TRIANGLES, 0, numVertices);

            /* Clear vertex data for next batch */
            verteciesBuff.clear();
            numVertices = 0;
        }
    }

    public int getTextWidth(CharSequence text) {
        return font.getWidth(text);
    }

    public int getTextHeight(CharSequence text) {
        return font.getHeight(text);
    }

    public int getDebugTextWidth(CharSequence text) {
        return debugFont.getWidth(text);
    }

    public int getDebugTextHeight(CharSequence text) {
        return debugFont.getHeight(text);
    }

    public void drawText(CharSequence text, float x, float y) {
        font.drawText(this, text, x, y);
    }

    public void drawDebugText(CharSequence text, float x, float y) {
        debugFont.drawText(this, text, x, y);
    }

    public void drawText(CharSequence text, float x, float y, Color c) {
        font.drawText(this, text, x, y, c);
    }

    public void drawDebugText(CharSequence text, float x, float y, Color c) {
        debugFont.drawText(this, text, x, y, c);
    }

    public void drawTexture(Texture texture, float x, float y) {
        drawTexture(texture, x, y, Color.WHITE);
    }

    /**
     * Draws the currently bound texture on specified coordinates and with
     * specified color.
     *
     * @param texture Used for getting width and height of the texture
     * @param x       X position of the texture
     * @param y       Y position of the texture
     * @param c       The color to use
     */
    public void drawTexture(Texture texture, float x, float y, Color c) {
        /* Vertex positions */
        float x1 = x;
        float y1 = y;
        float x2 = x1 + texture.getWidth();
        float y2 = y1 + texture.getHeight();

        /* Texture coordinates */
        float s1 = 0f;
        float t1 = 0f;
        float s2 = 1f;
        float t2 = 1f;

        drawTextureRegion(x1, y1, x2, y2, s1, t1, s2, t2, c);
    }

    /**
     * Draws a texture region with the currently bound texture on specified
     * coordinates.
     *
     * @param texture   Used for getting width and height of the texture
     * @param x         X position of the texture
     * @param y         Y position of the texture
     * @param regX      X position of the texture region
     * @param regY      Y position of the texture region
     * @param regWidth  Width of the texture region
     * @param regHeight Height of the texture region
     */
    public void drawTextureRegion(Texture texture, float x, float y, float regX, float regY, float regWidth,
            float regHeight) {
        drawTextureRegion(texture, x, y, regX, regY, regWidth, regHeight, Color.WHITE);
    }

    /**
     * Draws a texture region with the currently bound texture on specified
     * coordinates.
     *
     * @param texture   Used for getting width and height of the texture
     * @param x         X position of the texture
     * @param y         Y position of the texture
     * @param regX      X position of the texture region
     * @param regY      Y position of the texture region
     * @param regWidth  Width of the texture region
     * @param regHeight Height of the texture region
     * @param c         The color to use
     */
    public void drawTextureRegion(Texture texture, float x, float y, float regX, float regY, float regWidth,
            float regHeight, Color c) {
        /* Vertex positions */
        float x1 = x;
        float y1 = y;
        float x2 = x + regWidth;
        float y2 = y + regHeight;

        /* Texture coordinates */
        float s1 = regX / texture.getWidth();
        float t1 = regY / texture.getHeight();
        float s2 = (regX + regWidth) / texture.getWidth();
        float t2 = (regY + regHeight) / texture.getHeight();

        drawTextureRegion(x1, y1, x2, y2, s1, t1, s2, t2, c);
    }

    /**
     * Draws a texture region with the currently bound texture on specified
     * coordinates.
     *
     * @param x1 Bottom left x position
     * @param y1 Bottom left y position
     * @param x2 Top right x position
     * @param y2 Top right y position
     * @param s1 Bottom left s coordinate
     * @param t1 Bottom left t coordinate
     * @param s2 Top right s coordinate
     * @param t2 Top right t coordinate
     */
    public void drawTextureRegion(float x1, float y1, float x2, float y2, float s1, float t1, float s2, float t2) {
        drawTextureRegion(x1, y1, x2, y2, s1, t1, s2, t2, Color.WHITE);
    }

    /**
     * Draws a texture region with the currently bound texture on specified
     * coordinates.
     *
     * @param x1 Bottom left x position
     * @param y1 Bottom left y position
     * @param x2 Top right x position
     * @param y2 Top right y position
     * @param s1 Bottom left s coordinate
     * @param t1 Bottom left t coordinate
     * @param s2 Top right s coordinate
     * @param t2 Top right t coordinate
     * @param c  The color to use
     */
    public void drawTextureRegion(float x1, float y1, float x2, float y2, float s1, float t1, float s2, float t2,
            Color c) {
        if (verteciesBuff.remaining() < 8 * 6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }

        float r = c.getRed();
        float g = c.getGreen();
        float b = c.getBlue();
        float a = c.getAlpha();

        verteciesBuff.put(x1).put(y1).put(r).put(g).put(b).put(a).put(s1).put(t1);
        verteciesBuff.put(x1).put(y2).put(r).put(g).put(b).put(a).put(s1).put(t2);
        verteciesBuff.put(x2).put(y2).put(r).put(g).put(b).put(a).put(s2).put(t2);

        verteciesBuff.put(x1).put(y1).put(r).put(g).put(b).put(a).put(s1).put(t1);
        verteciesBuff.put(x2).put(y2).put(r).put(g).put(b).put(a).put(s2).put(t2);
        verteciesBuff.put(x2).put(y1).put(r).put(g).put(b).put(a).put(s2).put(t1);

        numVertices += 6;
    }
}