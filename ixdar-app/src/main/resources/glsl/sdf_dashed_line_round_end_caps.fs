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
    float rDashHeadPeriod = mod(dashBucket + 1.25, 2.);
    vec2 rDashHead = vec2(scaledTextureCoord.x - rDashHeadPeriod, 0.5);

    float lDashHeadPeriod = mod(dashBucket - 0.25, 2.);
    float lDashHeadOffset = 2. - lDashHeadPeriod;
    vec2 lDashHeadCoord = vec2(scaledTextureCoord.x - lDashHeadOffset, 0.5);

    if(rDashHeadPeriod <= 1.) {

        dashOpac = smoothstep(edgeDist + edgeSharpness, edgeDist, distance(scaledTextureCoord, rDashHead));

    } else if(lDashHeadPeriod >= 1.) {

        dashOpac = smoothstep(edgeDist + edgeSharpness, edgeDist, distance(scaledTextureCoord, lDashHeadCoord));
        
    }

    float distA = distance(scaledTextureCoord, pointA);
    float distB = distance(scaledTextureCoord, pointB);
    float opacA = smoothstep(edgeDist + edgeSharpness, edgeDist, min(distA, distB));
    fragColor = mix(vec4(fragColor.rgb, fragColor.a * dashOpac), fragColor, opacA);
}