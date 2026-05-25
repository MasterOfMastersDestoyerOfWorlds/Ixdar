#version 300 es
#extension GL_OES_standard_derivatives : enable
precision highp float;

flat in vec3 vCentroid;
flat in vec3 vDirU;
flat in vec3 vDirV;
flat in float vArmLength;
in vec3 vPos;
in vec3 Normal;

out vec4 FragColor;

uniform vec4 uLineColor;
uniform vec4 vLineColor;
uniform float lineHalfWidth;

void main() {
    vec3 normal = normalize(Normal);
    vec3 offset = vPos - vCentroid;
    offset -= dot(offset, normal) * normal;

    float armLength = max(vArmLength, 1.0e-8);
    float u = dot(offset, vDirU) / armLength;
    float v = dot(offset, vDirV) / armLength;

    float gradU = fwidth(u);
    float gradV = fwidth(v);
    float uLine = (1.0 - smoothstep(0.0, gradU * lineHalfWidth, abs(u))) * step(abs(u), 1.0);
    float vLine = (1.0 - smoothstep(0.0, gradV * lineHalfWidth, abs(v))) * step(abs(v), 1.0);

    float uMask = uLine * uLineColor.a;
    float vMask = vLine * vLineColor.a;
    if (uMask + vMask < 0.01) {
        discard;
    }

    vec3 colour = uLineColor.rgb * uMask + vLineColor.rgb * vMask;
    FragColor = vec4(colour, 1.0);
}
