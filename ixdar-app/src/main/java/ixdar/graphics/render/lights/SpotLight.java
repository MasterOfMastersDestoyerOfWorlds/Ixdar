package ixdar.graphics.render.lights;

import org.joml.Vector3f;

import ixdar.graphics.render.shaders.ShaderProgram;

public class SpotLight {
    public static final float NUM_0 = 0f;
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;
    public Vector3f position;
    public Vector3f diffuse;
    Vector3f ambient;
    Vector3f specular;
    float constant;
    float linear;
    float quadratic;
    double cutOff;
    double outerCutOff;
    Vector3f direction;

    /**
     * TODO: document {@code SpotLight}.
     *
     * @param position TODO: describe
     * @param direction TODO: describe
     * @param color TODO: describe
     * @param cutOff TODO: describe
     * @param outerCutOff TODO: describe
     * @param distance TODO: describe
     */
    public SpotLight(Vector3f position, Vector3f direction, Vector3f color, float cutOff, float outerCutOff,
            float distance) {
        this.position = position;
        this.direction = direction;
        this.diffuse = new Vector3f(color);
        this.ambient = new Vector3f(NUM_0);
        this.specular = new Vector3f(color);
        this.cutOff = cutOff;
        this.outerCutOff = outerCutOff;
        setAttenuation(distance);
    }

    /**
     * TODO: document {@code setAttenuation}.
     *
     * @param distance TODO: describe
     */
    public void setAttenuation(float distance) {
        int rows = PointLight.attenuationLookupTable.length / NUM_4;
        for (int i = rows - 1; i >= 1; i--) {
            if (distance >= PointLight.attenuationLookupTable[NUM_4 * i]
                    && distance < PointLight.attenuationLookupTable[NUM_4 * (i - 1)]) {
                this.constant = PointLight.attenuationLookupTable[NUM_4 * i + 1];
                this.linear = PointLight.attenuationLookupTable[NUM_4 * i + 2];
                this.quadratic = PointLight.attenuationLookupTable[NUM_4 * i + NUM_3];
            }
        }
    }

    /**
     * TODO: document {@code setShaderInfo}.
     *
     * @param shader TODO: describe
     * @param i TODO: describe
     */
    public void setShaderInfo(ShaderProgram shader, int i) {
        shader.setVec3("spotLight.position", position);
        shader.setVec3("spotLight.direction", direction);
        shader.setVec3("spotLight.ambient", ambient);
        shader.setVec3("spotLight.diffuse", diffuse);
        shader.setVec3("spotLight.specular", specular);
        shader.setFloat("spotLight.constant", constant);
        shader.setFloat("spotLight.linear", linear);
        shader.setFloat("spotLight.quadratic", quadratic);
        shader.setFloat("spotLight.cutOff", (float) Math.cos(Math.toRadians(cutOff)));
        shader.setFloat("spotLight.outerCutOff", (float) Math.cos(Math.toRadians(outerCutOff)));
    }
}