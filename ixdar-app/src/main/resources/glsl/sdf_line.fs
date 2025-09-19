#version 300 es
precision highp float;

in vec4 vertexColor;
in vec2 textureCoord;
in vec3 pos;

uniform vec2 pointA;
uniform vec2 pointB;
uniform float inverseLineLengthSq;
uniform float height;
uniform float width;
uniform float edgeDist;
uniform float edgeSharpness;
uniform vec4 linearGradientColor;

out vec4 fragColor;

void main() {
    float sigDist = -1.;
    vec2 pos = pos.xy;
    float t = max(0., min(1., dot(pos - pointA, pointB - pointA) * inverseLineLengthSq));
    vec2 projection = pointA + t * (pointB - pointA); // Projection falls on the segment A-B
    sigDist = distance(pos, projection) / height;
    float opacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);
    fragColor = vec4(mix(vertexColor.rgb, linearGradientColor.rgb, textureCoord.x), 1. * opacity * linearGradientColor.a);
}