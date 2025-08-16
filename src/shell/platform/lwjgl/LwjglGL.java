package shell.platform.lwjgl;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCompileShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glCreateShader;
import static org.lwjgl.opengl.GL20.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20.glGetProgramiv;
import static org.lwjgl.opengl.GL20.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20.glGetShaderiv;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glShaderSource;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glUniform1f;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniform2fv;
import static org.lwjgl.opengl.GL20.glUniform3fv;
import static org.lwjgl.opengl.GL20.glUniform4fv;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.glfwGetMouseButton;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;

import shell.platform.gl.GL;
import shell.platform.input.MouseButtons;

public class LwjglGL implements GL {

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
    public void bufferDataArray(FloatBuffer data, int usage) {
        glBufferData(GL_ARRAY_BUFFER, data, usage);
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
    public void uniform2fv(int loc, java.nio.FloatBuffer buf) {
        glUniform2fv(loc, buf);
    }

    @Override
    public void uniform3fv(int loc, java.nio.FloatBuffer buf) {
        glUniform3fv(loc, buf);
    }

    @Override
    public void uniform4fv(int loc, java.nio.FloatBuffer buf) {
        glUniform4fv(loc, buf);
    }

    @Override
    public void uniformMatrix4fv(int loc, boolean transpose, java.nio.FloatBuffer buf) {
        glUniformMatrix4fv(loc, transpose, buf);
    }

    @Override
    public int genTexture() {
        return org.lwjgl.opengl.GL11.glGenTextures();
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
}
