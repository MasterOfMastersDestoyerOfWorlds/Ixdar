/**
 * The two mesh representations. `HalfEdgeMesh`: mutable topology with vertex/edge/face/half-edge
 * adjacency, active flags for deletion without array shifts. `ArrayMesh`: dense position and index
 * arrays for GPU submission. Engines convert between them; `MeshTopology` is the shared interface.
 */
package ixdar.geometry.mesh.data.representation;
