package shell.render.shaders;

import static org.lwjgl.opengl.GL13.glActiveTexture;
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
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

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

    public ShaderProgram(String vertexShaderLocation, String fragmentShaderLocation, VertexArrayObject vao,
            VertexBufferObject vbo) {
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
        checkCompileErrors(vertexShader, ShaderType.Vertex);

        fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, fragmentShaderSource);
        glCompileShader(fragmentShader);
        checkCompileErrors(fragmentShader, ShaderType.Fragment);

        ID = glCreateProgram();
        glAttachShader(ID, vertexShader);
        glAttachShader(ID, fragmentShader);
        glLinkProgram(ID);
        checkCompileErrors(ID, ShaderType.Program);

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
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

    private void checkCompileErrors(int shader, ShaderType type) {
        IntBuffer success = BufferUtils.createIntBuffer(1);

        if (type != ShaderType.Program) {
            glGetShaderiv(shader, GL_COMPILE_STATUS, success);
            if (success.get(0) == 0) {
                String infoLog = GL33.glGetShaderInfoLog(shader);
                System.out.println("ERROR::SHADER::" + type.name() + "::COMPILATION_FAILED\n" + infoLog);
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

}
