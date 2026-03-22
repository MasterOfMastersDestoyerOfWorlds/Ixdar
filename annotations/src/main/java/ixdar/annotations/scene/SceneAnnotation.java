package ixdar.annotations.scene;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface SceneAnnotation {
    String id();
}
