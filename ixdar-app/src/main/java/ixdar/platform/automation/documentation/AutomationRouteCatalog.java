package ixdar.platform.automation.documentation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.google.gson.GsonBuilder;

import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParameterDoc;
import ixdar.platform.automation.AutomationRouteMap;

/**
 * Serializes every registered {@link AutomationRoute} — its path, method, description, parameters,
 * response hint, and source location — to a pretty-printed JSON manifest. Mirrors
 * {@code MeshNodeCatalog}; the manifest is the single source of truth the Python CLI reads to build
 * its server-backed subcommands and agent-facing docs.
 */
public final class AutomationRouteCatalog {
    public static final String PATH = "path";
    public static final String METHOD = "method";
    public static final String COMMAND_NAME = "commandName";
    public static final String DESCRIPTION = "description";
    public static final String SOURCE_CLASS = "sourceClass";
    public static final String SOURCE_PATH = "sourcePath";
    public static final String PARAMS = "params";
    public static final String RESPONSE_HINT = "responseHint";
    public static final String NAME = "name";
    public static final String CLI_NAME = "cliName";
    public static final String TYPE = "type";
    public static final String REQUIRED = "required";
    public static final String DEFAULT = "default";
    public static final String HELP = "help";
    public static final String EXAMPLE = "example";
    public static final String ROUTES = "routes";
    public static final String GENERATED_FROM = "generatedFrom";

    private static final String PATH_SEPARATOR = "/";
    private static final String SOURCE_ROOT = "ixdar-app/src/main/java/";
    private static final String JAVA_EXTENSION = ".java";

    private AutomationRouteCatalog() {
    }

    /**
     * Serialize the whole automation route registry to a JSON manifest, sorted by command name
     * for stable diffs.
     *
     * @return JSON document with {@code generatedFrom} and a {@code routes} array
     * @throws IllegalStateException if two routes resolve to the same CLI command name
     */
    public static String toJsonFromAnnotationRegistry() {
        List<Map<String, Object>> routes = new ArrayList<>();
        Map<String, String> commandNameOwners = new HashMap<>();
        for (Supplier<? extends AutomationRoute> supplier : AutomationRouteMap.MAP.values()) {
            AutomationRoute route = supplier.get();
            AutomationRouteAnnotation ann = route.getClass().getAnnotation(AutomationRouteAnnotation.class);
            if (ann == null) {
                continue;
            }
            String path = ann.path();
            if (!path.startsWith(PATH_SEPARATOR)) {
                path = PATH_SEPARATOR + path;
            }
            RouteDoc doc = route.describe();
            String fqcn = route.getClass().getName();
            String commandName = doc.commandName.isBlank() ? slugFromPath(path) : doc.commandName;
            String previousOwner = commandNameOwners.put(commandName, fqcn);
            if (previousOwner != null) {
                throw new IllegalStateException("Duplicate CLI command name \"" + commandName + "\" from "
                        + previousOwner + " and " + fqcn + "; set a distinct commandName() in describe().");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(COMMAND_NAME, commandName);
            entry.put(PATH, path);
            entry.put(METHOD, ann.method().name().toUpperCase());
            entry.put(DESCRIPTION, doc.description);
            entry.put(SOURCE_CLASS, fqcn);
            entry.put(SOURCE_PATH, SOURCE_ROOT + fqcn.replace('.', '/') + JAVA_EXTENSION);
            entry.put(PARAMS, serializeParams(doc.parameters));
            entry.put(RESPONSE_HINT, doc.responseHint);
            routes.add(entry);
        }
        routes.sort(Comparator.comparing((Map<String, Object> entry) -> (String) entry.get(COMMAND_NAME)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(GENERATED_FROM, SOURCE_ROOT + "ixdar/platform/automation/documentation/AutomationRouteCatalog" + JAVA_EXTENSION);
        root.put(ROUTES, routes);
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root);
    }

    private static String slugFromPath(String path) {
        String trimmed = path.startsWith(PATH_SEPARATOR) ? path.substring(1) : path;
        return trimmed.replace('/', '-');
    }

    private static List<Map<String, Object>> serializeParams(List<RouteParameterDoc> parameters) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RouteParameterDoc parameter : parameters) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(NAME, parameter.name);
            m.put(CLI_NAME, parameter.cliName);
            m.put(TYPE, parameter.type.jsonName());
            m.put(REQUIRED, parameter.required);
            m.put(DEFAULT, parameter.defaultValue);
            m.put(HELP, parameter.help);
            m.put(EXAMPLE, parameter.example);
            out.add(m);
        }
        return out;
    }
}
