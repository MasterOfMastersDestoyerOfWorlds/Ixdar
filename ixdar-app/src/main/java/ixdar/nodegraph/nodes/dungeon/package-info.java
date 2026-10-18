/**
 * Thin `MeshNode` adapters wrapping each algorithm stage for the DSL, all in the `dungeon` scope.
 * Every stage is one node for 2D and 3D: `grid_d` on rooms and corridors chooses 3D, and
 * grid-to-mesh reads the dimension off the tiles it is given.
 */
package ixdar.nodegraph.nodes.dungeon;
