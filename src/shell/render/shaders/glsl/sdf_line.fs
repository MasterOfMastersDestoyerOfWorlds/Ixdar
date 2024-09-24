#version 330 core
in vec4 vertexColor;
in vec2 textureCoord;

out vec4 fragColor;

uniform vec2 pointA;
uniform vec2 pointB;
uniform float edgeDist;
uniform float dashPhase;
uniform float dashLength;
uniform float borderInner;
uniform float borderOuter;

uniform float borderOffsetInner;
uniform float borderOffsetOuter;

uniform vec4 borderColor;
uniform bool dashed;

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
    float l2 = lengthSq(pointA, pointB);  // i.e. |w-v|^2 -  avoid a sqrt
    float distA = distance(textureCoord, pointA);
    float distB = distance(textureCoord, pointB);
    float block = smoothstep(0.5, 0.6, (sin(distA / dashLength + dashPhase) + 1) / 2);
    float blockB = 0;//smoothstep(0.5, 0.6, (sin(distB / dashLength) + 1) / 2);
    if(l2 == 0.0)
        sigDist = distA;   // v == w case
  // Consider the line extending the segment, parameterized as v + t (w - v).
  // We find projection of point p onto the line. 
  // It falls where t = [(p-v) . (w-v)] / |w-v|^2
  // We clamp t from [0,1] to handle points outside the segment vw.
    float t = max(0, min(1, dot(textureCoord - pointA, pointB - pointA) / l2));
    vec2 projection = pointA + t * (pointB - pointA);  // Projection falls on the segment
    sigDist = distance(textureCoord, projection);

    float opacity = smoothstep(edgeDist + (edgeDist / 2), edgeDist, sigDist);
    // if(sample.a < 0.3){
    //     sample.a = 0.0;
    // }
    //float borderOpac = smoothstep(1 - borderOuter, 1 - borderInner, sigDist);

    //float borderOffsetOpac = smoothstep(1 - borderOffsetOuter, 1 - borderOffsetInner, sigDist);

    fragColor = vec4(vertexColor.rgb, 1 * opacity);
    float var = block + blockB;
    float AOpac = smoothstep(edgeDist + (edgeDist / 2), edgeDist, distA);
    float BOpac = smoothstep(edgeDist + (edgeDist / 2), edgeDist, distB);
    if(dashed) {
        fragColor = mix(mix(vec4(fragColor.rgb, fragColor.a * opacity * var), fragColor, AOpac), fragColor, BOpac);
    } else {
        fragColor = vec4(fragColor.rgb, fragColor.a * opacity);
    }
    //fragColor =  mix(mix(vec4(borderColor.rgb, borderColor.a * borderOpac), vec4(0), borderOffsetOpac), vertexColor, opacity);

}