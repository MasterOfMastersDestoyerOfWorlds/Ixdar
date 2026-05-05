package ixdar.annotations.geometry;

import java.lang.annotation.*;

/**
 * Marks a {@code Geometry} class for inclusion in the generated
 * {@code GeometryRegistry_Geometries} map keyed by {@link #id()}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface GeometryAnnotation {
    /**
     * Stable string key under which the geometry is registered.
     *
     * @return registry key; if blank, the annotated class's simple name is used
     */
    String id();
}
