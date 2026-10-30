#version 300 es
precision mediump float;
in vec4 vertexColor;
in vec2 textureCoord;
in vec3 pos;

out vec4 fragColor;

uniform vec2 pointA;
uniform float radius;
uniform float edgeDist;

uniform vec4 borderColor;
uniform float borderThickness;

void main() {
    float sigDist = distance(pos.xy, pointA) / radius;
    // One screen pixel in radii, so both edges ramp over exactly one pixel at any zoom and size: sharp,
    // never aliased, and with no camera scale to keep in step with how the radius was sized.
    float pixel = max(fwidth(sigDist), 1e-6);

    float shape = smoothstep(edgeDist, edgeDist - pixel, sigDist);
    float fill = smoothstep(edgeDist, edgeDist - pixel, sigDist + borderThickness);
    // Weight each colour by its alpha, so a band round a transparent fill keeps its own colour across
    // the inner edge instead of darkening towards the transparent fill's black.
    float borderWeight = borderColor.a * (1.0 - fill);
    float fillWeight = vertexColor.a * fill;
    float alpha = borderWeight + fillWeight;
    vec3 rgb = alpha > 0.0 ? (borderColor.rgb * borderWeight + vertexColor.rgb * fillWeight) / alpha
            : vertexColor.rgb;
    fragColor = vec4(rgb, alpha * shape);
}
