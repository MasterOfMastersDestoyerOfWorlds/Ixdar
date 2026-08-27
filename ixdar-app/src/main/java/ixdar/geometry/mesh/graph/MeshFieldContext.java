package ixdar.geometry.mesh.graph;

import ixdar.geometry.mesh.nodes.api.FieldContext;
import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Field domain backed by a mesh (vertex domain).
 */
public interface MeshFieldContext extends FieldContext {

    /**
     * Underlying mesh topology that this field context is sampled over.
     *
     * @return mesh whose vertex domain backs the field
     */
    MeshTopology mesh();
}
