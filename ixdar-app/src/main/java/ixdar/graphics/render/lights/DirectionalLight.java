package ixdar.graphics.render.lights;

import org.joml.Vector3f;

import ixdar.graphics.render.shaders.ShaderProgram;

public class DirectionalLight {
    public static final double NUM_0_01 = 0.01;
    private Vector3f direction;
    private Vector3f diffuse;
    private Vector3f ambient;
    private Vector3f specular;

    /**
     * TODO: document {@code DirectionalLight}.
     *
     * @param direction TODO: describe
     * @param color TODO: describe
     */
    public DirectionalLight(Vector3f direction, Vector3f color) {
        this.direction = direction;
        this.diffuse = new Vector3f(color);
        this.ambient = new Vector3f(color).mul((float) NUM_0_01);
        this.specular = new Vector3f(color);
    }

    /**
     * TODO: document {@code setShaderInfo}.
     *
     * @param shader TODO: describe
     * @param i TODO: describe
     */
    public void setShaderInfo(ShaderProgram shader, int i) {
        shader.setVec3("dirLight.direction", direction);
        shader.setVec3("dirLight.ambient", ambient);
        shader.setVec3("dirLight.diffuse", diffuse);
        shader.setVec3("dirLight.specular", specular);
    }
}