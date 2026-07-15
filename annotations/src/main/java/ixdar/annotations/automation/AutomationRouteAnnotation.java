package ixdar.annotations.automation;

import java.lang.annotation.*;

/**
 * Marks an {@link AutomationRoute} class for inclusion in the generated
 * {@code AutomationRouteRegistry_AutomationRoutes} map; the registered
 * {@link #path()} and {@link #method()} are read at runtime by
 * {@code AutomationApiServer} to bind the HTTP endpoint.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AutomationRouteAnnotation {
    /**
     * URL path segment (without leading slash) the route is bound to.
     *
     * @return endpoint path, e.g. {@code "shutdown"}
     */
    String path();
    /**
     * HTTP method this route accepts.
     *
     * @return verb to match; defaults to {@link APIMethod#POST}
     */
    APIMethod method() default APIMethod.POST;
}
