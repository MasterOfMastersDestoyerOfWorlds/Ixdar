#version 330 core
out vec4 FragColor;
in vec3 ourColor;
in vec2 TexCoord;
uniform sampler2D texture1;
uniform sampler2D texture2;

void main() {
    vec4 tex = texture(texture1, TexCoord);
    vec4 tex2 = texture(texture2, TexCoord);
    float alpha =max(tex.w, tex2.w);
    FragColor = mix(vec4(tex2.xyz,alpha), vec4(min(ourColor, tex.www), alpha), 0.5);
}