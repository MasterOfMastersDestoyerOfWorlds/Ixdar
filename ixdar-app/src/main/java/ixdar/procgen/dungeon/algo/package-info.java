/**
 * Headless generation algorithms, no rendering or scene dependencies: room placers (may return
 * fewer rooms than asked), Bowyer-Watson Delaunay, Prim MST with probabilistic loop edges, A*
 * corridors (3D adds single-floor stair moves), grid-to-mesh with inward-wound hollow rooms, and
 * `DungeonGrids`, the builders/readers for the pipeline's geometry-plus-attribute shapes.
 */
package ixdar.procgen.dungeon.algo;
