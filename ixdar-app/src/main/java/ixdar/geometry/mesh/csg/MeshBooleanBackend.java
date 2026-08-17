package ixdar.geometry.mesh.csg;

/**
 * Exact triangle-mesh boolean, supplied by the platform so that the native CSG kernel stays out of
 * the browser build.
 */
public interface MeshBooleanBackend {

    /**
     * Boolean two triangulated solids, keeping each output triangle's provenance.
     *
     * @param operandA first solid, already triangulated with per-triangle source faces
     * @param operandB second solid, already triangulated with per-triangle source faces
     * @param operation which of union, difference or intersection to compute
     * @return the resulting mesh and the operand each of its faces came from
     */
    MeshBooleanResult compute(QuadTriangulation operandA, QuadTriangulation operandB,
            BooleanOperation operation);
}
