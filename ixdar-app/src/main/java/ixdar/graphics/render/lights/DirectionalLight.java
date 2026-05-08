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
     * Build a directional light pointing along {@code direction}. Diffuse and
     * specular both carry {@code color}; ambient is dimmed to 1% of the same
     * color so unlit faces still pick up a faint tint.
     *
     * @param direction normalized world-space direction the light travels
     * @param color shared RGB intensity for diffuse and specular
     */
    public DirectionalLight(Vector3f direction, Vector3f color) {
        this.direction = direction;
        this.diffuse = new Vector3f(color);
        this.ambient = new Vector3f(color).mul((float) NUM_0_01);
        this.specular = new Vector3f(color);
    }

    /**
     * Push this light's parameters into {@code shader}'s {@code dirLight}
     * uniform block.
     *
     * @param shader target shader program; must already be bound
     * @param i unused; preserved for symmetry with {@link PointLight#setShaderInfo(ShaderProgram, int)}
     */
    public void setShaderInfo(ShaderProgram shader, int i) {
        shader.setVec3("dirLight.direction", direction);
        shader.setVec3("dirLight.ambient", ambient);
        shader.setVec3("dirLight.diffuse", diffuse);
        shader.setVec3("dirLight.specular", specular);
    }
}