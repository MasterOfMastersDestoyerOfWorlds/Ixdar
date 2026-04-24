/**
 * Dungeon-scoped MeshNode wrappers. Every class in this package carries
 * {@code @MeshNodeAnnotation(scopes = {"dungeon"})} — never the default {@code {mesh, dungeon}} —
 * so Daud's mesh-modeling catalog (scope="mesh") never sees them. Wrappers are thin: they
 * delegate to {@link ixdar.procgen.dungeon.algo} and contain no algorithm logic.
 * Implementations land in PROCGEN-5.
 */
package ixdar.procgen.dungeon.nodes;
