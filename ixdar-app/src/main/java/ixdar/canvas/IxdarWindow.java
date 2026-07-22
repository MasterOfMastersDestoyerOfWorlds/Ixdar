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
import ixdar.platform.gl.headless.HeadlessGL;
import ixdar.platform.gl.headless.HeadlessPlatform;
import ixdar.platform.gl.lwjgl.LwjglGL;
import ixdar.platform.gl.lwjgl.LwjglPlatform;

public class IxdarWindow {
    public static final String CANVAS3D_NOT_FOUND_FOR = "Canvas3D not found for ";
    public static final int NUM_3 = 3;
    public static final int NUM_750 = 750;
    public static final int NUM_4 = 4;
    public static final int NUM_20 = 20;

    /** System property that runs a scene with no visible window, for screenshot capture. */
    public static final String HEADLESS_PROPERTY = "ixdar.headless";

    /** System property overriding the headless framebuffer's square side, in pixels. */
    public static final String HEADLESS_SIZE_PROPERTY = "ixdar.headless.size";

    /** Default headless framebuffer side when {@link #HEADLESS_SIZE_PROPERTY} is unset. */
    public static final int HEADLESS_DEFAULT_SIZE = 1024;

    /** Automation platform id, matching the value the headless render entrypoints use. */
    public static final int HEADLESS_PLATFORM_ID = 1;

    public static JFrame frame;
    public static float startTime;

    public static long window;
    private static final String FATAL_PREFIX = "Exception in thread \"main\" ";

    private static Canvas3D canvas;
    private static String canvasId;
    private static int windowWidth;
    private static int windowHeight;

    /**
     * Desktop entry point: pick the canvas id from {@code args[0]} (default
     * {@code "ixdar"}), record the start time, and launch the GLFW loop.
     *
     * @param args optional canvas id at index 0
     * @throws UnsupportedEncodingException propagated from window initialization
     * @throws IOException propagated from window initialization
     * @throws InterruptedException if the polling thread is interrupted
     */
    public static void main(String[] args) throws UnsupportedEncodingException, IOException, InterruptedException {
        if (args.length == 0) {
            canvasId = "ixdar";
        } else {
            canvasId = args[0];
        }
        startTime = Clock.time();
        try {
            if (Boolean.getBoolean(HEADLESS_PROPERTY)) {
                new IxdarWindow().runHeadless();
            } else {
                new IxdarWindow().runGLFW();
            }
        } catch (Throwable failure) {
            System.err.print(FATAL_PREFIX);
            failure.printStackTrace();
            System.out.flush();
            System.err.flush();
            System.exit(1);
        }
    }

    /**
     * Run the selected scene against an off-screen GL context with no visible window, so a
     * screenshot of it can be captured without anything appearing on the desktop.
     *
     * <p>The scene, its automation server, and the per-frame command pump are all the same
     * as in the windowed path — the only difference is the platform. {@link HeadlessGL}
     * still opens a GLFW window, but a hidden one, so this needs a graphical session (or an
     * X server such as Xvfb) to exist; it is not truly surfaceless. Everything runs on this
     * one thread, which is where {@code HeadlessGL} binds its context, so the automation
     * server's screenshot command reads the framebuffer on the thread that drew it.
     *
     * <p>The loop runs until the automation {@code /shutdown} endpoint calls
     * {@link System#exit}; there is no window to close.
     *
     * @throws RuntimeException when no scene is registered for the requested canvas id
     */
    public void runHeadless() {
        int size = Integer.getInteger(HEADLESS_SIZE_PROPERTY, HEADLESS_DEFAULT_SIZE);
        HeadlessPlatform platform = new HeadlessPlatform(size, size);
        platform.setPlatformID(HEADLESS_PLATFORM_ID);
        HeadlessGL gl = platform.getGL();
        gl.setPlatformID(HEADLESS_PLATFORM_ID);
        Platforms.init(platform, gl);
        gl.enable(gl.DEPTH_TEST());
        platform.setFrameBufferSize(size, size);

        Supplier<? extends SceneDrawable> sceneSupplier = CanvasSceneMap.MAP.get(canvasId);
        if (sceneSupplier == null) {
            throw new RuntimeException(CANVAS3D_NOT_FOUND_FOR + canvasId);
        }
        canvas = (Canvas3D) sceneSupplier.get();
        canvas.initGL();
        canvas.sceneReady = true;
        System.out.println("[headless] scene '" + canvasId + "' ready at " + size + "x" + size
                + "; automation server up, screenshot with `ixdar-cli screenshot`");

        try {
            while (true) {
                canvas.paintGL();
                Thread.sleep(NUM_20);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Width-over-height aspect ratio of the underlying Swing frame.
     *
     * @return {@code frame.getWidth() / frame.getHeight()}
     */
    public static float getAspectRatio() {
        return ((float) frame.getWidth()) / ((float) frame.getHeight());
    }

    /**
     * Run the full GLFW lifecycle: initialize, drive the render/poll loop, then
     * tear down callbacks, the window, GLFW itself, and the error callback.
     *
     * @throws UnsupportedEncodingException propagated from {@link #init()}
     * @throws IOException propagated from {@link #init()}
     * @throws InterruptedException if the polling thread is interrupted
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
        canvas.sceneReady = true;
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
     * Last reported logical window width in screen units (not framebuffer pixels).
     *
     * @return cached width from the most recent size callback
     */
    public static float getWidth() {
        return windowWidth;
    }

    /**
     * Last reported logical window height in screen units (not framebuffer pixels).
     *
     * @return cached height from the most recent size callback
     */
    public static float getHeight() {
        return windowHeight;
    }

    /**
     * Canvas id selected at startup (drives {@link CanvasSceneMap} lookup).
     *
     * @return the active canvas id
     */
    public static String getCanvasId() {
        return canvasId;
    }

    /**
     * Set the GLFW window title.
     *
     * @param title new title shown in the OS window chrome
     */
    public static void setTitle(String title) {
        glfwSetWindowTitle(window, title);
    }

    /**
     * Ask GLFW to close the window on the next poll; no-op if no window exists.
     */
    public static void requestClose() {
        if (window != 0) {
            glfwSetWindowShouldClose(window, true);
        }
    }

}