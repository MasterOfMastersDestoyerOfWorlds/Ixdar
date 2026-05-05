package ixdar.annotations.meshnode;

import java.lang.annotation.*;

/**
 * Marks a {@code MeshNode} class for inclusion in the generated
 * {@code MeshNodeRegistry_MeshNodes} map keyed by {@link #id()}. Retained at
 * runtime so consumers (e.g. {@code MeshNodeCatalog}) can filter nodes by
 * {@link #scopes()}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MeshNodeAnnotation {
    /**
     * Stable string key under which the mesh node is registered.
     *
     * @return registry key; if blank, the annotated class's simple name is used
     */
    String id();

    /**
     * Editor scopes in which this node is offered (e.g. {@code "mesh"} for
     * Daud's mesh-modeling editor, {@code "dungeon"} for procgen dungeons).
     *
     * @return non-empty list of scope tags; defaults to both editors
     */
    String[] scopes() default { "mesh", "dungeon" };
}
