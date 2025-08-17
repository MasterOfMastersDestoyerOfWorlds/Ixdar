package shell.platform.gl.web;

import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glGenBuffers;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.function.IntFunction;

import org.teavm.jso.JSBody;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.typedarrays.ArrayBufferView;
import org.teavm.jso.typedarrays.Uint8Array;
import org.teavm.jso.webgl.WebGLBuffer;
import org.teavm.jso.webgl.WebGLContextAttributes;
import org.teavm.jso.webgl.WebGLProgram;
import org.teavm.jso.webgl.WebGLRenderingContext;
import org.teavm.jso.webgl.WebGLShader;
import org.teavm.jso.webgl.WebGLTexture;
import org.teavm.jso.webgl.WebGLUniformLocation;

import shell.platform.gl.GL;
import shell.platform.input.MouseButtons;
import shell.ui.WebLauncher;

public class WebGL implements GL {

    private final WebGLRenderingContext gl;
    private final VAOExtension vaoExt;

    public WebGL(HTMLCanvasElement canvas) {
        WebGLContextAttributes attrs = WebGLContextAttributes.create();
        attrs.setAlpha(false); // opaque canvas
        attrs.setAntialias(true);
        this.gl = (WebGLRenderingContext) canvas.getContext("webgl2", attrs);
        this.vaoExt = new VAOExtension(gl);
    }

    @Override
    public void viewport(int x, int y, int w, int h) {
        gl.viewport(x, y, w, h);
    }

    @Override
    public void clearColor(float r, float g, float b, float a) {
        gl.clearColor(r, g, b, a);
    }

    @Override
    public void clear(int mask) {
        gl.clear(mask);
    }

    @Override
    public int createProgram() {
        return gl.createProgram().hashCode();
    }

    @Override
    public int createShader(int type) {
        return gl.createShader(type).hashCode();
    }

    @Override
    public void shaderSource(int shader, String src) {
        gl.shaderSource(shader(shader), src);
    }

    @Override
    public void compileShader(int shader) {
        gl.compileShader(shader(shader));
    }

    @Override
    public int getShaderiv(int shader, int pname) {
        return toInt(gl.getShaderParameter(shader(shader), pname));
    }

    @Override
    public String getShaderInfoLog(int shader) {
        return gl.getShaderInfoLog(shader(shader));
    }

    @Override
    public void attachShader(int program, int shader) {
        gl.attachShader(program(program), shader(shader));
    }

    @Override
    public void linkProgram(int program) {
        gl.linkProgram(program(program));
    }

    @Override
    public int getProgramiv(int program, int pname) {
        return toInt(gl.getProgramParameter(program(program), pname));
    }

    @Override
    public String getProgramInfoLog(int program) {
        return gl.getProgramInfoLog(program(program));
    }

    @Override
    public void useProgram(int program) {
        gl.useProgram(program(program));
    }

    @Override
    public void deleteShader(int shader) {
        gl.deleteShader(shader(shader));
    }

    @Override
    public void deleteProgram(int program) {
        gl.deleteProgram(program(program));
    }

    @Override
    public int genBuffer() {
        return gl.createBuffer().hashCode();
    }

    @Override
    public void bindArrayBuffer(int buffer) {
        gl.bindBuffer(WebGLRenderingContext.ARRAY_BUFFER, buffer(buffer));
    }

    @Override
    public void bufferDataArray(FloatBuffer data, int usage) {
        org.teavm.jso.typedarrays.Float32Array arr = org.teavm.jso.typedarrays.Float32Array.create(data.remaining());
        for (int i = 0, j = data.position(); j < data.limit(); i++, j++)
            arr.set(i, data.get(j));
        gl.bufferData(WebGLRenderingContext.ARRAY_BUFFER, arr, usage);
    }

    @Override
    public void bufferDataArray(float[] data, int usage) {
        org.teavm.jso.typedarrays.Float32Array arr = org.teavm.jso.typedarrays.Float32Array.create(data.length);
        for (int i = 0; i < data.length; i++)
            arr.set(i, data[i]);
        gl.bufferData(WebGLRenderingContext.ARRAY_BUFFER, arr, usage);
    }

    @Override
    public void enableVertexAttribArray(int index) {
        gl.enableVertexAttribArray(index);
    }

    @Override
    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int pointer) {
        gl.vertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    @Override
    public int genVertexArray() {
        return vaoExt.genVertexArray();
    }

    @Override
    public void bindVertexArray(int vao) {
        vaoExt.bindVertexArray(vao);
    }

    @Override
    public void drawArrays(int mode, int first, int count) {
        gl.drawArrays(mode, first, count);
    }

    @Override
    public int getUniformLocation(int program, String name) {
        WebGLUniformLocation l = gl.getUniformLocation(program(program), name);
        return l == null ? -1 : l.hashCode();
    }

    @Override
    public void uniform1f(int loc, float v) {
        gl.uniform1f(uniform(loc), v);
    }

    @Override
    public void uniform1i(int loc, int v) {
        gl.uniform1i(uniform(loc), v);
    }

    @Override
    public void uniform2fv(int loc, java.nio.FloatBuffer buf) {
        float[] a = new float[buf.remaining()];
        for (int i = 0, j = buf.position(); j < buf.limit(); i++, j++)
            a[i] = buf.get(j);
        gl.uniform2fv(uniform(loc), a);
    }

    @Override
    public void uniform3fv(int loc, java.nio.FloatBuffer buf) {
        float[] a = new float[buf.remaining()];
        for (int i = 0, j = buf.position(); j < buf.limit(); i++, j++)
            a[i] = buf.get(j);
        gl.uniform3fv(uniform(loc), a);
    }

    @Override
    public void uniform4fv(int loc, java.nio.FloatBuffer buf) {
        float[] a = new float[buf.remaining()];
        for (int i = 0, j = buf.position(); j < buf.limit(); i++, j++)
            a[i] = buf.get(j);
        gl.uniform4fv(uniform(loc), a);
    }

    @Override
    public void uniformMatrix4fv(int loc, boolean transpose, java.nio.FloatBuffer buf) {
        float[] a = new float[buf.remaining()];
        for (int i = 0, j = buf.position(); j < buf.limit(); i++, j++)
            a[i] = buf.get(j);
        gl.uniformMatrix4fv(uniform(loc), transpose, a);
    }

    @Override
    public int genTexture() {
        return gl.createTexture().hashCode();
    }

    @Override
    public void bindTexture2D(int id) {
        gl.bindTexture(WebGLRenderingContext.TEXTURE_2D, texture(id));
    }

    @Override
    public void texParameteri(int target, int pname, int param) {
        gl.texParameteri(target, pname, param);
    }

    @Override
    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format,
            int type, ByteBuffer data) {
        org.teavm.jso.typedarrays.Uint8Array arr = org.teavm.jso.typedarrays.Uint8Array.create(data.remaining());
        for (int i = 0, j = data.position(); j < data.limit(); i++, j++)
            arr.set(i, data.get(j));
        gl.texImage2D(target, level, internalFormat, width, height, border, format, type, arr);
    }

    @Override
    public void generateMipmap(int target) {
        gl.generateMipmap(target);
    }

    @Override
    public int COLOR_BUFFER_BIT() {
        return WebGLRenderingContext.COLOR_BUFFER_BIT;
    }

    @Override
    public int TRIANGLES() {
        return WebGLRenderingContext.TRIANGLES;
    }

    @Override
    public int ARRAY_BUFFER() {
        return WebGLRenderingContext.ARRAY_BUFFER;
    }

    @Override
    public int STATIC_DRAW() {
        return WebGLRenderingContext.STATIC_DRAW;
    }

    @Override
    public int FLOAT() {
        return WebGLRenderingContext.FLOAT;
    }

    @Override
    public int FRAGMENT_SHADER() {
        return WebGLRenderingContext.FRAGMENT_SHADER;
    }

    @Override
    public int VERTEX_SHADER() {
        return WebGLRenderingContext.VERTEX_SHADER;
    }

    @Override
    public int TEXTURE_2D() {
        return WebGLRenderingContext.TEXTURE_2D;
    }

    @Override
    public int RGBA() {
        return WebGLRenderingContext.RGBA;
    }

    @Override
    public int RGBA8() {
        return WebGLRenderingContext.RGBA;
    }

    @Override
    public int UNSIGNED_BYTE() {
        return WebGLRenderingContext.UNSIGNED_BYTE;
    }

    @Override
    public int TEXTURE_WRAP_S() {
        return WebGLRenderingContext.TEXTURE_WRAP_S;
    }

    @Override
    public int TEXTURE_WRAP_T() {
        return WebGLRenderingContext.TEXTURE_WRAP_T;
    }

    @Override
    public int TEXTURE_MIN_FILTER() {
        return WebGLRenderingContext.TEXTURE_MIN_FILTER;
    }

    @Override
    public int TEXTURE_MAG_FILTER() {
        return WebGLRenderingContext.TEXTURE_MAG_FILTER;
    }

    @Override
    public int LINEAR() {
        return WebGLRenderingContext.LINEAR;
    }

    @Override
    public int REPEAT() {
        return WebGLRenderingContext.REPEAT;
    }

    private WebGLProgram program(int id) {
        return (WebGLProgram) (Object) id;
    }

    private WebGLShader shader(int id) {
        return (WebGLShader) (Object) id;
    }

    private WebGLBuffer buffer(int id) {
        return (WebGLBuffer) (Object) id;
    }

    private WebGLTexture texture(int id) {
        return (WebGLTexture) (Object) id;
    }

    private WebGLUniformLocation uniform(int id) {
        return (WebGLUniformLocation) (Object) id;
    }

    // Minimal VAO emulation placeholder
    private static final class VAOExtension {
        @SuppressWarnings("unused")
        private final WebGLRenderingContext gl;
        @SuppressWarnings("unused")
        private final Object him;

        VAOExtension(WebGLRenderingContext gl) {
            this.gl = gl;
            this.him = gl.getExtension("OES_vertex_array_object");
        }

        int genVertexArray() {
            return 0;
        }

        void bindVertexArray(int vao) {
            /* no-op on WebGL1 */ }
    }

    @Override
    public boolean getMouseButton(long window, MouseButtons mouseButtonLeft) {
        return WebPlatformHelper.leftDown;
    }

    @Override
    public int SRC_ALPHA() {
        return WebGLRenderingContext.SRC_ALPHA;
    }

    @Override
    public int ONE_MINUS_SRC_ALPHA() {
        return WebGLRenderingContext.ONE_MINUS_SRC_ALPHA;
    }

    @Override
    public int BLEND() {
        return WebGLRenderingContext.BLEND;
    }

    @Override
    public void blendFunc(int SRC_ALPHA, int ONE_MINUS_SRC_ALPHA) {
        gl.blendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA);
    }

    @Override
    public void enable(int blend) {
        gl.enable(blend);
    }

    @JSBody(params = { "v" }, script = "return (v|0);")
    private static native int toInt(Object v);

    @Override
    public void createCapabilities(boolean b, IntFunction intFunction) {

    }

    @Override
    public int DEPTH_TEST() {
        return WebGLRenderingContext.DEPTH_TEST;
    }

    @Override
    public int DEPTH_BUFFER_BIT() {
        return WebGLRenderingContext.DEPTH_BUFFER_BIT;
    }

    @Override
    public void setWindowTitle(String string) {
        WebLauncher.setTitle(string);
    }

    @Override
    public int genVertexArrays() {
        return 0;
    }

    @Override
    public void deleteVertexArrays(int id) {
    }

    @Override
    public int genBuffers() {
        return 0;
    }

    @Override
    public void bindBuffer(int target, int id) {
    }

    @Override
    public void bufferData(int target, FloatBuffer data, int usage) {
    }

    @Override
    public void bufferData(int target, float[] data, int usage) {

    }

    @Override
    public void bufferData(int target, long size, int usage) {
    }

    @Override
    public void bufferSubData(int target, long offset, FloatBuffer data) {
    }

    @Override
    public void bufferData(int target, IntBuffer data, int usage) {
    }

    @Override
    public void deleteBuffers(int id) {
    }

    @Override
    public int getAttribLocation(int iD, CharSequence name) {
        return gl.getAttribLocation(program(iD), name.toString());
    }

    @Override
    public int DYNAMIC_DRAW() {
        return WebGLRenderingContext.DYNAMIC_DRAW;
    }

    @Override
    public void bindFragDataLocation(int iD, int i, String string) {

    }

    @Override
    public void activeTexture(int i) {
        gl.activeTexture(i);
    }

    @Override
    public void detachShader(int iD, int fragmentShader) {
        gl.detachShader(program(iD), shader(fragmentShader));
    }

    @Override
    public void shaderSource(int fragmentShader, CharSequence[] fragmentShaderSource) {
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : fragmentShaderSource) {
            sb.append(cs);
        }
        String result = sb.toString();
        gl.shaderSource(shader(fragmentShader), result);
    }

    @Override
    public int LINK_STATUS() {
        return WebGLRenderingContext.LINK_STATUS;
    }

    @Override
    public void getProgramiv(int program, int link_STATUS, IntBuffer success) {
        gl.getProgramParameteri(program(program), link_STATUS);
    }

    @Override
    public int COMPILE_STATUS() {
        return WebGLRenderingContext.COMPILE_STATUS;
    }

    @Override
    public void getShaderiv(int shader, int compile_STATUS, IntBuffer success) {
        gl.getShaderParameteri(shader(shader), compile_STATUS);
    }

    @Override
    public void uniform3fv(Integer integer, FloatBuffer vec3) {
        float[] a = new float[vec3.remaining()];
        for (int i = 0, j = vec3.position(); j < vec3.limit(); i++, j++)
            a[i] = vec3.get(j);
        gl.uniform3fv(uniform(integer), a);
    }

    @Override
    public int[] readPixels(int i, int j, int width, int height, int rgba, int unsigned_BYTE, int size) {
        Uint8Array fb = Uint8Array.create(width * height * 4);
        gl.readPixels(i, j, width, height, rgba, unsigned_BYTE, fb);
        // Convert Uint8Array to byte[]
        int[] pixels = new int[width * height];
        for (int k = 0; k < pixels.length; k++) {
            int bindex = k * 4;
            int r = fb.get(bindex) & 0xFF;
            int g = fb.get(bindex + 1) & 0xFF;
            int b = fb.get(bindex + 2) & 0xFF;
            pixels[k] = (r << 16) | (g << 8) | b;
        }

        return pixels;
    }

    @Override
    public int TEXTURE0() {
        return WebGLRenderingContext.TEXTURE0;
    }
}
