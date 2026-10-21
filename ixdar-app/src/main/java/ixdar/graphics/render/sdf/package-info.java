/**
 * Signed-distance-field drawables, the editor's 2D drawing vocabulary: the `ShaderDrawable` base,
 * `SDFLine` variants, `SDFCircle`, `SDFRoundedRect`, `SDFBezier`, `SDFUnion` and MSDF textures.
 * Each draws geometry first, then colors, then the camera: `draw(..., Color, Camera)`.
 */
package ixdar.graphics.render.sdf;
