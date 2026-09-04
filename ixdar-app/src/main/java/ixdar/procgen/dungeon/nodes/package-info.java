/**
 * Thin `MeshNode` adapters wrapping each algorithm stage for the DSL, all in the `dungeon` scope.
 * Rooms, Delaunay, and MST are dimension-neutral single nodes over point geometry; corridors and
 * grid-to-mesh keep 2D/3D ids because stairs and vertical adjacency have no 2D analog.
 */
package ixdar.procgen.dungeon.nodes;
