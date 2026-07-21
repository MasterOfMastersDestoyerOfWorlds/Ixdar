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
     * Stable registry key for this route. Because the generated registry map is keyed by this id,
     * two routes sharing a simple class name (e.g. {@code mesh.Compare} and {@code mesh.skeleton.Compare})
     * must set distinct ids or the second silently overwrites the first.
     *
     * @return registry key; if blank, the annotated class's simple name is used
     */
    String id() default "";

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
