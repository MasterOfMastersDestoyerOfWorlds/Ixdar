/**
 * Mesh rendering runtimes. `HalfEdgeMeshRuntime` uploads compiled meshes and draws them with tag
 * partitioning, scalar heat maps, feature-edge overlays, and wireframe. `QuadLayoutRuntime` extends
 * it with quad-layout overlays: port-typed values become a `LineSet`, a `PointSet`, or a corner
 * array, uploaded through `VertexBuffer` per `VertexLayout`. `AssimpModelRuntime` renders
 * loaded models.
 */
package ixdar.graphics.render.model;
