package ixdar.annotations.scene;

import java.lang.annotation.*;

/**
 * Marks a {@code SceneDrawable} class for inclusion in the generated
 * {@code SceneRegistry_Scenes} map keyed by {@link #id()}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface SceneAnnotation {
    /**
     * Stable string key under which the scene is registered.
     *
     * @return registry key; if blank, the annotated class's simple name is used
     */
    String id();
}
