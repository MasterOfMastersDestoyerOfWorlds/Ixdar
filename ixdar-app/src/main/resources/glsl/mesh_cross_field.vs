#version 300 es
precision highp float;

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 3) in vec3 aCentroid;
layout(location = 4) in vec3 aDirU;
layout(location = 5) in vec3 aDirV;
layout(location = 6) in float aArmLength;

flat out vec3 vCentroid;
flat out vec3 vDirU;
flat out vec3 vDirV;
flat out float vArmLength;
out vec3 vPos;
out vec3 Normal;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
uniform float depthBias;

void main() {
    vec4 worldPos = model * vec4(aPos, 1.0);
    gl_Position = projection * view * worldPos;
    gl_Position.z -= depthBias * gl_Position.w;
    vPos = worldPos.xyz;
    Normal = mat3(transpose(inverse(model))) * aNormal;
    vCentroid = (model * vec4(aCentroid, 1.0)).xyz;
    vDirU = mat3(model) * aDirU;
    vDirV = mat3(model) * aDirV;
    vArmLength = aArmLength;
}
