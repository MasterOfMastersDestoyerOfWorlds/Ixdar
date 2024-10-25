#version 330 core
#define PI 3.1415926538
#define TAU 2*3.1415926538
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

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}
float map(float value, float min1, float max1, float min2, float max2) {
    return min2 + (value - min1) * (max2 - min2) / (max1 - min1);
}
float lengthSq(vec2 a, vec2 b) {
    vec2 r = a - b;
    return r.x * r.x + r.y * r.y;
}

float wave(vec2 pos) {
    float l2 = lineLengthSq;
    float frequency = 50 / l2;
    float phase = 1;
    float theta = dot((pointA - pointB), pos);
    return (cos(frequency * (theta + phase)) + 1) / 2;
}
void main() {
    float sigDist = -1;
    float l2 = lineLengthSq;
    vec2 pos = pos.xy;
    float distA = distance(pos, pointA);
    float distB = distance(pos, pointB);

    float blockA = smoothstep(0.5, 0.5 + edgeSharpness, (sin((height * textureCoord.x * PI) / dashLength + dashPhase) + 1) / 2);

    if(l2 == 0.0)
        sigDist = distA;

    float t = max(0, min(1, dot(pos - pointA, pointB - pointA) / l2));
    vec2 projection = pointA + t * (pointB - pointA);  // Projection falls on the segment
    sigDist = distance(pos, projection) / width;

    float opacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);

    fragColor = vec4(mix(vertexColor.rgb, linearGradientColor.rgb, textureCoord.x), 1 * opacity * linearGradientColor.a);
    float dashOpac = blockA;
    float le2 = 1;
    float re2 = 1;
    /*float dashEndR = 1;
    float dashEndL = 1;
    float dashCenter = 1;*/
    if(dashed) {
        float x = (height * textureCoord.x * PI) / dashLength + dashPhase;
        le2 = mod(x + PI + dashEdgeDist, TAU);
        //dashEndL = mod(x + PI, TAU);
        //dashCenter = mod(x + (3 * PI / 2), TAU);
        float lee = le2 / (dashes);
        vec2 lDashHead = vec2(textureCoord.x - lee, 0.5);

        re2 = mod(x - dashEdgeDist, TAU);
        //dashEndR = mod(x + (TAU), TAU);
        float ree = (TAU - re2) / (dashes);
        vec2 rDashHead = vec2(textureCoord.x - ree, 0.5);

        vec2 texInWorld = vec2(textureCoord.x * height, textureCoord.y * width);
        vec2 lDashInWorld = vec2(lDashHead.x * height, lDashHead.y * width);
        vec2 rDashInWorld = vec2(rDashHead.x * height, rDashHead.y * width);
        if(roundCaps) {
            if(le2 <= PI) {
                dashOpac = smoothstep(edgeDist, edgeDist - edgeSharpness, distance(texInWorld, lDashInWorld) / width);
            } else if(re2 >= PI) {
                dashOpac = smoothstep(edgeDist, edgeDist - edgeSharpness, distance(texInWorld, rDashInWorld) / width);

            }
        }
    } else {
        dashOpac = 1;
    }

    float opacA = smoothstep(edgeDist + edgeSharpness, edgeDist, min(distA, distB) / (width));
    if(dashed) {
        fragColor = mix(vec4(fragColor.rgb, fragColor.a * dashOpac), fragColor, opacA * float(endCaps));
    } else {
        fragColor = vec4(fragColor.rgb, fragColor.a * opacity);
    }
    //float var = opacA;
    //fragColor = vec4(var, var, var, 1);

/*
    if(le2 <= 0.15) {
        //fragColor = vec4(1, 0, 0, 1);
    }
    if(re2 <= 0.15) {
        //fragColor = vec4(0, 0, 1, 1);
    }
    if(dashEndL <= 0.15) {
        //fragColor = vec4(1, 1, 1, 1);
    }
    if(dashEndR <= 0.15) {
        //fragColor = vec4(1, 1, 1, 1);
    }

    if(dashCenter <= 0.15) {
        //fragColor = vec4(0, 0, 0, 1);
    }*/

}