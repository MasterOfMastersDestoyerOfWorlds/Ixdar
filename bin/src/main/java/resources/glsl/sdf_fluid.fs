#version 300 es
precision mediump float;

in vec4 vertexColor;
in vec2 textureCoord;
in vec3 pos;

out vec4 fragColor;

uniform bool polar_coordinates;  //cool polar coordinates effect
uniform vec2 polar_center;
uniform float polar_zoom;
uniform float polar_repeat;

uniform highp vec2 TEXTURE_PIXEL_SIZE;
uniform highp float TIME;
uniform highp float spin_rotation;
uniform highp float spin_speed;
uniform highp vec2 offset;
uniform highp vec4 colour_1;
uniform highp vec4 colour_2;
uniform highp vec4 colour_3;
uniform highp float contrast;
uniform highp float spin_amount;
uniform highp float pixel_filter;
#define SPIN_EASE 0.0
#define PI 3.1415926538
#define TAU 2*3.1415926538

vec4 effect(vec2 screenSize, vec2 screen_coords) {
	//Convert to UV coords (0-1) and floor for pixel effect
    highp vec2 uv = (screen_coords.xy - 0.5 * screenSize.xy) / length(screenSize.xy) - offset;
    uv.y = screen_coords.y -0.5; 
    highp float uv_len = length(uv);

	// //Adding in a center swirl, changes with time. Only applies meaningfully if the 'spin amount' is a non-zero number
    highp float speed = (spin_rotation * SPIN_EASE * 0.1) + 20.2;
    highp float new_pixel_angle = (atan(uv.y, uv.x)) + speed - SPIN_EASE * 20. * (1. * spin_amount * uv_len + (1. - 1. * spin_amount));
    highp vec2 mid = (screenSize.xy / length(screenSize.xy)) / 2.;
    uv = (vec2((uv_len * cos(new_pixel_angle) + mid.x), (uv_len * sin(new_pixel_angle) + mid.y)) - mid);

	//Now add the paint effect to the swirled UV
    uv *= 20.;
    speed = TIME * (spin_speed) * 0.3;
    highp vec2 uv2 = vec2(uv.x + uv.y);

    for(int i = 0; i < 7; i++) {
        uv2 += sin(max(uv.x, uv.y)) + uv;
        uv += 0.6 * vec2(cos(60.1123314 + 0.1 * uv2.y + speed * 0.37), sin(uv2.x - 0.51 * speed));
        uv -= 1.0 * cos(uv.x + uv.y) - 1.0 * sin(uv.x * 0.711 - uv.y);
    }

	//Make the paint amount range from 0 - 2
    highp float contrast_mod = (0.20 * contrast + 0.5 * spin_amount + 1.2);
    highp float paint_res = min(2, max(0., length(uv) * (0.06) * contrast_mod));
    highp float c1p = max(0., 1. - contrast_mod * abs(1. - paint_res));
    highp float c2p = max(0., 1. - contrast_mod * abs(paint_res));
    highp float c3p = 1. - min(1., c1p + c2p);

    highp vec4 ret_col = (0.2 / contrast) * colour_1 + (1. - 0.3 / contrast) * (colour_1 * c1p + colour_2 * c2p + vec4(c3p * colour_3.rgb, c3p * colour_1.a));

    return ret_col;
}

vec2 polar_coords(vec2 uv, vec2 center, float zoom, float repeat) {
    vec2 dir = uv - center;
    float radius = length(dir) * 2.0;
    float angle = atan(dir.y, dir.x) * 1.0 / (PI * 2.0);
    return mod(vec2(radius * zoom, angle * repeat), 1.0);
}

void main() {
    vec2 polarCoords = textureCoord;
    if(polar_coordinates) {
        polarCoords = polar_coords(textureCoord.xy, polar_center, polar_zoom, polar_repeat);
    }
    fragColor = vertexColor * effect(TEXTURE_PIXEL_SIZE, polarCoords);
}
