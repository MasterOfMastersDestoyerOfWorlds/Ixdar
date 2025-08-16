package shell.platform.web;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.webgl.WebGLBuffer;
import org.teavm.jso.webgl.WebGLContextAttributes;
import org.teavm.jso.webgl.WebGLProgram;
import org.teavm.jso.webgl.WebGLRenderingContext;
import org.teavm.jso.webgl.WebGLShader;
import org.teavm.jso.webgl.WebGLTexture;
import org.teavm.jso.webgl.WebGLUniformLocation;
import org.teavm.jso.webgl.WebGLVertexArrayObjectOES;

import shell.platform.gl.GL;
import shell.platform.input.MouseButtons;

public class WebGL implements GL {

    private final WebGLRenderingContext gl;
    private final VAOExtension vaoExt;

    public WebGL(HTMLCanvasElement canvas) {
        WebGLContextAttributes attrs = WebGLContextAttributes.create();
        attrs.setAlpha(true);
        attrs.setAntialias(true);
        this.gl = (WebGLRenderingContext) canvas.getContext("webgl", attrs);
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
        return ((WebGLProgram) gl.createProgram()).getId();
    }

    @Override
    public int createShader(int type) {
        return ((WebGLShader) gl.createShader(type)).getId();
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
        return gl.getShaderParameter(shader(shader), pname).asInt();
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
        return gl.getProgramParameter(program(program), pname).asInt();
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
        return ((WebGLBuffer) gl.createBuffer()).getId();
    }

    @Override
    public void bindArrayBuffer(int buffer) {
        gl.bindBuffer(WebGLRenderingContext.ARRAY_BUFFER, buffer(buffer));
    }

    @Override
    public void bufferDataArray(FloatBuffer data, int usage) {
        gl.bufferData(WebGLRenderingContext.ARRAY_BUFFER, data, usage);
    }

    @Override
    public void bufferDataArray(float[] data, int usage) {
        gl.bufferData(WebGLRenderingContext.ARRAY_BUFFER, data, usage);
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
        return l == null ? -1 : l.getId();
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
        gl.uniform2fv(uniform(loc), buf);
    }

    @Override
    public void uniform3fv(int loc, java.nio.FloatBuffer buf) {
        gl.uniform3fv(uniform(loc), buf);
    }

    @Override
    public void uniform4fv(int loc, java.nio.FloatBuffer buf) {
        gl.uniform4fv(uniform(loc), buf);
    }

    @Override
    public void uniformMatrix4fv(int loc, boolean transpose, java.nio.FloatBuffer buf) {
        gl.uniformMatrix4fv(uniform(loc), transpose, buf);
    }

    @Override
    public int genTexture() {
        return ((WebGLTexture) gl.createTexture()).getId();
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
        gl.texImage2D(target, level, internalFormat, width, height, border, format, type, data);
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

    // Minimal VAO emulation via OES_vertex_array_object if present
    private static final class VAOExtension {
        private final WebGLRenderingContext gl;
        private final VAOS him;

        VAOExtension(WebGLRenderingContext gl) {
            this.gl = gl;
            this.him = (VAOS) gl.getExtension("OES_vertex_array_object");
        }

        int genVertexArray() {
            if (him == null)
                return 0;
            WebGLVertexArrayObjectOES vao = him.createVertexArrayOES();
            return vao == null ? 0 : vao.getId();
        }

        void bindVertexArray(int vao) {
            if (him == null)
                return;
            him.bindVertexArrayOES((WebGLVertexArrayObjectOES) (Object) vao);
        }

        private static interface VAOS {
            WebGLVertexArrayObjectOES createVertexArrayOES();

            void bindVertexArrayOES(WebGLVertexArrayObjectOES vao);
        }
    }

    @Override
    public boolean getMouseButton(long window, MouseButtons mouseButtonLeft) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMouseButton'");
    }

    @Override
    public int SRC_ALPHA() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'SRC_ALPHA'");
    }

    @Override
    public int ONE_MINUS_SRC_ALPHA() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'ONE_MINUS_SRC_ALPHA'");
    }

    @Override
    public int BLEND() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'BLEND'");
    }

    @Override
    public void blendFunc(int SRC_ALPHA, int ONE_MINUS_SRC_ALPHA) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'blendFunc'");
    }

    @Override
    public void enable(int blend) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'enable'");
    }
}
