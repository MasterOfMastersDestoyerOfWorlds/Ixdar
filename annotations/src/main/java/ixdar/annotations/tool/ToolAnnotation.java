package ixdar.annotations.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code Tool} class for the generated {@code ToolRegistry_Tools}, which builds the tool
 * for a scene of its {@link #host()} type.
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

    /**
     * The scene type that hosts the tool, which its public one-argument constructor accepts.
     *
     * @return the host scene class
     */
    Class<?> host();
}
