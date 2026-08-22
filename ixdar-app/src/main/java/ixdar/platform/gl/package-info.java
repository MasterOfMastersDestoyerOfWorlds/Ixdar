/**
 * The core portability seam: `GL` (the minimum GL surface, enum values as methods since numerics
 * differ per backend) and `Platform` (windowing, assets, and the injection point for native
 * backends: Cholesky, integer programs, mesh booleans). `IxBuffer` abstracts buffers; `GlslSource`
 * rewrites ES 300 shaders to core 330.
 */
package ixdar.platform.gl;
