package ixdar.geometry.mesh.csg;

/**
 * Exact triangle-mesh boolean, supplied by the platform so that the native CSG kernel stays out of
 * the browser build.
 */
public interface MeshBooleanBackend {

    String ACCEPTED_SOLID = "NO_ERROR";

    /**
     * The kernel's verdict on one triangulated mesh as a boolean operand, without running a
     * boolean.
     *
     * @param operand triangulated mesh to check
     * @return {@link #ACCEPTED_SOLID} when the kernel takes it as a closed solid, else the
     *         kernel's error name, such as {@code NOT_MANIFOLD}
     */
    String solidStatus(QuadTriangulation operand);

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
