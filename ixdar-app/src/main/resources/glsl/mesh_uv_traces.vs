#version 300 es
precision highp float;

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 3) in vec2 aUv;
layout(location = 4) in float aFlipped;
layout(location = 5) in vec4 aTrace0;
layout(location = 6) in vec4 aTrace1;
layout(location = 7) in vec4 aTrace2;
layout(location = 8) in vec4 aTrace3;
layout(location = 9) in float aPatchId;

out float vFlipped;
out vec3 Normal;
out vec2 vUv;
out vec4 vTrace0;
out vec4 vTrace1;
out vec4 vTrace2;
out vec4 vTrace3;
flat out float vPatchId;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
uniform float depthBias;

void main() {
    gl_Position = projection * view * model * vec4(aPos, 1.0);
    gl_Position.z -= depthBias * gl_Position.w;
    Normal = mat3(transpose(inverse(model))) * aNormal;
    vUv = aUv;
    vFlipped = aFlipped;
    vTrace0 = aTrace0;
    vTrace1 = aTrace1;
    vTrace2 = aTrace2;
    vTrace3 = aTrace3;
    vPatchId = aPatchId;
}
