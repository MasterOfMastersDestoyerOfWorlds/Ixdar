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
     * Build a spotlight at {@code position} aimed along {@code direction}, with a
     * conical falloff bracketed by {@code cutOff} (full intensity) and
     * {@code outerCutOff} (zero intensity). Diffuse and specular both use
     * {@code color}; ambient is zero. Distance attenuation coefficients are
     * picked from {@link PointLight#attenuationLookupTable} via
     * {@link #setAttenuation(float)}.
     *
     * @param position world-space position of the emitter
     * @param direction normalized cone axis pointing away from the emitter
     * @param color shared RGB intensity for diffuse and specular
     * @param cutOff inner cone half-angle in degrees; fragments inside this cone receive full intensity
     * @param outerCutOff outer cone half-angle in degrees; fragments beyond this cone receive nothing
     * @param distance approximate falloff range used to pick attenuation
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
     * Pick the constant/linear/quadratic attenuation triple from
     * {@link PointLight#attenuationLookupTable} whose effective range bracket
     * contains {@code distance}.
     *
     * @param distance falloff range in world units
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
     * Push this light's parameters into {@code shader}'s {@code spotLight}
     * uniform block. The cone angles are converted from degrees to the cosine
     * of radians, matching the dot-product comparison in the fragment shader.
     *
     * @param shader target shader program; must already be bound
     * @param i unused; preserved for symmetry with {@link PointLight#setShaderInfo(ShaderProgram, int)}
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