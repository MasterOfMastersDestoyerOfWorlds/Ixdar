package shell.ui;

import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
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

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.function.IntFunction;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import shell.cameras.Camera3D;
import shell.render.Clock;
import shell.render.Texture;
import shell.render.lights.DirectionalLight;
import shell.render.lights.PointLight;
import shell.render.lights.SpotLight;
import shell.render.sdf.SDFCircle;
import shell.render.shaders.DiffuseShader;
import shell.render.shaders.FontShader;
import shell.render.shaders.LightShader;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.VertexArrayObject;
import shell.render.shaders.VertexBufferObject;
import shell.render.text.Font;
import shell.ui.input.keys.KeyActions;
import shell.ui.input.keys.KeyGuy;
import shell.ui.input.mouse.MouseTrap;
import shell.ui.main.Main;
import shell.ui.menu.MenuBox;
import shell.utils.Utils;

public class Canvas3D {

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
    static Texture diffuseMap;
    static Texture specularMap;
    public static int SIZE_FLOAT = 4;
    public boolean printScreen = false;
    public File screenShotFile;
    public static int frameBufferWidth;
    public static int frameBufferHeight;
    public static Font font;

    public VertexArrayObject vao;
    public VertexBufferObject vbo;
    public FontShader fontShader;
    public int numVertices;
    public boolean drawing;
    public FloatBuffer verteciesBuff;
    public Font debugFont;
    public static MenuBox menu;
    boolean changedSize = false;
    // private SDFTexture logo;
    public static ArrayList<ShaderProgram> shaders = new ArrayList<>();
    public static Canvas3D canvas;
    public static boolean active;

    public static Camera3D camera = new Camera3D(new Vector3f(0, 0, 3.0f), -90.0f, 0.0f);
    public static MouseTrap mouse = new MouseTrap(null, camera, false);
    public static KeyGuy keys = new KeyGuy(camera, canvas);

    public Canvas3D() {
        activate(true);
        Canvas3D.canvas = this;
        diffuseMap = Texture.loadTextureThreaded("container2.png");
        specularMap = Texture.loadTextureThreaded("container2_specular.png");
        active = true;
    }

    public void setKeys(KeyGuy keyGuy) {
        Canvas3D.keys = keyGuy;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void initGL() {

        float start = Clock.time();
        coldStartStack();

        createCapabilities(false, (IntFunction) null);
        System.out.println("capabilities: " + (Clock.time() - start));
        VertexArrayObject vao = new VertexArrayObject();
        VertexBufferObject vbo = new VertexBufferObject();
        vao.bind();
        vbo.bind(GL_ARRAY_BUFFER);
        vbo.uploadData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        shader = new DiffuseShader(vao, vbo);
        shaders.add(shader);

        VertexArrayObject lvao = new VertexArrayObject();
        lightingShader = new LightShader(lvao, vbo);
        shaders.add(lightingShader);

        // logo = new SDFTexture("decal_sdf.png", Color.BLUE_WHITE, 0.5f, 0.5f, false);
        menu = new MenuBox();
        // menuInnerBorder = new SDFTexture("menu_inner.png", Color.BLUE_WHITE, 0.25f,
        // 0f, true);

        // sdfLine = new SDFLine();

        glViewport(0, 0, (int) IxdarWindow.getWidth(), (int) IxdarWindow.getHeight());
        mouse.setCanvas(this);
        // mouseTrap.captureMouse(false);

        glEnable(GL_DEPTH_TEST);

        glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        System.out.println("InitGL: " + (Clock.time() - start));
        System.out.println("Time to First Paint: " + (Clock.time() - IxdarWindow.startTime));
    }

    private void coldStartStack() {
        Thread t1 = new Thread(new Runnable() {
            @Override
            public void run() {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    @SuppressWarnings("unused")
                    FloatBuffer buffer = new Matrix4f().get(stack.mallocFloat(16));
                }
            }
        });
        t1.start();
    }

    public SDFCircle circle;

    public void paintGL() {

        glClearColor(0.07f, 0.07f, 0.07f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        camera.resetZIndex();

        if (MenuBox.menuVisible) {

            float SHIFT_MOD = 1;
            if (keys != null && KeyActions.DoubleSpeed.keyPressed(keys.pressedKeys)) {
                SHIFT_MOD = 2;
            }
            if (keys != null) {
                keys.paintUpdate(SHIFT_MOD);
            }
            if (mouse != null) {
                mouse.paintUpdate(SHIFT_MOD);
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
            // view/projection transformations
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(camera.fov),
                    ((float) frameBufferWidth) / ((float) frameBufferHeight), 0.1f, 100.0f);
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
            // Color c = new ColorRGB(Color.CYAN);
            // logo.drawRightBound(Canvas3D.frameBufferWidth, 0, 800f, 800f, Color.IXDAR,
            // camera);
        }

        if (Main.main != null) {
            Main.main.draw(camera);
        }

        glViewport(0, 0, (int) IxdarWindow.getWidth(), (int) IxdarWindow.getHeight());
        for (ShaderProgram s : shaders) {
            s.updateProjectionMatrix(frameBufferWidth, frameBufferHeight, 1f);
        }

        if (MenuBox.menuVisible) {
            menu.draw(camera);
        }
        // menuInnerBorder.drawCentered(frameBufferWidth / 2,
        // // frameBufferHeight / 2, 3, -10.5f, Color.TRANSPARENT);
        // sdfLine.drawCentered(frameBufferWidth / 2,
        // // frameBufferHeight / 2, 800, 800, -2f, c);
        // debugFont.drawTextCentered("FPS: " + (1 / Clock.deltaTime()),
        // frameBufferWidth / 2,
        // frameBufferHeight / 2, -1f, 1, Color.CYAN);
        // c.setAlpha(0.6f);

        if (printScreen) {
            printScreen = false;
            printScreen(screenShotFile);
        }
    }

    public void printScreen(String fileName) {
        printScreen = true;
        screenShotFile = new File(fileName);
    }

    public void printScreen(File outputfile) {
        // allocate space for RBG pixels

        ByteBuffer fb = MemoryUtil.memAlloc(frameBufferWidth * frameBufferHeight * 4);
        // grab a copy of the current frame contents as RGBA
        glReadPixels(0, 0, frameBufferWidth, frameBufferHeight, GL_RGBA, GL_UNSIGNED_BYTE, fb);
        Utils.snapByteBuffer(frameBufferWidth, frameBufferHeight, fb, 4);
        MemoryUtil.memFree(fb);

    }

    public static void activate(boolean state) {
        if (state) {
            glfwSetKeyCallback(IxdarWindow.window,
                    (window, key, scancode, action, mods) -> keys.keyCallback(window, key, scancode, action, mods));

            glfwSetMouseButtonCallback(IxdarWindow.window,
                    (window, button, action, mods) -> mouse.clickCallback(window, button, action, mods));

            glfwSetCursorPosCallback(IxdarWindow.window, (window, x, y) -> mouse.moveCallback(window, x, y));

            glfwSetScrollCallback(IxdarWindow.window, (window, x, y) -> mouse.mouseScrollCallback(window, y));

        }
        Canvas3D.keys.active = state;
        Canvas3D.mouse.active = state;
        MenuBox.menuVisible = state;
        active = state;
    }

}