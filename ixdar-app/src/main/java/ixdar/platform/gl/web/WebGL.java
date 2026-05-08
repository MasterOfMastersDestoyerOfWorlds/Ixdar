package ixdar.platform.gl.web;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

import org.joml.Vector4f;
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

import ixdar.canvas.WebLauncher;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.IxBuffer;
import ixdar.platform.input.MouseButtons;

public class WebGL implements GL {
    public static final int NUM_0x1405 = 0x1405;
    public static final int NUM_4 = 4;
    public static final int NUM_0xF = 0xFF;
    public static final int NUM_16 = 16;
    public static final int NUM_8 = 8;
    private static int staticId = 0;

    // Cache a single WebGL context per canvas to support multiple canvases reliably
    private static final java.util.Map<String, WebGLRenderingContext> CONTEXT_CACHE = new java.util.HashMap<>();
    private int id;
    private final WebGLRenderingContext gl;
    private final VAOExtension vaoExt;
    private int nextId = 1;
    private final java.util.Map<Integer, WebGLProgram> programMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, WebGLShader> shaderMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, WebGLBuffer> bufferMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, WebGLTexture> textureMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, WebGLUniformLocation> uniformMap = new java.util.HashMap<>();
    private final ArrayList<ShaderProgram> shaders = new ArrayList<>();

    /**
     * Construct a WebGL.
     *
     * @param canvas constructor argument
     */
    public WebGL(HTMLCanvasElement canvas) {
        WebGLContextAttributes attrs = WebGLContextAttributes.create();
        attrs.setAlpha(false); // opaque canvas
        attrs.setAntialias(true);
        this.gl = getOrCreateContext(canvas, attrs);
        this.vaoExt = new VAOExtension(gl);
        this.id = staticId++;
    }

    /** {@inheritDoc} */
    @Override
    public int getPlatformID() {
        return id;
    }

    /** {@inheritDoc} */
    @Override
    public boolean usesWebGlsl() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void setPlatformID(Integer p) {
        if (p != null) {
            this.id = p.intValue();
        }
    }

    /** {@inheritDoc} */
    @Override
    public ArrayList<ShaderProgram> getShaders() {
        return shaders;
    }

    /** {@inheritDoc} */
    @Override
    public void addShader(ShaderProgram shader) {
        shaders.add(shader);
    }

    private static WebGLRenderingContext getOrCreateContext(HTMLCanvasElement canvas, WebGLContextAttributes attrs) {
        String id = canvas != null ? canvas.getId() : null;
        WebGLRenderingContext cached = id != null ? CONTEXT_CACHE.get(id) : null;
        if (cached != null) {
            return cached;
        }
        WebGLRenderingContext created = acquireGL(canvas, attrs);
        if (id != null && created != null) {
            CONTEXT_CACHE.put(id, created);
        }
        return created;
    }

    /** {@inheritDoc} */
    @Override
    public void viewport(int x, int y, int w, int h) {
        gl.viewport(x, y, w, h);
    }

    /** {@inheritDoc} */
    @Override
    public void clearColor(float r, float g, float b, float a) {
        gl.clearColor(r, g, b, a);
    }

    /** {@inheritDoc} */
    @Override
    public void clear(int mask) {
        gl.clear(mask);
    }

    /** {@inheritDoc} */
    @Override
    public int createProgram() {
        WebGLProgram p = gl.createProgram();
        int id = nextId++;
        programMap.put(id, p);
        return id;
    }

    /** {@inheritDoc} */
    @Override
    public int createShader(int type) {
        WebGLShader s = gl.createShader(type);
        int id = nextId++;
        shaderMap.put(id, s);
        return id;
    }

    /** {@inheritDoc} */
    @Override
    public void shaderSource(int shader, String src) {
        WebGLShader sh = shader(shader);
        if (sh == null) {
            return;
        }
        gl.shaderSource(sh, src);
    }

    /** {@inheritDoc} */
    @Override
    public void compileShader(int shader) {
        WebGLShader sh = shader(shader);
        if (sh == null) {
            return;
        }
        gl.compileShader(sh);
    }

    /** {@inheritDoc} */
    @Override
    public int getShaderiv(int shader, int pname) {
        WebGLShader sh = shader(shader);
        if (sh == null) {
            return 0;
        }
        return toInt(gl.getShaderParameter(sh, pname));
    }

    /** {@inheritDoc} */
    @Override
    public String getShaderInfoLog(int shader) {
        WebGLShader sh = shader(shader);
        if (sh == null) {
            return "";
        }
        return gl.getShaderInfoLog(sh);
    }

    /** {@inheritDoc} */
    @Override
    public void attachShader(int program, int shader) {
        WebGLProgram p = program(program);
        WebGLShader s = shader(shader);
        if (p == null || s == null) {
            return;
        }
        gl.attachShader(p, s);
    }

    /** {@inheritDoc} */
    @Override
    public void linkProgram(int program) {
        WebGLProgram p = program(program);
        gl.linkProgram(p);
    }

    /** {@inheritDoc} */
    @Override
    public int getProgramiv(int program, int pname) {
        WebGLProgram p = program(program);
        return toInt(gl.getProgramParameter(p, pname));
    }

    /** {@inheritDoc} */
    @Override
    public String getProgramInfoLog(int program) {
        WebGLProgram p = program(program);
        return gl.getProgramInfoLog(p);
    }

    /** {@inheritDoc} */
    @Override
    public void useProgram(int program) {
        WebGLProgram p = program(program);
        gl.useProgram(p);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteShader(int shader) {
        WebGLShader s = shader(shader);
        if (s != null)
            gl.deleteShader(s);
        shaderMap.remove(shader);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteProgram(int program) {
        WebGLProgram p = program(program);
        if (p != null)
            gl.deleteProgram(p);
        programMap.remove(program);
    }

    /** {@inheritDoc} */
    @Override
    public int genBuffer() {
        WebGLBuffer b = gl.createBuffer();
        int id = nextId++;
        bufferMap.put(id, b);
        return id;
    }

    /** {@inheritDoc} */
    @Override
    public void bindArrayBuffer(int buffer) {
        WebGLBuffer b = buffer(buffer);
        gl.bindBuffer(WebGLRenderingContext.ARRAY_BUFFER, b);
    }

    /** {@inheritDoc} */
    @Override
    public void bufferDataArray(IxBuffer data, int usage) {
        gl.bufferData(WebGLRenderingContext.ARRAY_BUFFER, ((WebBuffer) data).getFloatBuffer(), usage);
    }

    /** {@inheritDoc} */
    @Override
    public void bufferDataArray(float[] data, int usage) {
        org.teavm.jso.typedarrays.Float32Array arr = org.teavm.jso.typedarrays.Float32Array.create(data.length);
        for (int i = 0; i < data.length; i++)
            arr.set(i, data[i]);
        gl.bufferData(WebGLRenderingContext.ARRAY_BUFFER, arr, usage);
    }

    /** {@inheritDoc} */
    @Override
    public void enableVertexAttribArray(int index) {
        gl.enableVertexAttribArray(index);
    }

    /** {@inheritDoc} */
    @Override
    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int pointer) {
        gl.vertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    /** {@inheritDoc} */
    @Override
    public int genVertexArray() {
        return vaoExt.genVertexArray();
    }

    /** {@inheritDoc} */
    @Override
    public void bindVertexArray(int vao) {
        vaoExt.bindVertexArray(vao);
    }

    /** {@inheritDoc} */
    @Override
    public void drawArrays(int mode, int first, int count) {
        gl.drawArrays(mode, first, count);
    }

    /** {@inheritDoc} */
    @Override
    public void drawElements(int mode, int count, int type, int indicesOffsetBytes) {
        gl.drawElements(mode, count, type, indicesOffsetBytes);
    }

    /** {@inheritDoc} */
    @Override
    public int getUniformLocation(int program, String name) {
        WebGLUniformLocation l = gl.getUniformLocation(program(program), name);
        if (l == null)
            return -1;
        int id = nextId++;
        uniformMap.put(id, l);
        return id;
    }

    /** {@inheritDoc} */
    @Override
    public void uniform1f(int loc, float v) {
        gl.uniform1f(uniform(loc), v);
    }

    /** {@inheritDoc} */
    @Override
    public void uniform1i(int loc, int v) {
        gl.uniform1i(uniform(loc), v);
    }

    /** {@inheritDoc} */
    @Override
    public void uniform2fv(int loc, IxBuffer buf) {
        gl.uniform2fv(uniform(loc), ((WebBuffer) buf).getFloatBuffer());
    }

    /** {@inheritDoc} */
    @Override
    public void uniform3fv(int loc, IxBuffer buf) {
        gl.uniform3fv(uniform(loc), ((WebBuffer) buf).getFloatBuffer());
    }

    /** {@inheritDoc} */
    @Override
    public void uniform4fv(int loc, IxBuffer buf) {
        gl.uniform4fv(uniform(loc), ((WebBuffer) buf).getFloatBuffer());
    }

    /** {@inheritDoc} */
    @Override
    public void uniformMatrix4fv(int loc, boolean transpose, IxBuffer buf) {
        gl.uniformMatrix4fv(uniform(loc), transpose, ((WebBuffer) buf).getFloatBuffer());
    }

    /** {@inheritDoc} */
    @Override
    public int genTexture() {
        WebGLTexture t = gl.createTexture();
        int id = nextId++;
        textureMap.put(id, t);
        return id;
    }

    /** {@inheritDoc} */
    @Override
    public void deleteTexture(int id) {
        WebGLTexture t = textureMap.remove(id);
        if (t != null) {
            gl.deleteTexture(t);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void bindTexture2D(int id) {
        gl.bindTexture(WebGLRenderingContext.TEXTURE_2D, texture(id));
    }

    /** {@inheritDoc} */
    @Override
    public void texParameteri(int target, int pname, int param) {
        gl.texParameteri(target, pname, param);
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    public void generateMipmap(int target) {
        gl.generateMipmap(target);
    }

    /** {@inheritDoc} */
    @Override
    public int COLOR_BUFFER_BIT() {
        return WebGLRenderingContext.COLOR_BUFFER_BIT;
    }

    /** {@inheritDoc} */
    @Override
    public int TRIANGLES() {
        return WebGLRenderingContext.TRIANGLES;
    }

    /** {@inheritDoc} */
    @Override
    public int ARRAY_BUFFER() {
        return WebGLRenderingContext.ARRAY_BUFFER;
    }

    /** {@inheritDoc} */
    @Override
    public int ELEMENT_ARRAY_BUFFER() {
        return WebGLRenderingContext.ELEMENT_ARRAY_BUFFER;
    }

    /** {@inheritDoc} */
    @Override
    public int STATIC_DRAW() {
        return WebGLRenderingContext.STATIC_DRAW;
    }

    /** {@inheritDoc} */
    @Override
    public int FLOAT() {
        return WebGLRenderingContext.FLOAT;
    }

    /** {@inheritDoc} */
    @Override
    public int FRAGMENT_SHADER() {
        return WebGLRenderingContext.FRAGMENT_SHADER;
    }

    /** {@inheritDoc} */
    @Override
    public int VERTEX_SHADER() {
        return WebGLRenderingContext.VERTEX_SHADER;
    }

    /** {@inheritDoc} */
    @Override
    public int TEXTURE_2D() {
        return WebGLRenderingContext.TEXTURE_2D;
    }

    /** {@inheritDoc} */
    @Override
    public int RGBA() {
        return WebGLRenderingContext.RGBA;
    }

    /** {@inheritDoc} */
    @Override
    public int LINES() {
        return WebGLRenderingContext.LINES;
    }

    /** {@inheritDoc} */
    @Override
    public void lineWidth(float width) {
        gl.lineWidth(width);
    }

    /** {@inheritDoc} */
    @Override
    public int RGBA8() {
        return WebGLRenderingContext.RGBA;
    }

    /** {@inheritDoc} */
    @Override
    public int UNSIGNED_BYTE() {
        return WebGLRenderingContext.UNSIGNED_BYTE;
    }

    /** {@inheritDoc} */
    @Override
    public int UNSIGNED_INT() {
        return NUM_0x1405;
    }

    /** {@inheritDoc} */
    @Override
    public int TEXTURE_WRAP_S() {
        return WebGLRenderingContext.TEXTURE_WRAP_S;
    }

    /** {@inheritDoc} */
    @Override
    public int TEXTURE_WRAP_T() {
        return WebGLRenderingContext.TEXTURE_WRAP_T;
    }

    /** {@inheritDoc} */
    @Override
    public int TEXTURE_MIN_FILTER() {
        return WebGLRenderingContext.TEXTURE_MIN_FILTER;
    }

    /** {@inheritDoc} */
    @Override
    public int TEXTURE_MAG_FILTER() {
        return WebGLRenderingContext.TEXTURE_MAG_FILTER;
    }

    /** {@inheritDoc} */
    @Override
    public int LINEAR() {
        return WebGLRenderingContext.LINEAR;
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    public boolean getMouseButton(long window, MouseButtons mouseButtonLeft) {
        return WebPlatformHelper.leftDown;
    }

    /** {@inheritDoc} */
    @Override
    public int SRC_ALPHA() {
        return WebGLRenderingContext.SRC_ALPHA;
    }

    /** {@inheritDoc} */
    @Override
    public int ONE_MINUS_SRC_ALPHA() {
        return WebGLRenderingContext.ONE_MINUS_SRC_ALPHA;
    }

    /** {@inheritDoc} */
    @Override
    public int BLEND() {
        return WebGLRenderingContext.BLEND;
    }

    /** {@inheritDoc} */
    @Override
    public void blendFunc(int SRC_ALPHA, int ONE_MINUS_SRC_ALPHA) {
        gl.blendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA);
    }

    /** {@inheritDoc} */
    @Override
    public void enable(int blend) {
        gl.enable(blend);
    }

    /** {@inheritDoc} */
    @Override
    public void disable(int depthTest) {
        gl.disable(depthTest);
    }

    /** {@inheritDoc} */
    @Override
    public void depthMask(boolean flag) {
        gl.depthMask(flag);
    }

    @JSBody(params = { "v" }, script = "return (v|0);")
    private static native int toInt(Object v);

    /** {@inheritDoc} */
    @Override
    public void createCapabilities() {
    }

    /** {@inheritDoc} */
    @Override
    public int DEPTH_TEST() {
        return WebGLRenderingContext.DEPTH_TEST;
    }

    /** {@inheritDoc} */
    @Override
    public int DEPTH_BUFFER_BIT() {
        return WebGLRenderingContext.DEPTH_BUFFER_BIT;
    }

    /** {@inheritDoc} */
    @Override
    public void setWindowTitle(String string) {
        WebLauncher.setTitle(string);
    }

    /** {@inheritDoc} */
    @Override
    public int genVertexArrays() {
        return vaoExt.genVertexArray();
    }

    /** {@inheritDoc} */
    @Override
    public void deleteVertexArrays(int id) {
        vaoExt.deleteVertexArray(id);
    }

    /** {@inheritDoc} */
    @Override
    public int genBuffers() {
        WebGLBuffer b = gl.createBuffer();
        int id = nextId++;
        bufferMap.put(id, b);
        return id;
    }

    /** {@inheritDoc} */
    @Override
    public void bindBuffer(int target, int id) {
        WebGLBuffer b = buffer(id);
        gl.bindBuffer(target, b);
    }

    /** {@inheritDoc} */
    @Override
    public void bufferData(int target, IxBuffer data, int usage) {
        gl.bufferData(target, ((WebBuffer) data).getFloatBuffer(), usage);
    }

    /** {@inheritDoc} */
    @Override
    public void bufferData(int target, float[] data, int usage) {
        org.teavm.jso.typedarrays.Float32Array arr = org.teavm.jso.typedarrays.Float32Array.create(data.length);
        for (int i = 0; i < data.length; i++) {
            arr.set(i, data[i]);
        }
        gl.bufferData(target, arr, usage);
    }

    /** {@inheritDoc} */
    @Override
    public void bufferData(int target, long size, int usage) {
        org.teavm.jso.typedarrays.Uint8Array arr = org.teavm.jso.typedarrays.Uint8Array.create((int) size);
        gl.bufferData(target, arr, usage);
    }

    /** {@inheritDoc} */
    @Override
    public void bufferSubData(int target, long offset, IxBuffer data) {
        gl.bufferSubData(target, (int) offset, ((WebBuffer) data).getFloatBuffer());
    }

    /** {@inheritDoc} */
    @Override
    public void bufferData(int target, IntBuffer data, int usage) {
        org.teavm.jso.typedarrays.Int32Array arr = org.teavm.jso.typedarrays.Int32Array.create(data.remaining());
        for (int i = 0, j = data.position(); j < data.limit(); i++, j++) {
            arr.set(i, data.get(j));
        }
        gl.bufferData(target, arr, usage);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteBuffers(int id) {
        WebGLBuffer b = bufferMap.remove(id);
        if (b != null) {
            gl.deleteBuffer(b);
        }
    }

    /** {@inheritDoc} */
    @Override
    public int getAttribLocation(int iD, CharSequence name) {
        return gl.getAttribLocation(program(iD), name.toString());
    }

    /** {@inheritDoc} */
    @Override
    public int DYNAMIC_DRAW() {
        return WebGLRenderingContext.DYNAMIC_DRAW;
    }

    /** {@inheritDoc} */
    @Override
    public void bindFragDataLocation(int iD, int i, String string) {

    }

    /** {@inheritDoc} */
    @Override
    public void activeTexture(int i) {
        gl.activeTexture(i);
    }

    /** {@inheritDoc} */
    @Override
    public void detachShader(int iD, int fragmentShader) {
        gl.detachShader(program(iD), shader(fragmentShader));
    }

    /** {@inheritDoc} */
    @Override
    public void shaderSource(int fragmentShader, CharSequence[] fragmentShaderSource) {
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : fragmentShaderSource) {
            sb.append(cs);
        }
        String result = sb.toString();
        gl.shaderSource(shader(fragmentShader), result);
    }

    /** {@inheritDoc} */
    @Override
    public int LINK_STATUS() {
        return WebGLRenderingContext.LINK_STATUS;
    }

    /** {@inheritDoc} */
    @Override
    public void getProgramiv(int program, int link_STATUS, IntBuffer success) {
        int val = toInt(gl.getProgramParameter(program(program), link_STATUS));
        if (success != null && success.remaining() > 0) {
            success.put(0, val);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void getAttachedShaders(int program, IntBuffer success) {
        int val = toInt(gl.getProgramParameter(program(program), WebGLRenderingContext.ATTACHED_SHADERS));
        if (success != null && success.remaining() > 0) {
            success.put(0, val);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void getActiveUniforms(int program, IntBuffer success) {
        int val = toInt(gl.getProgramParameter(program(program), WebGLRenderingContext.ACTIVE_UNIFORMS));
        if (success != null && success.remaining() > 0) {
            success.put(0, val);
        }
    }

    /** {@inheritDoc} */
    @Override
    public int COMPILE_STATUS() {
        return WebGLRenderingContext.COMPILE_STATUS;
    }

    /** {@inheritDoc} */
    @Override
    public void getShaderiv(int shader, int compile_STATUS, IntBuffer success) {
        int val = toInt(gl.getShaderParameter(shader(shader), compile_STATUS));
        if (success != null && success.remaining() > 0) {
            success.put(0, val);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void uniform3fv(Integer integer, IxBuffer vec3) {
        gl.uniform3fv(uniform(integer), ((WebBuffer) vec3).getFloatBuffer());
    }

    /** {@inheritDoc} */
    @Override
    public int[] readPixels(int i, int j, int width, int height, int rgba, int unsigned_BYTE, int size) {
        Uint8Array fb = Uint8Array.create(width * height * NUM_4);
        gl.readPixels(i, j, width, height, rgba, unsigned_BYTE, fb);
        // Convert Uint8Array to byte[]
        int[] pixels = new int[width * height];
        for (int k = 0; k < pixels.length; k++) {
            int bindex = k * NUM_4;
            int r = fb.get(bindex) & NUM_0xF;
            int g = fb.get(bindex + 1) & NUM_0xF;
            int b = fb.get(bindex + 2) & NUM_0xF;
            pixels[k] = (r << NUM_16) | (g << NUM_8) | b;
        }

        return pixels;
    }

    /** {@inheritDoc} */
    @Override
    public int TEXTURE0() {
        return WebGLRenderingContext.TEXTURE0;
    }

    /** {@inheritDoc} */
    @Override
    public void coldStartStack() {
    }

    @JSBody(params = { "canvas",
            "attrs" }, script = "return (canvas.getContext('webgl2', attrs) || canvas.getContext('webgl', attrs) || canvas.getContext('experimental-webgl', attrs));")
    private static native WebGLRenderingContext acquireGL(HTMLCanvasElement canvas, WebGLContextAttributes attrs);

    @JSBody(params = { "gl", "enable" }, script = "if(!gl){return;} try{gl.pixelStorei(0x9240, enable?1:0);}catch(e){}")
    private static native void setUnpackFlipY(WebGLRenderingContext gl, boolean enable);

    /** {@inheritDoc} */
    @Override
    public int ACTIVE_UNIFORMS() {
        return WebGLRenderingContext.ACTIVE_UNIFORMS;
    }

    /** {@inheritDoc} */
    @Override
    public String getActiveUniform(int iD, int i, IntBuffer sizeBuffer, IntBuffer typeBuffer) {
        return gl.getActiveUniform(program(iD), i).toString();
    }

    /** {@inheritDoc} */
    @Override
    public int FLOAT_VEC2() {
        return WebGLRenderingContext.FLOAT_VEC2;
    }

    /** {@inheritDoc} */
    @Override
    public int FLOAT_VEC4() {
        return WebGLRenderingContext.FLOAT_VEC4;
    }

    /** {@inheritDoc} */
    @Override
    public int SAMPLER_2D() {
        return WebGLRenderingContext.SAMPLER_2D;
    }

    /** {@inheritDoc} */
    @Override
    public void getUniformfv(int iD, int location, IxBuffer val) {
    }

    /** {@inheritDoc} */
    public int getDrawingBufferWidth() {
        return gl.getDrawingBufferWidth();
    }

    /** {@inheritDoc} */
    public int getDrawingBufferHeight() {
        return gl.getDrawingBufferHeight();
    }

    /** {@inheritDoc} */
    @Override
    public int LINEAR_MIPMAP_LINEAR() {
        return WebGLRenderingContext.LINEAR_MIPMAP_LINEAR;
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

    /** {@inheritDoc} */
    @Override
    public void clearColor(Color c) {
        Vector4f c4 = c.toVector4f();
        gl.clearColor(c4.x, c4.y, c4.z, c4.w);
    }

}
