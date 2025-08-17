package shell.render.shaders;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import shell.platform.Platforms;
import shell.platform.gl.GL;
import shell.render.Texture;
import shell.render.color.Color;
import shell.ui.Canvas3D;
import shell.ui.main.Main;

public abstract class ShaderProgram {

    public static enum ShaderType {
        TextureSDF(SDFShader.class, "font.vs", "sdf.fs"),

        LineSDF(SDFShader.class, "font.vs", "sdf_line.fs"),

        CircleSDF(SDFShader.class, "font.vs", "sdf_circle.fs"),

        UnionSDF(SDFShader.class, "font.vs", "sdf_union.fs"),

        Fluid(SDFShader.class, "font.vs", "sdf_fluid.fs"),

        Font(FontShader.class, "font.vs", "font.fs"),

        Color(ColorShader.class, "color.vs", "color.fs");

        public String vertexShaderLocation;
        public String fragmentShaderLocation;
        public ShaderProgram shader;

        @SuppressWarnings("rawtypes")
        ShaderType(Class shaderClass, String vertexShaderLocation, String fragmentShaderLocation) {
            this.vertexShaderLocation = vertexShaderLocation;
            this.fragmentShaderLocation = fragmentShaderLocation;
            if (shaderClass.equals(SDFShader.class)) {
                this.shader = new SDFShader(vertexShaderLocation, fragmentShaderLocation);
            } else if (shaderClass.equals(FontShader.class)) {
                this.shader = new FontShader(Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);
            } else if (shaderClass.equals(ColorShader.class)) {
                this.shader = new ColorShader(vertexShaderLocation, fragmentShaderLocation);
            }
            Canvas3D.shaders.add(shader);
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

    protected int ID = -1;
    int vertexShader, fragmentShader;
    private FloatBuffer verteciesBuff;
    private int numVertices;
    private boolean drawing;

    public final static float ORTHO_NEAR = -100f;
    public final static float ORTHO_FAR = 100f;

    @SuppressWarnings("unused")
    private String vertexShaderLocation, fragmentShaderLocation;
    private File fragmentShaderFile;
    private long fragmentLastModified;
    private File vertexShaderFile;
    private long vertexLastModified;
    private boolean useBuffer;
    private boolean reloadShader;
    private GL gl = Platforms.gl();

    public ShaderProgram(String vertexShaderLocation, String fragmentShaderLocation, VertexArrayObject vao,
            VertexBufferObject vbo, boolean useBuffer) {
        this.fragmentShaderLocation = fragmentShaderLocation;
        this.vertexShaderLocation = vertexShaderLocation;
        this.uniformLocations = new HashMap<>();
        this.vao = vao;
        this.vbo = vbo;
        this.useBuffer = useBuffer;
        try {
            // open files
            fragmentShaderFile = new File("./src/shell/render/shaders/glsl/" + fragmentShaderLocation);
            vertexShaderFile = new File("./src/shell/render/shaders/glsl/" + vertexShaderLocation);
            fragmentLastModified = fragmentShaderFile.lastModified();
            vertexLastModified = vertexShaderFile.lastModified();
            vertexShaderSource = readFile(vertexShaderFile);
            fragmentShaderSource = readFile(fragmentShaderFile);
        } catch (

        IOException e) {
            System.out.println(e.getLocalizedMessage());
        }

        recompileShaders(vertexShaderLocation, fragmentShaderLocation);
        init();
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
    }

    public void setInt(String name, int value) {
        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        gl.uniform1i(uniformLocations.get(name), value);
    }

    public void setFloat(String name, float value) {
        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        gl.uniform1f(uniformLocations.get(name), value);
    }

    public void setMat4(String name, Matrix4f mat) {
        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = mat.get(stack.mallocFloat(16));
            gl.uniformMatrix4fv(uniformLocations.get(name), false, buffer);
        }
    }

    public void setMat4(String name, FloatBuffer allocatedBuffer) {
        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        gl.uniformMatrix4fv(uniformLocations.get(name), false, allocatedBuffer);
    }

    public void setVec2(String name, Vector2f vec2) {

        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = vec2.get(stack.mallocFloat(2));
            gl.uniform2fv(uniformLocations.get(name), buffer);
        }
    }

    public void setVec3(String name, float f, float g, float h) {
        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer vec3 = new Vector3f(f, g, h).get(stack.mallocFloat(3));
            gl.uniform3fv(uniformLocations.get(name), vec3);
        }
    }

    public void setVec3(String name, Vector3f vec3) {

        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = vec3.get(stack.mallocFloat(3));
            gl.uniform3fv(uniformLocations.get(name), buffer);
        }
    }

    public void setVec4(String name, Vector4f vec4) {
        if (!uniformLocations.containsKey(name)) {
            uniformLocations.put(name, gl.getUniformLocation(ID, name));
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = vec4.get(stack.mallocFloat(4));
            gl.uniform4fv(uniformLocations.get(name), buffer);
        }
    }

    private void checkCompileErrors(int shader, ShaderOperationType type, String location) {
        IntBuffer success = BufferUtils.createIntBuffer(1);

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
                System.out.println("ERROR::SHADER::" + type.name() + "::LINK_FAILED\n" + infoLog);
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
        try {
            if (reloadShader) {
                this.vao = new VertexArrayObject();
                this.vbo = new VertexBufferObject();
                this.uniformLocations = new HashMap<>();
                recompileShaders(vertexShaderLocation, fragmentShaderLocation);
                init();
                reloadShader = false;
            }
            boolean vertexModified = vertexShaderFile.lastModified() != vertexLastModified;
            boolean fragmentModified = fragmentShaderFile.lastModified() != fragmentLastModified;
            if (vertexModified) {
                vertexShaderSource = readFile(vertexShaderFile);
                vertexLastModified = vertexShaderFile.lastModified();
            }
            if (fragmentModified) {
                fragmentShaderSource = readFile(fragmentShaderFile);
                fragmentLastModified = fragmentShaderFile.lastModified();
            }
            if (vertexModified || fragmentModified) {
                deleteShader();
                reloadShader = true;
            }
        } catch (IOException e) {
            Main.terminal.error("Could not Hot Reload: " + e.getMessage());
        }
    }

    private void deleteShader() {
        gl.detachShader(ID, vertexShader);
        gl.deleteShader(vertexShader);
        gl.detachShader(ID, fragmentShader);
        gl.deleteShader(fragmentShader);
        gl.deleteProgram(ID);
    }

    private void recompileShaders(String vertexShaderLocation, String fragmentShaderLocation) {
        vertexShader = gl.createShader(gl.VERTEX_SHADER());
        gl.shaderSource(vertexShader, vertexShaderSource);
        gl.compileShader(vertexShader);
        checkCompileErrors(vertexShader, ShaderOperationType.Vertex, vertexShaderLocation);

        fragmentShader = gl.createShader(gl.FRAGMENT_SHADER());
        gl.shaderSource(fragmentShader, fragmentShaderSource);
        gl.compileShader(fragmentShader);
        checkCompileErrors(fragmentShader, ShaderOperationType.Fragment, fragmentShaderLocation);

        ID = gl.createProgram();
        gl.attachShader(ID, vertexShader);
        gl.attachShader(ID, fragmentShader);
        gl.linkProgram(ID);
        gl.deleteShader(vertexShader);
        gl.deleteShader(fragmentShader);
        checkCompileErrors(ID, ShaderOperationType.Program, "both");

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
        if (numVertices > 0) {
            verteciesBuff.flip();

            if (vao != null) {
                vao.bind();
            } else {
                vbo.bind(gl.ARRAY_BUFFER());
            }
            use();

            /* Upload the new vertex data */
            vbo.bind(gl.ARRAY_BUFFER());
            vbo.uploadSubData(gl.ARRAY_BUFFER(), 0, verteciesBuff);

            /* Draw batch */
            gl.drawArrays(gl.TRIANGLES(), 0, numVertices);

            /* Clear vertex data for next batch */
            verteciesBuff.clear();
            numVertices = 0;
        }
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
        if (verteciesBuff.remaining() < 8 * 6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }

        Vector4f color = c.toVector4f();
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
        if (verteciesBuff.remaining() < 8 * 6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }

        Vector4f color = c.toVector4f();
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
        if (verteciesBuff.remaining() < 8 * 6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }

        Vector4f color = c.toVector4f();
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
        if (verteciesBuff.remaining() < 8 * 6) {
            /* We need more space in the buffer, so flush it */
            flush();
        }

        Vector4f color = c.toVector4f();
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

            verteciesBuff = MemoryUtil.memAllocFloat(4096);

            long size = verteciesBuff.capacity() * Float.BYTES;
            vbo.uploadData(gl.ARRAY_BUFFER(), size, gl.DYNAMIC_DRAW());

            numVertices = 0;
            drawing = false;
        }
    }

}
