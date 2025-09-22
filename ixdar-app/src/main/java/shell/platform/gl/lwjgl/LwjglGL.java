package shell.platform.gl.lwjgl;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetMouseButton;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCompileShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glCreateShader;
import static org.lwjgl.opengl.GL20.glDetachShader;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glGetAttribLocation;
import static org.lwjgl.opengl.GL20.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20.glGetProgramiv;
import static org.lwjgl.opengl.GL20.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20.glGetShaderiv;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glShaderSource;
import static org.lwjgl.opengl.GL20.glUniform1f;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniform2fv;
import static org.lwjgl.opengl.GL20.glUniform3fv;
import static org.lwjgl.opengl.GL20.glUniform4fv;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindFragDataLocation;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.function.IntFunction;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import shell.platform.gl.GL;
import shell.platform.gl.IxBuffer;
import shell.platform.input.MouseButtons;
import shell.render.shaders.ShaderProgram;
import shell.ui.IxdarWindow;

public class LwjglGL implements GL {

    private static int staticId = 0;
    private int id = staticId++;
    private final ArrayList<ShaderProgram> shaders = new ArrayList<>();

    @Override
    public Integer getID() {
        return id;
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
    public void viewport(int x, int y, int w, int h) {
        glViewport(x, y, w, h);
    }

    @Override
    public void clearColor(float r, float g, float b, float a) {
        glClearColor(r, g, b, a);
    }

    @Override
    public void clear(int mask) {
        glClear(mask);
    }

    @Override
    public int createProgram() {
        return glCreateProgram();
    }

    @Override
    public int createShader(int type) {
        return glCreateShader(type);
    }

    @Override
    public void shaderSource(int shader, String src) {
        glShaderSource(shader, src);
    }

    @Override
    public void compileShader(int shader) {
        glCompileShader(shader);
    }

    @Override
    public int getShaderiv(int shader, int pname) {
        IntBuffer buf = BufferUtils.createIntBuffer(1);
        glGetShaderiv(shader, pname, buf);
        return buf.get(0);
    }

    @Override
    public String getShaderInfoLog(int shader) {
        return glGetShaderInfoLog(shader);
    }

    @Override
    public void attachShader(int program, int shader) {
        glAttachShader(program, shader);
    }

    @Override
    public void linkProgram(int program) {
        glLinkProgram(program);
    }

    @Override
    public int getProgramiv(int program, int pname) {
        IntBuffer buf = BufferUtils.createIntBuffer(1);
        glGetProgramiv(program, pname, buf);
        return buf.get(0);
    }

    @Override
    public String getProgramInfoLog(int program) {
        return glGetProgramInfoLog(program);
    }

    @Override
    public void useProgram(int program) {
        glUseProgram(program);
    }

    @Override
    public void deleteShader(int shader) {
        /* glDeleteShader(shader); */ }

    @Override
    public void deleteProgram(int program) {
        /* glDeleteProgram(program); */ }

    @Override
    public int genBuffer() {
        return glGenBuffers();
    }

    @Override
    public void bindArrayBuffer(int buffer) {
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
    }

    @Override
    public void bufferDataArray(IxBuffer data, int usage) {
        glBufferData(GL_ARRAY_BUFFER, ((DefaultBuffer)data).getFloatBuffer(), usage);
    }

    @Override
    public void bufferDataArray(float[] data, int usage) {
        glBufferData(GL_ARRAY_BUFFER, data, usage);
    }

    @Override
    public void enableVertexAttribArray(int index) {
        glEnableVertexAttribArray(index);
    }

    @Override
    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int pointer) {
        glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    @Override
    public int genVertexArray() {
        return glGenVertexArrays();
    }

    @Override
    public void bindVertexArray(int vao) {
        glBindVertexArray(vao);
    }

    @Override
    public void drawArrays(int mode, int first, int count) {
        glDrawArrays(mode, first, count);
    }

    @Override
    public int getUniformLocation(int program, String name) {
        return glGetUniformLocation(program, name);
    }

    @Override
    public void uniform1f(int loc, float v) {
        glUniform1f(loc, v);
    }

    @Override
    public void uniform1i(int loc, int v) {
        glUniform1i(loc, v);
    }

    @Override
    public void uniform2fv(int loc, IxBuffer buf) {
        glUniform2fv(loc, ((DefaultBuffer)buf).getFloatBuffer());
    }

    @Override
    public void uniform3fv(int loc, IxBuffer buf) {
        glUniform3fv(loc, ((DefaultBuffer)buf).getFloatBuffer());
    }

    @Override
    public void uniform4fv(int loc, IxBuffer buf) {
        glUniform4fv(loc, ((DefaultBuffer)buf).getFloatBuffer());
    }

    @Override
    public void uniformMatrix4fv(int loc, boolean transpose, IxBuffer buf) {
        glUniformMatrix4fv(loc, transpose, ((DefaultBuffer)buf).getFloatBuffer());
    }

    @Override
    public int genTexture() {
        return glGenTextures();
    }

    @Override
    public void bindTexture2D(int id) {
        glBindTexture(GL_TEXTURE_2D, id);
    }

    @Override
    public void texParameteri(int target, int pname, int param) {
        glTexParameteri(target, pname, param);
    }

    @Override
    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format,
            int type, ByteBuffer data) {
        glTexImage2D(target, level, internalFormat, width, height, border, format, type, data);
    }

    @Override
    public void generateMipmap(int target) {
        glGenerateMipmap(target);
    }

    @Override
    public int COLOR_BUFFER_BIT() {
        return GL_COLOR_BUFFER_BIT;
    }

    @Override
    public int TRIANGLES() {
        return GL_TRIANGLES;
    }

    @Override
    public int ARRAY_BUFFER() {
        return GL_ARRAY_BUFFER;
    }

    @Override
    public int STATIC_DRAW() {
        return GL_STATIC_DRAW;
    }

    @Override
    public int FLOAT() {
        return GL_FLOAT;
    }

    @Override
    public int FRAGMENT_SHADER() {
        return GL_FRAGMENT_SHADER;
    }

    @Override
    public int VERTEX_SHADER() {
        return GL_VERTEX_SHADER;
    }

    @Override
    public int TEXTURE_2D() {
        return GL_TEXTURE_2D;
    }

    @Override
    public int RGBA() {
        return GL_RGBA;
    }

    @Override
    public int RGBA8() {
        return GL_RGBA8;
    }

    @Override
    public int UNSIGNED_BYTE() {
        return GL_UNSIGNED_BYTE;
    }

    @Override
    public int TEXTURE_WRAP_S() {
        return org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
    }

    @Override
    public int TEXTURE_WRAP_T() {
        return org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
    }

    @Override
    public int TEXTURE_MIN_FILTER() {
        return org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
    }

    @Override
    public int TEXTURE_MAG_FILTER() {
        return org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
    }

    @Override
    public int LINEAR() {
        return GL_LINEAR;
    }

    @Override
    public int REPEAT() {
        return GL_REPEAT;
    }

    @Override
    public boolean getMouseButton(long window, MouseButtons mouseButton) {
        int state = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT);
        return state == GLFW_PRESS;
    }

    @Override
    public int SRC_ALPHA() {
        return GL_SRC_ALPHA;
    }

    @Override
    public int ONE_MINUS_SRC_ALPHA() {
        return GL_ONE_MINUS_SRC_ALPHA;
    }

    @Override
    public int BLEND() {
        return GL_BLEND;
    }

    @Override
    public void blendFunc(int SRC_ALPHA, int ONE_MINUS_SRC_ALPHA) {
        glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA);
    }

    @Override
    public void enable(int blend) {
        glEnable(blend);
    }

    @Override
    public void createCapabilities(boolean b, IntFunction intFunction) {
        org.lwjgl.opengl.GL.createCapabilities(b, intFunction);
    }

    @Override
    public int DEPTH_TEST() {
        return GL_DEPTH_TEST;
    }

    @Override
    public int DEPTH_BUFFER_BIT() {
        return GL_DEPTH_BUFFER_BIT;
    }

    @Override
    public void setWindowTitle(String string) {
        IxdarWindow.setTitle(string);
    }

    @Override
    public int genVertexArrays() {
        return glGenVertexArrays();
    }

    @Override
    public void deleteVertexArrays(int id) {
        glDeleteVertexArrays(id);
    }

    @Override
    public int genBuffers() {
        return glGenBuffers();
    }

    @Override
    public void bindBuffer(int target, int id) {
        glBindBuffer(target, id);
    }

    @Override
    public void bufferData(int target, IxBuffer data, int usage) {
        glBufferData(target, ((DefaultBuffer)data).getFloatBuffer(), usage);
    }

    @Override
    public void bufferData(int target, float[] data, int usage) {
        glBufferData(target, data, usage);
    }

    @Override
    public void bufferData(int target, long size, int usage) {
        glBufferData(target, size, usage);
    }

    @Override
    public void bufferSubData(int target, long offset, IxBuffer data) {
        glBufferSubData(target, offset, ((DefaultBuffer)data).getFloatBuffer());
    }

    @Override
    public void bufferData(int target, IntBuffer data, int usage) {
        glBufferData(target, data, usage);
    }

    @Override
    public void deleteBuffers(int id) {
        glDeleteBuffers(id);
    }

    @Override
    public int getAttribLocation(int iD, CharSequence name) {
        return glGetAttribLocation(iD, name);
    }

    @Override
    public int DYNAMIC_DRAW() {
        return GL_DYNAMIC_DRAW;
    }

    @Override
    public void bindFragDataLocation(int iD, int i, String string) {
        glBindFragDataLocation(iD, i, string);
    }

    @Override
    public void activeTexture(int i) {
        glActiveTexture(i);
    }

    @Override
    public void detachShader(int iD, int fragmentShader) {
        glDetachShader(iD, fragmentShader);
    }

    @Override
    public void shaderSource(int fragmentShader, CharSequence[] fragmentShaderSource) {
        glShaderSource(fragmentShader, fragmentShaderSource);
    }

    @Override
    public int LINK_STATUS() {
        return GL_LINK_STATUS;
    }

    @Override
    public void getProgramiv(int shader, int link_STATUS, IntBuffer success) {
        glGetProgramiv(shader, link_STATUS, success);
    }

    @Override
    public int COMPILE_STATUS() {
        return GL_COMPILE_STATUS;
    }

    @Override
    public void getShaderiv(int shader, int compile_STATUS, IntBuffer success) {
        glGetShaderiv(shader, compile_STATUS, success);
    }

    @Override
    public void uniform3fv(Integer integer, IxBuffer vec3) {
        glUniform3fv(integer, ((DefaultBuffer)vec3).getFloatBuffer());
    }

    @Override
    public int[] readPixels(int i, int j, int width, int height, int rgba, int unsigned_BYTE, int size) {
        ByteBuffer frameBuffer = MemoryUtil.memAlloc(width * height * 4);
        glReadPixels(i, j, width, height, rgba, unsigned_BYTE, frameBuffer);
        int[] pixels = new int[width * height];
        // convert RGB data in ByteBuffer to integer array
        int bindex;
        for (int k = 0; k < pixels.length; k++) {
            bindex = k * 4;
            pixels[i] = ((frameBuffer.get(bindex) << 16)) +
                    ((frameBuffer.get(bindex + 1) << 8)) +
                    ((frameBuffer.get(bindex + 2) << 0));
        }
        MemoryUtil.memFree(frameBuffer);
        return pixels;
    }

    @Override
    public int TEXTURE0() {
        return GL_TEXTURE0;
    }

    @Override
    public void coldStartStack() {
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

}
