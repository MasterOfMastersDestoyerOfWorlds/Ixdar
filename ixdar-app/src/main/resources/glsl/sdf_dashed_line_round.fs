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

uniform bool dashed;
uniform bool endCaps;
uniform bool roundCaps;
uniform float dashPhase;
uniform float dashLength;
uniform float dashes;
uniform float dashEdgeDist;

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

    float blockA = smoothstep(0.5, 0.5 + edgeSharpness, (sin((width * textureCoord.x * PI) / dashLength + dashPhase) + 1.) / 2.);

    if(l2 == 0.0)
        sigDist = distA;

    float t = max(0., min(1., dot(pos - pointA, pointB - pointA) / l2));
    vec2 projection = pointA + t * (pointB - pointA);  // Projection falls on the segment
    sigDist = distance(pos, projection) / width;

    float opacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);

    fragColor = vec4(mix(vertexColor.rgb, linearGradientColor.rgb, textureCoord.x), 1. * opacity * linearGradientColor.a);
    float dashOpac = blockA;
    float le2 = 1.;
    float re2 = 1.;
    if(dashed) {
        float x = (width * textureCoord.x * PI) / dashLength + dashPhase;
        le2 = mod(x + PI + dashEdgeDist, TAU);
        float lee = le2 / (dashes);
        vec2 lDashHead = vec2(textureCoord.x - lee, 0.5);

        re2 = mod(x - dashEdgeDist, TAU);
        float ree = (TAU - re2) / (dashes);
        vec2 rDashHead = vec2(textureCoord.x - ree, 0.5);

        vec2 texInWorld = vec2(textureCoord.x * width, textureCoord.y * width);
        vec2 lDashInWorld = vec2(lDashHead.x * width, lDashHead.y * width);
        vec2 rDashInWorld = vec2(rDashHead.x * width, rDashHead.y * width);
        if(roundCaps) {
            if(le2 <= PI) {
                dashOpac = smoothstep(edgeDist, edgeDist - edgeSharpness, distance(texInWorld, lDashInWorld) / width);
            } else if(re2 >= PI) {
                dashOpac = smoothstep(edgeDist, edgeDist - edgeSharpness, distance(texInWorld, rDashInWorld) / width);

            }
        }
    } else {
        dashOpac = 1.;
    }

    float opacA = smoothstep(edgeDist + edgeSharpness, edgeDist, min(distA, distB) / (width));
    if(dashed) {
        fragColor = mix(vec4(fragColor.rgb, fragColor.a * dashOpac), fragColor, opacA * float(endCaps));
    } else {
        fragColor = vec4(fragColor.rgb, fragColor.a * opacity);
    }

}