package ixdar.annotations.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code Tool} class for the generated {@code ToolRegistry_Tools}, which builds the tool
 * for its host: the scene type its one public one-argument constructor takes.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ToolAnnotation {
    /**
     * Stable key the tool is registered under, the tool's own {@code toolName()}.
     *
     * @return registry key
     */
    String id();
}
