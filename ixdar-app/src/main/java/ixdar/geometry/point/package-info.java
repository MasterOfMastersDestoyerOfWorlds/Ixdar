/**
 * Shared point primitives (`PointND`, `PointSet`) with wide live usage across cameras,
 * scenes, and automation, plus the terminal-addable CLI geometries registered via
 * `@GeometryAnnotation`. Not TSP-free: `Grid` and `PointSet` import knot and shell.
 */
package ixdar.geometry.point;
