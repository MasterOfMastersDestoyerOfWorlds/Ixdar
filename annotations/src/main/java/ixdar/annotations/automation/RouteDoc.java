package ixdar.annotations.automation;

import java.util.List;

/**
 * Machine-readable documentation for one {@link AutomationRoute}: the CLI command name, a one-line
 * description, the ordered docs for each JSON body parameter, and a terse hint of the response shape.
 * Built via {@link #builder()} in each route's {@code describe()} and serialized by the routes-manifest
 * exporter. The command name is the single source of truth for how the CLI names the route.
 */
public final class RouteDoc {
    public final String commandName;
    public final String description;
    public final List<RouteParameterDoc> parameters;
    public final String responseHint;

    /**
     * Capture a route's full documentation.
     *
     * @param commandName CLI subcommand name; blank means the exporter derives it from the path
     * @param description one-line human description of what the route does
     * @param parameters ordered docs for each JSON body parameter the route reads
     * @param responseHint terse description of the returned JSON shape
     */
    public RouteDoc(String commandName, String description, List<RouteParameterDoc> parameters,
            String responseHint) {
        this.commandName = commandName;
        this.description = description;
        this.parameters = parameters;
        this.responseHint = responseHint;
    }

    /**
     * A blank doc for routes not yet documented, keeping the exporter total (every route serializes).
     *
     * @return a doc with blank command name and description, no parameters, and empty response hint
     */
    public static RouteDoc empty() {
        return new RouteDoc("", "", List.of(), "");
    }

    /**
     * Start building a {@link RouteDoc} fluently.
     *
     * @return a fresh builder
     */
    public static RouteDocBuilder builder() {
        return new RouteDocBuilder();
    }
}
