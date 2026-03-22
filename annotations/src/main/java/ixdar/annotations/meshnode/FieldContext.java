package ixdar.annotations.meshnode;

/**
 * Implicit geometry domain for field input nodes (vertex positions/normals for the current mesh).
 */
public interface FieldContext {

    int elementCount();

    Vec3Field positions();

    Vec3Field normals();
}
