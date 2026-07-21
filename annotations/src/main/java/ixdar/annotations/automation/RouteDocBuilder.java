package ixdar.annotations.automation;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for {@link RouteDoc}, letting each endpoint's {@code describe()} read as a single
 * chained expression rather than nested constructor calls. This is a behavior class, so its
 * accumulation fields are intentionally {@code private}.
 */
public final class RouteDocBuilder {
    private String commandName = "";
    private String description = "";
    private String responseHint = "";
    private final List<RouteParameterDoc> parameters = new ArrayList<>();

    /**
     * Set the CLI subcommand name for this route. Omit to let the exporter derive it from the path.
     *
     * @param name CLI subcommand name (e.g. {@code "screenshot"} for {@code /ui/screenshot})
     * @return this builder
     */
    public RouteDocBuilder commandName(String name) {
        this.commandName = name;
        return this;
    }

    /**
     * Set the one-line route description.
     *
     * @param routeDescription human summary of what the route does
     * @return this builder
     */
    public RouteDocBuilder description(String routeDescription) {
        this.description = routeDescription;
        return this;
    }

    /**
     * Append documentation for one JSON body parameter whose CLI flag matches its body key.
     *
     * @param name JSON body key the handler reads (also the CLI flag name)
     * @param type value type of the parameter
     * @param required whether the request must supply this key
     * @param defaultValue rendered default when the key is omitted; empty when {@code required}
     * @param help one-line description of the parameter
     * @param example a representative value a caller might pass
     * @return this builder
     */
    public RouteDocBuilder param(String name, RouteParamType type, boolean required,
            String defaultValue, String help, String example) {
        parameters.add(new RouteParameterDoc(name, name, type, required, defaultValue, help, example));
        return this;
    }

    /**
     * Append documentation for one JSON body parameter whose CLI flag differs from its body key.
     *
     * @param name JSON body key the handler reads
     * @param cliName flag name the CLI exposes (e.g. {@code "out"} for body key {@code "path"})
     * @param type value type of the parameter
     * @param required whether the request must supply this key
     * @param defaultValue rendered default when the key is omitted; empty when {@code required}
     * @param help one-line description of the parameter
     * @param example a representative value a caller might pass
     * @return this builder
     */
    public RouteDocBuilder paramAliased(String name, String cliName, RouteParamType type, boolean required,
            String defaultValue, String help, String example) {
        parameters.add(new RouteParameterDoc(name, cliName, type, required, defaultValue, help, example));
        return this;
    }

    /**
     * Set the terse response-shape hint.
     *
     * @param hint description of the returned JSON, e.g. {@code "{ok, width, height}"}
     * @return this builder
     */
    public RouteDocBuilder responseHint(String hint) {
        this.responseHint = hint;
        return this;
    }

    /**
     * Assemble the immutable {@link RouteDoc} from the accumulated state.
     *
     * @return a doc capturing the command name, description, parameters, and response hint set so far
     */
    public RouteDoc build() {
        return new RouteDoc(commandName, description, List.copyOf(parameters), responseHint);
    }
}
