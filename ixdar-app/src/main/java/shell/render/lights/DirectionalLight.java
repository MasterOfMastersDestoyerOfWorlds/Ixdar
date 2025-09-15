package shell.render.lights;

import org.joml.Vector3f;

import shell.render.shaders.ShaderProgram;

public class DirectionalLight {
    private Vector3f direction;
    private Vector3f diffuse;
    private Vector3f ambient;
    private Vector3f specular;

    public DirectionalLight(Vector3f direction, Vector3f color) {
        this.direction = direction;
        this.diffuse = new Vector3f(color);
        this.ambient = new Vector3f(color).mul((float) 0.01);
        this.specular = new Vector3f(color);
    }

    public void setShaderInfo(ShaderProgram shader, int i) {
        shader.setVec3("dirLight.direction", direction);
        shader.setVec3("dirLight.ambient", ambient);
        shader.setVec3("dirLight.diffuse", diffuse);
        shader.setVec3("dirLight.specular", specular);
    }
}