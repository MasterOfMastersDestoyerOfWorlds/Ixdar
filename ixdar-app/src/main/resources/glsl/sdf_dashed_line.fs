#version 300 es
precision highp float;
#define PI 3.1415926538
#define TAU 2.*3.1415926538
in vec4 vertexColor;
in vec2 textureCoord;
in vec3 pos;

out vec4 fragColor;

uniform vec2 pointA;
uniform vec2 pointB;
uniform float lineLengthSq;

uniform float width;
uniform float height;

uniform float dashPhase;
uniform float dashLength;
uniform float dashes;
uniform float dashEdgeDist;
uniform bool endCaps;

uniform float edgeDist;
uniform float edgeSharpness;

uniform float borderOffsetInner;
uniform float borderOffsetOuter;
uniform vec4 borderColor;
uniform float borderInner;
uniform float borderOuter;

uniform vec4 linearGradientColor;

void main() {
    float sigDist = -1.;
    float l2 = lineLengthSq;
    vec2 pos = pos.xy;
    float distA = distance(pos, pointA);
    float distB = distance(pos, pointB);
    float t = max(0., min(1., dot(pos - pointA, pointB - pointA) / l2));
    vec2 projection = pointA + t * (pointB - pointA);
    sigDist = distance(pos, projection) / height;
    float opacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);
    fragColor = vec4(mix(vertexColor.rgb, linearGradientColor.rgb, textureCoord.x), 1. * opacity * linearGradientColor.a);

    float blockA = smoothstep(0.5, 0.5 + edgeSharpness, (sin((width * textureCoord.x * PI) / dashLength + dashPhase) + 1.) / 2.);
    float dashOpac = blockA;
    float x = (width * textureCoord.x * PI) / dashLength + dashPhase;

    float opacA = smoothstep(edgeDist + edgeSharpness, edgeDist, min(distA, distB) / (width));
    fragColor = mix(vec4(fragColor.rgb, fragColor.a * dashOpac), fragColor, opacA * float(endCaps));
}