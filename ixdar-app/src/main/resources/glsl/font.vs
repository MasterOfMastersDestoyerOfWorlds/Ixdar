#version 300 es
precision mediump float;

in vec3 position;
in vec4 color;
in vec2 texCoord;

out vec4 vertexColor;
out vec2 textureCoord;
out vec3 pos;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;

void main() {
    vertexColor = color;
    textureCoord = texCoord;
    pos = position;
    mat4 mvp = projection * view * model;
    gl_Position = mvp * vec4(position, 1.0);
}
