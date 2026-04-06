package ixdar.platform.automation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for scene members that should be included in automation UI state exports.
 * 
 * When AutomationRuntime extracts scene state via reflection, only methods and fields
 * annotated with @AutomationVisible will be included in the export.
 * 
 * Scene authors should annotate:
 * - Parameterless getter methods that return serializable types (primitives, String, Vector3f, etc.)
 * - Public fields that hold meaningful scene state
 * 
 * This annotation enables a generic, reflection-based approach to scene state export
 * without requiring per-scene branches in AutomationRuntime.uiState().
 * 
 * Example usage in a scene class:
 * <pre>
 * {@code
 * @AutomationVisible
 * public int getMeshVertexCount() {
 *     return mesh == null ? 0 : mesh.vertexCount();
 * }
 * 
 * @AutomationVisible
 * public float getMeshRadius() {
 *     return mesh == null ? 0f : mesh.radius();
 * }
 * }
 * </pre>
 * 
 * @see SceneStateExtractor
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
@Documented
public @interface AutomationVisible {
    /**
     * Optional description of what this member represents in the scene state export.
     * This is included in the JSON output for documentation purposes.
     */
    String description() default "";
    
    /**
     * If true, this member will be excluded from the export even if annotated.
     * Useful for temporarily disabling export of expensive or noisy members.
     */
    boolean exclude() default false;
}
