package ixdar.platform.gl.web;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
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
     * TODO: document {@code WebGL}.
     *
     * @param canvas TODO: describe
     */
    public WebGL(HTMLCanvasElement canvas) {
        WebGLContextAttributes attrs = WebGLContextAttributes.create();
        attrs.setAlpha(false); // opaque canvas
        attrs.setAntialias(true);
        this.gl = getOrCreateContext(canvas, attrs);
        this.vaoExt = new VAOExtension(gl);
        this.id = staticId++;
    }

    /**
     * TODO: document {@code getPlatformID}.
     *
     * @return TODO: describe
     */
    @Override
    public int getPlatformID() {
        return id;
    }

    /**
     * TODO: document {@code usesWebGlsl}.
     *
     * @return TODO: describe
     */
    @Override
    public boolean usesWebGlsl() {
        return true;
    }

    /**
     * TODO: document {@code setPlatformID}.
     *
     * @param p TODO: describe
     */
    @Override
    public void setPlatformID(Integer p) {
        if (p != null) {
            this.id = p.intValue();
        }
    }

    /**
     * TODO: document {@code getShaders}.
     *
     * @return TODO: describe
     */
    @Override
    public ArrayList<ShaderProgram> getShaders() {
        return shaders;
    }

    /**
     * TODO: document {@code addShader}.
     *
     * @param shader TODO: describe
     */
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

    /**
     * TODO: document {@code viewport}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     * @param w TODO: describe
     * @param h TODO: describe
     */
    @Override
    public void viewport(int x, int y, int w, int h) {
        gl.viewport(x, y, w, h);
    }

    /**
     * TODO: document {@code clearColor}.
     *
     * @param r TODO: describe
     * @param g TODO: describe
     * @param b TODO: describe
     * @param a TODO: describe
     */
    @Override
    public void clearColor(float r, float g, float b, float a) {
        gl.clearColor(r, g, b, a);
    }

    /**
     * TODO: document {@code clear}.
     *
     * @param mask TODO: describe
     */
    @Override
    public void clear(int mask) {
        gl.clear(mask);
    }

    /**
     * TODO: document {@code createProgram}.
     *
     * @return TODO: describe
     */
    @Override
    public int createProgram() {
        WebGLProgram p = gl.createProgram();
        int id = nextId++;
        programMap.put(id, p);
        return id;
    }

    /**
     * TODO: document {@code createShader}.
     *
     * @param type TODO: describe
     * @return TODO: describe
     */
    @Override
    public int createShader(int type) {
        WebGLShader s = gl.createShader(type);
        int id = nextId++;
        shaderMap.put(id, s);
        return id;
    }

    /**
     * TODO: document {@code shaderSource}.
     *
     * @param shader TODO: describe
     * @param src TODO: describe
     */
    @Override
    public void shaderSource(int shader, String src) {
        WebGLShader sh = shader(shader);
        if (sh == null) {
            return;
        }
        gl.shaderSource(sh, src);
    }

    /**
     * TODO: document {@code compileShader}.
     *
     * @param shader TODO: describe
     */
    @Override
    public void compileShader(int shader) {
        WebGLShader sh = shader(shader);
        if (sh == null) {
            return;
        }
        gl.compileShader(sh);
    }

    /**
     * TODO: document {@code getShaderiv}.
     *
     * @param shader TODO: describe
     * @param pname TODO: describe
     * @return TODO: describe
     */
    @Override
    public int getShaderiv(int shader, int pname) {
        WebGLShader sh = shader(shader);
        if (sh == null) {
            return 0;
        }
        return toInt(gl.getShaderParameter(sh, pname));
    }

    /**
     * TODO: document {@code getShaderInfoLog}.
     *
     * @param shader TODO: describe
     * @return TODO: describe
     */
    @Override
    public String getShaderInfoLog(int shader) {
        WebGLShader sh = shader(shader);
        if (sh == null) {
            return "";
        }
        return gl.getShaderInfoLog(sh);
    }

    /**
     * TODO: document {@code attachShader}.
     *
     * @param program TODO: describe
     * @param shader TODO: describe
     */
    @Override
    public void attachShader(int program, int shader) {
        WebGLProgram p = program(program);
        WebGLShader s = shader(shader);
        if (p == null || s == null) {
            return;
        }
        gl.attachShader(p, s);
    }

    /**
     * TODO: document {@code linkProgram}.
     *
     * @param program TODO: describe
     */
    @Override
    public void linkProgram(int program) {
        WebGLProgram p = program(program);
        gl.linkProgram(p);
    }

    /**
     * TODO: document {@code getProgramiv}.
     *
     * @param program TODO: describe
     * @param pname TODO: describe
     * @return TODO: describe
     */
    @Override
    public int getProgramiv(int program, int pname) {
        WebGLProgram p = program(program);
        return toInt(gl.getProgramParameter(p, pname));
    }

    /**
     * TODO: document {@code getProgramInfoLog}.
     *
     * @param program TODO: describe
     * @return TODO: describe
     */
    @Override
    public String getProgramInfoLog(int program) {
        WebGLProgram p = program(program);
        return gl.getProgramInfoLog(p);
    }

    /**
     * TODO: document {@code useProgram}.
     *
     * @param program TODO: describe
     */
    @Override
    public void useProgram(int program) {
        WebGLProgram p = program(program);
        gl.useProgram(p);
    }

    /**
     * TODO: document {@code deleteShader}.
     *
     * @param shader TODO: describe
     */
    @Override
    public void deleteShader(int shader) {
        WebGLShader s = shader(shader);
        if (s != null)
            gl.deleteShader(s);
        shaderMap.remove(shader);
    }

    /**
     * TODO: document {@code deleteProgram}.
     *
     * @param program TODO: describe
     */
    @Override
    public void deleteProgram(int program) {
        WebGLProgram p = program(program);
        if (p != null)
            gl.deleteProgram(p);
        programMap.remove(program);
    }

    /**
     * TODO: document {@code genBuffer}.
     *
     * @return TODO: describe
     */
    @Override
    public int genBuffer() {
        WebGLBuffer b = gl.createBuffer();
        int id = nextId++;
        bufferMap.put(id, b);
        return id;
    }

    /**
     * TODO: document {@code bindArrayBuffer}.
     *
     * @param buffer TODO: describe
     */
    @Override
    public void bindArrayBuffer(int buffer) {
        WebGLBuffer b = buffer(buffer);
        gl.bindBuffer(WebGLRenderingContext.ARRAY_BUFFER, b);
    }

    /**
     * TODO: document {@code bufferDataArray}.
     *
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    @Override
    public void bufferDataArray(IxBuffer data, int usage) {
        gl.bufferData(WebGLRenderingContext.ARRAY_BUFFER, ((WebBuffer) data).getFloatBuffer(), usage);
    }

    /**
     * TODO: document {@code bufferDataArray}.
     *
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    @Override
    public void bufferDataArray(float[] data, int usage) {
        org.teavm.jso.typedarrays.Float32Array arr = org.teavm.jso.typedarrays.Float32Array.create(data.length);
        for (int i = 0; i < data.length; i++)
            arr.set(i, data[i]);
        gl.bufferData(WebGLRenderingContext.ARRAY_BUFFER, arr, usage);
    }

    /**
     * TODO: document {@code enableVertexAttribArray}.
     *
     * @param index TODO: describe
     */
    @Override
    public void enableVertexAttribArray(int index) {
        gl.enableVertexAttribArray(index);
    }

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
    @Override
    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int pointer) {
        gl.vertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    /**
     * TODO: document {@code genVertexArray}.
     *
     * @return TODO: describe
     */
    @Override
    public int genVertexArray() {
        return vaoExt.genVertexArray();
    }

    /**
     * TODO: document {@code bindVertexArray}.
     *
     * @param vao TODO: describe
     */
    @Override
    public void bindVertexArray(int vao) {
        vaoExt.bindVertexArray(vao);
    }

    /**
     * TODO: document {@code drawArrays}.
     *
     * @param mode TODO: describe
     * @param first TODO: describe
     * @param count TODO: describe
     */
    @Override
    public void drawArrays(int mode, int first, int count) {
        gl.drawArrays(mode, first, count);
    }

    /**
     * TODO: document {@code drawElements}.
     *
     * @param mode TODO: describe
     * @param count TODO: describe
     * @param type TODO: describe
     * @param indicesOffsetBytes TODO: describe
     */
    @Override
    public void drawElements(int mode, int count, int type, int indicesOffsetBytes) {
        gl.drawElements(mode, count, type, indicesOffsetBytes);
    }

    /**
     * TODO: document {@code getUniformLocation}.
     *
     * @param program TODO: describe
     * @param name TODO: describe
     * @return TODO: describe
     */
    @Override
    public int getUniformLocation(int program, String name) {
        WebGLUniformLocation l = gl.getUniformLocation(program(program), name);
        if (l == null)
            return -1;
        int id = nextId++;
        uniformMap.put(id, l);
        return id;
    }

    /**
     * TODO: document {@code uniform1f}.
     *
     * @param loc TODO: describe
     * @param v TODO: describe
     */
    @Override
    public void uniform1f(int loc, float v) {
        gl.uniform1f(uniform(loc), v);
    }

    /**
     * TODO: document {@code uniform1i}.
     *
     * @param loc TODO: describe
     * @param v TODO: describe
     */
    @Override
    public void uniform1i(int loc, int v) {
        gl.uniform1i(uniform(loc), v);
    }

    /**
     * TODO: document {@code uniform2fv}.
     *
     * @param loc TODO: describe
     * @param buf TODO: describe
     */
    @Override
    public void uniform2fv(int loc, IxBuffer buf) {
        gl.uniform2fv(uniform(loc), ((WebBuffer) buf).getFloatBuffer());
    }

    /**
     * TODO: document {@code uniform3fv}.
     *
     * @param loc TODO: describe
     * @param buf TODO: describe
     */
    @Override
    public void uniform3fv(int loc, IxBuffer buf) {
        gl.uniform3fv(uniform(loc), ((WebBuffer) buf).getFloatBuffer());
    }

    /**
     * TODO: document {@code uniform4fv}.
     *
     * @param loc TODO: describe
     * @param buf TODO: describe
     */
    @Override
    public void uniform4fv(int loc, IxBuffer buf) {
        gl.uniform4fv(uniform(loc), ((WebBuffer) buf).getFloatBuffer());
    }

    /**
     * TODO: document {@code uniformMatrix4fv}.
     *
     * @param loc TODO: describe
     * @param transpose TODO: describe
     * @param buf TODO: describe
     */
    @Override
    public void uniformMatrix4fv(int loc, boolean transpose, IxBuffer buf) {
        gl.uniformMatrix4fv(uniform(loc), transpose, ((WebBuffer) buf).getFloatBuffer());
    }

    /**
     * TODO: document {@code genTexture}.
     *
     * @return TODO: describe
     */
    @Override
    public int genTexture() {
        WebGLTexture t = gl.createTexture();
        int id = nextId++;
        textureMap.put(id, t);
        return id;
    }

    /**
     * TODO: document {@code deleteTexture}.
     *
     * @param id TODO: describe
     */
    @Override
    public void deleteTexture(int id) {
        WebGLTexture t = textureMap.remove(id);
        if (t != null) {
            gl.deleteTexture(t);
        }
    }

    /**
     * TODO: document {@code bindTexture2D}.
     *
     * @param id TODO: describe
     */
    @Override
    public void bindTexture2D(int id) {
        gl.bindTexture(WebGLRenderingContext.TEXTURE_2D, texture(id));
    }

    /**
     * TODO: document {@code texParameteri}.
     *
     * @param target TODO: describe
     * @param pname TODO: describe
     * @param param TODO: describe
     */
    @Override
    public void texParameteri(int target, int pname, int param) {
        gl.texParameteri(target, pname, param);
    }

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

    /**
     * TODO: document {@code generateMipmap}.
     *
     * @param target TODO: describe
     */
    @Override
    public void generateMipmap(int target) {
        gl.generateMipmap(target);
    }

    /**
     * TODO: document {@code COLOR_BUFFER_BIT}.
     *
     * @return TODO: describe
     */
    @Override
    public int COLOR_BUFFER_BIT() {
        return WebGLRenderingContext.COLOR_BUFFER_BIT;
    }

    /**
     * TODO: document {@code TRIANGLES}.
     *
     * @return TODO: describe
     */
    @Override
    public int TRIANGLES() {
        return WebGLRenderingContext.TRIANGLES;
    }

    /**
     * TODO: document {@code ARRAY_BUFFER}.
     *
     * @return TODO: describe
     */
    @Override
    public int ARRAY_BUFFER() {
        return WebGLRenderingContext.ARRAY_BUFFER;
    }

    /**
     * TODO: document {@code ELEMENT_ARRAY_BUFFER}.
     *
     * @return TODO: describe
     */
    @Override
    public int ELEMENT_ARRAY_BUFFER() {
        return WebGLRenderingContext.ELEMENT_ARRAY_BUFFER;
    }

    /**
     * TODO: document {@code STATIC_DRAW}.
     *
     * @return TODO: describe
     */
    @Override
    public int STATIC_DRAW() {
        return WebGLRenderingContext.STATIC_DRAW;
    }

    /**
     * TODO: document {@code FLOAT}.
     *
     * @return TODO: describe
     */
    @Override
    public int FLOAT() {
        return WebGLRenderingContext.FLOAT;
    }

    /**
     * TODO: document {@code FRAGMENT_SHADER}.
     *
     * @return TODO: describe
     */
    @Override
    public int FRAGMENT_SHADER() {
        return WebGLRenderingContext.FRAGMENT_SHADER;
    }

    /**
     * TODO: document {@code VERTEX_SHADER}.
     *
     * @return TODO: describe
     */
    @Override
    public int VERTEX_SHADER() {
        return WebGLRenderingContext.VERTEX_SHADER;
    }

    /**
     * TODO: document {@code TEXTURE_2D}.
     *
     * @return TODO: describe
     */
    @Override
    public int TEXTURE_2D() {
        return WebGLRenderingContext.TEXTURE_2D;
    }

    /**
     * TODO: document {@code RGBA}.
     *
     * @return TODO: describe
     */
    @Override
    public int RGBA() {
        return WebGLRenderingContext.RGBA;
    }

    /**
     * TODO: document {@code LINES}.
     *
     * @return TODO: describe
     */
    @Override
    public int LINES() {
        return WebGLRenderingContext.LINES;
    }

    /**
     * TODO: document {@code lineWidth}.
     *
     * @param width TODO: describe
     */
    @Override
    public void lineWidth(float width) {
        gl.lineWidth(width);
    }

    /**
     * TODO: document {@code RGBA8}.
     *
     * @return TODO: describe
     */
    @Override
    public int RGBA8() {
        return WebGLRenderingContext.RGBA;
    }

    /**
     * TODO: document {@code UNSIGNED_BYTE}.
     *
     * @return TODO: describe
     */
    @Override
    public int UNSIGNED_BYTE() {
        return WebGLRenderingContext.UNSIGNED_BYTE;
    }

    /**
     * TODO: document {@code UNSIGNED_INT}.
     *
     * @return TODO: describe
     */
    @Override
    public int UNSIGNED_INT() {
        return NUM_0x1405;
    }

    /**
     * TODO: document {@code TEXTURE_WRAP_S}.
     *
     * @return TODO: describe
     */
    @Override
    public int TEXTURE_WRAP_S() {
        return WebGLRenderingContext.TEXTURE_WRAP_S;
    }

    /**
     * TODO: document {@code TEXTURE_WRAP_T}.
     *
     * @return TODO: describe
     */
    @Override
    public int TEXTURE_WRAP_T() {
        return WebGLRenderingContext.TEXTURE_WRAP_T;
    }

    /**
     * TODO: document {@code TEXTURE_MIN_FILTER}.
     *
     * @return TODO: describe
     */
    @Override
    public int TEXTURE_MIN_FILTER() {
        return WebGLRenderingContext.TEXTURE_MIN_FILTER;
    }

    /**
     * TODO: document {@code TEXTURE_MAG_FILTER}.
     *
     * @return TODO: describe
     */
    @Override
    public int TEXTURE_MAG_FILTER() {
        return WebGLRenderingContext.TEXTURE_MAG_FILTER;
    }

    /**
     * TODO: document {@code LINEAR}.
     *
     * @return TODO: describe
     */
    @Override
    public int LINEAR() {
        return WebGLRenderingContext.LINEAR;
    }

    /**
     * TODO: document {@code REPEAT}.
     *
     * @return TODO: describe
     */
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

    /**
     * TODO: document {@code getMouseButton}.
     *
     * @param window TODO: describe
     * @param mouseButtonLeft TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean getMouseButton(long window, MouseButtons mouseButtonLeft) {
        return WebPlatformHelper.leftDown;
    }

    /**
     * TODO: document {@code SRC_ALPHA}.
     *
     * @return TODO: describe
     */
    @Override
    public int SRC_ALPHA() {
        return WebGLRenderingContext.SRC_ALPHA;
    }

    /**
     * TODO: document {@code ONE_MINUS_SRC_ALPHA}.
     *
     * @return TODO: describe
     */
    @Override
    public int ONE_MINUS_SRC_ALPHA() {
        return WebGLRenderingContext.ONE_MINUS_SRC_ALPHA;
    }

    /**
     * TODO: document {@code BLEND}.
     *
     * @return TODO: describe
     */
    @Override
    public int BLEND() {
        return WebGLRenderingContext.BLEND;
    }

    /**
     * TODO: document {@code blendFunc}.
     *
     * @param SRC_ALPHA TODO: describe
     * @param ONE_MINUS_SRC_ALPHA TODO: describe
     */
    @Override
    public void blendFunc(int SRC_ALPHA, int ONE_MINUS_SRC_ALPHA) {
        gl.blendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA);
    }

    /**
     * TODO: document {@code enable}.
     *
     * @param blend TODO: describe
     */
    @Override
    public void enable(int blend) {
        gl.enable(blend);
    }

    /**
     * TODO: document {@code disable}.
     *
     * @param depthTest TODO: describe
     */
    @Override
    public void disable(int depthTest) {
        gl.disable(depthTest);
    }

    /**
     * TODO: document {@code depthMask}.
     *
     * @param flag TODO: describe
     */
    @Override
    public void depthMask(boolean flag) {
        gl.depthMask(flag);
    }

    @JSBody(params = { "v" }, script = "return (v|0);")
    private static native int toInt(Object v);

    /**
     * TODO: document {@code createCapabilities}.
     */
    @Override
    public void createCapabilities() {
    }

    /**
     * TODO: document {@code DEPTH_TEST}.
     *
     * @return TODO: describe
     */
    @Override
    public int DEPTH_TEST() {
        return WebGLRenderingContext.DEPTH_TEST;
    }

    /**
     * TODO: document {@code DEPTH_BUFFER_BIT}.
     *
     * @return TODO: describe
     */
    @Override
    public int DEPTH_BUFFER_BIT() {
        return WebGLRenderingContext.DEPTH_BUFFER_BIT;
    }

    /**
     * TODO: document {@code setWindowTitle}.
     *
     * @param string TODO: describe
     */
    @Override
    public void setWindowTitle(String string) {
        WebLauncher.setTitle(string);
    }

    /**
     * TODO: document {@code genVertexArrays}.
     *
     * @return TODO: describe
     */
    @Override
    public int genVertexArrays() {
        return vaoExt.genVertexArray();
    }

    /**
     * TODO: document {@code deleteVertexArrays}.
     *
     * @param id TODO: describe
     */
    @Override
    public void deleteVertexArrays(int id) {
        vaoExt.deleteVertexArray(id);
    }

    /**
     * TODO: document {@code genBuffers}.
     *
     * @return TODO: describe
     */
    @Override
    public int genBuffers() {
        WebGLBuffer b = gl.createBuffer();
        int id = nextId++;
        bufferMap.put(id, b);
        return id;
    }

    /**
     * TODO: document {@code bindBuffer}.
     *
     * @param target TODO: describe
     * @param id TODO: describe
     */
    @Override
    public void bindBuffer(int target, int id) {
        WebGLBuffer b = buffer(id);
        gl.bindBuffer(target, b);
    }

    /**
     * TODO: document {@code bufferData}.
     *
     * @param target TODO: describe
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    @Override
    public void bufferData(int target, IxBuffer data, int usage) {
        gl.bufferData(target, ((WebBuffer) data).getFloatBuffer(), usage);
    }

    /**
     * TODO: document {@code bufferData}.
     *
     * @param target TODO: describe
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    @Override
    public void bufferData(int target, float[] data, int usage) {
        org.teavm.jso.typedarrays.Float32Array arr = org.teavm.jso.typedarrays.Float32Array.create(data.length);
        for (int i = 0; i < data.length; i++) {
            arr.set(i, data[i]);
        }
        gl.bufferData(target, arr, usage);
    }

    /**
     * TODO: document {@code bufferData}.
     *
     * @param target TODO: describe
     * @param size TODO: describe
     * @param usage TODO: describe
     */
    @Override
    public void bufferData(int target, long size, int usage) {
        org.teavm.jso.typedarrays.Uint8Array arr = org.teavm.jso.typedarrays.Uint8Array.create((int) size);
        gl.bufferData(target, arr, usage);
    }

    /**
     * TODO: document {@code bufferSubData}.
     *
     * @param target TODO: describe
     * @param offset TODO: describe
     * @param data TODO: describe
     */
    @Override
    public void bufferSubData(int target, long offset, IxBuffer data) {
        gl.bufferSubData(target, (int) offset, ((WebBuffer) data).getFloatBuffer());
    }

    /**
     * TODO: document {@code bufferData}.
     *
     * @param target TODO: describe
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    @Override
    public void bufferData(int target, IntBuffer data, int usage) {
        org.teavm.jso.typedarrays.Int32Array arr = org.teavm.jso.typedarrays.Int32Array.create(data.remaining());
        for (int i = 0, j = data.position(); j < data.limit(); i++, j++) {
            arr.set(i, data.get(j));
        }
        gl.bufferData(target, arr, usage);
    }

    /**
     * TODO: document {@code deleteBuffers}.
     *
     * @param id TODO: describe
     */
    @Override
    public void deleteBuffers(int id) {
        WebGLBuffer b = bufferMap.remove(id);
        if (b != null) {
            gl.deleteBuffer(b);
        }
    }

    /**
     * TODO: document {@code getAttribLocation}.
     *
     * @param iD TODO: describe
     * @param name TODO: describe
     * @return TODO: describe
     */
    @Override
    public int getAttribLocation(int iD, CharSequence name) {
        return gl.getAttribLocation(program(iD), name.toString());
    }

    /**
     * TODO: document {@code DYNAMIC_DRAW}.
     *
     * @return TODO: describe
     */
    @Override
    public int DYNAMIC_DRAW() {
        return WebGLRenderingContext.DYNAMIC_DRAW;
    }

    /**
     * TODO: document {@code bindFragDataLocation}.
     *
     * @param iD TODO: describe
     * @param i TODO: describe
     * @param string TODO: describe
     */
    @Override
    public void bindFragDataLocation(int iD, int i, String string) {

    }

    /**
     * TODO: document {@code activeTexture}.
     *
     * @param i TODO: describe
     */
    @Override
    public void activeTexture(int i) {
        gl.activeTexture(i);
    }

    /**
     * TODO: document {@code detachShader}.
     *
     * @param iD TODO: describe
     * @param fragmentShader TODO: describe
     */
    @Override
    public void detachShader(int iD, int fragmentShader) {
        gl.detachShader(program(iD), shader(fragmentShader));
    }

    /**
     * TODO: document {@code shaderSource}.
     *
     * @param fragmentShader TODO: describe
     * @param fragmentShaderSource TODO: describe
     */
    @Override
    public void shaderSource(int fragmentShader, CharSequence[] fragmentShaderSource) {
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : fragmentShaderSource) {
            sb.append(cs);
        }
        String result = sb.toString();
        gl.shaderSource(shader(fragmentShader), result);
    }

    /**
     * TODO: document {@code LINK_STATUS}.
     *
     * @return TODO: describe
     */
    @Override
    public int LINK_STATUS() {
        return WebGLRenderingContext.LINK_STATUS;
    }

    /**
     * TODO: document {@code getProgramiv}.
     *
     * @param program TODO: describe
     * @param link_STATUS TODO: describe
     * @param success TODO: describe
     */
    @Override
    public void getProgramiv(int program, int link_STATUS, IntBuffer success) {
        int val = toInt(gl.getProgramParameter(program(program), link_STATUS));
        if (success != null && success.remaining() > 0) {
            success.put(0, val);
        }
    }

    /**
     * TODO: document {@code getAttachedShaders}.
     *
     * @param program TODO: describe
     * @param success TODO: describe
     */
    @Override
    public void getAttachedShaders(int program, IntBuffer success) {
        int val = toInt(gl.getProgramParameter(program(program), WebGLRenderingContext.ATTACHED_SHADERS));
        if (success != null && success.remaining() > 0) {
            success.put(0, val);
        }
    }

    /**
     * TODO: document {@code getActiveUniforms}.
     *
     * @param program TODO: describe
     * @param success TODO: describe
     */
    @Override
    public void getActiveUniforms(int program, IntBuffer success) {
        int val = toInt(gl.getProgramParameter(program(program), WebGLRenderingContext.ACTIVE_UNIFORMS));
        if (success != null && success.remaining() > 0) {
            success.put(0, val);
        }
    }

    /**
     * TODO: document {@code COMPILE_STATUS}.
     *
     * @return TODO: describe
     */
    @Override
    public int COMPILE_STATUS() {
        return WebGLRenderingContext.COMPILE_STATUS;
    }

    /**
     * TODO: document {@code getShaderiv}.
     *
     * @param shader TODO: describe
     * @param compile_STATUS TODO: describe
     * @param success TODO: describe
     */
    @Override
    public void getShaderiv(int shader, int compile_STATUS, IntBuffer success) {
        int val = toInt(gl.getShaderParameter(shader(shader), compile_STATUS));
        if (success != null && success.remaining() > 0) {
            success.put(0, val);
        }
    }

    /**
     * TODO: document {@code uniform3fv}.
     *
     * @param integer TODO: describe
     * @param vec3 TODO: describe
     */
    @Override
    public void uniform3fv(Integer integer, IxBuffer vec3) {
        gl.uniform3fv(uniform(integer), ((WebBuffer) vec3).getFloatBuffer());
    }

    /**
     * TODO: document {@code readPixels}.
     *
     * @param i TODO: describe
     * @param j TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     * @param rgba TODO: describe
     * @param unsigned_BYTE TODO: describe
     * @param size TODO: describe
     * @return TODO: describe
     */
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

    /**
     * TODO: document {@code TEXTURE0}.
     *
     * @return TODO: describe
     */
    @Override
    public int TEXTURE0() {
        return WebGLRenderingContext.TEXTURE0;
    }

    /**
     * TODO: document {@code coldStartStack}.
     */
    @Override
    public void coldStartStack() {
    }

    @JSBody(params = { "canvas",
            "attrs" }, script = "return (canvas.getContext('webgl2', attrs) || canvas.getContext('webgl', attrs) || canvas.getContext('experimental-webgl', attrs));")
    private static native WebGLRenderingContext acquireGL(HTMLCanvasElement canvas, WebGLContextAttributes attrs);

    @JSBody(params = { "gl", "enable" }, script = "if(!gl){return;} try{gl.pixelStorei(0x9240, enable?1:0);}catch(e){}")
    private static native void setUnpackFlipY(WebGLRenderingContext gl, boolean enable);

    /**
     * TODO: document {@code ACTIVE_UNIFORMS}.
     *
     * @return TODO: describe
     */
    @Override
    public int ACTIVE_UNIFORMS() {
        return WebGLRenderingContext.ACTIVE_UNIFORMS;
    }

    /**
     * TODO: document {@code getActiveUniform}.
     *
     * @param iD TODO: describe
     * @param i TODO: describe
     * @param sizeBuffer TODO: describe
     * @param typeBuffer TODO: describe
     * @return TODO: describe
     */
    @Override
    public String getActiveUniform(int iD, int i, IntBuffer sizeBuffer, IntBuffer typeBuffer) {
        return gl.getActiveUniform(program(iD), i).toString();
    }

    /**
     * TODO: document {@code FLOAT_VEC2}.
     *
     * @return TODO: describe
     */
    @Override
    public int FLOAT_VEC2() {
        return WebGLRenderingContext.FLOAT_VEC2;
    }

    /**
     * TODO: document {@code FLOAT_VEC4}.
     *
     * @return TODO: describe
     */
    @Override
    public int FLOAT_VEC4() {
        return WebGLRenderingContext.FLOAT_VEC4;
    }

    /**
     * TODO: document {@code SAMPLER_2D}.
     *
     * @return TODO: describe
     */
    @Override
    public int SAMPLER_2D() {
        return WebGLRenderingContext.SAMPLER_2D;
    }

    /**
     * TODO: document {@code getUniformfv}.
     *
     * @param iD TODO: describe
     * @param location TODO: describe
     * @param val TODO: describe
     */
    @Override
    public void getUniformfv(int iD, int location, IxBuffer val) {
    }

    /**
     * TODO: document {@code getDrawingBufferWidth}.
     *
     * @return TODO: describe
     */
    public int getDrawingBufferWidth() {
        return gl.getDrawingBufferWidth();
    }

    /**
     * TODO: document {@code getDrawingBufferHeight}.
     *
     * @return TODO: describe
     */
    public int getDrawingBufferHeight() {
        return gl.getDrawingBufferHeight();
    }

    /**
     * TODO: document {@code LINEAR_MIPMAP_LINEAR}.
     *
     * @return TODO: describe
     */
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

}
