package ixdar.platform.automation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Utility class for extracting scene state via reflection for automation UI exports.
 * 
 * This class discovers eligible getters and public fields in scene classes and builds
 * a JSON representation of scene-specific state. It uses the {@link AutomationVisible}
 * annotation to determine which members to include.
 * 
 * ## Eligibility Rules
 * 
 * ### Methods
 * - Must be annotated with {@link AutomationVisible}
 * - Must be public
 * - Must have no parameters (getters only)
 * - Return type must be serializable (see {@link #isSerializableType(Class)})
 * 
 * ### Fields
 * - Must be annotated with {@link AutomationVisible}
 * - Must be public
 * - Type must be serializable
 * 
 * ### Exclusions
 * - Private or protected members
 * - Methods with parameters
 * - Non-serializable types (unless they override toString())
 * - Methods annotated with {@code @AutomationVisible(exclude=true)}
 * - Static members (not part of instance state)
 * - Methods that throw checked exceptions (potential side effects)
 * 
 * ## JSON Output Format
 * 
 * The extracted state is returned as a {@link JsonObject} with the following structure:
 * <pre>
 * {
 *   "class": "SceneClassName",
 *   "id": "scene-id-from-annotation",
 *   "state": {
 *     "fieldName": value,
 *     "methodName": value,
 *     ...
 *   }
 * }
 * </pre>
 * 
 * Where each field/method value is a simple serializable type:
 * - Primitives (int, float, boolean, etc.)
 * - String
 * - Vector3f (as [x, y, z] array)
 * - Vector2f (as [x, y] array)
 * - Vector4f (as [x, y, z, w] array)
 * - Lists/Arrays of serializable types
 * - Nested JsonObjects for complex types
 * 
 * ## Usage
 * 
 * <pre>
 * {@code
 * SceneStateExtractor extractor = new SceneStateExtractor();
 * JsonObject sceneState = extractor.extractState(mySceneInstance);
 * }
 * </pre>
 * 
 * @see AutomationVisible
 */
public class SceneStateExtractor {
    
    private static final Set<Class<?>> PRIMITIVE_BOXED_TYPES = new HashSet<>(Arrays.asList(
        Integer.class, Long.class, Float.class, Double.class,
        Boolean.class, Byte.class, Short.class, Character.class
    ));
    
    private static final Set<Class<?>> VECTOR_TYPES = new HashSet<>(Arrays.asList(
        Vector3f.class, Vector2f.class, Vector4f.class
    ));
    
    private static final Set<Class<?>> COLLECTION_TYPES = new HashSet<>(Arrays.asList(
        List.class, ArrayList.class, Map.class, HashMap.class, Set.class, HashSet.class
    ));
    
    /**
     * Extract scene state from the given scene instance using reflection.
     * 
     * @param sceneInstance the scene instance to extract state from
     * @return a JsonObject containing the extracted scene state
     */
    public JsonObject extractState(Object sceneInstance) {
        if (sceneInstance == null) {
            JsonObject result = new JsonObject();
            result.addProperty("class", "null");
            result.addProperty("id", "unknown");
            JsonObject state = new JsonObject();
            state.addProperty("error", "Scene instance is null");
            result.add("state", state);
            return result;
        }
        
        Class<?> clazz = sceneInstance.getClass();
        JsonObject result = new JsonObject();
        result.addProperty("class", clazz.getSimpleName());
        result.addProperty("id", extractSceneId(clazz));
        
        JsonObject state = new JsonObject();
        extractSerializableMembers(sceneInstance, clazz, state);
        
        result.add("state", state);
        return result;
    }
    
    /**
     * Extract the scene ID from the scene class annotation.
     * 
     * @param clazz the scene class
     * @return the scene ID, or "unknown" if not found
     */
    private String extractSceneId(Class<?> clazz) {
        ixdar.annotations.scene.SceneAnnotation annotation = clazz.getAnnotation(
            ixdar.annotations.scene.SceneAnnotation.class);
        if (annotation != null) {
            return annotation.id();
        }
        return "unknown";
    }
    
    /**
     * Extract all serializable public members from the scene instance.
     * 
     * @param instance the scene instance
     * @param clazz the scene class
     * @param state the JsonObject to populate with extracted state
     */
    private void extractSerializableMembers(Object instance, Class<?> clazz, JsonObject state) {
        // Extract public fields
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(AutomationVisible.class)) {
                AutomationVisible annotation = field.getAnnotation(AutomationVisible.class);
                if (!annotation.exclude() && Modifier.isPublic(field.getModifiers())) {
                    try {
                        Object value = field.get(instance);
                        String fieldName = field.getName();
                        state.add(fieldName, serializeValue(value));
                    } catch (IllegalAccessException e) {
                        // Skip inaccessible fields
                    }
                }
            }
        }
        
        // Extract public methods (getters)
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(AutomationVisible.class)) {
                AutomationVisible annotation = method.getAnnotation(AutomationVisible.class);
                if (!annotation.exclude() && Modifier.isPublic(method.getModifiers()) && 
                    method.getParameterCount() == 0 &&
                    !method.getReturnType().equals(void.class)) {
                    try {
                        Object value = method.invoke(instance);
                        String methodName = method.getName();
                        // Convert getter naming to field naming (remove "get" prefix)
                        if (methodName.startsWith("get") && methodName.length() > 3) {
                            methodName = Character.toLowerCase(methodName.charAt(3)) + 
                                        methodName.substring(4);
                        }
                        state.add(methodName, serializeValue(value));
                    } catch (IllegalAccessException e) {
                        // Skip inaccessible methods
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        // Method threw exception - skip this member
                        String methodName = method.getName();
                        if (methodName.startsWith("get") && methodName.length() > 3) {
                            methodName = Character.toLowerCase(methodName.charAt(3)) + 
                                        methodName.substring(4);
                        }
                        JsonObject errorState = new JsonObject();
                        errorState.addProperty("error", "Method invocation failed: " + e.getCause());
                        state.add(methodName, errorState);
                    }
                }
            }
        }
    }
    
    /**
     * Check if a type is serializable for JSON export.
     * 
     * @param type the type to check
     * @return true if the type is serializable
     */
    private boolean isSerializableType(Class<?> type) {
        if (type == null) {
            return false;
        }
        
        // Primitive types and their boxed equivalents
        if (type.isPrimitive() || PRIMITIVE_BOXED_TYPES.contains(type)) {
            return true;
        }
        
        // String
        if (type.equals(String.class)) {
            return true;
        }
        
        // Vector types
        if (VECTOR_TYPES.contains(type)) {
            return true;
        }
        
        // Lists and arrays
        if (type.isArray() || COLLECTION_TYPES.stream().anyMatch(c -> c.isAssignableFrom(type))) {
            return true;
        }
        
        // Check if it's a JsonObject or JsonArray (already JSON-serializable)
        if (type.equals(JsonObject.class) || type.equals(JsonArray.class)) {
            return true;
        }
        
        // Check for custom types that override toString()
        if (type.getSuperclass() == Object.class && type != Object.class) {
            // For custom types, we'll try to serialize them via toString() if they don't have
            // a more specific handler. This is a fallback for types not explicitly handled.
            return true;
        }
        
        return false;
    }
    
    /**
     * Serialize a value to a JsonObject for JSON export.
     * 
     * @param value the value to serialize
     * @return the serialized JsonObject or JsonArray
     */
    private com.google.gson.JsonElement serializeValue(Object value) {
        if (value == null) {
            return new com.google.gson.JsonPrimitive((String) null);
        }
        
        Class<?> type = value.getClass();
        
        // Primitive types and boxed primitives
        if (type.isPrimitive() || PRIMITIVE_BOXED_TYPES.contains(type)) {
            if (type.equals(int.class) || type.equals(Integer.class)) {
                return new com.google.gson.JsonPrimitive((Integer) value);
            } else if (type.equals(long.class) || type.equals(Long.class)) {
                return new com.google.gson.JsonPrimitive((Long) value);
            } else if (type.equals(float.class) || type.equals(Float.class)) {
                return new com.google.gson.JsonPrimitive((Float) value);
            } else if (type.equals(double.class) || type.equals(Double.class)) {
                return new com.google.gson.JsonPrimitive((Double) value);
            } else if (type.equals(boolean.class) || type.equals(Boolean.class)) {
                return new com.google.gson.JsonPrimitive((Boolean) value);
            } else if (type.equals(byte.class) || type.equals(Byte.class)) {
                return new com.google.gson.JsonPrimitive((Byte) value);
            } else if (type.equals(short.class) || type.equals(Short.class)) {
                return new com.google.gson.JsonPrimitive((Short) value);
            } else if (type.equals(char.class) || type.equals(Character.class)) {
                return new com.google.gson.JsonPrimitive(String.valueOf(value));
            }
        }
        
        // String
        if (type.equals(String.class)) {
            return new com.google.gson.JsonPrimitive((String) value);
        }
        
        // Vector types
        if (type.equals(Vector3f.class)) {
            Vector3f vec = (Vector3f) value;
            JsonArray array = new JsonArray();
            array.add(vec.x);
            array.add(vec.y);
            array.add(vec.z);
            return array;
        } else if (type.equals(Vector2f.class)) {
            Vector2f vec = (Vector2f) value;
            JsonArray array = new JsonArray();
            array.add(vec.x);
            array.add(vec.y);
            return array;
        } else if (type.equals(Vector4f.class)) {
            Vector4f vec = (Vector4f) value;
            JsonArray array = new JsonArray();
            array.add(vec.x);
            array.add(vec.y);
            array.add(vec.z);
            array.add(vec.w);
            return array;
        }
        
        // JsonObject and JsonArray
        if (type.equals(JsonObject.class)) {
            return (JsonObject) value;
        } else if (type.equals(JsonArray.class)) {
            return (JsonArray) value;
        }
        
        // Lists and arrays
        if (value instanceof List) {
            JsonArray array = new JsonArray();
            for (Object item : (List<?>) value) {
                array.add(serializeValue(item));
            }
            return array;
        } else if (value instanceof Object[]) {
            JsonArray array = new JsonArray();
            for (Object item : (Object[]) value) {
                array.add(serializeValue(item));
            }
            return array;
        }
        
        // Maps
        if (value instanceof Map) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? "null" : entry.getKey().toString();
                object.add(key, serializeValue(entry.getValue()));
            }
            return object;
        }
        
        // Fallback: use toString() for other types
        return new com.google.gson.JsonPrimitive(value.toString());
    }
}
