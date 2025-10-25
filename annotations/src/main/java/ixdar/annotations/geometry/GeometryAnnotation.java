package ixdar.annotations.geometry;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface GeometryAnnotation {
    String id();
}
