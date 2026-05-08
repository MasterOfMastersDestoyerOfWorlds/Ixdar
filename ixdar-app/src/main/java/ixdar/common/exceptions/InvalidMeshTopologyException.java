package ixdar.common.exceptions;

/**
 * Thrown when a mesh's topology is structurally invalid (e.g. dangling references,
 * inconsistent connectivity, or relationships that violate mesh-node invariants).
 */
public class InvalidMeshTopologyException extends RuntimeException {

    /**
     * Delegates to {@link RuntimeException#RuntimeException(String)}.
     *
     * @param message human-readable description of the topology violation
     */
    public InvalidMeshTopologyException(String message) {
        super(message);
    }
}
