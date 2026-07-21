package ixdar.annotations.automation;

import java.util.Locale;

/**
 * Value type of an {@link AutomationRoute} JSON body parameter. The lowercase {@link #jsonName()}
 * is what the routes manifest carries and what the CLI maps onto an argparse argument type.
 */
public enum RouteParamType {
    STRING,
    INT,
    FLOAT,
    BOOL;

    /**
     * Lowercase token used in the JSON manifest (and mapped to a CLI argument type).
     *
     * @return the enum constant name in lowercase, e.g. {@code "string"}
     */
    public String jsonName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
