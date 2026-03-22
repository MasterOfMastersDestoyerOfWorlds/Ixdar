package ixdar.annotations.meshnode;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface MeshNodeAnnotation {
    String id();
}
