#version 300 es
precision highp float;

in vec3 Normal;
in vec2 TexCoords;
in vec3 ViewPosition;
out vec4 FragColor;

uniform sampler2D albedoTex;
uniform vec4 solidColor;
uniform bool useTexture;
uniform vec3 lightDir;
uniform vec3 emissiveColor;
uniform float emissiveStrength;
uniform float rimStrength;
uniform mat4 view;

// SurfaceShading ordinal: 0 default, 1 clay, 2 curvature.
uniform int shadingStyle;
uniform vec3 clayColor;
// Clay and curvature key light straight from the camera instead of from its upper left.
uniform bool headlight;
// Bounding radius of the mesh, so curvature reads the same at any model scale.
uniform float meshRadius;

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

void main() {
    vec3 n = normalize(Normal);
    vec3 viewNormal = normalize(mat3(view) * n);
    float curvature = meanCurvature(viewNormal) * meshRadius;
    if (shadingStyle == 1) {
        FragColor = vec4(clayColor * clayLight(viewNormal), 1.0);
        return;
    }
    if (shadingStyle == 2) {
        float ridge = 0.5 + 0.5 * curvature / (abs(curvature) + CURVATURE_HALF_SATURATION);
        vec3 ramp = mix(CAVITY_COLOR, RIDGE_COLOR, ridge);
        FragColor = vec4(ramp * (0.45 + 0.55 * clayLight(viewNormal)), 1.0);
        return;
    }
    float diffuse = max(dot(n, normalize(-lightDir)), 0.2);
    vec4 base = useTexture ? texture(albedoTex, TexCoords) : solidColor;
    float facing = viewNormal.z;
    float rim = pow(max(1.0 - facing, 0.0), 2.0);
    vec3 emissive = emissiveColor * (emissiveStrength + rim * rimStrength);
    FragColor = vec4(base.rgb * diffuse + emissive, base.a);
}
