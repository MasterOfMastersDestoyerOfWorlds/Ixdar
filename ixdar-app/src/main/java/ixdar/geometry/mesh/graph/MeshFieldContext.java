package ixdar.geometry.mesh.graph;

import ixdar.annotations.meshnode.FieldContext;
import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Field domain backed by a mesh (vertex domain).
 */
public interface MeshFieldContext extends FieldContext {

    MeshTopology mesh();
}
