#version 300 es
precision mediump float;
#define PI 3.1415926538
#define TAU 2.*3.1415926538
in vec4 vertexColor;
in vec2 textureCoord;
in vec3 pos;

out vec4 fragColor;

uniform vec2 pointA;
uniform float radius;
uniform float edgeDist;
uniform float edgeSharpness;

void main() {
    float sigDist = -1.;
    vec2 p = pos.xy;
    float distA = distance(p, pointA);

    sigDist = (distA / radius);

    float opacity = smoothstep(1.0, 1.0 - edgeSharpness, sigDist);
    fragColor = vec4(vertexColor.rgb, vertexColor.a * opacity);
}