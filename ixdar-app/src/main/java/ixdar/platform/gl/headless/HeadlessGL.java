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

    private int platformId;
    private long window;
    private int framebufferWidth = 512;
    private int framebufferHeight = 512;
    private int idCounter = 1;
    private ArrayList<ShaderProgram> shaders = new ArrayList<>();
    private boolean initialized = false;

    public HeadlessGL() {
        this(512, 512);
    }

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
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
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

    @Override
    public void viewport(int x, int y, int w, int h) {
        GL11.glViewport(x, y, w, h);
    }

    @Override
    public void clearColor(float r, float g, float b, float a) {
        GL11.glClearColor(r, g, b, a);
    }

    @Override
    public void clear(int mask) {
        GL11.glClear(mask);
    }

    @Override
    public void drawArrays(int mode, int first, int count) {
        GL11.glDrawArrays(mode, first, count);
    }

    @Override
    public void drawElements(int mode, int count, int type, int indicesOffsetBytes) {
        GL11.glDrawElements(mode, count, type, indicesOffsetBytes);
    }

    @Override
    public int genTexture() {
        int[] textures = new int[1];
        GL11.glGenTextures(textures);
        return textures[0];
    }

    @Override
    public void deleteTexture(int id) {
        GL11.glDeleteTextures(id);
    }

    @Override
    public void bindTexture2D(int id) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
    }

    @Override
    public void texParameteri(int target, int pname, int param) {
        GL11.glTexParameteri(target, pname, param);
    }

    @Override
    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format,
            int type, ByteBuffer data) {
        GL11.glTexImage2D(target, level, internalFormat, width, height, border, format, type, data);
    }

    @Override
    public void enable(int cap) {
        GL11.glEnable(cap);
    }

    @Override
    public void disable(int cap) {
        GL11.glDisable(cap);
    }

    @Override
    public void depthMask(boolean flag) {
        GL11.glDepthMask(flag);
    }

    @Override
    public void blendFunc(int sfactor, int dfactor) {
        GL11.glBlendFunc(sfactor, dfactor);
    }

    @Override
    public void lineWidth(float width) {
        GL11.glLineWidth(width);
    }

    @Override
    public int[] readPixels(int x, int y, int width, int height, int format, int type, int fb) {
        ByteBuffer frameBuffer = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(x, y, width, height, format, type, frameBuffer);

        int[] pixels = new int[width * height];
        for (int k = 0; k < pixels.length; k++) {
            int bindex = k * 4;
            int r = frameBuffer.get(bindex) & 0xFF;
            int g = frameBuffer.get(bindex + 1) & 0xFF;
            int b = frameBuffer.get(bindex + 2) & 0xFF;
            int a = frameBuffer.get(bindex + 3) & 0xFF;
            pixels[k] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return pixels;
    }

    // ---- GL11 constants ----

    @Override public int COLOR_BUFFER_BIT() { return GL11.GL_COLOR_BUFFER_BIT; }
    @Override public int DEPTH_BUFFER_BIT() { return GL11.GL_DEPTH_BUFFER_BIT; }
    @Override public int TRIANGLES() { return GL11.GL_TRIANGLES; }
    @Override public int LINES() { return GL11.GL_LINES; }
    @Override public int FLOAT() { return GL11.GL_FLOAT; }
    @Override public int UNSIGNED_BYTE() { return GL11.GL_UNSIGNED_BYTE; }
    @Override public int UNSIGNED_INT() { return GL11.GL_UNSIGNED_INT; }
    @Override public int TEXTURE_2D() { return GL11.GL_TEXTURE_2D; }
    @Override public int RGBA() { return GL11.GL_RGBA; }
    @Override public int RGBA8() { return GL11.GL_RGBA8; }
    @Override public int TEXTURE_WRAP_S() { return GL11.GL_TEXTURE_WRAP_S; }
    @Override public int TEXTURE_WRAP_T() { return GL11.GL_TEXTURE_WRAP_T; }
    @Override public int TEXTURE_MIN_FILTER() { return GL11.GL_TEXTURE_MIN_FILTER; }
    @Override public int TEXTURE_MAG_FILTER() { return GL11.GL_TEXTURE_MAG_FILTER; }
    @Override public int LINEAR() { return GL11.GL_LINEAR; }
    @Override public int LINEAR_MIPMAP_LINEAR() { return GL11.GL_LINEAR_MIPMAP_LINEAR; }
    @Override public int REPEAT() { return GL11.GL_REPEAT; }
    @Override public int DEPTH_TEST() { return GL11.GL_DEPTH_TEST; }
    @Override public int BLEND() { return GL11.GL_BLEND; }
    @Override public int SRC_ALPHA() { return GL11.GL_SRC_ALPHA; }
    @Override public int ONE_MINUS_SRC_ALPHA() { return GL11.GL_ONE_MINUS_SRC_ALPHA; }

    // ---- GL13 (multitexture) ----

    @Override
    public void activeTexture(int unit) {
        GL13.glActiveTexture(unit);
    }

    @Override public int TEXTURE0() { return GL13.GL_TEXTURE0; }

    // ---- GL15 (buffer objects) ----

    @Override
    public int genBuffer() {
        return GL15.glGenBuffers();
    }

    @Override
    public int genBuffers() {
        return GL15.glGenBuffers();
    }

    @Override
    public void bindArrayBuffer(int buffer) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
    }

    @Override
    public void bindBuffer(int target, int id) {
        GL15.glBindBuffer(target, id);
    }

    @Override
    public void bufferDataArray(IxBuffer data, int usage) {
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ((HeadlessBuffer) data).getBuffer(), usage);
    }

    @Override
    public void bufferDataArray(float[] data, int usage) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(data.length);
        buf.put(data).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, usage);
    }

    @Override
    public void bufferData(int target, IxBuffer data, int usage) {
        GL15.glBufferData(target, ((HeadlessBuffer) data).getBuffer(), usage);
    }

    @Override
    public void bufferData(int target, float[] data, int usage) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(data.length);
        buf.put(data).flip();
        GL15.glBufferData(target, buf, usage);
    }

    @Override
    public void bufferData(int target, long size, int usage) {
        GL15.glBufferData(target, size, usage);
    }

    @Override
    public void bufferData(int target, IntBuffer data, int usage) {
        GL15.glBufferData(target, data, usage);
    }

    @Override
    public void bufferSubData(int target, long offset, IxBuffer data) {
        GL15.glBufferSubData(target, offset, ((HeadlessBuffer) data).getBuffer());
    }

    @Override
    public void deleteBuffers(int id) {
        GL15.glDeleteBuffers(id);
    }

    @Override public int ARRAY_BUFFER() { return GL15.GL_ARRAY_BUFFER; }
    @Override public int ELEMENT_ARRAY_BUFFER() { return GL15.GL_ELEMENT_ARRAY_BUFFER; }
    @Override public int STATIC_DRAW() { return GL15.GL_STATIC_DRAW; }
    @Override public int DYNAMIC_DRAW() { return GL15.GL_DYNAMIC_DRAW; }

    // ---- GL20 (shaders, programs, uniforms, attribs) ----

    @Override
    public int createProgram() {
        return GL20.glCreateProgram();
    }

    @Override
    public int createShader(int type) {
        return GL20.glCreateShader(type);
    }

    @Override
    public void shaderSource(int shader, String src) {
        GL20.glShaderSource(shader, src);
    }

    @Override
    public void shaderSource(int shader, CharSequence[] src) {
        GL20.glShaderSource(shader, src);
    }

    @Override
    public void compileShader(int shader) {
        GL20.glCompileShader(shader);
    }

    @Override
    public int getShaderiv(int shader, int pname) {
        IntBuffer buf = BufferUtils.createIntBuffer(1);
        GL20.glGetShaderiv(shader, pname, buf);
        return buf.get(0);
    }

    @Override
    public void getShaderiv(int shader, int pname, IntBuffer success) {
        GL20.glGetShaderiv(shader, pname, success);
    }

    @Override
    public String getShaderInfoLog(int shader) {
        return GL20.glGetShaderInfoLog(shader);
    }

    @Override
    public void attachShader(int program, int shader) {
        GL20.glAttachShader(program, shader);
    }

    @Override
    public void detachShader(int program, int shader) {
        GL20.glDetachShader(program, shader);
    }

    @Override
    public void linkProgram(int program) {
        GL20.glLinkProgram(program);
    }

    @Override
    public int getProgramiv(int program, int pname) {
        IntBuffer buf = BufferUtils.createIntBuffer(1);
        GL20.glGetProgramiv(program, pname, buf);
        return buf.get(0);
    }

    @Override
    public void getProgramiv(int program, int pname, IntBuffer success) {
        GL20.glGetProgramiv(program, pname, success);
    }

    @Override
    public String getProgramInfoLog(int program) {
        return GL20.glGetProgramInfoLog(program);
    }

    @Override
    public void useProgram(int program) {
        GL20.glUseProgram(program);
    }

    @Override
    public void deleteShader(int shader) {
        GL20.glDeleteShader(shader);
    }

    @Override
    public void deleteProgram(int program) {
        GL20.glDeleteProgram(program);
    }

    @Override
    public int getUniformLocation(int program, String name) {
        return GL20.glGetUniformLocation(program, name);
    }

    @Override
    public int getAttribLocation(int program, CharSequence name) {
        return GL20.glGetAttribLocation(program, name.toString());
    }

    @Override
    public void enableVertexAttribArray(int index) {
        GL20.glEnableVertexAttribArray(index);
    }

    @Override
    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int pointer) {
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    @Override
    public void uniform1f(int loc, float v) {
        GL20.glUniform1f(loc, v);
    }

    @Override
    public void uniform1i(int loc, int v) {
        GL20.glUniform1i(loc, v);
    }

    @Override
    public void uniform2fv(int loc, IxBuffer buffer) {
        GL20.glUniform2fv(loc, ((HeadlessBuffer) buffer).getBuffer());
    }

    @Override
    public void uniform3fv(int loc, IxBuffer buffer) {
        GL20.glUniform3fv(loc, ((HeadlessBuffer) buffer).getBuffer());
    }

    @Override
    public void uniform3fv(Integer loc, IxBuffer buffer) {
        GL20.glUniform3fv(loc, ((HeadlessBuffer) buffer).getBuffer());
    }

    @Override
    public void uniform4fv(int loc, IxBuffer buffer) {
        GL20.glUniform4fv(loc, ((HeadlessBuffer) buffer).getBuffer());
    }

    @Override
    public void uniformMatrix4fv(int loc, boolean transpose, IxBuffer buffer) {
        GL20.glUniformMatrix4fv(loc, transpose, ((HeadlessBuffer) buffer).getBuffer());
    }

    @Override
    public void getUniformfv(int program, int location, IxBuffer val) {
        GL20.glGetUniformfv(program, location, ((HeadlessBuffer) val).getBuffer());
    }

    @Override
    public String getActiveUniform(int program, int index, IntBuffer sizeBuffer, IntBuffer typeBuffer) {
        return GL20.glGetActiveUniform(program, index, sizeBuffer, typeBuffer);
    }

    @Override
    public void getAttachedShaders(int program, IntBuffer success) {
        if (success != null && success.remaining() > 0) {
            success.put(0, 0);
        }
    }

    @Override
    public void getActiveUniforms(int program, IntBuffer success) {
        if (success != null && success.remaining() > 0) {
            success.put(0, 0);
        }
    }

    @Override public int FRAGMENT_SHADER() { return GL20.GL_FRAGMENT_SHADER; }
    @Override public int VERTEX_SHADER() { return GL20.GL_VERTEX_SHADER; }
    @Override public int LINK_STATUS() { return GL20.GL_LINK_STATUS; }
    @Override public int COMPILE_STATUS() { return GL20.GL_COMPILE_STATUS; }
    @Override public int ACTIVE_UNIFORMS() { return GL20.GL_ACTIVE_UNIFORMS; }
    @Override public int FLOAT_VEC2() { return GL20.GL_FLOAT_VEC2; }
    @Override public int FLOAT_VEC4() { return GL20.GL_FLOAT_VEC4; }
    @Override public int SAMPLER_2D() { return GL20.GL_SAMPLER_2D; }

    // ---- GL30 (VAO, generateMipmap, bindFragDataLocation) ----

    @Override
    public int genVertexArray() {
        return GL30.glGenVertexArrays();
    }

    @Override
    public int genVertexArrays() {
        return GL30.glGenVertexArrays();
    }

    @Override
    public void bindVertexArray(int vao) {
        GL30.glBindVertexArray(vao);
    }

    @Override
    public void deleteVertexArrays(int id) {
        GL30.glDeleteVertexArrays(id);
    }

    @Override
    public void generateMipmap(int target) {
        GL30.glGenerateMipmap(target);
    }

    @Override
    public void bindFragDataLocation(int program, int colorNumber, String name) {
        GL30.glBindFragDataLocation(program, colorNumber, name);
    }

    // ---- Platform / misc ----

    @Override
    public boolean getMouseButton(long window, MouseButtons button) {
        return false;
    }

    @Override
    public void createCapabilities() {
        // Already created in init
    }

    @Override
    public void setWindowTitle(String title) {
        if (window != NULL) {
            org.lwjgl.glfw.GLFW.glfwSetWindowTitle(window, title);
        }
    }

    @Override
    public void coldStartStack() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            @SuppressWarnings("unused")
            FloatBuffer buffer = new org.joml.Matrix4f().get(stack.mallocFloat(16));
        }
    }

    @Override
    public ArrayList<ShaderProgram> getShaders() {
        return shaders;
    }

    @Override
    public void addShader(ShaderProgram shader) {
        shaders.add(shader);
    }

    @Override
    public int getPlatformID() {
        return platformId;
    }

    @Override
    public void setPlatformID(Integer p) {
        if (p != null) {
            this.platformId = p.intValue();
        }
    }

    // ---- Headless-specific methods ----

    public int getWidth() {
        return framebufferWidth;
    }

    public int getHeight() {
        return framebufferHeight;
    }

    public void swapBuffers() {
        if (window != NULL) {
            glfwSwapBuffers(window);
        }
    }

    public void pollEvents() {
        if (window != NULL) {
            glfwPollEvents();
        }
    }

    public void shutdown() {
        if (window != NULL) {
            glfwDestroyWindow(window);
            window = NULL;
        }
        glfwTerminate();
        initialized = false;
    }
}
