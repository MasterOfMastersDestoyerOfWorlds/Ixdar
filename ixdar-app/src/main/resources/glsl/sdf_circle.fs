#version 300 es
precision mediump float;
#define PI 3.1415926538
#define TAU 2.*3.1415926538
in vec4 vertexColor;
in vec2 textureCoord;
in vec3 pos;

out vec4 fragColor;

uniform vec2 pointA;
uniform float lineLengthSq;

uniform float width;
uniform float height;

uniform float edgeDist;
uniform float edgeSharpness;

uniform vec4 borderColor;
uniform float borderThickness;

float map(float value, float min1, float max1, float min2, float max2) {
    return min2 + (value - min1) * (max2 - min2) / (max1 - min1);
}
void main() {
    float sigDist = -1.;
    float l2 = lineLengthSq;
    vec2 pos = pos.xy;
    float distA = distance(pos, pointA);

    if(l2 == 0.0)
        sigDist = distA;

    sigDist = (distA / width);//((sin(* 2 * 20 * TAU) + 1)) / 2;

    float opacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);
    float borderDist = edgeDist + borderThickness;
    float borderOpac = smoothstep(borderDist, borderDist - edgeSharpness, sigDist);

    fragColor = mix(vec4(borderColor.rgb, borderColor.a * borderOpac), vertexColor, opacity);
    //float var = opacA;
    //fragColor = vec4(var, var, var, 1);

}