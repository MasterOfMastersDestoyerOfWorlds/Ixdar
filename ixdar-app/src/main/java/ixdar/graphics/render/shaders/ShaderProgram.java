package ixdar.graphics.render.shaders;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.IntBuffer;

import java.nio.ByteOrder;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import ixdar.graphics.render.Texture;
import ixdar.graphics.render.color.Color;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GlslSource;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.IxBuffer;
import ixdar.platform.gl.Platform;
import ixdar.scenes.main.MainScene;

public abstract class ShaderProgram {
    public static final String FONT_VS = "font.vs";
    public static final String MESH_VS = "mesh.vs";
    public static final String SRC_MAIN_RESOURCES_GLSL = "./src/main/resources/glsl/";
    public static final String GLSL = "glsl";
    public static final String ERROR_SHADER = "ERROR::SHADER::";
    public static final String N = "\n";
    public static final String STR_0 = "\0";
    public static final String SHADERPROGRAM_PLATFORM_MISMATCH = "ShaderProgram: Platform mismatch";
    public static final String POSITION = "position";
    public static final String COLOR = "color";
    public static final String TEXCOORD = "texCoord";
    public static final String VERTEXCOLOR = "vertexColor";
    public static final int NUM_16 = 16;
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final float NUM_1 = 1f;
    public static final float NUM_0 = 0f;
    public static final int NUM_9 = 9;
    public static final int NUM_6 = 6;
    public static final int NUM_7 = 7;
    public static final int NUM_8 = 8;

    public final static float ORTHO_FAR = 1000f;
    public final static float ORTHO_NEAR = -ORTHO_FAR;
    public final static float ORTHO_Z_INCREMENT = 0.1f;

    private static final Map<Long, Object> GLOBAL_OWNER_KEYS = new HashMap<>();
    public VertexArrayObject vao;
    public VertexBufferObject vbo;
    public HashMap<String, Integer> uniformLocations;

    public int ID = -1;
    public IxBuffer verteciesBuff;
    public Map<String, Object> uniformMap = new HashMap<>();
    public int platformId;
    public GL gl;
    public Platform platform;

    /** Number of floats per vertex for this shader's bound attributes. */
    protected int strideFloats = 0;

    String vertexCode;
    String fragmentCode;
    CharSequence[] vertexShaderSource;
    CharSequence[] fragmentShaderSource;
    int vertexShader, fragmentShader;
    private int numVertices;
    private boolean drawing;
    /** First vertex index reserved for persistent drawable allocations. */
    private int regionStartVertex = -1;
    /** Next free vertex cursor within the persistent region. */
    private int regionCursorVertex = -1;
    /** Queued draw ranges that refer to persistent VBO regions. */
    private final List<DrawRange> queuedRanges = new ArrayList<>();
    /** Allocation table per owner object for its persistent VBO slice. */
    private final Map<Object, Allocation> allocations = new HashMap<>();
    /** Current GPU buffer size in bytes. */
    private long vboSizeBytes = 0L;
    /** Reserved staging floats for legacy immediate path. */
    private int stagingReservedFloats = 0;

    @SuppressWarnings("unused")
    private String vertexShaderLocation, fragmentShaderLocation;
    private File fragmentShaderFile;
    private long fragmentLastModified;
    private File vertexShaderFile;
    private long vertexLastModified;
    private boolean useBuffer;
    private boolean reloadShader;

    private String originalFragmentSourceStr;

    private final Map<Long, Allocation> idToAllocation = new HashMap<>();

    /**
     * Reusable direct buffers for uniform uploads — avoids per-call
     * ByteBuffer.allocateDirect().
     */
    private IxBuffer vec2Buf;
    private IxBuffer vec3Buf;
    private IxBuffer vec4Buf;
    private IxBuffer mat4Buf;

    /**
     * Asynchronously load shader sources, normalize them for the active GL backend
     * (desktop GL3.3 vs WebGL ES 3.0), compile and link them, and call
     * {@link #init()} when ready.
     *
     * @param vertexShaderLocation   vertex GLSL resource path
     * @param fragmentShaderLocation fragment GLSL resource path
     * @param vao                    vertex array object owned by this program
     * @param vbo                    vertex buffer object owned by this program
     * @param strideFloats           number of floats per vertex
     * @param useBuffer              {@code true} to allocate a streaming staging
     *                               buffer for the legacy immediate-mode draw path
     * @throws UnsupportedEncodingException on shader source encoding error
     * @throws IOException                  on shader source I/O error
     */
    public ShaderProgram(String vertexShaderLocation, String fragmentShaderLocation, VertexArrayObject vao,
            VertexBufferObject vbo, int strideFloats, boolean useBuffer)
            throws UnsupportedEncodingException, IOException {
        this.fragmentShaderLocation = fragmentShaderLocation;
        this.vertexShaderLocation = vertexShaderLocation;
        this.uniformLocations = new HashMap<>();
        this.vao = vao;
        this.vbo = vbo;
        this.strideFloats = strideFloats;
        this.useBuffer = useBuffer;
        this.platformId = Platforms.gl().getPlatformID();
        gl = Platforms.gl();
        platform = Platforms.get();

        try {
            fragmentShaderFile = new File(SRC_MAIN_RESOURCES_GLSL + fragmentShaderLocation);
            vertexShaderFile = new File(SRC_MAIN_RESOURCES_GLSL + vertexShaderLocation);
            if (fragmentShaderFile.exists()) {
                fragmentLastModified = fragmentShaderFile.lastModified();
            }
            if (vertexShaderFile.exists()) {
                vertexLastModified = vertexShaderFile.lastModified();
            }
        } catch (Exception ignore) {
        }

        Platforms.get().loadShaderSourceAsync(GLSL, vertexShaderLocation, platformId, vertexShaderSource -> {
            Platforms.get().loadShaderSourceAsync(GLSL, fragmentShaderLocation, platformId, fragmentShaderSource -> {
                this.vertexShaderSource = normalizeSharedGlslForBackend(new CharSequence[] { vertexShaderSource });
                this.fragmentShaderSource = normalizeSharedGlslForBackend(new CharSequence[] { fragmentShaderSource });
                this.originalFragmentSourceStr = fragmentShaderSource;
                recompileShaders(vertexShaderLocation, fragmentShaderLocation);
                init();
            });
        });
    }

    private int uniformLocation(String name) {
        Integer existing = uniformLocations.get(name);
        if (existing != null) {
            return existing.intValue();
        }
        int loc = ID >= 0 ? gl.getUniformLocation(ID, name) : -1;
        uniformLocations.put(name, loc);
        return loc;
    }

    /**
     * Look up a vertex attribute location by name on the linked program.
     *
     * @param name attribute identifier in the vertex shader
     * @return GL attribute location, or -1 if not found
     */
    public int getAttributeLocation(CharSequence name) {

        return gl.getAttribLocation(ID, name);
    }

    /**
     * Make this program the current one (no-op if linking has not finished).
     */
    public void use() {
        if (ID < 0) {
            return;
        }
        gl.useProgram(ID);
    }

    /**
     * Upload a boolean uniform (encoded as 0/1 int) and cache it.
     *
     * @param name  uniform name
     * @param value boolean value
     */
    public void setBool(String name, boolean value) {

        gl.uniform1i(uniformLocation(name), value ? 1 : 0);
        uniformMap.put(name, value);
    }

    /**
     * Upload an integer uniform and cache it.
     *
     * @param name  uniform name
     * @param value integer value (or sampler binding)
     */
    public void setInt(String name, int value) {

        gl.uniform1i(uniformLocation(name), value);
        uniformMap.put(name, value);
    }

    /**
     * Upload a float uniform and cache it.
     *
     * @param name  uniform name
     * @param value float value
     */
    public void setFloat(String name, float value) {

        gl.uniform1f(uniformLocation(name), value);
        uniformMap.put(name, value);
    }

    /**
     * Upload a 4x4 matrix uniform via a reusable direct float buffer (avoids
     * per-call native allocations) and cache it.
     *
     * @param name uniform name
     * @param mat  matrix to upload
     */
    public void setMat4(String name, Matrix4f mat) {

        if (mat4Buf == null)
            mat4Buf = platform.allocateFloats(NUM_16);
        mat4Buf.clear();

        mat4Buf.put(mat.m00()).put(mat.m01()).put(mat.m02()).put(mat.m03());
        mat4Buf.put(mat.m10()).put(mat.m11()).put(mat.m12()).put(mat.m13());
        mat4Buf.put(mat.m20()).put(mat.m21()).put(mat.m22()).put(mat.m23());
        mat4Buf.put(mat.m30()).put(mat.m31()).put(mat.m32()).put(mat.m33());
        mat4Buf.flip();
        gl.uniformMatrix4fv(uniformLocation(name), false, mat4Buf);
        uniformMap.put(name, mat);
    }

    /**
     * Upload a 4x4 matrix uniform from a caller-owned, already-flipped buffer.
     *
     * @param name            uniform name
     * @param allocatedBuffer 16-float buffer ready for upload
     */
    public void setMat4(String name, IxBuffer allocatedBuffer) {

        gl.uniformMatrix4fv(uniformLocation(name), false, allocatedBuffer);
        uniformMap.put(name, allocatedBuffer);
    }

    /**
     * Upload a vec2 uniform via a reusable direct float buffer and cache it.
     *
     * @param name uniform name
     * @param vec2 (x, y) vector
     */
    public void setVec2(String name, Vector2f vec2) {

        if (vec2Buf == null)
            vec2Buf = platform.allocateFloats(2);
        vec2Buf.clear();
        vec2Buf.put(vec2.x).put(vec2.y).flip();
        gl.uniform2fv(uniformLocation(name), vec2Buf);
        uniformMap.put(name, vec2);
    }

    /**
     * Upload a vec3 uniform from three components and cache it.
     *
     * @param name uniform name
     * @param f    x component
     * @param g    y component
     * @param h    z component
     */
    public void setVec3(String name, float f, float g, float h) {

        if (vec3Buf == null)
            vec3Buf = platform.allocateFloats(NUM_3);
        vec3Buf.clear();
        vec3Buf.put(f).put(g).put(h).flip();
        gl.uniform3fv(uniformLocation(name), vec3Buf);
        uniformMap.put(name, new Vector3f(f, g, h));
    }

    /**
     * Upload a vec3 uniform from a {@link Vector3f} and cache it.
     *
     * @param name uniform name
     * @param vec3 (x, y, z) vector
     */
    public void setVec3(String name, Vector3f vec3) {
        if (vec3Buf == null)
            vec3Buf = platform.allocateFloats(NUM_3);
        vec3Buf.clear();
        vec3Buf.put(vec3.x).put(vec3.y).put(vec3.z).flip();
        gl.uniform3fv(uniformLocation(name), vec3Buf);
        uniformMap.put(name, vec3);
    }

    /**
     * Upload a vec4 uniform via a reusable direct float buffer and cache it.
     *
     * @param name uniform name
     * @param vec4 (x, y, z, w) vector
     */
    public void setVec4(String name, Vector4f vec4) {

        if (vec4Buf == null)
            vec4Buf = platform.allocateFloats(NUM_4);
        vec4Buf.clear();
        vec4Buf.put(vec4.x).put(vec4.y).put(vec4.z).put(vec4.w).flip();
        gl.uniform4fv(uniformLocation(name), vec4Buf);
        uniformMap.put(name, vec4);
    }

    private void checkCompileErrors(int shader, ShaderOperationType type, String location,
            CharSequence[] shaderSource) {

        IntBuffer success = ByteBuffer.allocateDirect(NUM_4).order(ByteOrder.nativeOrder()).asIntBuffer();

        if (type != ShaderOperationType.Program) {
            gl.getShaderiv(shader, gl.COMPILE_STATUS(), success);
            if (success.get(0) == 0) {
                String infoLog = gl.getShaderInfoLog(shader);
                System.out.println(
                        ERROR_SHADER + type.name() + "::COMPILATION_FAILED: " + location + " \n" + infoLog);
            }
        } else {
            gl.getProgramiv(shader, gl.LINK_STATUS(), success);
            if (success.get(0) == 0) {
                String infoLog = gl.getShaderInfoLog(shader);
                System.out.println(ERROR_SHADER + type.name() + "::LINK_FAILED: " + location + N + infoLog);
            }
        }
    }

    /**
     * Read a shader source file line-by-line and append a NUL terminator on the
     * final chunk (required by the desktop loader path).
     *
     * @param shaderFile file to read
     * @throws IOException on read error
     * @return per-line {@link CharSequence} array suitable for upload
     */
    protected CharSequence[] readFile(File shaderFile) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(shaderFile));
        ArrayList<String> lines = new ArrayList<>();
        String line;
        while ((line = br.readLine()) != null) {
            lines.add(line + N);
        }
        String zero = lines.get(lines.size() - 1).replace(N, STR_0);
        lines.remove(lines.size() - 1);
        lines.add(zero);
        CharSequence[] vertexShaderSource = new CharSequence[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            vertexShaderSource[i] = lines.get(i);
        }
        br.close();
        return vertexShaderSource;
    }

    /**
     * Re-read shader files on disk and recompile/relink the program if any have
     * changed since the last check (no-op on platforms that don't support hot
     * reload).
     */
    public void hotReload() {
        if (!Platforms.get().canHotReload()) {
            return;
        }
        try {
            boolean didChange = false;
            boolean vertexModified = vertexShaderFile != null && vertexShaderFile.exists()
                    && vertexShaderFile.lastModified() != vertexLastModified;
            boolean fragmentModified = fragmentShaderFile != null && fragmentShaderFile.exists()
                    && fragmentShaderFile.lastModified() != fragmentLastModified;
            if (vertexModified) {
                vertexShaderSource = normalizeSharedGlslForBackend(readFile(vertexShaderFile));
                vertexLastModified = vertexShaderFile.lastModified();
                didChange = true;
            }
            if (fragmentModified) {
                fragmentShaderSource = normalizeSharedGlslForBackend(readFile(fragmentShaderFile));
                fragmentLastModified = fragmentShaderFile.lastModified();
                didChange = true;
            }
            if (didChange || reloadShader) {

                boolean success = reloadProgram();
                if (success) {
                    reloadShader = false;
                }
            }
        } catch (IOException e) {
            MainScene.terminal.error("Could not Hot Reload: " + e.getMessage());
        }
    }

    /**
     * Recompile and relink shaders. On success, swap in the new program, delete the
     * old one, rebuild VAO/VBO/uniform-location caches, reapply cached uniforms,
     * and refresh the projection matrix. On failure, keep the previous program ID.
     *
     * @return {@code true} if the new program linked successfully
     */
    public synchronized boolean reloadProgram() {
        if (this.platformId != Platforms.gl().getPlatformID()) {
            Platforms.get().log(SHADERPROGRAM_PLATFORM_MISMATCH);
        }
        int prevID = ID;
        recompileShaders(vertexShaderLocation, fragmentShaderLocation);
        IntBuffer success = ByteBuffer.allocateDirect(NUM_4).order(ByteOrder.nativeOrder()).asIntBuffer();
        gl.getProgramiv(ID, gl.LINK_STATUS(), success);
        if (success.get(0) != 0) {
            gl.useProgram(ID);
            if (prevID >= 0) {
                gl.deleteProgram(prevID);
            }
            this.vao = new VertexArrayObject();
            this.vbo = new VertexBufferObject();
            this.uniformLocations = new HashMap<>();
            init();
            resetPersistentAllocations();
            reapplyUniforms();
            updateProjectionMatrix(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(),
                    NUM_1);
            return true;
        } else {
            gl.deleteProgram(ID);
            ID = prevID;
            return false;
        }
    }

    /**
     * Reload the program using a custom fragment-shader source string, reverting to
     * the previous source if the new one fails to compile or link (used by the
     * in-app shader editor).
     *
     * @param src raw fragment GLSL source
     */
    public synchronized void reloadWithFragmentSource(String src) {
        CharSequence[] prevFrag = this.fragmentShaderSource;
        this.fragmentShaderSource = buildPlatformFragmentSource(src);
        boolean success = reloadProgram();
        if (success) {
            this.fragmentShaderSource = prevFrag;
        }
    }

    private void recompileShaders(String vertexShaderLocation, String fragmentShaderLocation) {

        vertexShader = gl.createShader(gl.VERTEX_SHADER());
        gl.shaderSource(vertexShader, vertexShaderSource);
        gl.compileShader(vertexShader);
        checkCompileErrors(vertexShader, ShaderOperationType.Vertex, vertexShaderLocation, vertexShaderSource);

        fragmentShader = gl.createShader(gl.FRAGMENT_SHADER());
        gl.shaderSource(fragmentShader, fragmentShaderSource);
        gl.compileShader(fragmentShader);
        checkCompileErrors(fragmentShader, ShaderOperationType.Fragment, fragmentShaderLocation, fragmentShaderSource);

        ID = gl.createProgram();
        gl.attachShader(ID, vertexShader);
        gl.attachShader(ID, fragmentShader);

        gl.bindFragDataLocation(ID, 0, "fragColor");
        gl.linkProgram(ID);
        gl.deleteShader(vertexShader);
        gl.deleteShader(fragmentShader);
        checkCompileErrors(ID, ShaderOperationType.Program, "both", vertexShaderSource);

    }

    /**
     * Build fragment source array in the same form as platform file loading.
     * Desktop (LWJGL) expects a trailing NUL on the last chunk; Web (WebGL) uses a
     * plain string without NUL.
     *
     * @param src raw fragment GLSL source
     * @return platform-appropriate source array
     */
    private CharSequence[] buildPlatformFragmentSource(String src) {
        String body = gl.usesWebGlsl() ? src : GlslSource.adaptEs300SharedForDesktopCore330(src);
        try {
            String platformName = Platforms.get().getClass().getName();
            boolean isWeb = platformName != null && platformName.toLowerCase().contains("web");
            if (isWeb) {
                return new CharSequence[] { body };
            }
        } catch (Exception ignore) {
        }
        return new CharSequence[] { body + STR_0 };
    }

    private CharSequence[] normalizeSharedGlslForBackend(CharSequence[] raw) {
        if (raw == null || gl.usesWebGlsl()) {
            return raw;
        }
        String joined = GlslSource.joinChunks(raw);
        return new CharSequence[] { GlslSource.adaptEs300SharedForDesktopCore330(joined) };
    }

    private void reapplyUniforms() {
        if (uniformMap == null || uniformMap.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> e : uniformMap.entrySet()) {
            String name = e.getKey();
            Object v = e.getValue();
            try {
                if (v instanceof Boolean) {
                    setBool(name, (Boolean) v);
                } else if (v instanceof Integer) {
                    setInt(name, (Integer) v);
                } else if (v instanceof Float) {
                    setFloat(name, (Float) v);
                } else if (v instanceof Matrix4f) {
                    setMat4(name, (Matrix4f) v);
                } else if (v instanceof Vector2f) {
                    setVec2(name, (Vector2f) v);
                } else if (v instanceof Vector3f) {
                    setVec3(name, (Vector3f) v);
                } else if (v instanceof Vector4f) {
                    setVec4(name, (Vector4f) v);
                } else if (v instanceof Texture) {

                    setTexture(name, (Texture) v, gl.TEXTURE0(), 0);
                }
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * After a program or buffer rebuild, invalidate persistent VBO allocation state
     * so that geometry gets reallocated/re-uploaded on the next frame.
     */
    private void resetPersistentAllocations() {

        regionStartVertex = -1;
        regionCursorVertex = -1;

        allocations.clear();
        idToAllocation.clear();
        queuedRanges.clear();
    }

    /** Restore the original fragment source captured at startup. */
    public synchronized void reloadWithOriginalSources() {
        if (originalFragmentSourceStr != null) {
            reloadWithFragmentSource(originalFragmentSourceStr);
        }
    }

    /**
     * Bind {@code tex} to texture unit {@code j}, set the sampler uniform, activate
     * texture unit {@code i}, and cache the texture by name. Lazily uploads the
     * texture to GL if needed.
     *
     * @param glslName sampler uniform name in the shader
     * @param tex      texture to bind ({@code null} caches a null binding)
     * @param i        GL texture unit constant (e.g. {@code GL_TEXTURE0 + j})
     * @param j        sampler binding slot index
     */
    public void setTexture(String glslName, Texture tex, int i, int j) {

        if (tex != null) {
            if (!tex.initialized) {
                tex.initGL();
                if (!tex.initialized) {
                    return;
                }
            }

            setInt(glslName, j);
            gl.activeTexture(i);
            tex.bind();
        }
        uniformMap.put(glslName, tex);
    }

    /**
     * Bind a fragment-shader output to a draw-buffer color attachment.
     *
     * @param i      color attachment index
     * @param string fragment output variable name
     */
    public void bindFragmentDataLocation(int i, String string) {

        gl.bindFragDataLocation(ID, i, string);
    }

    /**
     * Begin rendering: reset the staging vertex count and clear queued
     * persistent-region draws.
     *
     * @throws IllegalStateException if {@link #begin()} was already called
     */
    public void begin() {
        if (drawing) {
            throw new IllegalStateException("Renderer is already drawing!");
        }
        drawing = true;
        numVertices = 0;
        queuedRanges.clear();
    }

    /**
     * End rendering: flush staged geometry and any queued persistent draws.
     *
     * @throws IllegalStateException if {@link #begin()} was not called
     */
    public void end() {
        if (!drawing) {
            throw new IllegalStateException("Renderer isn't drawing!");
        }
        drawing = false;
        flush();
    }

    /**
     * Upload any staged vertices and issue draws for both the legacy immediate-mode
     * buffer and the queued persistent-region draw ranges.
     */
    public void flush() {
        if (ID < 0) {
            return;
        }
        if (this.platformId != Platforms.gl().getPlatformID()) {
            Platforms.get().log(SHADERPROGRAM_PLATFORM_MISMATCH);
        }
        if (useBuffer) {

            if (verteciesBuff != null && numVertices > 0) {
                verteciesBuff.flip();

                if (vao != null) {
                    vao.bind();
                } else {
                    vbo.bind(gl.ARRAY_BUFFER());
                }
                use();

                try {
                    int posAttrib = getAttributeLocation(POSITION);
                    if (posAttrib >= 0) {
                        gl.enableVertexAttribArray(posAttrib);
                    }
                    int colAttrib = getAttributeLocation(COLOR);
                    if (colAttrib >= 0) {
                        gl.enableVertexAttribArray(colAttrib);
                    }
                    int texCoordAttrib = getAttributeLocation(TEXCOORD);
                    if (texCoordAttrib >= 0) {
                        gl.enableVertexAttribArray(texCoordAttrib);
                    }
                } catch (Exception ignore) {
                }

                vbo.bind(gl.ARRAY_BUFFER());
                vbo.uploadSubData(gl.ARRAY_BUFFER(), 0, verteciesBuff);
                gl.drawArrays(gl.TRIANGLES(), 0, numVertices);

                verteciesBuff.clear();
                numVertices = 0;
            }

            if (!queuedRanges.isEmpty()) {
                if (vao != null) {
                    vao.bind();
                } else {
                    vbo.bind(gl.ARRAY_BUFFER());
                }
                use();

                try {
                    int posAttrib = getAttributeLocation(POSITION);
                    if (posAttrib >= 0) {
                        gl.enableVertexAttribArray(posAttrib);
                    }
                    int colAttrib = getAttributeLocation(COLOR);
                    if (colAttrib >= 0) {
                        gl.enableVertexAttribArray(colAttrib);
                    }
                    int texCoordAttrib = getAttributeLocation(TEXCOORD);
                    if (texCoordAttrib >= 0) {
                        gl.enableVertexAttribArray(texCoordAttrib);
                    }
                } catch (Exception ignore) {
                }
                for (int i = 0; i < queuedRanges.size(); i++) {
                    DrawRange r = queuedRanges.get(i);
                    gl.drawArrays(gl.TRIANGLES(), r.firstVertex, r.vertexCount);
                }
                queuedRanges.clear();
            }
        }
    }

    /**
     * Reassemble the vertex shader source as a plain string with the NUL terminator
     * stripped (used by the shader editor / inspector).
     *
     * @return current vertex shader source text
     */
    public String getVertexSource() {
        if (vertexShaderSource == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : vertexShaderSource) {
            if (cs != null)
                sb.append(cs);
        }
        int nul = sb.indexOf(STR_0);
        if (nul >= 0)
            sb.delete(nul, sb.length());
        return sb.toString();
    }

    /**
     * Reassemble the fragment shader source as a plain string with the NUL
     * terminator stripped.
     *
     * @return current fragment shader source text
     */
    public String getFragmentSource() {
        if (fragmentShaderSource == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : fragmentShaderSource) {
            if (cs != null)
                sb.append(cs);
        }
        int nul = sb.indexOf(STR_0);
        if (nul >= 0)
            sb.delete(nul, sb.length());
        return sb.toString();
    }

    /**
     * Draws the currently bound texture on specified coordinates and with specified
     * color.
     *
     * @param texture Used for getting width and height of the texture
     * @param x       X position of the texture
     * @param y       Y position of the texture
     * @param c       The color to use
     * @param zIndex  depth value written to gl_Position.z
     */
    public void drawTexture(Texture texture, float x, float y, float zIndex, Color c) {
        /* Vertex positions */
        float x1 = x;
        float y1 = y;
        float x2 = x1 + texture.getWidth();
        float y2 = y1 + texture.getHeight();

        /* Texture coordinates */
        float s1 = NUM_0;
        float t1 = NUM_0;
        float s2 = NUM_1;
        float t2 = NUM_1;

        drawTextureRegion(x1, y1, x2, y2, zIndex, s1, t1, s2, t2, c);
    }

    /**
     * Draws a texture region with the currently bound texture on specified
     * coordinates.
     *
     * @param texture   Used for getting width and height of the texture
     * @param x         X position of the texture
     * @param y         Y position of the texture
     * @param regX      X position of the texture region
     * @param regY      Y position of the texture region
     * @param regWidth  Width of the texture region
     * @param regHeight Height of the texture region
     * @param zIndex    depth value written to gl_Position.z
     */
    public void drawTextureRegion(Texture texture, float x, float y, float zIndex, float regX, float regY,
            float regWidth,
            float regHeight) {
        drawTextureRegion(texture, x, y, zIndex, regX, regY, regWidth, regHeight, Color.WHITE);
    }

    /**
     * Draws a texture region with the currently bound texture on specified
     * coordinates.
     *
     * @param texture   Used for getting width and height of the texture
     * @param x         X position of the texture
     * @param y         Y position of the texture
     * @param regX      X position of the texture region
     * @param regY      Y position of the texture region
     * @param regWidth  Width of the texture region
     * @param regHeight Height of the texture region
     * @param c         The color to use
     * @param zIndex    depth value written to gl_Position.z
     */
    public void drawTextureRegion(Texture texture, float x, float y, float zIndex, float regX, float regY,
            float regWidth,
            float regHeight, Color c) {
        /* Vertex positions */
        float x1 = x;
        float y1 = y;
        float x2 = x + regWidth;
        float y2 = y + regHeight;

        /* Texture coordinates */
        float s1 = regX / texture.getWidth();
        float t1 = regY / texture.getHeight();
        float s2 = (regX + regWidth) / texture.getWidth();
        float t2 = (regY + regHeight) / texture.getHeight();

        drawTextureRegion(x1, y1, x2, y2, zIndex, s1, t1, s2, t2, c);
    }

    /**
     * Draw a texture region with explicit destination corners (allows non-square
     * stretching).
     *
     * @param texture   source texture (used for width/height to compute uvs)
     * @param x         bottom-left destination x
     * @param y         bottom-left destination y
     * @param x2        top-right destination x
     * @param y2        top-right destination y
     * @param zIndex    depth value written to gl_Position.z
     * @param regX      source x in texture pixels
     * @param regY      source y in texture pixels
     * @param regWidth  source width in texture pixels
     * @param regHeight source height in texture pixels
     * @param c         tint color
     */
    public void drawTextureRegion(Texture texture, float x, float y, float x2, float y2, float zIndex, float regX,
            float regY, float regWidth,
            float regHeight, Color c) {
        /* Vertex positions */
        float x1 = x;
        float y1 = y;

        /* Texture coordinates */
        float s1 = regX / texture.getWidth();
        float t1 = regY / texture.getHeight();
        float s2 = (regX + regWidth) / texture.getWidth();
        float t2 = (regY + regHeight) / texture.getHeight();

        drawTextureRegion(x1, y1, x2, y2, zIndex, s1, t1, s2, t2, c);
    }

    /**
     * Draw a quad with synthesized [0..1]x[0..1] uvs derived from
     * {@code regWidth}/{@code regHeight} (no underlying texture binding assumed).
     *
     * @param x         bottom-left destination x
     * @param y         bottom-left destination y
     * @param x2        top-right destination x
     * @param y2        top-right destination y
     * @param zIndex    depth value written to gl_Position.z
     * @param regX      source x divisor for s1
     * @param regY      source y divisor for t1
     * @param regWidth  full-width divisor (scales s coordinates)
     * @param regHeight full-height divisor (scales t coordinates)
     * @param c         tint color
     */
    public void drawBlankTextureRegion(float x, float y, float x2, float y2, float zIndex, float regX,
            float regY, float regWidth,
            float regHeight, Color c) {
        /* Vertex positions */
        float x1 = x;
        float y1 = y;

        /* Texture coordinates */
        float s1 = regX / regWidth;
        float t1 = regY / regHeight;
        float s2 = (regX + regWidth) / regWidth;
        float t2 = (regY + regHeight) / regHeight;

        drawTextureRegion(x1, y1, x2, y2, zIndex, s1, t1, s2, t2, c);
    }

    /**
     * Draws a texture region with the currently bound texture on specified
     * coordinates.
     *
     * @param x1     Bottom left x position
     * @param y1     Bottom left y position
     * @param x2     Top right x position
     * @param y2     Top right y position
     * @param s1     Bottom left s coordinate
     * @param t1     Bottom left t coordinate
     * @param s2     Top right s coordinate
     * @param t2     Top right t coordinate
     * @param zIndex depth value written to gl_Position.z
     */
    public void drawTextureRegion(float x1, float y1, float x2, float y2, float zIndex, float s1, float t1, float s2,
            float t2) {
        drawTextureRegion(x1, y1, x2, y2, zIndex, s1, t1, s2, t2, Color.WHITE);
    }

    /**
     * Draws a texture region with the currently bound texture on specified
     * coordinates.
     *
     * @param x1     Bottom left x position
     * @param y1     Bottom left y position
     * @param x2     Top right x position
     * @param y2     Top right y position
     * @param s1     Bottom left s coordinate
     * @param t1     Bottom left t coordinate
     * @param s2     Top right s coordinate
     * @param t2     Top right t coordinate
     * @param c      The color to use
     * @param zIndex depth value written to gl_Position.z
     */
    public void drawTextureRegion(float x1, float y1, float x2, float y2, float zIndex, float s1, float t1, float s2,
            float t2,
            Color c) {
        if (verteciesBuff.remaining() < NUM_9 * NUM_6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }
        Vector4f color = c.toVector4f();
        uniformMap.put(VERTEXCOLOR, color);
        float r = color.x;
        float g = color.y;
        float b = color.z;
        float a = color.w;

        verteciesBuff.put(x1).put(y1).put(zIndex).put(r).put(g).put(b).put(a).put(s1).put(t1);
        verteciesBuff.put(x1).put(y2).put(zIndex).put(r).put(g).put(b).put(a).put(s1).put(t2);
        verteciesBuff.put(x2).put(y2).put(zIndex).put(r).put(g).put(b).put(a).put(s2).put(t2);

        verteciesBuff.put(x1).put(y1).put(zIndex).put(r).put(g).put(b).put(a).put(s1).put(t1);
        verteciesBuff.put(x2).put(y2).put(zIndex).put(r).put(g).put(b).put(a).put(s2).put(t2);
        verteciesBuff.put(x2).put(y1).put(zIndex).put(r).put(g).put(b).put(a).put(s2).put(t1);

        numVertices += NUM_6;
    }

    /**
     * Stage two triangles forming a flat-color rectangle (7-float vertex:
     * pos+color, no uv).
     *
     * @param x1     bottom-left x
     * @param y1     bottom-left y
     * @param x2     top-right x
     * @param y2     top-right y
     * @param zIndex depth value written to gl_Position.z
     * @param c      fill color
     */
    public void drawColorRegion(float x1, float y1, float x2, float y2, float zIndex, Color c) {
        if (verteciesBuff.remaining() < NUM_7 * NUM_6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }

        Vector4f color = c.toVector4f();
        uniformMap.put(VERTEXCOLOR, color);
        float r = color.x;
        float g = color.y;
        float b = color.z;
        float a = color.w;

        verteciesBuff.put(x1).put(y1).put(zIndex).put(r).put(g).put(b).put(a);
        verteciesBuff.put(x1).put(y2).put(zIndex).put(r).put(g).put(b).put(a);
        verteciesBuff.put(x2).put(y2).put(zIndex).put(r).put(g).put(b).put(a);

        verteciesBuff.put(x1).put(y1).put(zIndex).put(r).put(g).put(b).put(a);
        verteciesBuff.put(x2).put(y2).put(zIndex).put(r).put(g).put(b).put(a);
        verteciesBuff.put(x2).put(y1).put(zIndex).put(r).put(g).put(b).put(a);

        numVertices += NUM_6;
    }

    /**
     * Stage two triangles forming an arbitrary quadrilateral with SDF texture
     * coordinates (used for line/arrow strokes whose quads aren't axis-aligned).
     *
     * @param x1     first vertex x
     * @param y1     first vertex y
     * @param x2     second vertex x
     * @param y2     second vertex y
     * @param x3     third vertex x
     * @param y3     third vertex y
     * @param x4     fourth vertex x
     * @param y4     fourth vertex y
     * @param zIndex depth value written to gl_Position.z
     * @param s1     left u
     * @param t1     bottom v
     * @param s2     right u
     * @param t2     top v
     * @param c      tint color
     */
    public void drawSDFRegion(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4,
            float zIndex, float s1, float t1, float s2, float t2, Color c) {
        if (verteciesBuff.remaining() < NUM_9 * NUM_6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }

        Vector4f color = c.toVector4f();
        uniformMap.put(VERTEXCOLOR, color);
        float r = color.x;
        float g = color.y;
        float b = color.z;
        float a = color.w;

        verteciesBuff.put(x1).put(y1).put(zIndex).put(r).put(g).put(b).put(a).put(s1).put(t1);
        verteciesBuff.put(x2).put(y2).put(zIndex).put(r).put(g).put(b).put(a).put(s2).put(t1);
        verteciesBuff.put(x3).put(y3).put(zIndex).put(r).put(g).put(b).put(a).put(s1).put(t2);

        verteciesBuff.put(x3).put(y3).put(zIndex).put(r).put(g).put(b).put(a).put(s1).put(t2);
        verteciesBuff.put(x4).put(y4).put(zIndex).put(r).put(g).put(b).put(a).put(s2).put(t2);
        verteciesBuff.put(x2).put(y2).put(zIndex).put(r).put(g).put(b).put(a).put(s2).put(t1);

        numVertices += NUM_6;
    }

    /**
     * Stage two triangles for an SDF quad with a linear gradient: the first
     * triangle gets {@code c}, the second gets {@code c2} (the GPU interpolates
     * per-vertex colors).
     *
     * @param x1     first vertex x
     * @param y1     first vertex y
     * @param x2     second vertex x
     * @param y2     second vertex y
     * @param x3     third vertex x
     * @param y3     third vertex y
     * @param x4     fourth vertex x
     * @param y4     fourth vertex y
     * @param zIndex depth value written to gl_Position.z
     * @param s1     left u
     * @param t1     bottom v
     * @param s2     right u
     * @param t2     top v
     * @param c      color at the start of the gradient
     * @param c2     color at the end of the gradient
     */
    public void drawSDFLinearGradient(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4,
            float zIndex, float s1, float t1, float s2, float t2, Color c, Color c2) {
        if (verteciesBuff.remaining() < NUM_9 * NUM_6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }

        Vector4f color = c.toVector4f();
        uniformMap.put(VERTEXCOLOR, color);
        float r = color.x;
        float g = color.y;
        float b = color.z;
        float a = color.w;

        verteciesBuff.put(x1).put(y1).put(zIndex).put(r).put(g).put(b).put(a).put(s1).put(t1);
        verteciesBuff.put(x2).put(y2).put(zIndex).put(r).put(g).put(b).put(a).put(s2).put(t1);
        verteciesBuff.put(x3).put(y3).put(zIndex).put(r).put(g).put(b).put(a).put(s1).put(t2);

        color = c2.toVector4f();
        r = color.x;
        g = color.y;
        b = color.z;
        a = color.w;
        verteciesBuff.put(x3).put(y3).put(zIndex).put(r).put(g).put(b).put(a).put(s1).put(t2);
        verteciesBuff.put(x4).put(y4).put(zIndex).put(r).put(g).put(b).put(a).put(s2).put(t2);
        verteciesBuff.put(x2).put(y2).put(zIndex).put(r).put(g).put(b).put(a).put(s2).put(t1);

        numVertices += NUM_6;
    }

    /**
     * Build and upload this program's projection matrix.
     *
     * @param framebufferWidth  viewport width in pixels
     * @param framebufferHeight viewport height in pixels
     * @param f                 DPI/zoom scale hint
     */
    public abstract void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float f);

    /**
     * Allocate the staging vertex buffer (when {@code useBuffer} is on), size the
     * GPU VBO, and reset draw bookkeeping. Subclasses override to additionally wire
     * vertex attributes.
     */
    public void init() {
        if (useBuffer) {

            vao.bind();

            vbo.bind(gl.ARRAY_BUFFER());

            verteciesBuff = platform.allocateFloats((int) Math.pow(2, NUM_16));

            long size = (long) verteciesBuff.capacity() * (long) Float.BYTES;
            vbo.uploadData(gl.ARRAY_BUFFER(), size, gl.DYNAMIC_DRAW());
            vboSizeBytes = size;
            stagingReservedFloats = verteciesBuff.capacity();

            numVertices = 0;
            drawing = false;
        }
    }

    /**
     * The number of floats per vertex in this program's layout.
     *
     * @return per-vertex stride in float units
     */
    public int getStrideFloats() {
        return strideFloats;
    }

    private void ensureRegionInitialized() {
        if (regionStartVertex >= 0) {
            return;
        }

        int reservedFloats = stagingReservedFloats;
        int stride = Math.max(1, strideFloats);
        regionStartVertex = reservedFloats / stride;
        regionCursorVertex = regionStartVertex;
    }

    /**
     * Get or grow this owner's persistent VBO slice. New allocations are rounded up
     * to the next power of two, and undersized existing allocations are relocated
     * to the next free region.
     *
     * @param owner             identity key for the allocation (e.g. a drawable
     *                          instance)
     * @param minVertexCapacity required vertex capacity
     * @throws IllegalArgumentException if {@code owner} is {@code null}
     * @return existing or newly-created allocation
     */
    public Allocation ensureAllocation(Object owner, int minVertexCapacity) {
        if (owner == null) {
            throw new IllegalArgumentException("owner cannot be null");
        }
        ensureRegionInitialized();
        Allocation alloc = allocations.get(owner);
        if (alloc == null) {
            int capacity = nextPowerOfTwo(minVertexCapacity);
            int first = regionCursorVertex;
            regionCursorVertex += capacity;
            growBufferIfNeeded(regionCursorVertex);
            alloc = new Allocation(first, capacity);
            allocations.put(owner, alloc);
        } else if (alloc.vertexCapacity < minVertexCapacity) {

            int capacity = nextPowerOfTwo(minVertexCapacity);
            int first = regionCursorVertex;
            regionCursorVertex += capacity;
            growBufferIfNeeded(regionCursorVertex);
            alloc.firstVertex = first;
            alloc.vertexCapacity = capacity;
            alloc.dirty = true;
        }
        return alloc;
    }

    private static synchronized Object getGlobalOwnerKey(long id) {
        Object key = GLOBAL_OWNER_KEYS.get(id);
        if (key == null) {
            key = new Object();
            GLOBAL_OWNER_KEYS.put(id, key);
        }
        return key;
    }

    /**
     * Variant of {@link #ensureAllocation(Object, int)} keyed by a numeric id
     * (mapped to a stable global owner key).
     *
     * @param id                numeric allocation id
     * @param minVertexCapacity required vertex capacity
     * @return existing or newly-created allocation
     */
    public Allocation ensureAllocation(long id, int minVertexCapacity) {
        Object key = getGlobalOwnerKey(id);
        Allocation alloc = ensureAllocation(key, minVertexCapacity);
        idToAllocation.put(id, alloc);
        return alloc;
    }

    /**
     * Look up the allocation previously created via the long-id overload.
     *
     * @param id allocation id
     * @return matching allocation, or {@code null} if none exists
     */
    public Allocation getAllocationById(long id) {
        return idToAllocation.get(id);
    }

    /**
     * Queue a draw of {@code vertexCount} vertices from the allocation registered
     * under {@code id}; no-op if the id is unknown.
     *
     * @param id          allocation id
     * @param vertexCount number of vertices to draw
     */
    public void queueDraw(long id, int vertexCount) {
        Allocation alloc = getAllocationById(id);
        if (alloc != null) {
            queueDraw(alloc, vertexCount);
        }
    }

    /**
     * Upload {@code data} into the GPU buffer at the allocation's vertex offset,
     * mark it clean, and remember the vertex count.
     *
     * @param allocation       target slice in the persistent VBO
     * @param data             already-flipped float buffer of vertex data
     * @param verticesToUpload number of vertices written into {@code data}
     */
    public void uploadAllocation(Allocation allocation, IxBuffer data, int verticesToUpload) {
        if (this.platformId != Platforms.gl().getPlatformID()) {
            Platforms.get().log(SHADERPROGRAM_PLATFORM_MISMATCH);
        }
        if (allocation == null || data == null || verticesToUpload <= 0) {
            return;
        }
        vbo.bind(gl.ARRAY_BUFFER());
        long byteOffset = (long) allocation.firstVertex * (long) strideFloats * (long) Float.BYTES;
        vbo.uploadSubData(gl.ARRAY_BUFFER(), byteOffset, data);
        allocation.dirty = false;
        allocation.lastVertexCount = verticesToUpload;
    }

    /**
     * Queue a draw range for the given allocation; rendered on the next
     * {@link #flush()}.
     *
     * @param allocation  slice to draw from
     * @param vertexCount number of vertices to draw
     */
    public void queueDraw(Allocation allocation, int vertexCount) {
        if (allocation == null || vertexCount <= 0)
            return;
        queuedRanges.add(new DrawRange(allocation.firstVertex, vertexCount));
    }

    /**
     * Discard any queued persistent-region draw ranges without flushing.
     */
    public void clearQueuedRanges() {
        queuedRanges.clear();
    }

    private void growBufferIfNeeded(int requiredMaxVertexIndexExclusive) {

        int requiredFloats = requiredMaxVertexIndexExclusive * Math.max(1, strideFloats);
        long currentSizeBytes = vboSizeBytes;
        long requiredBytes = (long) requiredFloats * (long) Float.BYTES;
        if (requiredBytes <= currentSizeBytes) {
            return;
        }

        int newFloatCapacity = nextPowerOfTwo(Math.max(stagingReservedFloats, requiredFloats));
        vbo.bind(gl.ARRAY_BUFFER());
        long newSizeBytes = (long) newFloatCapacity * (long) Float.BYTES;
        vbo.uploadData(gl.ARRAY_BUFFER(), newSizeBytes, gl.DYNAMIC_DRAW());
        vboSizeBytes = newSizeBytes;
    }

    private static int nextPowerOfTwo(int x) {
        int v = x - 1;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> NUM_4;
        v |= v >> NUM_8;
        v |= v >> NUM_16;
        return (v < 0) ? 1 : v + 1;
    }

    /**
     * Diagnostic: print every active uniform's current GPU value (float, vec2,
     * vec4, sampler2D) to stdout.
     */
    public void printCurrentUniformValues() {
        System.out.println("--- Current Uniform Values for Shader Program ID: " + ID + " ---");
        int numUniforms = gl.getProgramiv(ID, gl.ACTIVE_UNIFORMS());
        if (numUniforms == 0) {
            System.out.println("No active uniforms found.");
            return;
        }

        IntBuffer sizeBuffer = ByteBuffer.allocateDirect(NUM_4).order(ByteOrder.nativeOrder())
                .asIntBuffer();
        IntBuffer typeBuffer = ByteBuffer.allocateDirect(NUM_4).order(ByteOrder.nativeOrder())
                .asIntBuffer();

        for (int i = 0; i < numUniforms; i++) {
            sizeBuffer.clear();
            typeBuffer.clear();
            String name = gl.getActiveUniform(ID, i, sizeBuffer, typeBuffer);
            int type = typeBuffer.get(0);
            int location = gl.getUniformLocation(ID, name);

            if (type == gl.FLOAT()) {
                IxBuffer val = platform.allocateFloats(1);
                gl.getUniformfv(ID, location, val);
                System.out.printf("  '%s' (float): %f%n", name, val.get(0));
            } else if (type == gl.FLOAT_VEC2()) {
                IxBuffer val = platform.allocateFloats(2);
                gl.getUniformfv(ID, location, val);
                System.out.printf("  '%s' (vec2): (%f, %f)%n", name, val.get(0), val.get(1));
            } else if (type == gl.FLOAT_VEC4()) {
                IxBuffer val = platform.allocateFloats(NUM_4);
                gl.getUniformfv(ID, location, val);
                System.out.printf("  '%s' (vec4): (%f, %f, %f, %f)%n", name, val.get(0), val.get(1), val.get(2),
                        val.get(NUM_3));
            } else if (type == gl.SAMPLER_2D()) {
                System.out.printf("  '%s' (sampler2D): [Texture Sampler]%n", name);
            } else {
                System.out.printf("  '%s': [Unhandled Type: 0x%X]%n", name, type);
            }
        }
        System.out.println("---------------------------------------------------------");
    }

    public static enum ShaderType {
        TextureSDF(SDFShader.class, FONT_VS, "sdf.fs"),

        LineSDF(SDFShader.class, FONT_VS, "sdf_line.fs"),

        DashedLineSDF(SDFShader.class, FONT_VS, "sdf_dashed_line.fs"),

        DashedLineRoundSDF(SDFShader.class, FONT_VS, "sdf_dashed_line_round.fs"),

        DashedLineEndCapsSDF(SDFShader.class, FONT_VS, "sdf_dashed_line_round_end_caps.fs"),

        ArrowLineSDF(SDFShader.class, FONT_VS, "sdf_arrow_line.fs"),

        CircleSDF(SDFShader.class, FONT_VS, "sdf_circle.fs"),

        CircleSDFSimple(SDFShader.class, FONT_VS, "sdf_circle_simple.fs"),

        UnionSDF(SDFShader.class, FONT_VS, "sdf_union.fs"),

        Fluid(SDFShader.class, FONT_VS, "sdf_fluid.fs"),

        Font(FontShader.class, FONT_VS, "font.fs"),

        Color(ColorShader.class, "color.vs", "color.fs"),

        Mesh(MeshShader.class, MESH_VS, "mesh.fs"),

        MeshUnlit(MeshShader.class, MESH_VS, "mesh_unlit.fs"),

        MeshScalar(MeshShader.class, "mesh_scalar.vs", "mesh_scalar.fs"),

        MeshUv(MeshShader.class, "mesh_uv.vs", "mesh_uv.fs"),

        MeshUvTraces(MeshShader.class, "mesh_uv_traces.vs", "mesh_uv_traces.fs"),

        MeshCrossField(MeshShader.class, "mesh_cross_field.vs", "mesh_cross_field.fs"),

        BezierSDF(SDFShader.class, FONT_VS, "sdf_bezier_simple.fs");

        public String vertexShaderLocation;
        public String fragmentShaderLocation;
        public HashMap<Integer, ShaderProgram> shaderMap = new HashMap<>();
        private Class<?> shaderClass;

        @SuppressWarnings("rawtypes")
        ShaderType(Class shaderClass, String vertexShaderLocation, String fragmentShaderLocation) {
            this.vertexShaderLocation = vertexShaderLocation;
            this.fragmentShaderLocation = fragmentShaderLocation;
            this.shaderClass = shaderClass;
            createShader();

        }

        /**
         * Instantiate the concrete shader subclass for this enum entry on the active GL
         * platform and register it with the platform's shader list.
         *
         * @throws RuntimeException if the shader class is unrecognized or its sources
         *                          fail to load
         */
        public void createShader() {
            ShaderProgram shader = null;
            try {
                if (shaderClass.equals(SDFShader.class)) {
                    shader = new SDFShader(vertexShaderLocation, fragmentShaderLocation);
                } else if (shaderClass.equals(FontShader.class)) {
                    shader = new FontShader(Platforms.get().getFrameBufferWidth(),
                            Platforms.get().getFrameBufferHeight());
                } else if (shaderClass.equals(ColorShader.class)) {
                    shader = new ColorShader(vertexShaderLocation, fragmentShaderLocation);
                } else if (shaderClass.equals(MeshShader.class)) {
                    shader = new MeshShader(vertexShaderLocation, fragmentShaderLocation);
                } else {
                    throw new RuntimeException("Unknown shader type: " + shaderClass.getName());
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to load shader resources: " + fragmentShaderLocation, e);
            }
            this.shaderMap.put(Platforms.gl().getPlatformID(), shader);
            Platforms.gl().addShader(shader);
        }

        /**
         * Get the shader for the current platform, creating it on first use.
         *
         * @return shader instance for the active platform id
         */
        public ShaderProgram getShader() {
            int p = Platforms.gl().getPlatformID();
            if (!shaderMap.containsKey(p)) {
                createShader();
            }
            return shaderMap.get(p);
        }

        /**
         * Get the shader for a specific platform id, initializing the platform and
         * creating the shader on first use.
         *
         * @param p target platform id
         * @return shader instance for that platform
         */
        public ShaderProgram getShader(Integer p) {
            if (!shaderMap.containsKey(p)) {
                Platforms.init(p);
                createShader();
            }
            return shaderMap.get(p);
        }
    }

    public enum ShaderOperationType {
        Fragment, Program, Vertex
    }

    public static final class Allocation {
        int firstVertex;
        int vertexCapacity;
        int lastVertexCount;
        boolean dirty = true;

        /**
         * Build an allocation describing a vertex slice in the persistent VBO.
         *
         * @param firstVertex    starting vertex index
         * @param vertexCapacity reserved vertex count (power of two)
         */
        public Allocation(int firstVertex, int vertexCapacity) {
            this.firstVertex = firstVertex;
            this.vertexCapacity = vertexCapacity;
            this.lastVertexCount = 0;
        }

        /**
         * Starting vertex index of this slice in the GPU buffer.
         *
         * @return first vertex index
         */
        public int getFirstVertex() {
            return firstVertex;
        }

        /**
         * Reserved vertex count for this slice (upper bound on uploads).
         *
         * @return capacity in vertices
         */
        public int getVertexCapacity() {
            return vertexCapacity;
        }

        /**
         * Vertex count from the most recent successful upload.
         *
         * @return last uploaded vertex count
         */
        public int getLastVertexCount() {
            return lastVertexCount;
        }

        /**
         * Whether this slice's GPU contents are stale and must be re-uploaded.
         *
         * @return {@code true} if a re-upload is required
         */
        public boolean isDirty() {
            return dirty;
        }
    }

    private static final class DrawRange {
        final int firstVertex;
        final int vertexCount;

        DrawRange(int firstVertex, int vertexCount) {
            this.firstVertex = firstVertex;
            this.vertexCount = vertexCount;
        }
    }

}
