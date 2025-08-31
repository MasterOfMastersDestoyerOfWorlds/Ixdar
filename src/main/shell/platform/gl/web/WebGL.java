package shell.platform.gl.web;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.function.IntFunction;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.dom.html.HTMLCanvasElement;
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
    private int nextId = 1;
    private final java.util.Map<Integer, WebGLProgram> programMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, WebGLShader> shaderMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, WebGLBuffer> bufferMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, WebGLTexture> textureMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, WebGLUniformLocation> uniformMap = new java.util.HashMap<>();

    public WebGL(HTMLCanvasElement canvas) {
        WebGLContextAttributes attrs = WebGLContextAttributes.create();
        attrs.setAlpha(false); // opaque canvas
        attrs.setAntialias(true);
        this.gl = acquireGL(canvas, attrs);
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
        WebGLProgram p = gl.createProgram();
        int id = nextId++;
        programMap.put(id, p);
        return id;
    }

    @Override
    public int createShader(int type) {
        WebGLShader s = gl.createShader(type);
        int id = nextId++;
        shaderMap.put(id, s);
        return id;
    }

    @Override
    public void shaderSource(int shader, String src) {
        WebGLShader sh = shader(shader);
        if (sh == null) {
            return;
        }
        gl.shaderSource(sh, src);
    }

    @Override
    public void compileShader(int shader) {
        WebGLShader sh = shader(shader);
        if (sh == null) {
            return;
        }
        gl.compileShader(sh);
    }

    @Override
    public int getShaderiv(int shader, int pname) {
        WebGLShader sh = shader(shader);
        if (sh == null) {
            return 0;
        }
        return toInt(gl.getShaderParameter(sh, pname));
    }

    @Override
    public String getShaderInfoLog(int shader) {
        WebGLShader sh = shader(shader);
        if (sh == null) {
            return "";
        }
        return gl.getShaderInfoLog(sh);
    }

    @Override
    public void attachShader(int program, int shader) {
        WebGLProgram p = program(program);
        WebGLShader s = shader(shader);
        if (p == null || s == null) {
            return;
        }
        gl.attachShader(p, s);
    }

    @Override
    public void linkProgram(int program) {
        WebGLProgram p = program(program);
        gl.linkProgram(p);
    }

    @Override
    public int getProgramiv(int program, int pname) {
        WebGLProgram p = program(program);
        return toInt(gl.getProgramParameter(p, pname));
    }

    @Override
    public String getProgramInfoLog(int program) {
        WebGLProgram p = program(program);
        return gl.getProgramInfoLog(p);
    }

    @Override
    public void useProgram(int program) {
        WebGLProgram p = program(program);
        gl.useProgram(p);
    }

    @Override
    public void deleteShader(int shader) {
        WebGLShader s = shader(shader);
        if (s != null)
            gl.deleteShader(s);
        shaderMap.remove(shader);
    }

    @Override
    public void deleteProgram(int program) {
        WebGLProgram p = program(program);
        if (p != null)
            gl.deleteProgram(p);
        programMap.remove(program);
    }

    @Override
    public int genBuffer() {
        WebGLBuffer b = gl.createBuffer();
        int id = nextId++;
        bufferMap.put(id, b);
        return id;
    }

    @Override
    public void bindArrayBuffer(int buffer) {
        WebGLBuffer b = buffer(buffer);
        gl.bindBuffer(WebGLRenderingContext.ARRAY_BUFFER, b);
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
        if (l == null)
            return -1;
        int id = nextId++;
        uniformMap.put(id, l);
        return id;
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
        WebGLTexture t = gl.createTexture();
        int id = nextId++;
        textureMap.put(id, t);
        return id;
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
        // Ensure textures uploaded match typical OpenGL origin expectations
        setUnpackFlipY(gl, true);
        gl.texImage2D(target, level, internalFormat, width, height, border, format, type, arr);
        setUnpackFlipY(gl, false);
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
        return programMap.get(id);
    }

    private WebGLShader shader(int id) {
        return shaderMap.get(id);
    }

    private WebGLBuffer buffer(int id) {
        return bufferMap.get(id);
    }

    private WebGLTexture texture(int id) {
        return textureMap.get(id);
    }

    private WebGLUniformLocation uniform(int id) {
        return uniformMap.get(id);
    }

    // Minimal VAO emulation placeholder
    private static final class VAOExtension {
        private final WebGLRenderingContext gl;
        private int nextId = 1;
        private final java.util.Map<Integer, JSObject> vaoMap = new java.util.HashMap<>();

        VAOExtension(WebGLRenderingContext gl) {
            this.gl = gl;
        }

        int genVertexArray() {
            JSObject vao = jsCreateVAO(gl);
            if (vao == null) {
                return 0;
            }
            int id = nextId++;
            vaoMap.put(id, vao);
            return id;
        }

        void bindVertexArray(int vao) {
            if (vao == 0) {
                jsUnbindVAO(gl);
                return;
            }
            JSObject o = vaoMap.get(vao);
            if (o != null) {
                jsBindVAO(gl, o);
            }
        }

        void deleteVertexArray(int id) {
            JSObject o = vaoMap.remove(id);
            if (o != null) {
                jsDeleteVAO(gl, o);
            }
        }

        @JSBody(params = {
                "gl" }, script = "var v=null; if(gl && gl.createVertexArray){v=gl.createVertexArray();} else {var ext=gl?gl.getExtension('OES_vertex_array_object'):null; if(ext){v=ext.createVertexArrayOES();}} return v;")
        private static native JSObject jsCreateVAO(WebGLRenderingContext gl);

        @JSBody(params = { "gl",
                "vao" }, script = "if(gl && gl.bindVertexArray){gl.bindVertexArray(vao);} else {var ext=gl?gl.getExtension('OES_vertex_array_object'):null; if(ext){ext.bindVertexArrayOES(vao);}}")
        private static native void jsBindVAO(WebGLRenderingContext gl, JSObject vao);

        @JSBody(params = {
                "gl" }, script = "if(gl && gl.bindVertexArray){gl.bindVertexArray(null);} else {var ext=gl?gl.getExtension('OES_vertex_array_object'):null; if(ext){ext.bindVertexArrayOES(null);}}")
        private static native void jsUnbindVAO(WebGLRenderingContext gl);

        @JSBody(params = { "gl",
                "vao" }, script = "if(gl && gl.deleteVertexArray){gl.deleteVertexArray(vao);} else {var ext=gl?gl.getExtension('OES_vertex_array_object'):null; if(ext){ext.deleteVertexArrayOES(vao);}}")
        private static native void jsDeleteVAO(WebGLRenderingContext gl, JSObject vao);
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
        return vaoExt.genVertexArray();
    }

    @Override
    public void deleteVertexArrays(int id) {
        vaoExt.deleteVertexArray(id);
    }

    @Override
    public int genBuffers() {
        WebGLBuffer b = gl.createBuffer();
        int id = nextId++;
        bufferMap.put(id, b);
        return id;
    }

    @Override
    public void bindBuffer(int target, int id) {
        WebGLBuffer b = buffer(id);
        gl.bindBuffer(target, b);
    }

    @Override
    public void bufferData(int target, FloatBuffer data, int usage) {
        org.teavm.jso.typedarrays.Float32Array arr = org.teavm.jso.typedarrays.Float32Array.create(data.remaining());
        for (int i = 0, j = data.position(); j < data.limit(); i++, j++) {
            arr.set(i, data.get(j));
        }
        gl.bufferData(target, arr, usage);
    }

    @Override
    public void bufferData(int target, float[] data, int usage) {
        org.teavm.jso.typedarrays.Float32Array arr = org.teavm.jso.typedarrays.Float32Array.create(data.length);
        for (int i = 0; i < data.length; i++) {
            arr.set(i, data[i]);
        }
        gl.bufferData(target, arr, usage);
    }

    @Override
    public void bufferData(int target, long size, int usage) {
        org.teavm.jso.typedarrays.Uint8Array arr = org.teavm.jso.typedarrays.Uint8Array.create((int) size);
        gl.bufferData(target, arr, usage);
    }

    @Override
    public void bufferSubData(int target, long offset, FloatBuffer data) {
        org.teavm.jso.typedarrays.Float32Array arr = org.teavm.jso.typedarrays.Float32Array.create(data.remaining());
        for (int i = 0, j = data.position(); j < data.limit(); i++, j++) {
            arr.set(i, data.get(j));
        }
        gl.bufferSubData(target, (int) offset, arr);
    }

    @Override
    public void bufferData(int target, IntBuffer data, int usage) {
        org.teavm.jso.typedarrays.Int32Array arr = org.teavm.jso.typedarrays.Int32Array.create(data.remaining());
        for (int i = 0, j = data.position(); j < data.limit(); i++, j++) {
            arr.set(i, data.get(j));
        }
        gl.bufferData(target, arr, usage);
    }

    @Override
    public void deleteBuffers(int id) {
        WebGLBuffer b = bufferMap.remove(id);
        if (b != null) {
            gl.deleteBuffer(b);
        }
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
        int val = toInt(gl.getProgramParameter(program(program), link_STATUS));
        if (success != null && success.remaining() > 0) {
            success.put(0, val);
        }
    }

    @Override
    public int COMPILE_STATUS() {
        return WebGLRenderingContext.COMPILE_STATUS;
    }

    @Override
    public void getShaderiv(int shader, int compile_STATUS, IntBuffer success) {
        int val = toInt(gl.getShaderParameter(shader(shader), compile_STATUS));
        if (success != null && success.remaining() > 0) {
            success.put(0, val);
        }
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

    @Override
    public void coldStartStack() {
    }

    @JSBody(params = { "canvas",
            "attrs" }, script = "return (canvas.getContext('webgl2', attrs) || canvas.getContext('webgl', attrs) || canvas.getContext('experimental-webgl', attrs));")
    private static native WebGLRenderingContext acquireGL(HTMLCanvasElement canvas, WebGLContextAttributes attrs);

    @JSBody(params = { "gl", "enable" }, script = "if(!gl){return;} try{gl.pixelStorei(0x9240, enable?1:0);}catch(e){}")
    private static native void setUnpackFlipY(WebGLRenderingContext gl, boolean enable);
}
