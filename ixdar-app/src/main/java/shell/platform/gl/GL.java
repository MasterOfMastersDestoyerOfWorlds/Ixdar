package shell.platform.gl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.function.IntFunction;

import org.lwjgl.PointerBuffer;

import shell.platform.input.MouseButtons;
import shell.render.shaders.ShaderProgram;

public interface GL {
    void viewport(int x, int y, int w, int h);

    void clearColor(float r, float g, float b, float a);

    void clear(int mask);

    int createProgram();

    int createShader(int type);

    void shaderSource(int shader, String src);

    void compileShader(int shader);

    int getShaderiv(int shader, int pname);

    String getShaderInfoLog(int shader);

    void attachShader(int program, int shader);

    void linkProgram(int program);

    int getProgramiv(int program, int pname);

    String getProgramInfoLog(int program);

    void useProgram(int program);

    void deleteShader(int shader);

    void deleteProgram(int program);

    int genBuffer();

    void bindArrayBuffer(int buffer);

    void bufferDataArray(IxBuffer data, int usage);

    void bufferDataArray(float[] data, int usage);

    void enableVertexAttribArray(int index);

    void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int pointer);

    int genVertexArray();

    void bindVertexArray(int vao);

    void drawArrays(int mode, int first, int count);

    int getUniformLocation(int program, String name);

    void uniform1f(int loc, float v);

    void uniform1i(int loc, int v);

    void uniform2fv(int loc, IxBuffer buffer);

    void uniform3fv(int loc, IxBuffer buffer);

    void uniform4fv(int loc, IxBuffer buffer);

    void uniformMatrix4fv(int loc, boolean transpose, IxBuffer buffer);

    int genTexture();

    void bindTexture2D(int id);

    void texParameteri(int target, int pname, int param);

    void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type,
            ByteBuffer data);

    void generateMipmap(int target);

    int COLOR_BUFFER_BIT();

    int DEPTH_BUFFER_BIT();

    int TRIANGLES();

    int ARRAY_BUFFER();

    int STATIC_DRAW();

    int FLOAT();

    int FRAGMENT_SHADER();

    int VERTEX_SHADER();

    int TEXTURE_2D();

    int RGBA();

    int RGBA8();

    int UNSIGNED_BYTE();

    int TEXTURE_WRAP_S();

    int TEXTURE_WRAP_T();

    int TEXTURE_MIN_FILTER();

    int TEXTURE_MAG_FILTER();

    int LINEAR();

    int REPEAT();

    boolean getMouseButton(long window, MouseButtons mouseButtonLeft);

    int SRC_ALPHA();

    int ONE_MINUS_SRC_ALPHA();

    int BLEND();

    void blendFunc(int SRC_ALPHA, int ONE_MINUS_SRC_ALPHA);

    void enable(int blend);

    void createCapabilities(boolean b, IntFunction<PointerBuffer> intFunction);

    int DEPTH_TEST();

    void setWindowTitle(String string);

    int genVertexArrays();

    void deleteVertexArrays(int id);

    int genBuffers();

    void bindBuffer(int target, int id);

    void bufferData(int target, IxBuffer data, int usage);

    void bufferData(int target, float[] data, int usage);

    void bufferData(int target, long size, int usage);

    void bufferSubData(int target, long offset, IxBuffer data);

    void bufferData(int target, IntBuffer data, int usage);

    void deleteBuffers(int id);

    int getAttribLocation(int iD, CharSequence name);

    int DYNAMIC_DRAW();

    void bindFragDataLocation(int iD, int i, String string);

    void activeTexture(int i);

    void detachShader(int iD, int fragmentShader);

    void shaderSource(int fragmentShader, CharSequence[] fragmentShaderSource);

    int LINK_STATUS();

    void getProgramiv(int shader, int link_STATUS, IntBuffer success);

    int COMPILE_STATUS();

    void getShaderiv(int shader, int compile_STATUS, IntBuffer success);

    void uniform3fv(Integer integer, IxBuffer vec3);

    int[] readPixels(int i, int j, int width, int height, int rgba, int unsigned_BYTE, int fb);

    int TEXTURE0();

    void coldStartStack();

    ArrayList<ShaderProgram> getShaders();

    void addShader(ShaderProgram shader);

    Integer getID();

    void getAttachedShaders(int shader, IntBuffer success);

    void getActiveUniforms(int shader, IntBuffer success);

    int ACTIVE_UNIFORMS();

    String getActiveUniform(int iD, int i, IntBuffer sizeBuffer, IntBuffer typeBuffer);

    int FLOAT_VEC2();

    int FLOAT_VEC4();

    int SAMPLER_2D();

    void getUniformfv(int iD, int location, IxBuffer val);
}
