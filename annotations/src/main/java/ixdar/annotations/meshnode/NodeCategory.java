package ixdar.annotations.meshnode;

/**
 * Closed set of catalog categories a {@link MeshNodeAnnotation} node is listed under. The
 * category is declared on the node, never derived from its package, so moving a node keeps it.
 */
public enum NodeCategory {
    PRIMITIVES("Primitives"),
    MODIFIERS("Modifiers"),
    MATH_LOGIC("Math & Logic"),
    FIELD_DATA("Field & Data"),
    GEOMETRY_OPERATIONS("Geometry Operations"),
    CURVE_OPERATIONS("Curve Operations"),
    CONTROL_FLOW("Control Flow"),
    CLOSURE("Closure"),
    PATCH_SURFACE("Patch & Surface"),
    SELECTION("Selection"),
    TRANSFORM("Transform"),
    QUAD_LAYOUT("Quad Layout"),
    NETWORK("Network"),
    DUNGEON("Dungeon");

    /** Name shown in the node catalog and the editor's node menu. */
    public final String displayName;

    NodeCategory(String displayName) {
        this.displayName = displayName;
    }
}
