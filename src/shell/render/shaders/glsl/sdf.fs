#version 330 core
in vec4 vertexColor;
in vec2 textureCoord;

out vec4 fragColor;

uniform float borderInner;
uniform float borderOuter;

uniform vec4 borderColor;

uniform sampler2D texImage;
float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}
float map(float value, float min1, float max1, float min2, float max2) {
    return min2 + (value - min1) * (max2 - min2) / (max1 - min1);
}
void main() {
    vec4 sample = texture(texImage, textureCoord);
    float sigDist = median(sample.r, sample.g, sample.b);
    float w = fwidth(sigDist);

    float opacity = smoothstep(0.5 - w, 0.5 + w, sigDist);
    // if(sample.a < 0.3){
    //     sample.a = 0.0;
    // }
    float newRange = map(sample.a, 0, 1, 0, 2);
    float borderOpac = smoothstep(1 - borderOuter, 1 - borderInner, newRange);
   // fragColor = vec4(var, var, var, 1);

    fragColor = mix(vec4(borderColor.rgb, borderColor.a * borderOpac), vec4(vertexColor.rgb, vertexColor.a), opacity);

}