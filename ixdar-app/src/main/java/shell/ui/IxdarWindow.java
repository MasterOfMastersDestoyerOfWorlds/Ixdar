package shell.ui;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
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
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.system.MemoryStack.stackPush;

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
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import shell.platform.Platforms;
import shell.platform.gl.lwjgl.LwjglGL;
import shell.platform.gl.lwjgl.LwjglPlatform;
import shell.render.Clock;
import shell.ui.scenes.CanvasScene;

public class IxdarWindow {

    public static JFrame frame;
    private static Canvas3D canvas;
    public static float startTime;
    private static String canvasId;

    public static void main(String[] args) throws UnsupportedEncodingException, IOException {
        if (args.length == 0) {
            canvasId = "ixdar";
        } else {
            canvasId = args[0];
        }
        startTime = Clock.time();
        new IxdarWindow().runGLFW();
    }

    public static float getAspectRatio() {
        return ((float) frame.getWidth()) / ((float) frame.getHeight());
    }

    public static long window;
    private static int windowWidth;
    private static int windowHeight;

    public void runGLFW() throws UnsupportedEncodingException, IOException {

        init();
        loop();

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
        System.out.println("glfw init Time: " + (Clock.time() - startTime));
        window = glfwCreateWindow(750, 750, "Ixdar", 0, 0);
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
                Platforms.get().setFrameBufferSize(width* xScale.get(0), height* yScale.get(0));
                canvas.changedSize = true;

                canvas.paintGL();

                glfwSwapBuffers(window);
            }
        });
        IntBuffer w = BufferUtils.createIntBuffer(1);
        IntBuffer h = BufferUtils.createIntBuffer(1);
        IntBuffer channels = BufferUtils.createIntBuffer(1);
        File file = new File("src/main/resources/res/decalSmall.png");
        String filePath = file.getAbsolutePath();
        ByteBuffer icon = STBImage.stbi_load(filePath, w, h, channels, 4);
        int limit = icon.limit();
        ByteBuffer iconFlipped = MemoryUtil.memAlloc(limit);
        int hPix = h.get(0);
        int wPix = w.get(0);
        for (int i = 0; i < hPix; i++) {
            for (int j = 0; j < wPix; j++) {
                int pixelStart = i * (wPix * 4) + j * 4;
                int flippedPixel = (hPix - 1 - i) * (wPix * 4) + (j) * 4;
                iconFlipped.put(flippedPixel + 0, icon.get(pixelStart + 0));
                iconFlipped.put(flippedPixel + 1, icon.get(pixelStart + 1));
                iconFlipped.put(flippedPixel + 2, icon.get(pixelStart + 2));
                iconFlipped.put(flippedPixel + 3, icon.get(pixelStart + 3));
            }
        }
        GLFWImage.Buffer gb = GLFWImage.create(1);
        int width = w.get(0);
        int height = h.get(0);
        GLFWImage iconGI = GLFWImage.create().set(width, height, iconFlipped);
        gb.put(0, iconGI);

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
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            glfwSetWindowPos(
                    window,
                    (vidmode.width() / 2 - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2);
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);

        System.out.println("Window Time: " + (Clock.time() - startTime));
        
        Supplier<? extends Canvas3D> cs = CanvasScene.MAP.get(canvasId);
        if (cs == null) {
            Platforms.get().log("Canvas3D not found for " + canvasId);
            return;
        }
        canvas = (Canvas3D) cs.get();
        canvas.initGL();
        glfwSetWindowIcon(window, gb);
    }

    private void loop() {
        System.out.println("Time to First Paint" + (Clock.time() - startTime));

        while (!glfwWindowShouldClose(window)) {

            canvas.paintGL();
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    public static float getWidth() {
        return windowWidth;
    }

    public static float getHeight() {
        return windowHeight;
    }

    public static void setTitle(String title) {
        glfwSetWindowTitle(window, title);
    }

}