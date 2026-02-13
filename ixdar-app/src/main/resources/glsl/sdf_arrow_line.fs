#version 300 es
precision highp float;
#define PI 3.1415926538

in vec4 vertexColor;
in vec2 scaledTextureCoord;
in vec2 textureCoord;

out vec4 fragColor;

uniform vec2 pointA;
uniform vec2 pointB;
uniform float lineLengthSq;

// Animation uniforms
uniform float dashPhase;  
uniform float dashLength; // Controls arrow spacing

// Styling uniforms
uniform float edgeDist;      // Controls line thickness
uniform float edgeSharpness; // Controls anti-aliasing
uniform vec4 linearGradientColor;

void main() {
    // --- 1. Line Container Logic (Same as your standard line) ---
    // Calculate progress (t) along the line segment (0.0 to 1.0)
    float t = max(0., min(1., dot(scaledTextureCoord - pointA, pointB - pointA) / lineLengthSq));
    
    // Project current pixel onto the line to get distance from center (sigDist)
    vec2 projection = pointA + t * (pointB - pointA);
    float sigDist = distance(scaledTextureCoord, projection);

    // Calculate base opacity for the line shape (handling caps/width)
    float baseOpacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);

    // --- 2. Arrow Pattern Logic ---
    
    // Calculate distance along the line (unclamped)
    // We use dot product to project the current coord onto the normalized line vector
    vec2 lineDir = normalize(pointB - pointA);
    float distAlongLine = dot(scaledTextureCoord - pointA, lineDir);

    // Animation: offset distance by phase * spacing
    // We multiply dashPhase by dashLength so 1.0 phase = 1 full arrow step
    float animatedDist = distAlongLine - (dashPhase * dashLength);

    // Create repeating cells. 
    // 'cellX' will range from -dashLength/2 to +dashLength/2
    float cellX = mod(animatedDist, dashLength) - (dashLength * 0.5);

    // Define Arrow Shape (Chevron >)
    // A chevron is defined where x > |y|.
    // We scale sigDist (y) to control the arrow angle (steeper or wider)
    float arrowAngle = 1.0; 
    float chevronDist = cellX - (sigDist * arrowAngle);

    // 'arrowWidth' defines how thick the V-shape lines are.
    // We mask the chevron: valid if it's 'behind' the tip but not 'too far behind'
    float arrowThickness = edgeDist * 0.8; 
    float arrowMask = smoothstep(0.0, edgeSharpness, chevronDist) 
                    * smoothstep(arrowThickness + edgeSharpness, arrowThickness, chevronDist);

    // Optional: Hard cut the edges of the cell to prevent wrapping artifacts
    if (cellX < -dashLength * 0.4) arrowMask = 0.0;

    // --- 3. Final Composite ---
    
    // Combine the outer line shape (baseOpacity) with the inner arrow pattern (arrowMask)
    float finalOpacity = baseOpacity * arrowMask;

    // Mix vertex color with gradient based on texture position
    vec4 baseColor = mix(vertexColor, linearGradientColor, textureCoord.x);
    
    // Output: Color where arrow is, Transparent (0 opacity) where gap is
    fragColor = vec4(baseColor.rgb, baseColor.a * finalOpacity);
}