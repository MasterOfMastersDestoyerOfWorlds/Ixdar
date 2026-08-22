/**
 * TeaVM bytecode transformers registered in the `web-teavm` profile: `WebMathTransformer` supplies
 * the missing `Math.fma` (true fused multiply-add via BigDecimal; two roundings would break the
 * exact orientation predicates), `JomlUnsafeTransformer` drops JOML's `sun.misc.Unsafe` path.
 */
package ixdar.platform.teavm;
