/**
 * Split point between disjoint bodies of geometry code: `mesh` (the current node-graph stack),
 * `tsp` (the knot/cuts/shell lineage and its `.ix` file format) and `grid2d` (the 2D grids).
 * Nothing under `mesh` imports `tsp`.
 */
package ixdar.geometry;
