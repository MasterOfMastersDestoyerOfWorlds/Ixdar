package ixdar.annotations.command;

import java.lang.annotation.*;

/**
 * Marks a {@code TerminalOption} class for inclusion in the generated
 * {@code CommandRegistry_Commands} map keyed by {@link #id()}, with what it acts on in its
 * {@code ACTS} map.
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

    /**
     * The subsystems the command acts on; the terminal refuses it in a scene missing any of them.
     *
     * @return the subsystems, at least one; required, so no command is left untagged
     */
    Subsystem[] acts();
}
