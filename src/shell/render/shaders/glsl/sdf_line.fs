#version 330 core
#define PI 3.1415926538
#define TAU 2*3.1415926538
in vec4 vertexColor;
in vec2 textureCoord;
in vec3 pos;

out vec4 fragColor;

uniform vec2 pointA;
uniform vec2 pointB;
uniform float edgeDist;
uniform float dashPhase;
uniform float dashLength;
uniform float borderInner;
uniform float borderOuter;
uniform float width;
uniform float height;

uniform float borderOffsetInner;
uniform float borderOffsetOuter;

uniform vec4 borderColor;
uniform bool dashed;
uniform float edgeSharpness;

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
    float l2 = distance(pointA, pointB);
    float frequency = 50 / l2;
    float phase = 1;
    float theta = dot((pointA - pointB), pos);
    return (cos(frequency * (theta + phase)) + 1) / 2;
}
void main() {
    float sigDist = -1;
    float l2 = lengthSq(pointA, pointB);
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

    fragColor = vec4(vertexColor.rgb, 1 * opacity);
    float dashOpac = blockA;
    if(dashed) {
        float dashes = PI * height / (2 * dashLength);
        float x = (height * textureCoord.x * PI) / dashLength + dashPhase;
        float re2 = mod(x - (0.75 * PI), TAU);
        float ree = re2 / (2 * dashes);
        vec2 rDashHead = vec2(textureCoord.x - ree, 0.5);

        float le2 = mod(x - (0.25 * PI), TAU);
        float lee = (TAU - le2) / (2 * dashes);
        vec2 lDashHead = vec2(textureCoord.x + lee, 0.5);

        vec2 texInWorld = vec2(textureCoord.x * height, textureCoord.y * width);
        vec2 rDashInWorld = vec2(rDashHead.x * height, rDashHead.y * width);
        vec2 lDashInWorld = vec2(lDashHead.x * height, lDashHead.y * width);

        if(re2 <= PI) {
            dashOpac = smoothstep(edgeDist, edgeDist - edgeSharpness, distance(texInWorld, rDashInWorld) / width);
        } else if(le2 >= PI) {
            dashOpac = smoothstep(edgeDist, edgeDist - edgeSharpness, distance(texInWorld, lDashInWorld) / width);

        }
    } else {
        dashOpac = 1;
    }

    float opacA = smoothstep(edgeDist + edgeSharpness, edgeDist, min(distA, distB) / (width));
    if(dashed) {
        fragColor = mix(vec4(fragColor.rgb, fragColor.a * dashOpac), fragColor, opacA);
    } else {
        fragColor = vec4(fragColor.rgb, fragColor.a * opacity);
    }
    //float var = opacA;
    //fragColor = vec4(var, var, var, 1);
    /*if(le2 <= 0.15) {
        fragColor = vec4(0, 0, 1, 1);
    }
    if(re2 <= 0.15) {
        fragColor = vec4(1, 0, 0, 1);
    }*/

}