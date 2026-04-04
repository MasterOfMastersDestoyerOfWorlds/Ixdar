package ixdar.platform.gl.headless;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.IxBuffer;
import ixdar.platform.input.MouseButtons;

/**
 * Headless GL stub for testing without actual OpenGL context.
 * All methods are no-op or return sensible defaults.
 */
public class HeadlessGL implements GL {

    private int platformId;
    private int idCounter = 1;
    private ArrayList<ShaderProgram> shaders = new ArrayList<>();

    @Override
    public void viewport(int x, int y, int w, int h) {
    }

    @Override
    public void clearColor(float r, float g, float b, float a) {
    }

    @Override
    public void clear(int mask) {
    }

    @Override
    public int createProgram() {
        return idCounter++;
    }

    @Override
    public int createShader(int type) {
        return idCounter++;
    }

    @Override
    public void shaderSource(int shader, String src) {
    }

    @Override
    public void compileShader(int shader) {
    }

    @Override
    public int getShaderiv(int shader, int pname) {
        return 1;
    }

    @Override
    public String getShaderInfoLog(int shader) {
        return "";
    }

    @Override
    public void attachShader(int program, int shader) {
    }

    @Override
    public void linkProgram(int program) {
    }

    @Override
    public int getProgramiv(int program, int pname) {
        return 1;
    }

    @Override
    public String getProgramInfoLog(int program) {
        return "";
    }

    @Override
    public void useProgram(int program) {
    }

    @Override
    public void deleteShader(int shader) {
    }

    @Override
    public void deleteProgram(int program) {
    }

    @Override
    public int genBuffer() {
        return idCounter++;
    }

    @Override
    public void bindArrayBuffer(int buffer) {
    }

    @Override
    public void bufferDataArray(IxBuffer data, int usage) {
    }

    @Override
    public void bufferDataArray(float[] data, int usage) {
    }

    @Override
    public void enableVertexAttribArray(int index) {
    }

    @Override
    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int pointer) {
    }

    @Override
    public int genVertexArray() {
        return idCounter++;
    }

    @Override
    public void bindVertexArray(int vao) {
    }

    @Override
    public void drawArrays(int mode, int first, int count) {
    }

    @Override
    public void drawElements(int mode, int count, int type, int indicesOffsetBytes) {
    }

    @Override
    public int getUniformLocation(int program, String name) {
        return idCounter++;
    }

    @Override
    public void uniform1f(int loc, float v) {
    }

    @Override
    public void uniform1i(int loc, int v) {
    }

    @Override
    public void uniform2fv(int loc, IxBuffer buffer) {
    }

    @Override
    public void uniform3fv(int loc, IxBuffer buffer) {
    }

    @Override
    public void uniform4fv(int loc, IxBuffer buffer) {
    }

    @Override
    public void uniformMatrix4fv(int loc, boolean transpose, IxBuffer buffer) {
    }

    @Override
    public int genTexture() {
        return idCounter++;
    }

    @Override
    public void deleteTexture(int id) {
    }

    @Override
    public void bindTexture2D(int id) {
    }

    @Override
    public void texParameteri(int target, int pname, int param) {
    }

    @Override
    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format,
            int type, ByteBuffer data) {
    }

    @Override
    public void generateMipmap(int target) {
    }

    @Override
    public int COLOR_BUFFER_BIT() {
        return 0x4000;
    }

    @Override
    public int DEPTH_BUFFER_BIT() {
        return 0x100;
    }

    @Override
    public int TRIANGLES() {
        return 4;
    }

    @Override
    public int ARRAY_BUFFER() {
        return 0x8892;
    }

    @Override
    public int ELEMENT_ARRAY_BUFFER() {
        return 0x8893;
    }

    @Override
    public int STATIC_DRAW() {
        return 0x88E4;
    }

    @Override
    public int FLOAT() {
        return 0x1406;
    }

    @Override
    public int FRAGMENT_SHADER() {
        return 0x8B30;
    }

    @Override
    public int VERTEX_SHADER() {
        return 0x8B31;
    }

    @Override
    public int TEXTURE_2D() {
        return 0x0DE1;
    }

    @Override
    public int RGBA() {
        return 0x1908;
    }

    @Override
    public int RGBA8() {
        return 0x8058;
    }

    @Override
    public int UNSIGNED_BYTE() {
        return 0x1401;
    }

    @Override
    public int UNSIGNED_INT() {
        return 0x1405;
    }

    @Override
    public int TEXTURE_WRAP_S() {
        return 0x2802;
    }

    @Override
    public int TEXTURE_WRAP_T() {
        return 0x2803;
    }

    @Override
    public int TEXTURE_MIN_FILTER() {
        return 0x2801;
    }

    @Override
    public int TEXTURE_MAG_FILTER() {
        return 0x2800;
    }

    @Override
    public int LINEAR() {
        return 0x2601;
    }

    @Override
    public int REPEAT() {
        return 0x2901;
    }

    @Override
    public int LINES() {
        return 0x0001;
    }

    @Override
    public void lineWidth(float width) {
    }

    @Override
    public boolean getMouseButton(long window, MouseButtons mouseButtonLeft) {
        return false;
    }

    @Override
    public int SRC_ALPHA() {
        return 0x0302;
    }

    @Override
    public int ONE_MINUS_SRC_ALPHA() {
        return 0x0303;
    }

    @Override
    public int BLEND() {
        return 0x0BE2;
    }

    @Override
    public void blendFunc(int srcAlpha, int oneMinusSrcAlpha) {
    }

    @Override
    public void enable(int blend) {
    }
    @Override
    public void disable(int depthTest) {
    }

    @Override
    public void createCapabilities() {
    }

    @Override
    public int DEPTH_TEST() {
        return 0x0B71;
    }

    @Override
    public void setWindowTitle(String string) {
    }

    @Override
    public int genVertexArrays() {
        return idCounter++;
    }

    @Override
    public void deleteVertexArrays(int id) {
    }

    @Override
    public int genBuffers() {
        return idCounter++;
    }

    @Override
    public void bindBuffer(int target, int id) {
    }

    @Override
    public void bufferData(int target, IxBuffer data, int usage) {
    }

    @Override
    public void bufferData(int target, float[] data, int usage) {
    }

    @Override
    public void bufferData(int target, long size, int usage) {
    }

    @Override
    public void bufferSubData(int target, long offset, IxBuffer data) {
    }

    @Override
    public void bufferData(int target, IntBuffer data, int usage) {
    }

    @Override
    public void deleteBuffers(int id) {
    }

    @Override
    public int getAttribLocation(int iD, CharSequence name) {
        return idCounter++;
    }

    @Override
    public int DYNAMIC_DRAW() {
        return 0x88E8;
    }

    @Override
    public void bindFragDataLocation(int iD, int i, String string) {
    }

    @Override
    public void activeTexture(int i) {
    }

    @Override
    public void detachShader(int iD, int fragmentShader) {
    }

    @Override
    public void shaderSource(int fragmentShader, CharSequence[] fragmentShaderSource) {
    }

    @Override
    public int LINK_STATUS() {
        return 0x8B82;
    }

    @Override
    public void getProgramiv(int shader, int linkStatus, IntBuffer success) {
        if (success != null && success.remaining() > 0) {
            success.put(0, 1);
        }
    }

    @Override
    public int COMPILE_STATUS() {
        return 0x8B81;
    }

    @Override
    public void getShaderiv(int shader, int compileStatus, IntBuffer success) {
        if (success != null && success.remaining() > 0) {
            success.put(0, 1);
        }
    }

    @Override
    public void uniform3fv(Integer integer, IxBuffer vec3) {
    }

    @Override
    public int[] readPixels(int i, int j, int width, int height, int rgba, int unsignedByte, int fb) {
        return new int[width * height * 4];
    }

    @Override
    public int TEXTURE0() {
        return 0x84C0;
    }

    @Override
    public void coldStartStack() {
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
    public void getAttachedShaders(int shader, IntBuffer success) {
        if (success != null && success.remaining() > 0) {
            success.put(0, 0);
        }
    }

    @Override
    public void getActiveUniforms(int shader, IntBuffer success) {
        if (success != null && success.remaining() > 0) {
            success.put(0, 0);
        }
    }

    @Override
    public int ACTIVE_UNIFORMS() {
        return 0x8B86;
    }

    @Override
    public String getActiveUniform(int iD, int i, IntBuffer sizeBuffer, IntBuffer typeBuffer) {
        return "";
    }

    @Override
    public int FLOAT_VEC2() {
        return 0x8B50;
    }

    @Override
    public int FLOAT_VEC4() {
        return 0x8B52;
    }

    @Override
    public int SAMPLER_2D() {
        return 0x8B5E;
    }

    @Override
    public void getUniformfv(int iD, int location, IxBuffer val) {
    }

    @Override
    public void setPlatformID(Integer p) {
        if (p != null) {
            this.platformId = p.intValue();
        }
    }

    @Override
    public int LINEAR_MIPMAP_LINEAR() {
        return 0x2703;
    }
}
