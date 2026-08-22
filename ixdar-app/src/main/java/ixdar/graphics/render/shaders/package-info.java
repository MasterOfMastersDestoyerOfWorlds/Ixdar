/**
 * Shader compilation and GL object wrappers. `ShaderProgram.ShaderType` is the central registry
 * mapping every logical shader to its class and `.vs`/`.fs` pair; subclasses differ mainly in
 * vertex stride and attribute layout. Sources pass through `GlslSource` for the dialect rewrite.
 */
package ixdar.graphics.render.shaders;
