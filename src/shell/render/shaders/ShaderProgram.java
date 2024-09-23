package shell.render.shaders;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.glBindFragDataLocation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import shell.render.Color;
import shell.render.Texture;
import shell.render.VertexArrayObject;
import shell.render.VertexBufferObject;

public class ShaderProgram {

    String vertexCode;
    String fragmentCode;
    CharSequence[] vertexShaderSource;
    CharSequence[] fragmentShaderSource;
    public VertexArrayObject vao;
    public VertexBufferObject vbo;

    protected int ID;

    private FloatBuffer verteciesBuff;
    private int numVertices;
    private boolean drawing;

    @SuppressWarnings("unused")
    private String vertexShaderLocation, fragmentShaderLocation;

    public ShaderProgram(String vertexShaderLocation, String fragmentShaderLocation, VertexArrayObject vao,
            VertexBufferObject vbo, boolean useBuffer) {
        this.fragmentShaderLocation = fragmentShaderLocation;
        this.vertexShaderLocation = vertexShaderLocation;
        this.vao = vao;
        this.vbo = vbo;
        try {
            // open files

            vertexShaderSource = readFile(vertexShaderLocation);
            fragmentShaderSource = readFile(fragmentShaderLocation);
        } catch (IOException e) {
            System.out.println(e.getLocalizedMessage());
        }

        int vertexShader, fragmentShader;

        vertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShader, vertexShaderSource);
        glCompileShader(vertexShader);
        checkCompileErrors(vertexShader, ShaderType.Vertex, vertexShaderLocation);

        fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, fragmentShaderSource);
        glCompileShader(fragmentShader);
        checkCompileErrors(fragmentShader, ShaderType.Fragment, fragmentShaderLocation);

        ID = glCreateProgram();
        glAttachShader(ID, vertexShader);
        glAttachShader(ID, fragmentShader);
        glLinkProgram(ID);
        checkCompileErrors(ID, ShaderType.Program, "both");

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        if (useBuffer) {
            vao.bind();

            vbo.bind(GL_ARRAY_BUFFER);

            verteciesBuff = MemoryUtil.memAllocFloat(4096);

            long size = verteciesBuff.capacity() * Float.BYTES;
            vbo.uploadData(GL_ARRAY_BUFFER, size, GL_DYNAMIC_DRAW);

            numVertices = 0;
            drawing = false;
        }
    }

    public int getAttributeLocation(CharSequence name) {
        return glGetAttribLocation(ID, name);
    }

    public void use() {
        glUseProgram(ID);
    }

    void setBool(String name, boolean value) {
        glUniform1i(glGetUniformLocation(ID, name), value ? 1 : 0);
    }

    protected void setInt(String name, int value) {
        glUniform1i(glGetUniformLocation(ID, name), value);
    }

    public void setFloat(String name, float value) {
        glUniform1f(glGetUniformLocation(ID, name), value);
    }

    public void setMat4(String name, Matrix4f mat) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = mat.get(stack.mallocFloat(16));
            glUniformMatrix4fv(glGetUniformLocation(ID, name), false, buffer);
        }
    }

    public void setMat4(String name, FloatBuffer allocatedBuffer) {
        glUniformMatrix4fv(glGetUniformLocation(ID, name), false, allocatedBuffer);
    }

    public void setVec3(String name, float f, float g, float h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer vec3 = new Vector3f(f, g, h).get(stack.mallocFloat(3));
            glUniform3fv(glGetUniformLocation(ID, name), vec3);
        }
    }

    public void setVec3(String name, Vector3f vec3) {

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = vec3.get(stack.mallocFloat(3));
            glUniform3fv(glGetUniformLocation(ID, name), buffer);
        }
    }

    public void setVec4(String name, Vector4f vec4) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = vec4.get(stack.mallocFloat(4));
            glUniform4fv(glGetUniformLocation(ID, name), buffer);
        }
    }

    private void checkCompileErrors(int shader, ShaderType type, String location) {
        IntBuffer success = BufferUtils.createIntBuffer(1);

        if (type != ShaderType.Program) {
            glGetShaderiv(shader, GL_COMPILE_STATUS, success);
            if (success.get(0) == 0) {
                String infoLog = GL33.glGetShaderInfoLog(shader);
                System.out.println(
                        "ERROR::SHADER::" + type.name() + "::COMPILATION_FAILED: " + location + " \n" + infoLog);
            }
        } else {
            glGetProgramiv(shader, GL_LINK_STATUS, success);
            if (success.get(0) == 0) {
                String infoLog = GL33.glGetShaderInfoLog(shader);
                System.out.println("ERROR::SHADER::" + type.name() + "::LINK_FAILED\n" + infoLog);
            }
        }
    }

    protected CharSequence[] readFile(String shaderName) throws IOException {
        File vShaderFile = new File("./src/shell/render/shaders/glsl/" + shaderName);
        BufferedReader br = new BufferedReader(new FileReader(vShaderFile));
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

    public void setTexture(String glslName, Texture tex, int i, int j) {
        setInt(glslName, j);
        glActiveTexture(i);
        tex.bind();
    }

    public void bindFragmentDataLocation(int i, String string) {
        glBindFragDataLocation(ID, i, string);
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
                vbo.bind(GL_ARRAY_BUFFER);
            }
            use();

            /* Upload the new vertex data */
            vbo.bind(GL_ARRAY_BUFFER);
            vbo.uploadSubData(GL_ARRAY_BUFFER, 0, verteciesBuff);

            /* Draw batch */
            glDrawArrays(GL_TRIANGLES, 0, numVertices);

            /* Clear vertex data for next batch */
            verteciesBuff.clear();
            numVertices = 0;
        }
    }

    /**
     * Draws the currently bound texture on specified coordinates and with
     * specified color.
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
    public void drawTextureRegion(Texture texture, float x, float y, float zIndex, float regX, float regY, float regWidth,
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

        float r = c.getRed();
        float g = c.getGreen();
        float b = c.getBlue();
        float a = c.getAlpha();

        verteciesBuff.put(x1).put(y1).put(zIndex).put(r).put(g).put(b).put(a).put(s1).put(t1);
        verteciesBuff.put(x1).put(y2).put(zIndex).put(r).put(g).put(b).put(a).put(s1).put(t2);
        verteciesBuff.put(x2).put(y2).put(zIndex).put(r).put(g).put(b).put(a).put(s2).put(t2);

        verteciesBuff.put(x1).put(y1).put(zIndex).put(r).put(g).put(b).put(a).put(s1).put(t1);
        verteciesBuff.put(x2).put(y2).put(zIndex).put(r).put(g).put(b).put(a).put(s2).put(t2);
        verteciesBuff.put(x2).put(y1).put(zIndex).put(r).put(g).put(b).put(a).put(s2).put(t1);

        numVertices += 6;
    }

}
