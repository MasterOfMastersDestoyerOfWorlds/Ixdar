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
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;

import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

import javax.swing.JFrame;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.awt.AWTGLCanvas;
import org.lwjgl.system.MemoryUtil;

import shell.cameras.Camera3D;
import shell.render.lights.DirectionalLight;
import shell.render.lights.PointLight;
import shell.render.lights.SpotLight;
import shell.render.sdf.SDFLine;
import shell.render.sdf.SDFTexture;
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
    public static int framebufferWidth;
    public static int framebufferHeight;
    public Font font;

    public VertexArrayObject vao;
    public VertexBufferObject vbo;
    public FontShader fontShader;

    public int numVertices;
    public boolean drawing;
    public FloatBuffer verteciesBuff;
    public Font debugFont;
    private SDFTextureShader sdfShader;
    private SDFTexture sdf;
    boolean changedSize = false;
    public ArrayList<ShaderProgram> shaders;
    private SDFShapeShader sdfLineShader;
    private SDFLine sdfLine;

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
        AffineTransform t = this.getGraphicsConfiguration().getDefaultTransform();
        float sx = (float) t.getScaleX(), sy = (float) t.getScaleY();
        this.framebufferWidth = (int) (getWidth() * sx);
        this.framebufferHeight = (int) (getHeight() * sy);
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
        shaders = new ArrayList<>();
        shader = new DiffuseShader(vao, vbo);
        shaders.add(shader);
        diffuseMap = Texture.loadTexture("container2.png");
        specularMap = Texture.loadTexture("container2_specular.png");

        VertexArrayObject lvao = new VertexArrayObject();
        lightingShader = new LightShader(lvao, vbo);
        shaders.add(lightingShader);

        fontShader = new FontShader(framebufferWidth, framebufferHeight);
        shaders.add(fontShader);
        font = new Font(fontShader);
        debugFont = new Font(fontShader, 12, false);

        sdfShader = new SDFTextureShader(framebufferWidth, framebufferHeight);
        shaders.add(sdfShader);
        sdf = new SDFTexture(sdfShader, "decal_sdf.png", new Color(Color.IXDAR), 1, 0f);

        sdfLineShader = new SDFShapeShader(framebufferWidth, framebufferHeight);
        shaders.add(sdfLineShader);
        sdfLine = new SDFLine(sdfLineShader);


        glViewport(0, 0, (int) framebufferWidth, (int) framebufferHeight);
        mouseTrap.setCanvas(this);
        //mouseTrap.captureMouse(false);

        glEnable(GL_DEPTH_TEST);
        this.addComponentListener(listener);
        
        glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
    }

    public final ComponentAdapter listener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            java.awt.geom.AffineTransform t = Canvas3D.this.getGraphicsConfiguration().getDefaultTransform();
            float sx = (float) t.getScaleX(), sy = (float) t.getScaleY();
            Canvas3D.framebufferWidth = (int) (getWidth() * sx);
            Canvas3D.framebufferHeight = (int) (getHeight() * sy);
            Canvas3D.this.changedSize = true;
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
        if (changedSize) {
            glViewport(0, 0, (int) framebufferWidth, (int) framebufferHeight);
            changedSize = false;
            for(ShaderProgram s: shaders){
                s.updateProjectionMatrix(framebufferWidth, framebufferHeight);
            }
        }
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
                ((float) framebufferWidth) / ((float) framebufferHeight), 0.1f, 100.0f);
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
        debugFont.drawTextCentered(this, "FPS: " + (1 / Clock.deltaTime()),
                framebufferWidth / 2,
                framebufferHeight / 2, Color.CYAN);
        Color c = new Color(Color.CYAN);
        c.setAlpha(0.6f); 
        //sdf.setBorderDist(Clock.sin(0f, 1f, 1, 0));
        sdfLine.drawCentered(framebufferWidth / 2,
                framebufferHeight / 2, 800, 800, 1, c);

        Clock.frameRendered();
        if (printScreen) {
            printScreen = false;
            printScreen(screenShotFile);
        }

        swapBuffers();
    }

    public void printScreen(String fileName) {
        printScreen = true;
        screenShotFile = new File(fileName);
    }

    public void printScreen(File outputfile) {
        // allocate space for RBG pixels

        ByteBuffer fb = MemoryUtil.memAlloc(framebufferWidth * framebufferHeight * 4);
        // grab a copy of the current frame contents as RGBA
        glReadPixels(0, 0, framebufferWidth, framebufferHeight, GL_RGBA, GL_UNSIGNED_BYTE, fb);
        Utils.snapByteBuffer(framebufferWidth, framebufferHeight, fb, 4);
        MemoryUtil.memFree(fb);

    }

}