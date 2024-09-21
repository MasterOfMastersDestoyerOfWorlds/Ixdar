package shell.render.lights;

import org.joml.Vector3f;

import shell.render.Shader;

public class PointLight {
    public Vector3f position;
    public Vector3f diffuse;
    Vector3f ambient;
    Vector3f specular;
    float constant;
    float linear;
    float quadratic;

    public static float[] attenuationLookupTable = { 3250, 1.0f, 0.0014f, 0.000007f,

            600, 1.0f, 0.007f, 0.0002f,

            325, 1.0f, 0.014f, 0.0007f,

            200, 1.0f, 0.022f, 0.0019f,

            160, 1.0f, 0.027f, 0.0028f,

            100f, 1.0f, 0.045f, 0.0075f,

            65f, 1.0f, 0.07f, 0.017f,

            50f, 1.0f, 0.09f, 0.032f,

            32f, 1.0f, 0.14f, 0.07f,

            20f, 1.0f, 0.22f, 0.20f,

            13f, 1.0f, 0.35f, 0.44f,

            7f, 1.0f, 0.7f, 1.8f,

            0f, 1.0f, 0.7f, 1.8f };

    public PointLight(Vector3f position, Vector3f color, float distance) {
        this.position = position;
        this.diffuse = new Vector3f(color);
        this.ambient = new Vector3f(color);
        this.specular = new Vector3f(color);
        setAttenuation(distance);
    }

    public void setAttenuation(float distance) {
        int rows = attenuationLookupTable.length / 4;
        for (int i = rows - 1; i >= 1; i--) {
            if (distance >= attenuationLookupTable[4 * i] && distance < attenuationLookupTable[4 * (i - 1)]) {
                this.constant = attenuationLookupTable[4 * i + 1];
                this.linear = attenuationLookupTable[4 * i + 2];
                this.quadratic = attenuationLookupTable[4 * i + 3];
            }
        }
    }

    public void setShaderInfo(Shader shader, int i) {
        shader.setVec3("pointLights[" + i + "].position", position);
        shader.setVec3("pointLights[" + i + "].ambient", ambient);
        shader.setVec3("pointLights[" + i + "].diffuse", diffuse);
        shader.setVec3("pointLights[" + i + "].specular", specular);
        shader.setFloat("pointLights[" + i + "].constant", constant);
        shader.setFloat("pointLights[" + i + "].linear", linear);
        shader.setFloat("pointLights[" + i + "].quadratic", constant);
    }
}