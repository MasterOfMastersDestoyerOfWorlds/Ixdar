#version 300 es
precision mediump float;
in vec4 vertexColor;
in vec2 textureCoord;
in vec3 pos;

out vec4 fragColor;

uniform vec2 pointA;
uniform float radius;
uniform float edgeDist;
uniform float edgeSharpness;

uniform vec4 borderColor;
uniform float borderThickness;

void main() {
    float sigDist = distance(pos.xy, pointA) / radius;

    float shape = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);
    float fill = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist + borderThickness);
    vec4 color = mix(borderColor, vertexColor, fill);
    fragColor = vec4(color.rgb, color.a * shape);
}
