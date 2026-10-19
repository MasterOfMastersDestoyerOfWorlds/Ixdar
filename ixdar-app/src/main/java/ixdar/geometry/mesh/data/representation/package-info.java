/**
 * The mesh representations: `HalfEdgeMesh` (mutable adjacency, what every producer builds) with
 * its engine, and `ArrayMesh`, the indexed form of a loaded or repaired mesh that has no half-edge
 * form; the shared `MeshTopology` interface, plus edge keys, triangle measurements and the
 * unchecked `InvalidMeshTopologyException`.
 */
package ixdar.geometry.mesh.data.representation;
