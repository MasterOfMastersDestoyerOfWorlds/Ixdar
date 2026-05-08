package ixdar.canvas;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwGetWindowContentScale;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowIcon;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwSetWindowSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.function.Supplier;

import javax.swing.JFrame;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import ixdar.annotations.scene.SceneDrawable;
import ixdar.graphics.render.Clock;
import ixdar.platform.Platforms;
import ixdar.platform.gl.lwjgl.LwjglGL;
import ixdar.platform.gl.lwjgl.LwjglPlatform;

public class IxdarWindow {
    public static final String CANVAS3D_NOT_FOUND_FOR = "Canvas3D not found for ";
    public static final int NUM_3 = 3;
    public static final int NUM_750 = 750;
    public static final int NUM_4 = 4;
    public static final int NUM_20 = 20;

    public static JFrame frame;
    public static float startTime;

    public static long window;
    private static Canvas3D canvas;
    private static String canvasId;
    private static int windowWidth;
    private static int windowHeight;

    /**
     * TODO: document {@code main}.
     *
     * @param args TODO: describe
     * @throws UnsupportedEncodingException TODO: describe
     * @throws IOException TODO: describe
     * @throws InterruptedException TODO: describe
     */
    public static void main(String[] args) throws UnsupportedEncodingException, IOException, InterruptedException {
        if (args.length == 0) {
            canvasId = "ixdar";
        } else {
            canvasId = args[0];
        }
        startTime = Clock.time();
        new IxdarWindow().runGLFW();
    }

    /**
     * TODO: document {@code getAspectRatio}.
     *
     * @return TODO: describe
     */
    public static float getAspectRatio() {
        return ((float) frame.getWidth()) / ((float) frame.getHeight());
    }

    /**
     * TODO: document {@code runGLFW}.
     *
     * @throws UnsupportedEncodingException TODO: describe
     * @throws IOException TODO: describe
     * @throws InterruptedException TODO: describe
     */
    public void runGLFW() throws UnsupportedEncodingException, IOException, InterruptedException {

        init();
        loop();
        if (canvas != null) {
            canvas.shutdown();
        }

        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);

        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    private void init() throws UnsupportedEncodingException, IOException {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE); // the window will stay hidden after creation
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE); // the window will be resizable
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, NUM_3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, NUM_3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        System.out.println("glfw init Time: " + (Clock.time() - startTime));
        window = glfwCreateWindow(NUM_750, NUM_750, "Ixdar", 0, 0);
        if (window == 0)
            throw new RuntimeException("Failed to create the GLFW window");

        System.out.println("Window Create Time: " + (Clock.time() - startTime));
        Platforms.init(new LwjglPlatform(window), new LwjglGL());

        glfwSetWindowSizeCallback(window, (long windowID, int width, int height) -> {
            try (MemoryStack stack = stackPush()) {
                FloatBuffer xScale = stack.mallocFloat(1);
                FloatBuffer yScale = stack.mallocFloat(1);
                glfwGetWindowContentScale(windowID, xScale, yScale);
                IxdarWindow.windowWidth = width;
                IxdarWindow.windowHeight = height;
                Platforms.get().setFrameBufferSize(width * xScale.get(0), height * yScale.get(0));
                canvas.changedSize = true;
            }
        });
        IntBuffer w = BufferUtils.createIntBuffer(1);
        IntBuffer h = BufferUtils.createIntBuffer(1);
        IntBuffer channels = BufferUtils.createIntBuffer(1);
        File file = new File("src/main/resources/res/decalSmall.png");
        String filePath = file.getAbsolutePath();
        ByteBuffer icon = STBImage.stbi_load(filePath, w, h, channels, NUM_4);
        GLFWImage.Buffer gb = null;
        if (icon != null && w.get(0) > 0 && h.get(0) > 0) {
            gb = GLFWImage.create(1);
            int width = w.get(0);
            int height = h.get(0);
            GLFWImage iconGI = GLFWImage.create().set(width, height, icon);
            gb.put(0, iconGI);
        } else {
            System.out.println("[IxdarWindow] Icon load failed, continuing without window icon: " + filePath);
        }

        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(window, pWidth, pHeight);
            windowWidth = pWidth.get(0);
            windowHeight = pHeight.get(0);
            FloatBuffer xScale = stack.mallocFloat(1);
            FloatBuffer yScale = stack.mallocFloat(1);
            glfwGetWindowContentScale(window, xScale, yScale);
            Platforms.get().setFrameBufferSize(windowWidth * xScale.get(0), windowHeight * yScale.get(0));
            long monitor = glfwGetPrimaryMonitor();
            if (monitor != 0) {
                GLFWVidMode vidmode = glfwGetVideoMode(monitor);
                if (vidmode != null) {
                    glfwSetWindowPos(
                            window,
                            (vidmode.width() / 2 - pWidth.get(0)) / 2,
                            (vidmode.height() - pHeight.get(0)) / 2);
                }
            }
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);

        System.out.println("Window Time: " + (Clock.time() - startTime));

        Supplier<? extends SceneDrawable> cs = CanvasSceneMap.MAP.get(canvasId);
        if (cs == null) {
            Platforms.get().log(CANVAS3D_NOT_FOUND_FOR + canvasId);
            throw new RuntimeException(CANVAS3D_NOT_FOUND_FOR + canvasId);
        }
        canvas = (Canvas3D) cs.get();
        canvas.initGL();
        if (gb != null) {
            glfwSetWindowIcon(window, gb);
        }
    }

    private void loop() throws InterruptedException {
        System.out.println("Time to First Paint" + (Clock.time() - startTime));

        glfwMakeContextCurrent(NULL);
        Thread renderThread = new Thread(() -> {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            while (!glfwWindowShouldClose(window)) {
                Platforms.get().processInputQueue();
                canvas.paintGL();
                glfwSwapBuffers(window);
            }
        });
        renderThread.start();
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            Thread.sleep(NUM_20);
        }
        renderThread.join();
    }

    /**
     * TODO: document {@code getWidth}.
     *
     * @return TODO: describe
     */
    public static float getWidth() {
        return windowWidth;
    }

    /**
     * TODO: document {@code getHeight}.
     *
     * @return TODO: describe
     */
    public static float getHeight() {
        return windowHeight;
    }

    /**
     * TODO: document {@code getCanvasId}.
     *
     * @return TODO: describe
     */
    public static String getCanvasId() {
        return canvasId;
    }

    /**
     * TODO: document {@code setTitle}.
     *
     * @param title TODO: describe
     */
    public static void setTitle(String title) {
        glfwSetWindowTitle(window, title);
    }

    /**
     * TODO: document {@code requestClose}.
     */
    public static void requestClose() {
        if (window != 0) {
            glfwSetWindowShouldClose(window, true);
        }
    }

}