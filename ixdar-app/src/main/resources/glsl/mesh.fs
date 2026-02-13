#version 300 es
precision mediump float;

in vec3 Normal;
in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D albedoTex;
uniform vec4 solidColor;
uniform bool useTexture;
uniform vec3 lightDir;

void main() {
    vec3 n = normalize(Normal);
    float diffuse = max(dot(n, normalize(-lightDir)), 0.2);
    vec4 base = useTexture ? texture(albedoTex, TexCoords) : solidColor;
    FragColor = vec4(base.rgb * diffuse, base.a);
}
