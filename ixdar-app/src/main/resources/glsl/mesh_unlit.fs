#version 300 es
precision highp float;

in vec3 Normal;
in vec2 TexCoords;
out vec4 FragColor;

uniform vec4 solidColor;

void main() {
    FragColor = solidColor;
}
