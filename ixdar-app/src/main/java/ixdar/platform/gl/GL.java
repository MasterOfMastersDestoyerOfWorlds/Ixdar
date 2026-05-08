package ixdar.platform.gl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.input.MouseButtons;

public interface GL {
    /**
     * TODO: document {@code viewport}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     * @param w TODO: describe
     * @param h TODO: describe
     */
    void viewport(int x, int y, int w, int h);

    /**
     * TODO: document {@code clearColor}.
     *
     * @param r TODO: describe
     * @param g TODO: describe
     * @param b TODO: describe
     * @param a TODO: describe
     */
    void clearColor(float r, float g, float b, float a);

    /**
     * TODO: document {@code clear}.
     *
     * @param mask TODO: describe
     */
    void clear(int mask);

    /**
     * TODO: document {@code createProgram}.
     *
     * @return TODO: describe
     */
    int createProgram();

    /**
     * TODO: document {@code createShader}.
     *
     * @param type TODO: describe
     * @return TODO: describe
     */
    int createShader(int type);

    /**
     * TODO: document {@code shaderSource}.
     *
     * @param shader TODO: describe
     * @param src TODO: describe
     */
    void shaderSource(int shader, String src);

    /**
     * TODO: document {@code compileShader}.
     *
     * @param shader TODO: describe
     */
    void compileShader(int shader);

    /**
     * TODO: document {@code getShaderiv}.
     *
     * @param shader TODO: describe
     * @param pname TODO: describe
     * @return TODO: describe
     */
    int getShaderiv(int shader, int pname);

    /**
     * TODO: document {@code getShaderInfoLog}.
     *
     * @param shader TODO: describe
     * @return TODO: describe
     */
    String getShaderInfoLog(int shader);

    /**
     * TODO: document {@code attachShader}.
     *
     * @param program TODO: describe
     * @param shader TODO: describe
     */
    void attachShader(int program, int shader);

    /**
     * TODO: document {@code linkProgram}.
     *
     * @param program TODO: describe
     */
    void linkProgram(int program);

    /**
     * TODO: document {@code getProgramiv}.
     *
     * @param program TODO: describe
     * @param pname TODO: describe
     * @return TODO: describe
     */
    int getProgramiv(int program, int pname);

    /**
     * TODO: document {@code getProgramInfoLog}.
     *
     * @param program TODO: describe
     * @return TODO: describe
     */
    String getProgramInfoLog(int program);

    /**
     * TODO: document {@code useProgram}.
     *
     * @param program TODO: describe
     */
    void useProgram(int program);

    /**
     * TODO: document {@code deleteShader}.
     *
     * @param shader TODO: describe
     */
    void deleteShader(int shader);

    /**
     * TODO: document {@code deleteProgram}.
     *
     * @param program TODO: describe
     */
    void deleteProgram(int program);

    /**
     * TODO: document {@code genBuffer}.
     *
     * @return TODO: describe
     */
    int genBuffer();

    /**
     * TODO: document {@code bindArrayBuffer}.
     *
     * @param buffer TODO: describe
     */
    void bindArrayBuffer(int buffer);

    /**
     * TODO: document {@code bufferDataArray}.
     *
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    void bufferDataArray(IxBuffer data, int usage);

    /**
     * TODO: document {@code bufferDataArray}.
     *
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    void bufferDataArray(float[] data, int usage);

    /**
     * TODO: document {@code enableVertexAttribArray}.
     *
     * @param index TODO: describe
     */
    void enableVertexAttribArray(int index);

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
    void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int pointer);

    /**
     * TODO: document {@code genVertexArray}.
     *
     * @return TODO: describe
     */
    int genVertexArray();

    /**
     * TODO: document {@code bindVertexArray}.
     *
     * @param vao TODO: describe
     */
    void bindVertexArray(int vao);

    /**
     * TODO: document {@code drawArrays}.
     *
     * @param mode TODO: describe
     * @param first TODO: describe
     * @param count TODO: describe
     */
    void drawArrays(int mode, int first, int count);
    /**
     * TODO: document {@code drawElements}.
     *
     * @param mode TODO: describe
     * @param count TODO: describe
     * @param type TODO: describe
     * @param indicesOffsetBytes TODO: describe
     */
    void drawElements(int mode, int count, int type, int indicesOffsetBytes);

    /**
     * TODO: document {@code getUniformLocation}.
     *
     * @param program TODO: describe
     * @param name TODO: describe
     * @return TODO: describe
     */
    int getUniformLocation(int program, String name);

    /**
     * TODO: document {@code uniform1f}.
     *
     * @param loc TODO: describe
     * @param v TODO: describe
     */
    void uniform1f(int loc, float v);

    /**
     * TODO: document {@code uniform1i}.
     *
     * @param loc TODO: describe
     * @param v TODO: describe
     */
    void uniform1i(int loc, int v);

    /**
     * TODO: document {@code uniform2fv}.
     *
     * @param loc TODO: describe
     * @param buffer TODO: describe
     */
    void uniform2fv(int loc, IxBuffer buffer);

    /**
     * TODO: document {@code uniform3fv}.
     *
     * @param loc TODO: describe
     * @param buffer TODO: describe
     */
    void uniform3fv(int loc, IxBuffer buffer);

    /**
     * TODO: document {@code uniform4fv}.
     *
     * @param loc TODO: describe
     * @param buffer TODO: describe
     */
    void uniform4fv(int loc, IxBuffer buffer);

    /**
     * TODO: document {@code uniformMatrix4fv}.
     *
     * @param loc TODO: describe
     * @param transpose TODO: describe
     * @param buffer TODO: describe
     */
    void uniformMatrix4fv(int loc, boolean transpose, IxBuffer buffer);

    /**
     * TODO: document {@code genTexture}.
     *
     * @return TODO: describe
     */
    int genTexture();
    /**
     * TODO: document {@code deleteTexture}.
     *
     * @param id TODO: describe
     */
    void deleteTexture(int id);

    /**
     * TODO: document {@code bindTexture2D}.
     *
     * @param id TODO: describe
     */
    void bindTexture2D(int id);

    /**
     * TODO: document {@code texParameteri}.
     *
     * @param target TODO: describe
     * @param pname TODO: describe
     * @param param TODO: describe
     */
    void texParameteri(int target, int pname, int param);

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
    void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type,
            ByteBuffer data);

    /**
     * TODO: document {@code generateMipmap}.
     *
     * @param target TODO: describe
     */
    void generateMipmap(int target);

    /**
     * TODO: document {@code COLOR_BUFFER_BIT}.
     *
     * @return TODO: describe
     */
    int COLOR_BUFFER_BIT();

    /**
     * TODO: document {@code DEPTH_BUFFER_BIT}.
     *
     * @return TODO: describe
     */
    int DEPTH_BUFFER_BIT();

    /**
     * TODO: document {@code TRIANGLES}.
     *
     * @return TODO: describe
     */
    int TRIANGLES();

    /**
     * TODO: document {@code ARRAY_BUFFER}.
     *
     * @return TODO: describe
     */
    int ARRAY_BUFFER();
    /**
     * TODO: document {@code ELEMENT_ARRAY_BUFFER}.
     *
     * @return TODO: describe
     */
    int ELEMENT_ARRAY_BUFFER();

    /**
     * TODO: document {@code STATIC_DRAW}.
     *
     * @return TODO: describe
     */
    int STATIC_DRAW();

    /**
     * TODO: document {@code FLOAT}.
     *
     * @return TODO: describe
     */
    int FLOAT();

    /**
     * TODO: document {@code FRAGMENT_SHADER}.
     *
     * @return TODO: describe
     */
    int FRAGMENT_SHADER();

    /**
     * TODO: document {@code VERTEX_SHADER}.
     *
     * @return TODO: describe
     */
    int VERTEX_SHADER();

    /**
     * TODO: document {@code TEXTURE_2D}.
     *
     * @return TODO: describe
     */
    int TEXTURE_2D();

    /**
     * TODO: document {@code RGBA}.
     *
     * @return TODO: describe
     */
    int RGBA();

    /**
     * TODO: document {@code RGBA8}.
     *
     * @return TODO: describe
     */
    int RGBA8();

    /**
     * TODO: document {@code UNSIGNED_BYTE}.
     *
     * @return TODO: describe
     */
    int UNSIGNED_BYTE();
    /**
     * TODO: document {@code UNSIGNED_INT}.
     *
     * @return TODO: describe
     */
    int UNSIGNED_INT();

    /**
     * TODO: document {@code TEXTURE_WRAP_S}.
     *
     * @return TODO: describe
     */
    int TEXTURE_WRAP_S();

    /**
     * TODO: document {@code TEXTURE_WRAP_T}.
     *
     * @return TODO: describe
     */
    int TEXTURE_WRAP_T();

    /**
     * TODO: document {@code TEXTURE_MIN_FILTER}.
     *
     * @return TODO: describe
     */
    int TEXTURE_MIN_FILTER();

    /**
     * TODO: document {@code TEXTURE_MAG_FILTER}.
     *
     * @return TODO: describe
     */
    int TEXTURE_MAG_FILTER();

    /**
     * TODO: document {@code LINES}.
     *
     * @return TODO: describe
     */
    int LINES();

    /**
     * TODO: document {@code lineWidth}.
     *
     * @param width TODO: describe
     */
    void lineWidth(float width);

    /**
     * TODO: document {@code LINEAR}.
     *
     * @return TODO: describe
     */
    int LINEAR();

    /**
     * TODO: document {@code REPEAT}.
     *
     * @return TODO: describe
     */
    int REPEAT();

    /**
     * TODO: document {@code getMouseButton}.
     *
     * @param window TODO: describe
     * @param mouseButtonLeft TODO: describe
     * @return TODO: describe
     */
    boolean getMouseButton(long window, MouseButtons mouseButtonLeft);

    /**
     * TODO: document {@code SRC_ALPHA}.
     *
     * @return TODO: describe
     */
    int SRC_ALPHA();

    /**
     * TODO: document {@code ONE_MINUS_SRC_ALPHA}.
     *
     * @return TODO: describe
     */
    int ONE_MINUS_SRC_ALPHA();

    /**
     * TODO: document {@code BLEND}.
     *
     * @return TODO: describe
     */
    int BLEND();

    /**
     * TODO: document {@code blendFunc}.
     *
     * @param SRC_ALPHA TODO: describe
     * @param ONE_MINUS_SRC_ALPHA TODO: describe
     */
    void blendFunc(int SRC_ALPHA, int ONE_MINUS_SRC_ALPHA);

    /**
     * TODO: document {@code enable}.
     *
     * @param blend TODO: describe
     */
    void enable(int blend);

    /**
     * TODO: document {@code disable}.
     *
     * @param depthTest TODO: describe
     */
    void disable(int depthTest);

    /**
     * TODO: document {@code depthMask}.
     *
     * @param flag TODO: describe
     */
    void depthMask(boolean flag);

    /**
     * TODO: document {@code createCapabilities}.
     */
    void createCapabilities();

    /**
     * TODO: document {@code DEPTH_TEST}.
     *
     * @return TODO: describe
     */
    int DEPTH_TEST();

    /**
     * TODO: document {@code setWindowTitle}.
     *
     * @param string TODO: describe
     */
    void setWindowTitle(String string);

    /**
     * TODO: document {@code genVertexArrays}.
     *
     * @return TODO: describe
     */
    int genVertexArrays();

    /**
     * TODO: document {@code deleteVertexArrays}.
     *
     * @param id TODO: describe
     */
    void deleteVertexArrays(int id);

    /**
     * TODO: document {@code genBuffers}.
     *
     * @return TODO: describe
     */
    int genBuffers();

    /**
     * TODO: document {@code bindBuffer}.
     *
     * @param target TODO: describe
     * @param id TODO: describe
     */
    void bindBuffer(int target, int id);

    /**
     * TODO: document {@code bufferData}.
     *
     * @param target TODO: describe
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    void bufferData(int target, IxBuffer data, int usage);

    /**
     * TODO: document {@code bufferData}.
     *
     * @param target TODO: describe
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    void bufferData(int target, float[] data, int usage);

    /**
     * TODO: document {@code bufferData}.
     *
     * @param target TODO: describe
     * @param size TODO: describe
     * @param usage TODO: describe
     */
    void bufferData(int target, long size, int usage);

    /**
     * TODO: document {@code bufferSubData}.
     *
     * @param target TODO: describe
     * @param offset TODO: describe
     * @param data TODO: describe
     */
    void bufferSubData(int target, long offset, IxBuffer data);

    /**
     * TODO: document {@code bufferData}.
     *
     * @param target TODO: describe
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    void bufferData(int target, IntBuffer data, int usage);

    /**
     * TODO: document {@code deleteBuffers}.
     *
     * @param id TODO: describe
     */
    void deleteBuffers(int id);

    /**
     * TODO: document {@code getAttribLocation}.
     *
     * @param iD TODO: describe
     * @param name TODO: describe
     * @return TODO: describe
     */
    int getAttribLocation(int iD, CharSequence name);

    /**
     * TODO: document {@code DYNAMIC_DRAW}.
     *
     * @return TODO: describe
     */
    int DYNAMIC_DRAW();

    /**
     * TODO: document {@code bindFragDataLocation}.
     *
     * @param iD TODO: describe
     * @param i TODO: describe
     * @param string TODO: describe
     */
    void bindFragDataLocation(int iD, int i, String string);

    /**
     * TODO: document {@code activeTexture}.
     *
     * @param i TODO: describe
     */
    void activeTexture(int i);

    /**
     * TODO: document {@code detachShader}.
     *
     * @param iD TODO: describe
     * @param fragmentShader TODO: describe
     */
    void detachShader(int iD, int fragmentShader);

    /**
     * TODO: document {@code shaderSource}.
     *
     * @param fragmentShader TODO: describe
     * @param fragmentShaderSource TODO: describe
     */
    void shaderSource(int fragmentShader, CharSequence[] fragmentShaderSource);

    /**
     * TODO: document {@code LINK_STATUS}.
     *
     * @return TODO: describe
     */
    int LINK_STATUS();

    /**
     * TODO: document {@code getProgramiv}.
     *
     * @param shader TODO: describe
     * @param link_STATUS TODO: describe
     * @param success TODO: describe
     */
    void getProgramiv(int shader, int link_STATUS, IntBuffer success);

    /**
     * TODO: document {@code COMPILE_STATUS}.
     *
     * @return TODO: describe
     */
    int COMPILE_STATUS();

    /**
     * TODO: document {@code getShaderiv}.
     *
     * @param shader TODO: describe
     * @param compile_STATUS TODO: describe
     * @param success TODO: describe
     */
    void getShaderiv(int shader, int compile_STATUS, IntBuffer success);

    /**
     * TODO: document {@code uniform3fv}.
     *
     * @param integer TODO: describe
     * @param vec3 TODO: describe
     */
    void uniform3fv(Integer integer, IxBuffer vec3);

    /**
     * TODO: document {@code readPixels}.
     *
     * @param i TODO: describe
     * @param j TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     * @param rgba TODO: describe
     * @param unsigned_BYTE TODO: describe
     * @param fb TODO: describe
     * @return TODO: describe
     */
    int[] readPixels(int i, int j, int width, int height, int rgba, int unsigned_BYTE, int fb);

    /**
     * TODO: document {@code TEXTURE0}.
     *
     * @return TODO: describe
     */
    int TEXTURE0();

    /**
     * TODO: document {@code coldStartStack}.
     */
    void coldStartStack();

    /**
     * TODO: document {@code getShaders}.
     *
     * @return TODO: describe
     */
    ArrayList<ShaderProgram> getShaders();

    /**
     * TODO: document {@code addShader}.
     *
     * @param shader TODO: describe
     */
    void addShader(ShaderProgram shader);

    /**
     * TODO: document {@code getPlatformID}.
     *
     * @return TODO: describe
     */
    int getPlatformID();

    /**
     * TODO: document {@code getAttachedShaders}.
     *
     * @param shader TODO: describe
     * @param success TODO: describe
     */
    void getAttachedShaders(int shader, IntBuffer success);

    /**
     * TODO: document {@code getActiveUniforms}.
     *
     * @param shader TODO: describe
     * @param success TODO: describe
     */
    void getActiveUniforms(int shader, IntBuffer success);

    /**
     * TODO: document {@code ACTIVE_UNIFORMS}.
     *
     * @return TODO: describe
     */
    int ACTIVE_UNIFORMS();

    /**
     * TODO: document {@code getActiveUniform}.
     *
     * @param iD TODO: describe
     * @param i TODO: describe
     * @param sizeBuffer TODO: describe
     * @param typeBuffer TODO: describe
     * @return TODO: describe
     */
    String getActiveUniform(int iD, int i, IntBuffer sizeBuffer, IntBuffer typeBuffer);

    /**
     * TODO: document {@code FLOAT_VEC2}.
     *
     * @return TODO: describe
     */
    int FLOAT_VEC2();

    /**
     * TODO: document {@code FLOAT_VEC4}.
     *
     * @return TODO: describe
     */
    int FLOAT_VEC4();

    /**
     * TODO: document {@code SAMPLER_2D}.
     *
     * @return TODO: describe
     */
    int SAMPLER_2D();

    /**
     * TODO: document {@code getUniformfv}.
     *
     * @param iD TODO: describe
     * @param location TODO: describe
     * @param val TODO: describe
     */
    void getUniformfv(int iD, int location, IxBuffer val);

    /**
     * TODO: document {@code setPlatformID}.
     *
     * @param p TODO: describe
     */
    void setPlatformID(Integer p);

    /**
     * TODO: document {@code LINEAR_MIPMAP_LINEAR}.
     *
     * @return TODO: describe
     */
    int LINEAR_MIPMAP_LINEAR();

    /**
     * When true, shader sources are left as GLSL ES ({@code #version 300 es}). When false, shared
     * sources are adapted for desktop OpenGL 3.3 core ({@link GlslSource}).
     *
     * @return TODO: describe
     */
    default boolean usesWebGlsl() {
        return false;
    }
}
