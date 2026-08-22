/**
 * Minimal dungeon collision: `CapsuleShape`, `AabbBox`, MTV separation, and `CapsuleMover` (sub-
 * stepped move-and-slide over the tile grid). Must agree with `GridToMesh3D` on cell size and the
 * origin-centered convention; `EMPTY` and out-of-grid are the obstacles.
 */
package ixdar.procgen.dungeon.physics;
