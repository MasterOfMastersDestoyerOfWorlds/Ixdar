/**
 * Mesh rendering runtimes. `HalfEdgeMeshRuntime` uploads compiled meshes and draws them with tag
 * partitioning, scalar heat maps, feature-edge overlays, and wireframe. `MeshOverlayRuntime` extends
 * it with general overlays: points, lines, labels, fields and arcs become a `LineSet`, a
 * `PointSet`, or a corner array, uploaded through `VertexBuffer` per `VertexLayout`. `AssimpModelRuntime` renders
 * loaded models.
 */
package ixdar.graphics.render.model;
