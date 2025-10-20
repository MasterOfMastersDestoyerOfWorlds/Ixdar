#version 300 es
precision highp float;
#define PI 3.1415926538
in vec4 vertexColor;
in vec2 textureCoord;
in vec2 scaledTextureCoord;

out vec4 fragColor;

uniform vec2 pointA;
uniform vec2 pointB;
uniform float lineLengthSq;

uniform float dashPhase;
uniform float dashLength;
uniform float dashes;

uniform float edgeDist;
uniform float edgeSharpness;

uniform vec4 linearGradientColor;

void main() {
    
    float t = max(0., min(1., dot(scaledTextureCoord - pointA, pointB - pointA) / lineLengthSq));
    vec2 projection = pointA + t * (pointB - pointA);

    float sigDist = distance(scaledTextureCoord, projection);
    float opacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);
    fragColor = vec4(mix(vertexColor.rgb, linearGradientColor.rgb, textureCoord.x), opacity * linearGradientColor.a);

    float dashWave = (sin((scaledTextureCoord.x * PI) / dashLength + dashPhase) + 1.) / 2.;
    float dashOpac = smoothstep(0.5, 0.5 + edgeSharpness, dashWave);

    float dashBucket = (scaledTextureCoord.x) / dashLength + (dashPhase / PI);
    
    float rDashHeadOffset = (1. - edgeDist/dashLength);
    float rDashHeadPeriod = mod(dashBucket - rDashHeadOffset, 2.) * dashLength ;
    vec2 rDashHead = vec2(scaledTextureCoord.x - rDashHeadPeriod, 0.5);
    float rDistance = distance(scaledTextureCoord, rDashHead);


    float lDashHeadOffset =  edgeDist/dashLength;
    float lDashHeadPeriod = (2. - mod(dashBucket - lDashHeadOffset, 2.)) * dashLength;
    vec2 lDashHeadCoord = vec2(scaledTextureCoord.x - lDashHeadPeriod , 0.5);
    float lDistance = distance(scaledTextureCoord, lDashHeadCoord);

    if(rDashHeadPeriod <= 1.) {
        dashOpac = smoothstep(edgeDist, edgeDist - edgeSharpness, rDistance);
    } else if(lDashHeadPeriod <= 1.) {
        dashOpac = smoothstep(edgeDist, edgeDist - edgeSharpness, lDistance);
    }

    fragColor = vec4(fragColor.rgb, fragColor.a * dashOpac);

}