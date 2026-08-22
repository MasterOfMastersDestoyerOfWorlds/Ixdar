/**
 * Input handling: GLFW-numbered `Keys` (the codebase-wide canonical key encoding; the web backend
 * translates into it), semantic `KeyActions`, and the `KeyGuy`/`MouseTrap` handler hierarchy. The
 * base classes are fused to `MainScene`; the orbit/trade subclasses exist to strip that back out.
 */
package ixdar.platform.input;
