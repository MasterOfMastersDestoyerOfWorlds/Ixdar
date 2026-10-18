package ixdar.nodegraph.catalog;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.HeapSampler;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.syntax.PythonParser;

/**
 * Renders a node-tree record and walks field paths below it by reflection over public fields,
 * with a depth limit on nesting and a length limit on lists, arrays and maps, so any value a
 * node produced can be read without a source edit.
 */
public final class NodeInspector {

    private static final Pattern FIELD_NAME_THEN_SUBSCRIPTS = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)?((?:\\[[^\\]]*\\])*)");

    private static final Pattern SUBSCRIPT = Pattern.compile("\\[([^\\]]*)\\]");

    private static final String TYPE = "type";
    private static final String SIZE = "size";
    private static final String FIELD_SEPARATOR = ".";
    private static final String ARRAY_SUFFIX = "[]";

    /** How many levels of nested objects a rendering expands. */
    public final int depth;

    /** How many elements of a list, array or map a rendering shows. */
    public final int limit;

    /**
     * An inspector with the given limits.
     *
     * @param depth levels of nested objects to expand, 0 for a one-line summary per value
     * @param limit elements of a list, array or map to show
     */
    public NodeInspector(int depth, int limit) {
        this.depth = depth;
        this.limit = limit;
    }

    /**
     * A record's view: its type, timing, children, inputs and their sources ({@code path.port}
     * or the literal), outputs and the node instance's public fields, expanded to {@link #depth}.
     *
     * @param record the node-tree record
     * @return the view as JSON
     */
    public JsonObject node(GraphNodeContext record) {
        JsonObject view = new JsonObject();
        view.addProperty("node", record.path);
        view.addProperty(TYPE, record.type);
        view.addProperty("ms", record.elapsedMs());
        view.addProperty("peak_heap_mib", Math.round(record.peakHeapBytes / HeapSampler.BYTES_PER_MIB));
        JsonArray children = new JsonArray();
        for (GraphNodeContext child : record.children) {
            children.add(child.path);
        }
        view.add("children", children);
        view.add("inputs", renderMap(new TreeMap<>(record.getInputsSnapshot()), depth));
        JsonObject sources = new JsonObject();
        for (Map.Entry<String, Object> source : new TreeMap<>(record.inputSources).entrySet()) {
            sources.add(source.getKey(), source.getValue() instanceof PythonParser.NodeReference wire
                    ? new JsonPrimitive(wire.nodeId + NodeGraphRuntime.PORT_SEPARATOR + wire.portName)
                    : render(source.getValue(), 0));
        }
        view.add("input_sources", sources);
        view.add("outputs", renderMap(new TreeMap<>(record.getOutputsSnapshot()), depth));
        if (record.node != null) {
            view.add("fields", renderFields(record.node, depth));
        }
        return view;
    }

    /**
     * Follows a field path from a record, e.g. {@code traces[127].state}: the first name is an
     * output port, an input port, or the first non-null public field of that name on the node or
     * its outputs; later names are public fields, and subscripts index lists, arrays and maps.
     *
     * @param record    the node-tree record the path starts at
     * @param fieldPath dotted path with optional subscripts
     * @throws IllegalArgumentException when a step names nothing, naming what exists there
     * @return the value at the path, rendered to {@link #depth}
     */
    public JsonElement walk(GraphNodeContext record, String fieldPath) {
        Object current = null;
        String walked = record.path;
        boolean first = true;
        for (String step : fieldPath.split("\\.")) {
            Matcher matcher = FIELD_NAME_THEN_SUBSCRIPTS.matcher(step);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("cannot parse step '" + step + "' of " + fieldPath);
            }
            String name = matcher.group(1);
            if (name == null && first) {
                throw new IllegalArgumentException("a field path starts with a port or field name");
            }
            if (name != null && !first) {
                current = field(current, name, walked);
            } else if (name != null && record.hasOutput(name)) {
                current = record.getOutput(name);
            } else if (name != null && record.getInputsSnapshot().containsKey(name)) {
                current = record.getInputValue(name);
            } else if (name != null) {
                // The first non-null public field of that name on the node or its outputs.
                List<Object> owners = new ArrayList<>();
                if (record.node != null) {
                    owners.add(record.node);
                }
                owners.addAll(new TreeMap<>(record.getOutputsSnapshot()).values());
                boolean found = false;
                for (Object owner : owners) {
                    if (current == null && owner != null && fieldNamed(owner.getClass(), name) != null) {
                        found = true;
                        current = field(owner, name, record.path);
                    }
                }
                if (!found) {
                    throw new IllegalArgumentException("no port or field '" + name + "' on "
                            + record.path + "; outputs " + new TreeMap<>(record.getOutputsSnapshot()).keySet()
                            + ", inputs " + record.getInputsSnapshot().keySet()
                            + (record.node == null ? "" : ", fields " + names(record.node.getClass())));
                }
            }
            if (name != null) {
                walked += (first ? " " : FIELD_SEPARATOR) + name;
            }
            first = false;
            Matcher subscripts = SUBSCRIPT.matcher(matcher.group(2));
            while (subscripts.find()) {
                // A list or array index, or a map key compared as text.
                String key = subscripts.group(1).trim();
                if (current instanceof Map<?, ?> map) {
                    Object element = null;
                    boolean found = false;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!found && String.valueOf(entry.getKey()).equals(key)) {
                            element = entry.getValue();
                            found = true;
                        }
                    }
                    if (!found) {
                        throw new IllegalArgumentException(walked + " has no key " + key
                                + " among its " + map.size() + " entries");
                    }
                    current = element;
                } else {
                    int position;
                    try {
                        position = Integer.parseInt(key);
                    } catch (NumberFormatException notAnIndex) {
                        throw new IllegalArgumentException(walked + "[" + key + "]: not an integer index");
                    }
                    int size;
                    if (current instanceof List<?> list) {
                        size = list.size();
                    } else if (current != null && current.getClass().isArray()) {
                        size = Array.getLength(current);
                    } else {
                        throw new IllegalArgumentException(walked + " ("
                                + (current == null ? "null" : current.getClass().getSimpleName())
                                + ") cannot be indexed");
                    }
                    if (position < 0 || position >= size) {
                        throw new IllegalArgumentException("index " + position + " out of range: "
                                + walked + " has " + size + " elements");
                    }
                    current = current instanceof List<?> list ? list.get(position)
                            : Array.get(current, position);
                }
                walked += "[" + key + "]";
            }
        }
        return render(current, depth);
    }

    /**
     * Renders any value: primitives as themselves, containers with their size and first
     * {@link #limit} elements, objects with their public fields, down to {@code remaining} levels.
     *
     * @param value     the value
     * @param remaining levels still to expand; at 0 a container or object is a one-line summary
     * @return the rendering
     */
    public JsonElement render(Object value, int remaining) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Boolean flag) {
            return new JsonPrimitive(flag);
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) {
            return new JsonPrimitive(value.toString());
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> items = new ArrayList<>();
            for (int index = 0; index < Math.min(length, limit); index++) {
                items.add(Array.get(value, index));
            }
            return renderSequence(value.getClass().getComponentType().getSimpleName() + ARRAY_SUFFIX,
                    length, items, remaining);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> items = new ArrayList<>();
            for (Object item : collection) {
                if (items.size() >= limit) {
                    break;
                }
                items.add(item);
            }
            return renderSequence(value.getClass().getSimpleName(), collection.size(), items, remaining);
        }
        if (value instanceof Map<?, ?> map) {
            if (remaining <= 0) {
                return new JsonPrimitive(value.getClass().getSimpleName() + "{" + map.size() + "}");
            }
            JsonObject rendered = new JsonObject();
            rendered.addProperty(TYPE, value.getClass().getSimpleName());
            rendered.addProperty(SIZE, map.size());
            rendered.add("entries", renderMap(map, remaining - 1));
            return rendered;
        }
        if (remaining <= 0) {
            return new JsonPrimitive(value.getClass().getSimpleName());
        }
        JsonObject rendered = renderFields(value, remaining - 1);
        rendered.addProperty(TYPE, value.getClass().getSimpleName());
        return rendered;
    }

    private JsonElement renderSequence(String type, int size, List<Object> items, int remaining) {
        if (remaining <= 0) {
            return new JsonPrimitive(type.replace(ARRAY_SUFFIX, "") + "[" + size + "]");
        }
        JsonObject rendered = new JsonObject();
        rendered.addProperty(TYPE, type);
        rendered.addProperty(SIZE, size);
        JsonArray shown = new JsonArray();
        for (Object item : items) {
            shown.add(render(item, remaining - 1));
        }
        rendered.add("items", shown);
        return rendered;
    }

    private JsonObject renderMap(Map<?, ?> map, int remaining) {
        JsonObject rendered = new JsonObject();
        int shown = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (shown++ >= limit) {
                break;
            }
            rendered.add(String.valueOf(entry.getKey()), render(entry.getValue(), remaining));
        }
        return rendered;
    }

    private JsonObject renderFields(Object value, int remaining) {
        JsonObject rendered = new JsonObject();
        for (Field field : publicFields(value.getClass())) {
            try {
                rendered.add(field.getName(), render(field.get(value), remaining));
            } catch (IllegalAccessException | RuntimeException unreadable) {
                rendered.addProperty(field.getName(), "<unreadable: " + unreadable + ">");
            }
        }
        return rendered;
    }

    private static Object field(Object owner, String name, String walked) {
        if (owner == null) {
            throw new IllegalArgumentException(walked + " is null; it has no field '" + name + "'");
        }
        Field field = fieldNamed(owner.getClass(), name);
        if (field == null) {
            throw new IllegalArgumentException(walked + " (" + owner.getClass().getSimpleName()
                    + ") has no public field '" + name + "'; fields: " + names(owner.getClass()));
        }
        try {
            return field.get(owner);
        } catch (IllegalAccessException unreadable) {
            throw new IllegalArgumentException("cannot read " + walked + FIELD_SEPARATOR + name,
                    unreadable);
        }
    }

    private static Field fieldNamed(Class<?> type, String name) {
        for (Field field : publicFields(type)) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return null;
    }

    private static List<Field> publicFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static List<String> names(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field field : publicFields(type)) {
            names.add(field.getName());
        }
        return names;
    }
}
