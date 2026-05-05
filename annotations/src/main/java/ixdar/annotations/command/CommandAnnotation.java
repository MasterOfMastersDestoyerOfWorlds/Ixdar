package ixdar.annotations.command;

import java.lang.annotation.*;

/**
 * Marks a {@code TerminalOption} class for inclusion in the generated
 * {@code CommandRegistry_Commands} map keyed by {@link #id()}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface CommandAnnotation {
    /**
     * Stable string key under which the command is registered.
     *
     * @return registry key; if blank, the annotated class's simple name is used
     */
    String id();
}
