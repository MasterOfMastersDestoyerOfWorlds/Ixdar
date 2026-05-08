package ixdar.platform.gl.headless;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.gl.IxBuffer;
import ixdar.platform.input.MouseButtons;

/**
 * Headless GL implementation using LWJGL GLFW with invisible window.
 * Creates an offscreen OpenGL 3.3 core context for headless rendering.
 * Works on macOS and Linux (with display server like Xvfb in CI).
 */
public class HeadlessGL implements ixdar.platform.gl.GL {
    public static final int NUM_512 = 512;
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final int NUM_0xF = 0xFF;
    public static final int NUM_24 = 24;
    public static final int NUM_16 = 16;
    public static final int NUM_8 = 8;

    private int platformId;
    private long window;
    private int framebufferWidth = 512;
    private int framebufferHeight = 512;
    private int idCounter = 1;
    private ArrayList<ShaderProgram> shaders = new ArrayList<>();
    private boolean initialized = false;

    /**
     * Construct a HeadlessGL.
     */
    public HeadlessGL() {
        this(NUM_512, NUM_512);
    }

    /**
     * Construct a HeadlessGL.
     *
     * @param width  constructor argument
     * @param height constructor argument
     */
    public HeadlessGL(int width, int height) {
        this.framebufferWidth = width;
        this.framebufferHeight = height;
        init();
    }

    private void init() {
        if (initialized) {
            return;
        }

        try {
            GLFWErrorCallback.createPrint(System.err).set();

            if (!org.lwjgl.glfw.GLFW.glfwInit()) {
                throw new RuntimeException("Failed to initialize GLFW");
            }

            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, NUM_3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, NUM_3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

            window = glfwCreateWindow(framebufferWidth, framebufferHeight, "Headless Render", NULL, NULL);
            if (window == NULL) {
                glfwTerminate();
                throw new RuntimeException("Failed to create headless window");
            }

            glfwMakeContextCurrent(window);
            GL.createCapabilities();

            initialized = true;
        } catch (Exception e) {
            if (window != NULL) {
                glfwDestroyWindow(window);
            }
            glfwTerminate();
            throw new RuntimeException("Failed to initialize headless OpenGL", e);
        }
    }

    /** {@inheritDoc}. */
    @Override
    public void viewport(int x, int y, int w, int h) {
        GL11.glViewport(x, y, w, h);
    }

    /** {@inheritDoc}. */
    @Override
    public void clearColor(float r, float g, float b, float a) {
        GL11.glClearColor(r, g, b, a);
    }

    /** {@inheritDoc}. */
    @Override
    public void clear(int mask) {
        GL11.glClear(mask);
    }

    /** {@inheritDoc}. */
    @Override
    public void drawArrays(int mode, int first, int count) {
        GL11.glDrawArrays(mode, first, count);
    }

    /** {@inheritDoc}. */
    @Override
    public void drawElements(int mode, int count, int type, int indicesOffsetBytes) {
        GL11.glDrawElements(mode, count, type, indicesOffsetBytes);
    }

    /** {@inheritDoc}. */
    @Override
    public int genTexture() {
        int[] textures = new int[1];
        GL11.glGenTextures(textures);
        return textures[0];
    }

    /** {@inheritDoc}. */
    @Override
    public void deleteTexture(int id) {
        GL11.glDeleteTextures(id);
    }

    /** {@inheritDoc}. */
    @Override
    public void bindTexture2D(int id) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
    }

    /** {@inheritDoc}. */
    @Override
    public void texParameteri(int target, int pname, int param) {
        GL11.glTexParameteri(target, pname, param);
    }

    /** {@inheritDoc}. */
    @Override
    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format,
            int type, ByteBuffer data) {
        GL11.glTexImage2D(target, level, internalFormat, width, height, border, format, type, data);
    }

    /** {@inheritDoc}. */
    @Override
    public void enable(int cap) {
        GL11.glEnable(cap);
    }

    /** {@inheritDoc}. */
    @Override
    public void disable(int cap) {
        GL11.glDisable(cap);
    }

    /** {@inheritDoc}. */
    @Override
    public void depthMask(boolean flag) {
        GL11.glDepthMask(flag);
    }

    /** {@inheritDoc}. */
    @Override
    public void blendFunc(int sfactor, int dfactor) {
        GL11.glBlendFunc(sfactor, dfactor);
    }

    /** {@inheritDoc}. */
    @Override
    public void lineWidth(float width) {
        GL11.glLineWidth(width);
    }

    /** {@inheritDoc}. */
    @Override
    public int[] readPixels(int x, int y, int width, int height, int format, int type, int fb) {
        ByteBuffer frameBuffer = BufferUtils.createByteBuffer(width * height * NUM_4);
        GL11.glReadPixels(x, y, width, height, format, type, frameBuffer);

        int[] pixels = new int[width * height];
        for (int k = 0; k < pixels.length; k++) {
            int bindex = k * NUM_4;
            int r = frameBuffer.get(bindex) & NUM_0xF;
            int g = frameBuffer.get(bindex + 1) & NUM_0xF;
            int b = frameBuffer.get(bindex + 2) & NUM_0xF;
            int a = frameBuffer.get(bindex + NUM_3) & NUM_0xF;
            pixels[k] = (a << NUM_24) | (r << NUM_16) | (g << NUM_8) | b;
        }
        return pixels;
    }

    /** {@inheritDoc}. */
    @Override
    public int COLOR_BUFFER_BIT() {
        return GL11.GL_COLOR_BUFFER_BIT;
    }

    /** {@inheritDoc}. */
    @Override
    public int DEPTH_BUFFER_BIT() {
        return GL11.GL_DEPTH_BUFFER_BIT;
    }

    /** {@inheritDoc}. */
    @Override
    public int TRIANGLES() {
        return GL11.GL_TRIANGLES;
    }

    /** {@inheritDoc}. */
    @Override
    public int LINES() {
        return GL11.GL_LINES;
    }

    /** {@inheritDoc}. */
    @Override
    public int FLOAT() {
        return GL11.GL_FLOAT;
    }

    /** {@inheritDoc}. */
    @Override
    public int UNSIGNED_BYTE() {
        return GL11.GL_UNSIGNED_BYTE;
    }

    /** {@inheritDoc}. */
    @Override
    public int UNSIGNED_INT() {
        return GL11.GL_UNSIGNED_INT;
    }

    /** {@inheritDoc}. */
    @Override
    public int TEXTURE_2D() {
        return GL11.GL_TEXTURE_2D;
    }

    /** {@inheritDoc}. */
    @Override
    public int RGBA() {
        return GL11.GL_RGBA;
    }

    /** {@inheritDoc}. */
    @Override
    public int RGBA8() {
        return GL11.GL_RGBA8;
    }

    /** {@inheritDoc}. */
    @Override
    public int TEXTURE_WRAP_S() {
        return GL11.GL_TEXTURE_WRAP_S;
    }

    /** {@inheritDoc}. */
    @Override
    public int TEXTURE_WRAP_T() {
        return GL11.GL_TEXTURE_WRAP_T;
    }

    /** {@inheritDoc}. */
    @Override
    public int TEXTURE_MIN_FILTER() {
        return GL11.GL_TEXTURE_MIN_FILTER;
    }

    /** {@inheritDoc}. */
    @Override
    public int TEXTURE_MAG_FILTER() {
        return GL11.GL_TEXTURE_MAG_FILTER;
    }

    /** {@inheritDoc}. */
    @Override
    public int LINEAR() {
        return GL11.GL_LINEAR;
    }

    /** {@inheritDoc}. */
    @Override
    public int LINEAR_MIPMAP_LINEAR() {
        return GL11.GL_LINEAR_MIPMAP_LINEAR;
    }

    /** {@inheritDoc}. */
    @Override
    public int REPEAT() {
        return GL11.GL_REPEAT;
    }

    /** {@inheritDoc}. */
    @Override
    public int DEPTH_TEST() {
        return GL11.GL_DEPTH_TEST;
    }

    /** {@inheritDoc}. */
    @Override
    public int BLEND() {
        return GL11.GL_BLEND;
    }

    /** {@inheritDoc}. */
    @Override
    public int SRC_ALPHA() {
        return GL11.GL_SRC_ALPHA;
    }

    /** {@inheritDoc}. */
    @Override
    public int ONE_MINUS_SRC_ALPHA() {
        return GL11.GL_ONE_MINUS_SRC_ALPHA;
    }


    /** {@inheritDoc}. */
    @Override
    public void activeTexture(int unit) {
        GL13.glActiveTexture(unit);
    }

    /** {@inheritDoc}. */
    @Override
    public int TEXTURE0() {
        return GL13.GL_TEXTURE0;
    }

    // ---- GL15 (buffer objects) ----

    /** {@inheritDoc}. */
    @Override
    public int genBuffer() {
        return GL15.glGenBuffers();
    }

    /** {@inheritDoc}. */
    @Override
    public int genBuffers() {
        return GL15.glGenBuffers();
    }

    /** {@inheritDoc}. */
    @Override
    public void bindArrayBuffer(int buffer) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
    }

    /** {@inheritDoc}. */
    @Override
    public void bindBuffer(int target, int id) {
        GL15.glBindBuffer(target, id);
    }

    /** {@inheritDoc}. */
    @Override
    public void bufferDataArray(IxBuffer data, int usage) {
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ((HeadlessBuffer) data).getBuffer(), usage);
    }

    /** {@inheritDoc}. */
    @Override
    public void bufferDataArray(float[] data, int usage) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(data.length);
        buf.put(data).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, usage);
    }

    /** {@inheritDoc}. */
    @Override
    public void bufferData(int target, IxBuffer data, int usage) {
        GL15.glBufferData(target, ((HeadlessBuffer) data).getBuffer(), usage);
    }

    /** {@inheritDoc}. */
    @Override
    public void bufferData(int target, float[] data, int usage) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(data.length);
        buf.put(data).flip();
        GL15.glBufferData(target, buf, usage);
    }

    /** {@inheritDoc}. */
    @Override
    public void bufferData(int target, long size, int usage) {
        GL15.glBufferData(target, size, usage);
    }

    /** {@inheritDoc}. */
    @Override
    public void bufferData(int target, IntBuffer data, int usage) {
        GL15.glBufferData(target, data, usage);
    }

    /** {@inheritDoc}. */
    @Override
    public void bufferSubData(int target, long offset, IxBuffer data) {
        GL15.glBufferSubData(target, offset, ((HeadlessBuffer) data).getBuffer());
    }

    /** {@inheritDoc}. */
    @Override
    public void deleteBuffers(int id) {
        GL15.glDeleteBuffers(id);
    }

    /** {@inheritDoc}. */
    @Override
    public int ARRAY_BUFFER() {
        return GL15.GL_ARRAY_BUFFER;
    }

    /** {@inheritDoc}. */
    @Override
    public int ELEMENT_ARRAY_BUFFER() {
        return GL15.GL_ELEMENT_ARRAY_BUFFER;
    }

    /** {@inheritDoc}. */
    @Override
    public int STATIC_DRAW() {
        return GL15.GL_STATIC_DRAW;
    }

    /** {@inheritDoc}. */
    @Override
    public int DYNAMIC_DRAW() {
        return GL15.GL_DYNAMIC_DRAW;
    }

    /** {@inheritDoc}. */
    @Override
    public int createProgram() {
        return GL20.glCreateProgram();
    }

    /** {@inheritDoc}. */
    @Override
    public int createShader(int type) {
        return GL20.glCreateShader(type);
    }

    /** {@inheritDoc}. */
    @Override
    public void shaderSource(int shader, String src) {
        GL20.glShaderSource(shader, src);
    }

    /** {@inheritDoc}. */
    @Override
    public void shaderSource(int shader, CharSequence[] src) {
        GL20.glShaderSource(shader, src);
    }

    /** {@inheritDoc}. */
    @Override
    public void compileShader(int shader) {
        GL20.glCompileShader(shader);
    }

    /** {@inheritDoc}. */
    @Override
    public int getShaderiv(int shader, int pname) {
        IntBuffer buf = BufferUtils.createIntBuffer(1);
        GL20.glGetShaderiv(shader, pname, buf);
        return buf.get(0);
    }

    /** {@inheritDoc}. */
    @Override
    public void getShaderiv(int shader, int pname, IntBuffer success) {
        GL20.glGetShaderiv(shader, pname, success);
    }

    /** {@inheritDoc}. */
    @Override
    public String getShaderInfoLog(int shader) {
        return GL20.glGetShaderInfoLog(shader);
    }

    /** {@inheritDoc}. */
    @Override
    public void attachShader(int program, int shader) {
        GL20.glAttachShader(program, shader);
    }

    /** {@inheritDoc}. */
    @Override
    public void detachShader(int program, int shader) {
        GL20.glDetachShader(program, shader);
    }

    /** {@inheritDoc}. */
    @Override
    public void linkProgram(int program) {
        GL20.glLinkProgram(program);
    }

    /** {@inheritDoc}. */
    @Override
    public int getProgramiv(int program, int pname) {
        IntBuffer buf = BufferUtils.createIntBuffer(1);
        GL20.glGetProgramiv(program, pname, buf);
        return buf.get(0);
    }

    /** {@inheritDoc}. */
    @Override
    public void getProgramiv(int program, int pname, IntBuffer success) {
        GL20.glGetProgramiv(program, pname, success);
    }

    /** {@inheritDoc}. */
    @Override
    public String getProgramInfoLog(int program) {
        return GL20.glGetProgramInfoLog(program);
    }

    /** {@inheritDoc}. */
    @Override
    public void useProgram(int program) {
        GL20.glUseProgram(program);
    }

    /** {@inheritDoc}. */
    @Override
    public void deleteShader(int shader) {
        GL20.glDeleteShader(shader);
    }

    /** {@inheritDoc}. */
    @Override
    public void deleteProgram(int program) {
        GL20.glDeleteProgram(program);
    }

    /** {@inheritDoc}. */
    @Override
    public int getUniformLocation(int program, String name) {
        return GL20.glGetUniformLocation(program, name);
    }

    /** {@inheritDoc}. */
    @Override
    public int getAttribLocation(int program, CharSequence name) {
        return GL20.glGetAttribLocation(program, name.toString());
    }

    /** {@inheritDoc}. */
    @Override
    public void enableVertexAttribArray(int index) {
        GL20.glEnableVertexAttribArray(index);
    }

    /** {@inheritDoc}. */
    @Override
    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int pointer) {
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    /** {@inheritDoc}. */
    @Override
    public void uniform1f(int loc, float v) {
        GL20.glUniform1f(loc, v);
    }

    /** {@inheritDoc}. */
    @Override
    public void uniform1i(int loc, int v) {
        GL20.glUniform1i(loc, v);
    }

    /** {@inheritDoc}. */
    @Override
    public void uniform2fv(int loc, IxBuffer buffer) {
        GL20.glUniform2fv(loc, ((HeadlessBuffer) buffer).getBuffer());
    }

    /** {@inheritDoc}. */
    @Override
    public void uniform3fv(int loc, IxBuffer buffer) {
        GL20.glUniform3fv(loc, ((HeadlessBuffer) buffer).getBuffer());
    }

    /** {@inheritDoc}. */
    @Override
    public void uniform3fv(Integer loc, IxBuffer buffer) {
        GL20.glUniform3fv(loc, ((HeadlessBuffer) buffer).getBuffer());
    }

    /** {@inheritDoc}. */
    @Override
    public void uniform4fv(int loc, IxBuffer buffer) {
        GL20.glUniform4fv(loc, ((HeadlessBuffer) buffer).getBuffer());
    }

    /** {@inheritDoc}. */
    @Override
    public void uniformMatrix4fv(int loc, boolean transpose, IxBuffer buffer) {
        GL20.glUniformMatrix4fv(loc, transpose, ((HeadlessBuffer) buffer).getBuffer());
    }

    /** {@inheritDoc}. */
    @Override
    public void getUniformfv(int program, int location, IxBuffer val) {
        GL20.glGetUniformfv(program, location, ((HeadlessBuffer) val).getBuffer());
    }

    /** {@inheritDoc}. */
    @Override
    public String getActiveUniform(int program, int index, IntBuffer sizeBuffer, IntBuffer typeBuffer) {
        return GL20.glGetActiveUniform(program, index, sizeBuffer, typeBuffer);
    }

    /** {@inheritDoc}. */
    @Override
    public void getAttachedShaders(int program, IntBuffer success) {
        if (success != null && success.remaining() > 0) {
            success.put(0, 0);
        }
    }

    /** {@inheritDoc}. */
    @Override
    public void getActiveUniforms(int program, IntBuffer success) {
        if (success != null && success.remaining() > 0) {
            success.put(0, 0);
        }
    }

    /** {@inheritDoc}. */
    @Override
    public int FRAGMENT_SHADER() {
        return GL20.GL_FRAGMENT_SHADER;
    }

    /** {@inheritDoc}. */
    @Override
    public int VERTEX_SHADER() {
        return GL20.GL_VERTEX_SHADER;
    }

    /** {@inheritDoc}. */
    @Override
    public int LINK_STATUS() {
        return GL20.GL_LINK_STATUS;
    }

    /** {@inheritDoc}. */
    @Override
    public int COMPILE_STATUS() {
        return GL20.GL_COMPILE_STATUS;
    }

    /** {@inheritDoc}. */
    @Override
    public int ACTIVE_UNIFORMS() {
        return GL20.GL_ACTIVE_UNIFORMS;
    }

    /** {@inheritDoc}. */
    @Override
    public int FLOAT_VEC2() {
        return GL20.GL_FLOAT_VEC2;
    }

    /** {@inheritDoc}. */
    @Override
    public int FLOAT_VEC4() {
        return GL20.GL_FLOAT_VEC4;
    }

    /** {@inheritDoc}. */
    @Override
    public int SAMPLER_2D() {
        return GL20.GL_SAMPLER_2D;
    }


    /** {@inheritDoc}. */
    @Override
    public int genVertexArray() {
        return GL30.glGenVertexArrays();
    }

    /** {@inheritDoc}. */
    @Override
    public int genVertexArrays() {
        return GL30.glGenVertexArrays();
    }

    /** {@inheritDoc}. */
    @Override
    public void bindVertexArray(int vao) {
        GL30.glBindVertexArray(vao);
    }

    /** {@inheritDoc}. */
    @Override
    public void deleteVertexArrays(int id) {
        GL30.glDeleteVertexArrays(id);
    }

    /** {@inheritDoc}. */
    @Override
    public void generateMipmap(int target) {
        GL30.glGenerateMipmap(target);
    }

    /** {@inheritDoc}. */
    @Override
    public void bindFragDataLocation(int program, int colorNumber, String name) {
        GL30.glBindFragDataLocation(program, colorNumber, name);
    }

    /** {@inheritDoc}. */
    @Override
    public boolean getMouseButton(long window, MouseButtons button) {
        return false;
    }

    /** {@inheritDoc}. */
    @Override
    public void createCapabilities() {
        // Already created in init
    }

    /** {@inheritDoc}. */
    @Override
    public void setWindowTitle(String title) {
        if (window != NULL) {
            org.lwjgl.glfw.GLFW.glfwSetWindowTitle(window, title);
        }
    }

    /** {@inheritDoc}. */
    @Override
    public void coldStartStack() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            @SuppressWarnings("unused")
            FloatBuffer buffer = new org.joml.Matrix4f().get(stack.mallocFloat(NUM_16));
        }
    }

    /** {@inheritDoc}. */
    @Override
    public ArrayList<ShaderProgram> getShaders() {
        return shaders;
    }

    /** {@inheritDoc}. */
    @Override
    public void addShader(ShaderProgram shader) {
        shaders.add(shader);
    }

    /** {@inheritDoc}. */
    @Override
    public int getPlatformID() {
        return platformId;
    }

    /** {@inheritDoc}. */
    @Override
    public void setPlatformID(Integer p) {
        if (p != null) {
            this.platformId = p.intValue();
        }
    }

    /** {@inheritDoc}. */
    public int getWidth() {
        return framebufferWidth;
    }

    /** {@inheritDoc}. */
    public int getHeight() {
        return framebufferHeight;
    }

    /** {@inheritDoc}. */
    public void swapBuffers() {
        if (window != NULL) {
            glfwSwapBuffers(window);
        }
    }

    /** {@inheritDoc}. */
    public void pollEvents() {
        if (window != NULL) {
            glfwPollEvents();
        }
    }

    /** {@inheritDoc}. */
    public void shutdown() {
        if (window != NULL) {
            glfwDestroyWindow(window);
            window = NULL;
        }
        glfwTerminate();
        initialized = false;
    }

    /** {@inheritDoc}. */
    @Override
    public void clearColor(Color c) {
        Vector4f c4 = c.toVector4f();
        GL11.glClearColor(c4.x, c4.y, c4.z, c4.w);
    }
}
