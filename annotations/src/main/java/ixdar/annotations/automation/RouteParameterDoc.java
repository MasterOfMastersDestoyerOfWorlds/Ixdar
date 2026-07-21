package ixdar.annotations.automation;

/**
 * Documentation for one JSON body parameter of an {@link AutomationRoute}. Instances are serialized
 * into the automation routes manifest and drive the CLI's dynamic subcommand flags and README.
 */
public final class RouteParameterDoc {
    public final String name;
    public final String cliName;
    public final RouteParamType type;
    public final boolean required;
    public final String defaultValue;
    public final String help;
    public final String example;

    /**
     * Capture the documentation for a single request parameter.
     *
     * @param name JSON body key the handler reads; must match the endpoint's body-key constant so
     *        the docs cannot drift from the code
     * @param cliName flag name the CLI exposes (without dashes); usually equal to {@code name}, but
     *        distinct when the friendly flag differs from the body key (e.g. {@code out} for {@code path})
     * @param type value type of the parameter
     * @param required whether the request must supply this key
     * @param defaultValue rendered default when the key is omitted; empty string when {@code required}
     * @param help one-line human description of the parameter
     * @param example a representative value a caller might pass
     */
    public RouteParameterDoc(String name, String cliName, RouteParamType type, boolean required,
            String defaultValue, String help, String example) {
        this.name = name;
        this.cliName = cliName;
        this.type = type;
        this.required = required;
        this.defaultValue = defaultValue;
        this.help = help;
        this.example = example;
    }
}
