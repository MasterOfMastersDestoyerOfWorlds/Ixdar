/**
 * Pure-Java physics for the dungeon viewer / player controller. Capsule-vs-AABB collision tests
 * and a {@code moveAndSlide} integrator that resolves desired motion against the dungeon's
 * tile grid. Obstacle convention: in the hollow-room geometry from
 * {@link ixdar.procgen.dungeon.algo.GridToMesh3D}, players walk inside non-empty cells; EMPTY
 * cells (and out-of-grid space) are the solid obstacles to push out of. No MeshNode dependency;
 * unit-testable in isolation.
 */
package ixdar.procgen.dungeon.physics;
