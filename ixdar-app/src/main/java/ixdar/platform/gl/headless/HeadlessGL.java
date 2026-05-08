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

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

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
     * TODO: document {@code HeadlessGL}.
     */
    public HeadlessGL() {
        this(NUM_512, NUM_512);
    }

    /**
     * TODO: document {@code HeadlessGL}.
     *
     * @param width TODO: describe
     * @param height TODO: describe
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

    // ---- GL11 methods (viewport, clear, draw, textures, blend, enable/disable) ----

    /**
     * TODO: document {@code viewport}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     * @param w TODO: describe
     * @param h TODO: describe
     */
    @Override
    public void viewport(int x, int y, int w, int h) {
        GL11.glViewport(x, y, w, h);
    }

    /**
     * TODO: document {@code clearColor}.
     *
     * @param r TODO: describe
     * @param g TODO: describe
     * @param b TODO: describe
     * @param a TODO: describe
     */
    @Override
    public void clearColor(float r, float g, float b, float a) {
        GL11.glClearColor(r, g, b, a);
    }

    /**
     * TODO: document {@code clear}.
     *
     * @param mask TODO: describe
     */
    @Override
    public void clear(int mask) {
        GL11.glClear(mask);
    }

    /**
     * TODO: document {@code drawArrays}.
     *
     * @param mode TODO: describe
     * @param first TODO: describe
     * @param count TODO: describe
     */
    @Override
    public void drawArrays(int mode, int first, int count) {
        GL11.glDrawArrays(mode, first, count);
    }

    /**
     * TODO: document {@code drawElements}.
     *
     * @param mode TODO: describe
     * @param count TODO: describe
     * @param type TODO: describe
     * @param indicesOffsetBytes TODO: describe
     */
    @Override
    public void drawElements(int mode, int count, int type, int indicesOffsetBytes) {
        GL11.glDrawElements(mode, count, type, indicesOffsetBytes);
    }

    /**
     * TODO: document {@code genTexture}.
     *
     * @return TODO: describe
     */
    @Override
    public int genTexture() {
        int[] textures = new int[1];
        GL11.glGenTextures(textures);
        return textures[0];
    }

    /**
     * TODO: document {@code deleteTexture}.
     *
     * @param id TODO: describe
     */
    @Override
    public void deleteTexture(int id) {
        GL11.glDeleteTextures(id);
    }

    /**
     * TODO: document {@code bindTexture2D}.
     *
     * @param id TODO: describe
     */
    @Override
    public void bindTexture2D(int id) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
    }

    /**
     * TODO: document {@code texParameteri}.
     *
     * @param target TODO: describe
     * @param pname TODO: describe
     * @param param TODO: describe
     */
    @Override
    public void texParameteri(int target, int pname, int param) {
        GL11.glTexParameteri(target, pname, param);
    }

    /**
     * TODO: document {@code texImage2D}.
     *
     * @param target TODO: describe
     * @param level TODO: describe
     * @param internalFormat TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     * @param border TODO: describe
     * @param format TODO: describe
     * @param type TODO: describe
     * @param data TODO: describe
     */
    @Override
    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format,
            int type, ByteBuffer data) {
        GL11.glTexImage2D(target, level, internalFormat, width, height, border, format, type, data);
    }

    /**
     * TODO: document {@code enable}.
     *
     * @param cap TODO: describe
     */
    @Override
    public void enable(int cap) {
        GL11.glEnable(cap);
    }

    /**
     * TODO: document {@code disable}.
     *
     * @param cap TODO: describe
     */
    @Override
    public void disable(int cap) {
        GL11.glDisable(cap);
    }

    /**
     * TODO: document {@code depthMask}.
     *
     * @param flag TODO: describe
     */
    @Override
    public void depthMask(boolean flag) {
        GL11.glDepthMask(flag);
    }

    /**
     * TODO: document {@code blendFunc}.
     *
     * @param sfactor TODO: describe
     * @param dfactor TODO: describe
     */
    @Override
    public void blendFunc(int sfactor, int dfactor) {
        GL11.glBlendFunc(sfactor, dfactor);
    }

    /**
     * TODO: document {@code lineWidth}.
     *
     * @param width TODO: describe
     */
    @Override
    public void lineWidth(float width) {
        GL11.glLineWidth(width);
    }

    /**
     * TODO: document {@code readPixels}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     * @param format TODO: describe
     * @param type TODO: describe
     * @param fb TODO: describe
     * @return TODO: describe
     */
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

    // ---- GL11 constants ----

    /**
     * TODO: document {@code COLOR_BUFFER_BIT}.
     *
     * @return TODO: describe
     */
    @Override public int COLOR_BUFFER_BIT() { return GL11.GL_COLOR_BUFFER_BIT; }
    /**
     * TODO: document {@code DEPTH_BUFFER_BIT}.
     *
     * @return TODO: describe
     */
    @Override public int DEPTH_BUFFER_BIT() { return GL11.GL_DEPTH_BUFFER_BIT; }
    /**
     * TODO: document {@code TRIANGLES}.
     *
     * @return TODO: describe
     */
    @Override public int TRIANGLES() { return GL11.GL_TRIANGLES; }
    /**
     * TODO: document {@code LINES}.
     *
     * @return TODO: describe
     */
    @Override public int LINES() { return GL11.GL_LINES; }
    /**
     * TODO: document {@code FLOAT}.
     *
     * @return TODO: describe
     */
    @Override public int FLOAT() { return GL11.GL_FLOAT; }
    /**
     * TODO: document {@code UNSIGNED_BYTE}.
     *
     * @return TODO: describe
     */
    @Override public int UNSIGNED_BYTE() { return GL11.GL_UNSIGNED_BYTE; }
    /**
     * TODO: document {@code UNSIGNED_INT}.
     *
     * @return TODO: describe
     */
    @Override public int UNSIGNED_INT() { return GL11.GL_UNSIGNED_INT; }
    /**
     * TODO: document {@code TEXTURE_2D}.
     *
     * @return TODO: describe
     */
    @Override public int TEXTURE_2D() { return GL11.GL_TEXTURE_2D; }
    /**
     * TODO: document {@code RGBA}.
     *
     * @return TODO: describe
     */
    @Override public int RGBA() { return GL11.GL_RGBA; }
    /**
     * TODO: document {@code RGBA8}.
     *
     * @return TODO: describe
     */
    @Override public int RGBA8() { return GL11.GL_RGBA8; }
    /**
     * TODO: document {@code TEXTURE_WRAP_S}.
     *
     * @return TODO: describe
     */
    @Override public int TEXTURE_WRAP_S() { return GL11.GL_TEXTURE_WRAP_S; }
    /**
     * TODO: document {@code TEXTURE_WRAP_T}.
     *
     * @return TODO: describe
     */
    @Override public int TEXTURE_WRAP_T() { return GL11.GL_TEXTURE_WRAP_T; }
    /**
     * TODO: document {@code TEXTURE_MIN_FILTER}.
     *
     * @return TODO: describe
     */
    @Override public int TEXTURE_MIN_FILTER() { return GL11.GL_TEXTURE_MIN_FILTER; }
    /**
     * TODO: document {@code TEXTURE_MAG_FILTER}.
     *
     * @return TODO: describe
     */
    @Override public int TEXTURE_MAG_FILTER() { return GL11.GL_TEXTURE_MAG_FILTER; }
    /**
     * TODO: document {@code LINEAR}.
     *
     * @return TODO: describe
     */
    @Override public int LINEAR() { return GL11.GL_LINEAR; }
    /**
     * TODO: document {@code LINEAR_MIPMAP_LINEAR}.
     *
     * @return TODO: describe
     */
    @Override public int LINEAR_MIPMAP_LINEAR() { return GL11.GL_LINEAR_MIPMAP_LINEAR; }
    /**
     * TODO: document {@code REPEAT}.
     *
     * @return TODO: describe
     */
    @Override public int REPEAT() { return GL11.GL_REPEAT; }
    /**
     * TODO: document {@code DEPTH_TEST}.
     *
     * @return TODO: describe
     */
    @Override public int DEPTH_TEST() { return GL11.GL_DEPTH_TEST; }
    /**
     * TODO: document {@code BLEND}.
     *
     * @return TODO: describe
     */
    @Override public int BLEND() { return GL11.GL_BLEND; }
    /**
     * TODO: document {@code SRC_ALPHA}.
     *
     * @return TODO: describe
     */
    @Override public int SRC_ALPHA() { return GL11.GL_SRC_ALPHA; }
    /**
     * TODO: document {@code ONE_MINUS_SRC_ALPHA}.
     *
     * @return TODO: describe
     */
    @Override public int ONE_MINUS_SRC_ALPHA() { return GL11.GL_ONE_MINUS_SRC_ALPHA; }

    // ---- GL13 (multitexture) ----

    /**
     * TODO: document {@code activeTexture}.
     *
     * @param unit TODO: describe
     */
    @Override
    public void activeTexture(int unit) {
        GL13.glActiveTexture(unit);
    }

    /**
     * TODO: document {@code TEXTURE0}.
     *
     * @return TODO: describe
     */
    @Override public int TEXTURE0() { return GL13.GL_TEXTURE0; }

    // ---- GL15 (buffer objects) ----

    /**
     * TODO: document {@code genBuffer}.
     *
     * @return TODO: describe
     */
    @Override
    public int genBuffer() {
        return GL15.glGenBuffers();
    }

    /**
     * TODO: document {@code genBuffers}.
     *
     * @return TODO: describe
     */
    @Override
    public int genBuffers() {
        return GL15.glGenBuffers();
    }

    /**
     * TODO: document {@code bindArrayBuffer}.
     *
     * @param buffer TODO: describe
     */
    @Override
    public void bindArrayBuffer(int buffer) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
    }

    /**
     * TODO: document {@code bindBuffer}.
     *
     * @param target TODO: describe
     * @param id TODO: describe
     */
    @Override
    public void bindBuffer(int target, int id) {
        GL15.glBindBuffer(target, id);
    }

    /**
     * TODO: document {@code bufferDataArray}.
     *
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    @Override
    public void bufferDataArray(IxBuffer data, int usage) {
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ((HeadlessBuffer) data).getBuffer(), usage);
    }

    /**
     * TODO: document {@code bufferDataArray}.
     *
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    @Override
    public void bufferDataArray(float[] data, int usage) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(data.length);
        buf.put(data).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, usage);
    }

    /**
     * TODO: document {@code bufferData}.
     *
     * @param target TODO: describe
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    @Override
    public void bufferData(int target, IxBuffer data, int usage) {
        GL15.glBufferData(target, ((HeadlessBuffer) data).getBuffer(), usage);
    }

    /**
     * TODO: document {@code bufferData}.
     *
     * @param target TODO: describe
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    @Override
    public void bufferData(int target, float[] data, int usage) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(data.length);
        buf.put(data).flip();
        GL15.glBufferData(target, buf, usage);
    }

    /**
     * TODO: document {@code bufferData}.
     *
     * @param target TODO: describe
     * @param size TODO: describe
     * @param usage TODO: describe
     */
    @Override
    public void bufferData(int target, long size, int usage) {
        GL15.glBufferData(target, size, usage);
    }

    /**
     * TODO: document {@code bufferData}.
     *
     * @param target TODO: describe
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    @Override
    public void bufferData(int target, IntBuffer data, int usage) {
        GL15.glBufferData(target, data, usage);
    }

    /**
     * TODO: document {@code bufferSubData}.
     *
     * @param target TODO: describe
     * @param offset TODO: describe
     * @param data TODO: describe
     */
    @Override
    public void bufferSubData(int target, long offset, IxBuffer data) {
        GL15.glBufferSubData(target, offset, ((HeadlessBuffer) data).getBuffer());
    }

    /**
     * TODO: document {@code deleteBuffers}.
     *
     * @param id TODO: describe
     */
    @Override
    public void deleteBuffers(int id) {
        GL15.glDeleteBuffers(id);
    }

    /**
     * TODO: document {@code ARRAY_BUFFER}.
     *
     * @return TODO: describe
     */
    @Override public int ARRAY_BUFFER() { return GL15.GL_ARRAY_BUFFER; }
    /**
     * TODO: document {@code ELEMENT_ARRAY_BUFFER}.
     *
     * @return TODO: describe
     */
    @Override public int ELEMENT_ARRAY_BUFFER() { return GL15.GL_ELEMENT_ARRAY_BUFFER; }
    /**
     * TODO: document {@code STATIC_DRAW}.
     *
     * @return TODO: describe
     */
    @Override public int STATIC_DRAW() { return GL15.GL_STATIC_DRAW; }
    /**
     * TODO: document {@code DYNAMIC_DRAW}.
     *
     * @return TODO: describe
     */
    @Override public int DYNAMIC_DRAW() { return GL15.GL_DYNAMIC_DRAW; }

    // ---- GL20 (shaders, programs, uniforms, attribs) ----

    /**
     * TODO: document {@code createProgram}.
     *
     * @return TODO: describe
     */
    @Override
    public int createProgram() {
        return GL20.glCreateProgram();
    }

    /**
     * TODO: document {@code createShader}.
     *
     * @param type TODO: describe
     * @return TODO: describe
     */
    @Override
    public int createShader(int type) {
        return GL20.glCreateShader(type);
    }

    /**
     * TODO: document {@code shaderSource}.
     *
     * @param shader TODO: describe
     * @param src TODO: describe
     */
    @Override
    public void shaderSource(int shader, String src) {
        GL20.glShaderSource(shader, src);
    }

    /**
     * TODO: document {@code shaderSource}.
     *
     * @param shader TODO: describe
     * @param src TODO: describe
     */
    @Override
    public void shaderSource(int shader, CharSequence[] src) {
        GL20.glShaderSource(shader, src);
    }

    /**
     * TODO: document {@code compileShader}.
     *
     * @param shader TODO: describe
     */
    @Override
    public void compileShader(int shader) {
        GL20.glCompileShader(shader);
    }

    /**
     * TODO: document {@code getShaderiv}.
     *
     * @param shader TODO: describe
     * @param pname TODO: describe
     * @return TODO: describe
     */
    @Override
    public int getShaderiv(int shader, int pname) {
        IntBuffer buf = BufferUtils.createIntBuffer(1);
        GL20.glGetShaderiv(shader, pname, buf);
        return buf.get(0);
    }

    /**
     * TODO: document {@code getShaderiv}.
     *
     * @param shader TODO: describe
     * @param pname TODO: describe
     * @param success TODO: describe
     */
    @Override
    public void getShaderiv(int shader, int pname, IntBuffer success) {
        GL20.glGetShaderiv(shader, pname, success);
    }

    /**
     * TODO: document {@code getShaderInfoLog}.
     *
     * @param shader TODO: describe
     * @return TODO: describe
     */
    @Override
    public String getShaderInfoLog(int shader) {
        return GL20.glGetShaderInfoLog(shader);
    }

    /**
     * TODO: document {@code attachShader}.
     *
     * @param program TODO: describe
     * @param shader TODO: describe
     */
    @Override
    public void attachShader(int program, int shader) {
        GL20.glAttachShader(program, shader);
    }

    /**
     * TODO: document {@code detachShader}.
     *
     * @param program TODO: describe
     * @param shader TODO: describe
     */
    @Override
    public void detachShader(int program, int shader) {
        GL20.glDetachShader(program, shader);
    }

    /**
     * TODO: document {@code linkProgram}.
     *
     * @param program TODO: describe
     */
    @Override
    public void linkProgram(int program) {
        GL20.glLinkProgram(program);
    }

    /**
     * TODO: document {@code getProgramiv}.
     *
     * @param program TODO: describe
     * @param pname TODO: describe
     * @return TODO: describe
     */
    @Override
    public int getProgramiv(int program, int pname) {
        IntBuffer buf = BufferUtils.createIntBuffer(1);
        GL20.glGetProgramiv(program, pname, buf);
        return buf.get(0);
    }

    /**
     * TODO: document {@code getProgramiv}.
     *
     * @param program TODO: describe
     * @param pname TODO: describe
     * @param success TODO: describe
     */
    @Override
    public void getProgramiv(int program, int pname, IntBuffer success) {
        GL20.glGetProgramiv(program, pname, success);
    }

    /**
     * TODO: document {@code getProgramInfoLog}.
     *
     * @param program TODO: describe
     * @return TODO: describe
     */
    @Override
    public String getProgramInfoLog(int program) {
        return GL20.glGetProgramInfoLog(program);
    }

    /**
     * TODO: document {@code useProgram}.
     *
     * @param program TODO: describe
     */
    @Override
    public void useProgram(int program) {
        GL20.glUseProgram(program);
    }

    /**
     * TODO: document {@code deleteShader}.
     *
     * @param shader TODO: describe
     */
    @Override
    public void deleteShader(int shader) {
        GL20.glDeleteShader(shader);
    }

    /**
     * TODO: document {@code deleteProgram}.
     *
     * @param program TODO: describe
     */
    @Override
    public void deleteProgram(int program) {
        GL20.glDeleteProgram(program);
    }

    /**
     * TODO: document {@code getUniformLocation}.
     *
     * @param program TODO: describe
     * @param name TODO: describe
     * @return TODO: describe
     */
    @Override
    public int getUniformLocation(int program, String name) {
        return GL20.glGetUniformLocation(program, name);
    }

    /**
     * TODO: document {@code getAttribLocation}.
     *
     * @param program TODO: describe
     * @param name TODO: describe
     * @return TODO: describe
     */
    @Override
    public int getAttribLocation(int program, CharSequence name) {
        return GL20.glGetAttribLocation(program, name.toString());
    }

    /**
     * TODO: document {@code enableVertexAttribArray}.
     *
     * @param index TODO: describe
     */
    @Override
    public void enableVertexAttribArray(int index) {
        GL20.glEnableVertexAttribArray(index);
    }

    /**
     * TODO: document {@code vertexAttribPointer}.
     *
     * @param index TODO: describe
     * @param size TODO: describe
     * @param type TODO: describe
     * @param normalized TODO: describe
     * @param stride TODO: describe
     * @param pointer TODO: describe
     */
    @Override
    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int pointer) {
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    /**
     * TODO: document {@code uniform1f}.
     *
     * @param loc TODO: describe
     * @param v TODO: describe
     */
    @Override
    public void uniform1f(int loc, float v) {
        GL20.glUniform1f(loc, v);
    }

    /**
     * TODO: document {@code uniform1i}.
     *
     * @param loc TODO: describe
     * @param v TODO: describe
     */
    @Override
    public void uniform1i(int loc, int v) {
        GL20.glUniform1i(loc, v);
    }

    /**
     * TODO: document {@code uniform2fv}.
     *
     * @param loc TODO: describe
     * @param buffer TODO: describe
     */
    @Override
    public void uniform2fv(int loc, IxBuffer buffer) {
        GL20.glUniform2fv(loc, ((HeadlessBuffer) buffer).getBuffer());
    }

    /**
     * TODO: document {@code uniform3fv}.
     *
     * @param loc TODO: describe
     * @param buffer TODO: describe
     */
    @Override
    public void uniform3fv(int loc, IxBuffer buffer) {
        GL20.glUniform3fv(loc, ((HeadlessBuffer) buffer).getBuffer());
    }

    /**
     * TODO: document {@code uniform3fv}.
     *
     * @param loc TODO: describe
     * @param buffer TODO: describe
     */
    @Override
    public void uniform3fv(Integer loc, IxBuffer buffer) {
        GL20.glUniform3fv(loc, ((HeadlessBuffer) buffer).getBuffer());
    }

    /**
     * TODO: document {@code uniform4fv}.
     *
     * @param loc TODO: describe
     * @param buffer TODO: describe
     */
    @Override
    public void uniform4fv(int loc, IxBuffer buffer) {
        GL20.glUniform4fv(loc, ((HeadlessBuffer) buffer).getBuffer());
    }

    /**
     * TODO: document {@code uniformMatrix4fv}.
     *
     * @param loc TODO: describe
     * @param transpose TODO: describe
     * @param buffer TODO: describe
     */
    @Override
    public void uniformMatrix4fv(int loc, boolean transpose, IxBuffer buffer) {
        GL20.glUniformMatrix4fv(loc, transpose, ((HeadlessBuffer) buffer).getBuffer());
    }

    /**
     * TODO: document {@code getUniformfv}.
     *
     * @param program TODO: describe
     * @param location TODO: describe
     * @param val TODO: describe
     */
    @Override
    public void getUniformfv(int program, int location, IxBuffer val) {
        GL20.glGetUniformfv(program, location, ((HeadlessBuffer) val).getBuffer());
    }

    /**
     * TODO: document {@code getActiveUniform}.
     *
     * @param program TODO: describe
     * @param index TODO: describe
     * @param sizeBuffer TODO: describe
     * @param typeBuffer TODO: describe
     * @return TODO: describe
     */
    @Override
    public String getActiveUniform(int program, int index, IntBuffer sizeBuffer, IntBuffer typeBuffer) {
        return GL20.glGetActiveUniform(program, index, sizeBuffer, typeBuffer);
    }

    /**
     * TODO: document {@code getAttachedShaders}.
     *
     * @param program TODO: describe
     * @param success TODO: describe
     */
    @Override
    public void getAttachedShaders(int program, IntBuffer success) {
        if (success != null && success.remaining() > 0) {
            success.put(0, 0);
        }
    }

    /**
     * TODO: document {@code getActiveUniforms}.
     *
     * @param program TODO: describe
     * @param success TODO: describe
     */
    @Override
    public void getActiveUniforms(int program, IntBuffer success) {
        if (success != null && success.remaining() > 0) {
            success.put(0, 0);
        }
    }

    /**
     * TODO: document {@code FRAGMENT_SHADER}.
     *
     * @return TODO: describe
     */
    @Override public int FRAGMENT_SHADER() { return GL20.GL_FRAGMENT_SHADER; }
    /**
     * TODO: document {@code VERTEX_SHADER}.
     *
     * @return TODO: describe
     */
    @Override public int VERTEX_SHADER() { return GL20.GL_VERTEX_SHADER; }
    /**
     * TODO: document {@code LINK_STATUS}.
     *
     * @return TODO: describe
     */
    @Override public int LINK_STATUS() { return GL20.GL_LINK_STATUS; }
    /**
     * TODO: document {@code COMPILE_STATUS}.
     *
     * @return TODO: describe
     */
    @Override public int COMPILE_STATUS() { return GL20.GL_COMPILE_STATUS; }
    /**
     * TODO: document {@code ACTIVE_UNIFORMS}.
     *
     * @return TODO: describe
     */
    @Override public int ACTIVE_UNIFORMS() { return GL20.GL_ACTIVE_UNIFORMS; }
    /**
     * TODO: document {@code FLOAT_VEC2}.
     *
     * @return TODO: describe
     */
    @Override public int FLOAT_VEC2() { return GL20.GL_FLOAT_VEC2; }
    /**
     * TODO: document {@code FLOAT_VEC4}.
     *
     * @return TODO: describe
     */
    @Override public int FLOAT_VEC4() { return GL20.GL_FLOAT_VEC4; }
    /**
     * TODO: document {@code SAMPLER_2D}.
     *
     * @return TODO: describe
     */
    @Override public int SAMPLER_2D() { return GL20.GL_SAMPLER_2D; }

    // ---- GL30 (VAO, generateMipmap, bindFragDataLocation) ----

    /**
     * TODO: document {@code genVertexArray}.
     *
     * @return TODO: describe
     */
    @Override
    public int genVertexArray() {
        return GL30.glGenVertexArrays();
    }

    /**
     * TODO: document {@code genVertexArrays}.
     *
     * @return TODO: describe
     */
    @Override
    public int genVertexArrays() {
        return GL30.glGenVertexArrays();
    }

    /**
     * TODO: document {@code bindVertexArray}.
     *
     * @param vao TODO: describe
     */
    @Override
    public void bindVertexArray(int vao) {
        GL30.glBindVertexArray(vao);
    }

    /**
     * TODO: document {@code deleteVertexArrays}.
     *
     * @param id TODO: describe
     */
    @Override
    public void deleteVertexArrays(int id) {
        GL30.glDeleteVertexArrays(id);
    }

    /**
     * TODO: document {@code generateMipmap}.
     *
     * @param target TODO: describe
     */
    @Override
    public void generateMipmap(int target) {
        GL30.glGenerateMipmap(target);
    }

    /**
     * TODO: document {@code bindFragDataLocation}.
     *
     * @param program TODO: describe
     * @param colorNumber TODO: describe
     * @param name TODO: describe
     */
    @Override
    public void bindFragDataLocation(int program, int colorNumber, String name) {
        GL30.glBindFragDataLocation(program, colorNumber, name);
    }

    // ---- Platform / misc ----

    /**
     * TODO: document {@code getMouseButton}.
     *
     * @param window TODO: describe
     * @param button TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean getMouseButton(long window, MouseButtons button) {
        return false;
    }

    /**
     * TODO: document {@code createCapabilities}.
     */
    @Override
    public void createCapabilities() {
        // Already created in init
    }

    /**
     * TODO: document {@code setWindowTitle}.
     *
     * @param title TODO: describe
     */
    @Override
    public void setWindowTitle(String title) {
        if (window != NULL) {
            org.lwjgl.glfw.GLFW.glfwSetWindowTitle(window, title);
        }
    }

    /**
     * TODO: document {@code coldStartStack}.
     */
    @Override
    public void coldStartStack() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            @SuppressWarnings("unused")
            FloatBuffer buffer = new org.joml.Matrix4f().get(stack.mallocFloat(NUM_16));
        }
    }

    /**
     * TODO: document {@code getShaders}.
     *
     * @return TODO: describe
     */
    @Override
    public ArrayList<ShaderProgram> getShaders() {
        return shaders;
    }

    /**
     * TODO: document {@code addShader}.
     *
     * @param shader TODO: describe
     */
    @Override
    public void addShader(ShaderProgram shader) {
        shaders.add(shader);
    }

    /**
     * TODO: document {@code getPlatformID}.
     *
     * @return TODO: describe
     */
    @Override
    public int getPlatformID() {
        return platformId;
    }

    /**
     * TODO: document {@code setPlatformID}.
     *
     * @param p TODO: describe
     */
    @Override
    public void setPlatformID(Integer p) {
        if (p != null) {
            this.platformId = p.intValue();
        }
    }

    // ---- Headless-specific methods ----

    /**
     * TODO: document {@code getWidth}.
     *
     * @return TODO: describe
     */
    public int getWidth() {
        return framebufferWidth;
    }

    /**
     * TODO: document {@code getHeight}.
     *
     * @return TODO: describe
     */
    public int getHeight() {
        return framebufferHeight;
    }

    /**
     * TODO: document {@code swapBuffers}.
     */
    public void swapBuffers() {
        if (window != NULL) {
            glfwSwapBuffers(window);
        }
    }

    /**
     * TODO: document {@code pollEvents}.
     */
    public void pollEvents() {
        if (window != NULL) {
            glfwPollEvents();
        }
    }

    /**
     * TODO: document {@code shutdown}.
     */
    public void shutdown() {
        if (window != NULL) {
            glfwDestroyWindow(window);
            window = NULL;
        }
        glfwTerminate();
        initialized = false;
    }
}
