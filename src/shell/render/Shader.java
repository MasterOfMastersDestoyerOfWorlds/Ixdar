package shell.render;

import static org.lwjgl.opengl.GL20.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;

public class Shader {

    String vertexCode;
    String fragmentCode;
    CharSequence[] vertexShaderSource;
    CharSequence[] fragmentShaderSource;

    protected int ID;

    public Shader(String vertexShaderLocation, String fragmentShaderLocation) {

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

    void use() {
        glUseProgram(ID);
    }

    void setBool(String name, boolean value) {
        glUniform1i(glGetUniformLocation(ID, name), value ? 1 : 0);
    }

    void setInt(String name, int value) {
        glUniform1i(glGetUniformLocation(ID, name), value);
    }

    void setFloat(String name, float value) {
        glUniform1f(glGetUniformLocation(ID, name), value);
    }

    public void setMat4(String name, FloatBuffer value) {
        glUniformMatrix4fv(glGetUniformLocation(ID, name), false, value);
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
        File vShaderFile = new File("./src/shell/render/shaders/" + shaderName);
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

}
