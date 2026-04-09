#version 300 es
precision highp float;

in vec3 Normal;
in vec2 TexCoords;
in vec3 VertexColor;
out vec4 FragColor;

uniform sampler2D albedoTex;
uniform vec4 solidColor;
uniform bool useTexture;
uniform vec3 lightDir;
uniform vec3 emissiveColor;
uniform float emissiveStrength;
uniform float rimStrength;
uniform bool useVertexColor;

void main() {
    vec3 n = normalize(Normal);
    float diffuse = max(dot(n, normalize(-lightDir)), 0.2);
    vec3 baseColor = useVertexColor ? VertexColor : solidColor.rgb;
    vec4 base = useTexture ? vec4(texture(albedoTex, TexCoords).rgb * baseColor, texture(albedoTex, TexCoords).a) : vec4(baseColor, solidColor.a);
    float rim = pow(max(1.0 - n.z, 0.0), 2.0);
    vec3 emissive = emissiveColor * (emissiveStrength + rim * rimStrength);
    FragColor = vec4(base.rgb * diffuse + emissive, base.a);
}
