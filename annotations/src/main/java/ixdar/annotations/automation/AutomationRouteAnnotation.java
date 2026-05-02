package ixdar.annotations.automation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface AutomationRouteAnnotation {
    String path();
    APIMethod method() default APIMethod.POST;
}
