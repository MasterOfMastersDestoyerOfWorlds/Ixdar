/**
 * `Color` interface with the named palette, `ColorRGB`, and animated lerps driven by the static
 * `Clock`. `PatchColorHash` mirrors the GLSL `patchColor()` hash; but surface fill and layout
 * overlay hash different id spaces, so a shared palette does not mean matching colors.
 */
package ixdar.graphics.render.color;
