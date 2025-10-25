package ixdar.annotations.command;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface CommandAnnotation {
    String id();
}
