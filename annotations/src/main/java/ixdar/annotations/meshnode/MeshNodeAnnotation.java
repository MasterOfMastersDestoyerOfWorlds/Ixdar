package ixdar.annotations.meshnode;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MeshNodeAnnotation {
    String id();

    String[] scopes() default { "mesh", "dungeon" };
}
