package shell.render.shaders;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import shell.platform.Platforms;
import shell.platform.gl.GL;
import shell.platform.gl.IxBuffer;
import shell.platform.gl.Platform;
import shell.render.Texture;
import shell.render.color.Color;
import shell.ui.main.Main;

public abstract class ShaderProgram {

    public static enum ShaderType {
        TextureSDF(SDFShader.class, "font.vs", "sdf.fs"),

        LineSDF(SDFShader.class, "font.vs", "sdf_line.fs"),

        DashedLineSDF(SDFShader.class, "font.vs", "sdf_dashed_line.fs"),

        DashedLineRoundSDF(SDFShader.class, "font.vs", "sdf_dashed_line_round.fs"),

        DashedLineEndCapsSDF(SDFShader.class, "font.vs", "sdf_dashed_line_round_end_caps.fs"),

        CircleSDF(SDFShader.class, "font.vs", "sdf_circle.fs"),

        CircleSDFSimple(SDFShader.class, "font.vs", "sdf_circle_simple.fs"),

        UnionSDF(SDFShader.class, "font.vs", "sdf_union.fs"),

        Fluid(SDFShader.class, "font.vs", "sdf_fluid.fs"),

        Font(FontShader.class, "font.vs", "font.fs"),

        Color(ColorShader.class, "color.vs", "color.fs"),

        BezierSDF(SDFShader.class, "font.vs", "sdf_bezier_simple.fs");

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
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to load shader resources: " + fragmentShaderLocation, e);
            }
            this.shaderMap.put(Platforms.gl().getPlatformID(), shader);
            Platforms.gl().addShader(shader);
        }

        public ShaderProgram getShader() {
            Integer p = Platforms.gl().getPlatformID();
            if (!shaderMap.containsKey(p)) {
                createShader();
            }
            return shaderMap.get(p);
        }

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

    String vertexCode;
    String fragmentCode;
    CharSequence[] vertexShaderSource;
    CharSequence[] fragmentShaderSource;
    public VertexArrayObject vao;
    public VertexBufferObject vbo;
    public HashMap<String, Integer> uniformLocations;

    public int ID = -1;
    int vertexShader, fragmentShader;
    public IxBuffer verteciesBuff;
    private int numVertices;
    private boolean drawing;

    /** Number of floats per vertex for this shader's bound attributes. */
    protected int strideFloats = 0;
    /** First vertex index reserved for persistent drawable allocations. */
    private int regionStartVertex = -1;
    /** Next free vertex cursor within the persistent region. */
    private int regionCursorVertex = -1;
    /** Queued draw ranges that refer to persistent VBO regions. */
    private final java.util.List<DrawRange> queuedRanges = new java.util.ArrayList<>();
    /** Allocation table per owner object for its persistent VBO slice. */
    private final java.util.Map<Object, Allocation> allocations = new java.util.HashMap<>();
    /** Current GPU buffer size in bytes. */
    private long vboSizeBytes = 0L;
    /** Reserved staging floats for legacy immediate path. */
    private int stagingReservedFloats = 0;

    public final static float ORTHO_FAR = 1000f;
    public final static float ORTHO_NEAR = -ORTHO_FAR;
    public final static float ORTHO_Z_INCREMENT = 0.1f;

    @SuppressWarnings("unused")
    private String vertexShaderLocation, fragmentShaderLocation;
    private File fragmentShaderFile;
    private long fragmentLastModified;
    private File vertexShaderFile;
    private long vertexLastModified;
    private boolean useBuffer;
    private boolean reloadShader;
    public Map<String, Object> uniformMap = new HashMap<>();
    public int platformId;
    public GL gl;
    public Platform platform;

    private String originalFragmentSourceStr;

    private static final java.util.Map<Long, Object> GLOBAL_OWNER_KEYS = new java.util.HashMap<>();

    private final java.util.Map<Long, Allocation> idToAllocation = new java.util.HashMap<>();

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
            fragmentShaderFile = new File("./src/main/resources/glsl/" + fragmentShaderLocation);
            vertexShaderFile = new File("./src/main/resources/glsl/" + vertexShaderLocation);
            if (fragmentShaderFile.exists()) {
                fragmentLastModified = fragmentShaderFile.lastModified();
            }
            if (vertexShaderFile.exists()) {
                vertexLastModified = vertexShaderFile.lastModified();
            }
        } catch (Exception ignore) {
        }

        Platforms.get().loadShaderSourceAsync("glsl", vertexShaderLocation, platformId, vertexShaderSource -> {
            Platforms.get().loadShaderSourceAsync("glsl", fragmentShaderLocation, platformId, fragmentShaderSource -> {
                this.vertexShaderSource = new CharSequence[] { vertexShaderSource };
                this.fragmentShaderSource = new CharSequence[] { fragmentShaderSource };
                this.originalFragmentSourceStr = fragmentShaderSource;
                recompileShaders(vertexShaderLocation, fragmentShaderLocation);
                init();
            });
        }); 
    }

    public int getAttributeLocation(CharSequence name) {

        return gl.getAttribLocation(ID, name);
    }

    public void use() {

        gl.useProgram(ID);
    }

    public void setBool(String name, boolean value) {

        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        gl.uniform1i(uniformLocations.get(name), value ? 1 : 0);
        uniformMap.put(name, value);
    }

    public void setInt(String name, int value) {

        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        gl.uniform1i(uniformLocations.get(name), value);
        uniformMap.put(name, value);
    }

    public void setFloat(String name, float value) {

        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        gl.uniform1f(uniformLocations.get(name), value);
        uniformMap.put(name, value);
    }

    public void setMat4(String name, Matrix4f mat) {

        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        IxBuffer buffer = platform.allocateFloats(16);

        buffer.put(mat.m00()).put(mat.m01()).put(mat.m02()).put(mat.m03());
        buffer.put(mat.m10()).put(mat.m11()).put(mat.m12()).put(mat.m13());
        buffer.put(mat.m20()).put(mat.m21()).put(mat.m22()).put(mat.m23());
        buffer.put(mat.m30()).put(mat.m31()).put(mat.m32()).put(mat.m33());
        buffer.flip();
        gl.uniformMatrix4fv(uniformLocations.get(name), false, buffer);
        uniformMap.put(name, mat);
    }

    public void setMat4(String name, IxBuffer allocatedBuffer) {

        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        gl.uniformMatrix4fv(uniformLocations.get(name), false, allocatedBuffer);
        uniformMap.put(name, allocatedBuffer);
    }

    public void setVec2(String name, Vector2f vec2) {

        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        IxBuffer buffer = platform.allocateFloats(2);
        buffer.put(vec2.x).put(vec2.y).flip();
        gl.uniform2fv(uniformLocations.get(name), buffer);
        uniformMap.put(name, vec2);
    }

    public void setVec3(String name, float f, float g, float h) {

        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        IxBuffer vec3buf = platform.allocateFloats(3);
        vec3buf.put(f).put(g).put(h).flip();
        gl.uniform3fv(uniformLocations.get(name), vec3buf);
        uniformMap.put(name, new Vector3f(f, g, h));
    }

    public void setVec3(String name, Vector3f vec3) {
        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        IxBuffer buffer = platform.allocateFloats(3);
        buffer.put(vec3.x).put(vec3.y).put(vec3.z).flip();
        gl.uniform3fv(uniformLocations.get(name), buffer);
        uniformMap.put(name, vec3);
    }

    public void setVec4(String name, Vector4f vec4) {

        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        IxBuffer buffer = platform.allocateFloats(4);
        buffer.put(vec4.x).put(vec4.y).put(vec4.z).put(vec4.w).flip();
        gl.uniform4fv(uniformLocations.get(name), buffer);
        uniformMap.put(name, vec4);
    }

    private void checkCompileErrors(int shader, ShaderOperationType type, String location,
            CharSequence[] shaderSource) {

        IntBuffer success = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();

        if (type != ShaderOperationType.Program) {
            gl.getShaderiv(shader, gl.COMPILE_STATUS(), success);
            if (success.get(0) == 0) {
                String infoLog = gl.getShaderInfoLog(shader);
                System.out.println(
                        "ERROR::SHADER::" + type.name() + "::COMPILATION_FAILED: " + location + " \n" + infoLog);
            }
        } else {
            gl.getProgramiv(shader, gl.LINK_STATUS(), success);
            if (success.get(0) == 0) {
                String infoLog = gl.getShaderInfoLog(shader);
                System.out.println("ERROR::SHADER::" + type.name() + "::LINK_FAILED: " + location + "\n" + infoLog);
            }
        }
    }

    protected CharSequence[] readFile(File shaderFile) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(shaderFile));
        ArrayList<String> lines = new ArrayList<>();
        String line;
        while ((line = br.readLine()) != null) {
            lines.add(line + "\n");
        }
        String zero = lines.get(lines.size() - 1).replace("\n", "\0");
        lines.remove(lines.size() - 1);
        lines.add(zero);
        CharSequence[] vertexShaderSource = new CharSequence[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            vertexShaderSource[i] = lines.get(i);
        }
        br.close();
        return vertexShaderSource;
    }

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
                vertexShaderSource = readFile(vertexShaderFile);
                vertexLastModified = vertexShaderFile.lastModified();
                didChange = true;
            }
            if (fragmentModified) {
                fragmentShaderSource = readFile(fragmentShaderFile);
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
            Main.terminal.error("Could not Hot Reload: " + e.getMessage());
        }
    }

    public synchronized boolean reloadProgram() {
        if (this.platformId != Platforms.gl().getPlatformID()) {
            Platforms.get().log("ShaderProgram: Platform mismatch");
        }
        int prevID = ID;
        recompileShaders(vertexShaderLocation, fragmentShaderLocation);
        IntBuffer success = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
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
            updateProjectionMatrix(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(), 1f);
            return true;
        } else {
            gl.deleteProgram(ID);
            ID = prevID;
            return false;
        }
    }

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
     */
    private CharSequence[] buildPlatformFragmentSource(String src) {
        try {
            String platformName = shell.platform.Platforms.get().getClass().getName();
            boolean isWeb = platformName != null && platformName.toLowerCase().contains("web");
            if (isWeb) {
                return new CharSequence[] { src };
            }
        } catch (Exception ignore) {
        }
        return new CharSequence[] { src + "\0" };
    }

    private void reapplyUniforms() {
        if (uniformMap == null || uniformMap.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<String, Object> e : uniformMap.entrySet()) {
            String name = e.getKey();
            Object v = e.getValue();
            try {
                if (v instanceof Boolean) {
                    setBool(name, (Boolean) v);
                } else if (v instanceof Integer) {
                    setInt(name, (Integer) v);
                } else if (v instanceof Float) {
                    setFloat(name, (Float) v);
                } else if (v instanceof org.joml.Matrix4f) {
                    setMat4(name, (org.joml.Matrix4f) v);
                } else if (v instanceof org.joml.Vector2f) {
                    setVec2(name, (org.joml.Vector2f) v);
                } else if (v instanceof org.joml.Vector3f) {
                    setVec3(name, (org.joml.Vector3f) v);
                } else if (v instanceof org.joml.Vector4f) {
                    setVec4(name, (org.joml.Vector4f) v);
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

    public void bindFragmentDataLocation(int i, String string) {

        gl.bindFragDataLocation(ID, i, string);
    }

    /**
     * Begin rendering.
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
     * End rendering.
     */
    public void end() {
        if (!drawing) {
            throw new IllegalStateException("Renderer isn't drawing!");
        }
        drawing = false;
        flush();
    }

    public void flush() {
        if (this.platformId != Platforms.gl().getPlatformID()) {
            Platforms.get().log("ShaderProgram: Platform mismatch");
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
                    int posAttrib = getAttributeLocation("position");
                    if (posAttrib >= 0) {
                        gl.enableVertexAttribArray(posAttrib);
                    }
                    int colAttrib = getAttributeLocation("color");
                    if (colAttrib >= 0) {
                        gl.enableVertexAttribArray(colAttrib);
                    }
                    int texCoordAttrib = getAttributeLocation("texCoord");
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
                    int posAttrib = getAttributeLocation("position");
                    if (posAttrib >= 0) {
                        gl.enableVertexAttribArray(posAttrib);
                    }
                    int colAttrib = getAttributeLocation("color");
                    if (colAttrib >= 0) {
                        gl.enableVertexAttribArray(colAttrib);
                    }
                    int texCoordAttrib = getAttributeLocation("texCoord");
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

    public String getVertexSource() {
        if (vertexShaderSource == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : vertexShaderSource) {
            if (cs != null)
                sb.append(cs);
        }
        int nul = sb.indexOf("\0");
        if (nul >= 0)
            sb.delete(nul, sb.length());
        return sb.toString();
    }

    public String getFragmentSource() {
        if (fragmentShaderSource == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : fragmentShaderSource) {
            if (cs != null)
                sb.append(cs);
        }
        int nul = sb.indexOf("\0");
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
     */
    public void drawTexture(Texture texture, float x, float y, float zIndex, Color c) {
        /* Vertex positions */
        float x1 = x;
        float y1 = y;
        float x2 = x1 + texture.getWidth();
        float y2 = y1 + texture.getHeight();

        /* Texture coordinates */
        float s1 = 0f;
        float t1 = 0f;
        float s2 = 1f;
        float t2 = 1f;

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
     * @param x1 Bottom left x position
     * @param y1 Bottom left y position
     * @param x2 Top right x position
     * @param y2 Top right y position
     * @param s1 Bottom left s coordinate
     * @param t1 Bottom left t coordinate
     * @param s2 Top right s coordinate
     * @param t2 Top right t coordinate
     */
    public void drawTextureRegion(float x1, float y1, float x2, float y2, float zIndex, float s1, float t1, float s2,
            float t2) {
        drawTextureRegion(x1, y1, x2, y2, zIndex, s1, t1, s2, t2, Color.WHITE);
    }

    /**
     * Draws a texture region with the currently bound texture on specified
     * coordinates.
     *
     * @param x1 Bottom left x position
     * @param y1 Bottom left y position
     * @param x2 Top right x position
     * @param y2 Top right y position
     * @param s1 Bottom left s coordinate
     * @param t1 Bottom left t coordinate
     * @param s2 Top right s coordinate
     * @param t2 Top right t coordinate
     * @param c  The color to use
     */
    public void drawTextureRegion(float x1, float y1, float x2, float y2, float zIndex, float s1, float t1, float s2,
            float t2,
            Color c) {
        if (verteciesBuff.remaining() < 9 * 6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }
        Vector4f color = c.toVector4f();
        uniformMap.put("vertexColor", color);
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

        numVertices += 6;
    }

    public void drawColorRegion(float x1, float y1, float x2, float y2, float zIndex, Color c) {
        if (verteciesBuff.remaining() < 7 * 6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }

        Vector4f color = c.toVector4f();
        uniformMap.put("vertexColor", color);
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

        numVertices += 6;
    }

    public void drawSDFRegion(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4,
            float zIndex, float s1, float t1, float s2, float t2, Color c) {
        if (verteciesBuff.remaining() < 9 * 6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }

        Vector4f color = c.toVector4f();
        uniformMap.put("vertexColor", color);
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

        numVertices += 6;
    }

    public void drawSDFLinearGradient(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4,
            float zIndex, float s1, float t1, float s2, float t2, Color c, Color c2) {
        if (verteciesBuff.remaining() < 9 * 6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }

        Vector4f color = c.toVector4f();
        uniformMap.put("vertexColor", color);
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

        numVertices += 6;
    }

    public abstract void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float f);

    public void init() {
        if (useBuffer) {

            vao.bind();

            vbo.bind(gl.ARRAY_BUFFER());

            verteciesBuff = platform.allocateFloats((int) Math.pow(2, 16));

            long size = (long) verteciesBuff.capacity() * (long) Float.BYTES;
            vbo.uploadData(gl.ARRAY_BUFFER(), size, gl.DYNAMIC_DRAW());
            vboSizeBytes = size;
            stagingReservedFloats = verteciesBuff.capacity();

            numVertices = 0;
            drawing = false;
        }
    }

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

    public Allocation ensureAllocation(long id, int minVertexCapacity) {
        Object key = getGlobalOwnerKey(id);
        Allocation alloc = ensureAllocation(key, minVertexCapacity);
        idToAllocation.put(id, alloc);
        return alloc;
    }

    public Allocation getAllocationById(long id) {
        return idToAllocation.get(id);
    }

    public void queueDraw(long id, int vertexCount) {
        Allocation alloc = getAllocationById(id);
        if (alloc != null) {
            queueDraw(alloc, vertexCount);
        }
    }

    public void uploadAllocation(Allocation allocation, IxBuffer data, int verticesToUpload) {
        if (this.platformId != Platforms.gl().getPlatformID()) {
            Platforms.get().log("ShaderProgram: Platform mismatch");
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

    public void queueDraw(Allocation allocation, int vertexCount) {
        if (allocation == null || vertexCount <= 0)
            return;
        queuedRanges.add(new DrawRange(allocation.firstVertex, vertexCount));
    }

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
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        return (v < 0) ? 1 : v + 1;
    }

    public static final class Allocation {
        int firstVertex;
        int vertexCapacity;
        int lastVertexCount;
        boolean dirty = true;

        public Allocation(int firstVertex, int vertexCapacity) {
            this.firstVertex = firstVertex;
            this.vertexCapacity = vertexCapacity;
            this.lastVertexCount = 0;
        }

        public int getFirstVertex() {
            return firstVertex;
        }

        public int getVertexCapacity() {
            return vertexCapacity;
        }

        public int getLastVertexCount() {
            return lastVertexCount;
        }

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

    public void printCurrentUniformValues() {
        System.out.println("--- Current Uniform Values for Shader Program ID: " + ID + " ---");
        int numUniforms = gl.getProgramiv(ID, gl.ACTIVE_UNIFORMS());
        if (numUniforms == 0) {
            System.out.println("No active uniforms found.");
            return;
        }

        IntBuffer sizeBuffer = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
                .asIntBuffer();
        IntBuffer typeBuffer = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
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
                IxBuffer val = platform.allocateFloats(4);
                gl.getUniformfv(ID, location, val);
                System.out.printf("  '%s' (vec4): (%f, %f, %f, %f)%n", name, val.get(0), val.get(1), val.get(2),
                        val.get(3));
            } else if (type == gl.SAMPLER_2D()) {
                System.out.printf("  '%s' (sampler2D): [Texture Sampler]%n", name);
            } else {
                System.out.printf("  '%s': [Unhandled Type: 0x%X]%n", name, type);
            }
        }
        System.out.println("---------------------------------------------------------");
    }

}
