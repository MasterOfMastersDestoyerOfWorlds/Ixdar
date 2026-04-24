/**
 * Procedural dungeon generation — vazgriz-style room placement + Delaunay + MST + A* corridors,
 * wired into the Ixdar mesh DSL as dungeon-scoped nodes.
 *
 * <p>Every MeshNode in {@code ixdar.procgen.dungeon.nodes} carries
 * {@code @MeshNodeAnnotation(scopes = {"dungeon"})} so it never enters Daud's mesh-modeling
 * catalog. See {@code ixdar.geometry.mesh.documentation.MeshNodeCatalog} for the scope filter
 * and {@code MeshNodeAnnotation.scopes()} for the annotation contract.
 *
 * <p>Subpackages:
 * <ul>
 *   <li>{@code .algo} — pure-Java algorithm classes, unit-testable without any MeshNode plumbing.</li>
 *   <li>{@code .values} — immutable port value types (RoomList, EdgeGraph, TileGrid) used to pass
 *       stage outputs between nodes. Kept here, NOT in {@code ixdar.annotations.meshnode}, so
 *       dungeon-only concepts stay out of the general mesh schema.</li>
 *   <li>{@code .nodes} — thin MeshNode wrappers that delegate to {@code .algo}.</li>
 *   <li>{@code .scene} — viewer scene(s) that run a dungeon DSL and render the output mesh.</li>
 * </ul>
 *
 * <p>Design notes and motivation live in the Archipelago-inspired GDD at
 * {@code KriegEterna/web/content/essays/archipelago-dungeon-design.md}.
 */
package ixdar.procgen.dungeon;
