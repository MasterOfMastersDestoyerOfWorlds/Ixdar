package shell.ui;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwGetWindowContentScale;
import static org.lwjgl.glfw.GLFW.glfwGetWindowPos;
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

import java.awt.Point;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import javax.swing.JFrame;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import shell.render.Clock;

public class IxdarWindow {

    public static JFrame frame;
    private static Canvas3D canvas;
    public static float startTime;

    public static void main(String[] args) {
        startTime = Clock.time();
        new IxdarWindow().runGLFW();
    }

    public static float getAspectRatio() {
        return ((float) frame.getWidth()) / ((float) frame.getHeight());
    }

    public static long window;
    private static int windowWidth;
    private static int windowHeight;

    public void runGLFW() {

        init();
        loop();

        // Free the window callbacks and destroy the window
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);

        // Terminate GLFW and free the error callback
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    private void init() {
        // Setup an error callback. The default implementation
        // will print the error message in System.err.
        GLFWErrorCallback.createPrint(System.err).set();

        // Initialize GLFW. Most GLFW functions will not work before doing this.
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");
        // Configure GLFW
        glfwDefaultWindowHints(); // optional, the current window hints are already the default
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE); // the window will stay hidden after creation
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE); // the window will be resizable

        System.out.println("glfw init Time: " + (Clock.time() - startTime));

        // Create the window
        window = glfwCreateWindow(750, 750, "Ixdar", 0, 0);
        if (window == 0)
            throw new RuntimeException("Failed to create the GLFW window");

        System.out.println("Window Create Time: " + (Clock.time() - startTime));
        // Setup a key callback. It will be called every time a key is pressed, repeated
        // or released.

        canvas = new Canvas3D();

        glfwSetWindowSizeCallback(window, (long windowID, int width, int height) -> {
            try (MemoryStack stack = stackPush()) {
                FloatBuffer xScale = stack.mallocFloat(1);
                FloatBuffer yScale = stack.mallocFloat(1);
                glfwGetWindowContentScale(windowID, xScale, yScale);
                IxdarWindow.windowWidth = width;
                IxdarWindow.windowHeight = height;
                Canvas3D.frameBufferWidth = (int) (width * xScale.get(0));
                Canvas3D.frameBufferHeight = (int) (height * yScale.get(0));
                canvas.changedSize = true;

                canvas.paintGL();

                glfwSwapBuffers(window);
            }
        });
        // Setting the window icon
        IntBuffer w = BufferUtils.createIntBuffer(1);
        IntBuffer h = BufferUtils.createIntBuffer(1);
        IntBuffer channels = BufferUtils.createIntBuffer(1);
        File file = new File("res/decalSmall.png");
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
        glfwSetWindowIcon(window, gb);
        STBImage.stbi_image_free(icon);
        MemoryUtil.memFree(iconFlipped);

        // Get the thread stack and push a new frame
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1); // int*
            IntBuffer pHeight = stack.mallocInt(1); // int*

            // Get the window size passed to glfwCreateWindow
            glfwGetWindowSize(window, pWidth, pHeight);
            windowWidth = pWidth.get(0);
            windowHeight = pHeight.get(0);
            FloatBuffer xScale = stack.mallocFloat(1);
            FloatBuffer yScale = stack.mallocFloat(1);
            glfwGetWindowContentScale(window, xScale, yScale);
            Canvas3D.frameBufferWidth = (int) (windowWidth * xScale.get(0));
            Canvas3D.frameBufferHeight = (int) (windowHeight * yScale.get(0));
            // Get the resolution of the primary monitor
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

            // Center the window
            glfwSetWindowPos(
                    window,
                    (vidmode.width() / 2 - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2);
        } // the stack frame is popped automatically

        // Make the OpenGL context current
        glfwMakeContextCurrent(window);
        // Enable v-sync
        glfwSwapInterval(1);

        System.out.println("Window Time: " + (Clock.time() - startTime));
        canvas.initGL();
    }

    private void loop() {
        // This line is critical for LWJGL's interoperation with GLFW's
        // OpenGL context, or any context that is managed externally.
        // LWJGL detects the context that is current in the current thread,
        // creates the GLCapabilities instance and makes the OpenGL
        // bindings available for use.
        System.out.println("Time to First Paint" + (Clock.time() - startTime));
        // Set the clear color
        // canvas.initGL();

        // Run the rendering loop until the user has attempted to close
        // the window or has pressed the ESCAPE key.
        while (!glfwWindowShouldClose(window)) {

            canvas.paintGL();

            glfwSwapBuffers(window); // swap the color buffers

            // Poll for window events. The key callback above will only be
            // invoked during this call.
            glfwPollEvents();
        }
    }

    public static float getWidth() {
        return windowWidth;
    }

    public static float getHeight() {
        return windowHeight;
    }

    public static Point getLocationOnScreen() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowPos(window, pWidth, pHeight);
            return new Point(pWidth.get(0), pHeight.get(0));
        }
    }

    public static void setTitle(String title) {
        glfwSetWindowTitle(window, title);
    }

}