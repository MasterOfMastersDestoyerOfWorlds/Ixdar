/**
 * Input handling: GLFW-numbered `Keys` (the codebase-wide canonical key encoding; the web backend
 * translates into it), semantic `KeyActions`, and the `KeyGuy`/`MouseTrap` handler hierarchy. The
 * base classes hand scene-specific keys and panes to subclass hooks; the TSP editor's handlers live
 * with it in `scenes.main`.
 */
package ixdar.platform.input;
