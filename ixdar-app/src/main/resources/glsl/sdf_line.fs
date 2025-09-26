#version 300 es
precision highp float;

in vec4 vertexColor;
in vec2 scaledTextureCoord;
in vec2 textureCoord;

uniform vec2 pointA;
uniform vec2 pointB;
uniform float inverseLineLengthSq;
uniform float widthToHeightRatio;
uniform float edgeDist;
uniform float edgeSharpness;
uniform vec4 linearGradientColor;

out vec4 fragColor;

void main() {

    float sigDist = -1.;
    float t = max(0., min(1., dot(scaledTextureCoord - pointA, pointB - pointA) * inverseLineLengthSq));
    vec2 projection = pointA + t * (pointB - pointA);
    sigDist = distance(scaledTextureCoord, projection);
    float opacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);
    fragColor = vec4(mix(vertexColor.rgb, linearGradientColor.rgb, textureCoord.x), opacity * linearGradientColor.a);

}