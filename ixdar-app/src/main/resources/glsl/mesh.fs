#version 300 es
precision highp float;

in vec3 Normal;
in vec2 TexCoords;
in vec3 ViewPosition;
in float vScalar;
out vec4 FragColor;

uniform sampler2D albedoTex;
uniform vec4 solidColor;
uniform bool useTexture;
uniform vec3 lightDir;
uniform vec3 emissiveColor;
uniform float emissiveStrength;
uniform float rimStrength;
uniform mat4 view;

// SurfaceLook.Colour ordinal: 0 surface, 1 texture, 2 clay, 3 curvature, 4 field.
uniform int colourSource;
// SurfaceLook.Light ordinal: 0 view, 1 clay, 2 soft clay.
uniform int lighting;
uniform vec3 clayColor;
// The clay lights key straight from the camera instead of from its upper left.
uniform bool headlight;
// Bounding radius of the mesh, so curvature reads the same at any model scale.
uniform float meshRadius;
// A field look's range: values outside it clamp to the ramp's ends.
uniform float scalarMin;
uniform float scalarMax;

// The worn Palette's scalar ramp, low to high, at evenly spaced stops.
const int RAMP_STOPS = 4;
uniform vec4 rampColors[RAMP_STOPS];

// View-space light directions: they follow the camera, so no view is lit from behind.
const vec3 KEY_LIGHT = vec3(-0.45, 0.55, 0.70);
const vec3 FILL_LIGHT = vec3(0.55, -0.25, 0.80);
const vec3 CAVITY_COLOR = vec3(0.16, 0.18, 0.28);
const vec3 RIDGE_COLOR = vec3(1.0, 0.97, 0.90);
// Mean curvature times the mesh radius at which the cavity/ridge ramp is half saturated.
const float CURVATURE_HALF_SATURATION = 6.0;

float clayLight(vec3 viewNormal) {
    vec3 key = headlight ? vec3(0.0, 0.0, 1.0) : normalize(KEY_LIGHT);
    float keyTerm = max(dot(viewNormal, key), 0.0);
    float fillTerm = max(dot(viewNormal, normalize(FILL_LIGHT)), 0.0);
    float sky = 0.5 + 0.5 * viewNormal.y;
    return 0.12 + 0.68 * keyTerm + 0.18 * fillTerm + 0.12 * sky;
}

// Mean curvature from screen-space derivatives: the normal's change along each pixel step,
// projected on the position's change, over that step's squared length. Positive is convex.
float meanCurvature(vec3 viewNormal) {
    vec3 stepX = dFdx(ViewPosition);
    vec3 stepY = dFdy(ViewPosition);
    float alongX = dot(dFdx(viewNormal), stepX) / max(dot(stepX, stepX), 1e-12);
    float alongY = dot(dFdy(viewNormal), stepY) / max(dot(stepY, stepY), 1e-12);
    return 0.5 * (alongX + alongY);
}

vec3 rampColor(float v) {
    float position = clamp(v, 0.0, 1.0) * float(RAMP_STOPS - 1);
    int low = min(int(position), RAMP_STOPS - 2);
    return mix(rampColors[low].rgb, rampColors[low + 1].rgb, position - float(low));
}

void main() {
    vec3 n = normalize(Normal);
    vec3 viewNormal = normalize(mat3(view) * n);
    // Derivatives are taken before any branch, where every fragment of the quad still runs.
    float curvature = meanCurvature(viewNormal) * meshRadius;
    vec4 base;
    if (colourSource == 1) {
        base = texture(albedoTex, TexCoords);
    } else if (colourSource == 2) {
        base = vec4(clayColor, 1.0);
    } else if (colourSource == 3) {
        float ridge = 0.5 + 0.5 * curvature / (abs(curvature) + CURVATURE_HALF_SATURATION);
        base = vec4(mix(CAVITY_COLOR, RIDGE_COLOR, ridge), 1.0);
    } else if (colourSource == 4) {
        base = vec4(rampColor((vScalar - scalarMin) / max(scalarMax - scalarMin, 1e-6)), 1.0);
    } else {
        // AssimpModelRuntime draws through this branch and samples its texture with useTexture.
        base = useTexture ? texture(albedoTex, TexCoords) : solidColor;
    }
    if (lighting == 1) {
        FragColor = vec4(base.rgb * clayLight(viewNormal), base.a);
        return;
    }
    if (lighting == 2) {
        FragColor = vec4(base.rgb * (0.45 + 0.55 * clayLight(viewNormal)), base.a);
        return;
    }
    float diffuse = max(dot(n, normalize(-lightDir)), 0.2);
    float rim = pow(max(1.0 - viewNormal.z, 0.0), 2.0);
    vec3 emissive = emissiveColor * (emissiveStrength + rim * rimStrength);
    FragColor = vec4(base.rgb * diffuse + emissive, base.a);
}
