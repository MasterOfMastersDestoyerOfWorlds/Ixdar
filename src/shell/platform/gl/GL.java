package shell.platform.gl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import shell.platform.input.MouseButtons;

public interface GL {

    // Core
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

    // Buffers
    int genBuffer();

    void bindArrayBuffer(int buffer);

    void bufferDataArray(FloatBuffer data, int usage);

    void bufferDataArray(float[] data, int usage);

    void enableVertexAttribArray(int index);

    void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int pointer);

    // Vertex arrays (no-op on WebGL1)
    int genVertexArray();

    void bindVertexArray(int vao);

    // Draw
    void drawArrays(int mode, int first, int count);

    // Uniforms
    int getUniformLocation(int program, String name);

    void uniform1f(int loc, float v);

    void uniform1i(int loc, int v);

    void uniform2fv(int loc, FloatBuffer buf);

    void uniform3fv(int loc, FloatBuffer buf);

    void uniform4fv(int loc, FloatBuffer buf);

    void uniformMatrix4fv(int loc, boolean transpose, FloatBuffer buf);

    // Textures
    int genTexture();

    void bindTexture2D(int id);

    void texParameteri(int target, int pname, int param);

    void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type,
            ByteBuffer data);

    void generateMipmap(int target);

    // Constants (subset)
    int COLOR_BUFFER_BIT();

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
}
