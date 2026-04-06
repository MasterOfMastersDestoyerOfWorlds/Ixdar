# Automation Scene State Export

This document describes how scene authors can expose scene-specific state for automation testing and debugging via the reflection-based export mechanism.

## Overview

The `AutomationRuntime.uiState()` endpoint now uses a reflection-based approach to automatically discover and export scene state. This eliminates the need for hard-coded per-scene branches in `AutomationRuntime` when adding new scenes or new state fields.

## How It Works

When `AutomationRuntime.uiState()` is called:

1. A `SceneStateExtractor` instance is created
2. The current active scene (`canvas`) is passed to `extractState()`
3. The extractor uses reflection to discover:
   - Public methods annotated with `@AutomationVisible` (getters)
   - Public fields annotated with `@AutomationVisible`
4. The extracted state is serialized to JSON and included in the response

## Exported JSON Structure

The scene state is included in the UI state response under the `sceneState` key:

```json
{
  "sceneState": {
    "class": "SceneClassName",
    "id": "scene-id-from-annotation",
    "state": {
      "fieldName": value,
      "methodName": value,
      ...
    }
  }
}
```

### Special Case: TradeScene

The TradeScene is not a Scene subclass, so it is exported separately under `tradeState`:

```json
{
  "tradeState": {
    "class": "TradeScene",
    "id": "unknown",  // TradeScene doesn't have a SceneAnnotation
    "state": {
      "network": {...},
      "hoveredCity": {...},
      "gold": 100,
      "hqPickerTool": {...},
      "routePlanningTool": {...},
      "activeTool": {...},
      "headquartersCity": {...}
    }
  }
}
```

### Complete UI State Example

```json
{
  "timestamp": "2024-01-01T00:00:00Z",
  "windowWidth": 1920,
  "windowHeight": 1080,
  "framebufferWidth": 1920,
  "framebufferHeight": 1080,
  "menuVisible": false,
  "sceneId": "mesh-viewer",
  "sceneClass": "MeshNodeViewerScene",
  "mode": "main",
  "sceneState": {
    "class": "MeshNodeViewerScene",
    "id": "mesh-viewer",
    "state": {
      "meshVertexCount": 1234,
      "meshEdgeCount": 2345,
      "meshFaceCount": 1111,
      "meshRadius": 2.5,
      "meshCenter": [0.0, 0.0, 0.0],
      "boundingBoxMin": [-1.0, -1.0, -1.0],
      "boundingBoxMax": [1.0, 1.0, 1.0],
      "closed": true,
      "eulerCharacteristic": 2,
      "boundaryEdgeCount": 0,
      "degenerateFaceCount": 0
    }
  },
  "tradeState": {
    "class": "TradeScene",
    "id": "unknown",
    "state": {
      "gold": 100,
      "hoveredCity": null,
      "activeTool": "headquarters-picker"
    }
  },
  "textElements": [...],
  "menuItems": [...],
  "audio": {...}
}
```

## Marking Members for Export

Scene authors should annotate public getters and fields with `@AutomationVisible`:

```java
import ixdar.platform.automation.AutomationVisible;

public class MyScene extends Scene {
    
    /**
     * Export this getter's value to automation clients.
     */
    @AutomationVisible(description = "Total number of vertices in the mesh")
    public int getMeshVertexCount() {
        return mesh == null ? 0 : mesh.vertexCount();
    }
    
    /**
     * Export this field's value to automation clients.
     */
    @AutomationVisible(description = "Random seed used for grid generation")
    public long seed;
    
    /**
     * This getter is NOT exported (no annotation).
     */
    public int getInternalCounter() {
        return internalCounter;
    }
}
```

## Eligibility Rules

### Methods (Getters)
- Must be annotated with `@AutomationVisible`
- Must be `public`
- Must have **no parameters** (getters only)
- Return type must be serializable (see below)

### Fields
- Must be annotated with `@AutomationVisible`
- Must be `public`
- Type must be serializable

### Excluded Members
The following members are automatically excluded:
- Private or protected members
- Methods with parameters
- Non-serializable types (unless they override `toString()`)
- Methods annotated with `@AutomationVisible(exclude=true)`
- Static members (not part of instance state)
- Methods that throw checked exceptions (potential side effects)

## Serializable Types

The following types are automatically serialized:

| Type | JSON Representation |
|------|---------------------|
| `int`, `Integer` | JSON number |
| `long`, `Long` | JSON number |
| `float`, `Float` | JSON number |
| `double`, `Double` | JSON number |
| `boolean`, `Boolean` | JSON boolean |
| `byte`, `Byte` | JSON number |
| `short`, `Short` | JSON number |
| `char`, `Character` | JSON string (single character) |
| `String` | JSON string |
| `Vector3f` | JSON array `[x, y, z]` |
| `Vector2f` | JSON array `[x, y]` |
| `Vector4f` | JSON array `[x, y, z, w]` |
| `List<T>` | JSON array of serialized items |
| `Map<K,V>` | JSON object with string keys |
| Custom objects | JSON string (via `toString()`) |

## Annotation Parameters

The `@AutomationVisible` annotation supports the following parameters:

```java
@AutomationVisible(
    description = "Optional description of this member",
    exclude = false  // Set to true to temporarily exclude from export
)
```

### Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `description` | `String` | `""` | Human-readable description of what this member represents |
| `exclude` | `boolean` | `false` | If `true`, the member is excluded from export even if annotated |

## Migration from Old Approach

If your scene previously had hard-coded export logic in `AutomationRuntime.uiState()`:

### Before (Hard-coded in AutomationRuntime)

```java
if (canvas instanceof MeshNodeViewerScene) {
    MeshNodeViewerScene meshScene = (MeshNodeViewerScene) canvas;
    JsonObject mesh = new JsonObject();
    mesh.addProperty("vertexCount", meshScene.getMeshVertexCount());
    mesh.addProperty("edgeCount", meshScene.getMeshEdgeCount());
    mesh.addProperty("faceCount", meshScene.getMeshFaceCount());
    // ... more properties
    root.add("mesh", mesh);
}
```

### After (Annotate in Scene)

```java
public class MeshNodeViewerScene extends Scene {
    
    @AutomationVisible(description = "Total number of vertices in the mesh")
    public int getMeshVertexCount() {
        return mesh == null ? 0 : mesh.vertexCount();
    }
    
    @AutomationVisible(description = "Total number of edges in the mesh")
    public int getMeshEdgeCount() {
        return mesh == null ? 0 : mesh.edgeCount();
    }
    
    @AutomationVisible(description = "Total number of faces in the mesh")
    public int getMeshFaceCount() {
        return mesh == null ? 0 : mesh.faceCount();
    }
    
    // ... other annotated getters
}
```

The `AutomationRuntime.uiState()` method will automatically discover and export these members.

## Testing Your Changes

After adding annotations, verify the export by calling the `/ui/state` endpoint:

```bash
curl http://127.0.0.1:47832/ui/state | jq '.sceneState'
```

You should see your annotated members in the `state` object.

## Best Practices

1. **Use descriptive names**: Annotated getters should have clear, self-documenting names
2. **Add descriptions**: Always provide a `description` parameter for better documentation
3. **Keep getters simple**: Avoid complex computations or side effects in exported getters
4. **Use `exclude=true` for temporary exclusion**: If you need to disable export temporarily, use `exclude=true` instead of removing the annotation
5. **Document non-exported members**: If a getter is not annotated, consider adding a comment explaining why

## Examples

### MeshViewerScene Example

```java
@AutomationVisible(description = "Total number of vertices in the mesh")
public int getMeshVertexCount() {
    return mesh == null ? 0 : mesh.vertexCount();
}

@AutomationVisible(description = "Whether the mesh is closed (has no boundary edges)")
public boolean isMeshClosed() {
    return mesh != null && getMeshBoundaryEdgeCount() == 0;
}

@AutomationVisible(description = "Center point of the mesh in 3D space")
public Vector3f getMeshCenter() {
    return mesh == null ? new Vector3f() : mesh.center(new Vector3f());
}
```

### IrregularGridScene Example

```java
@AutomationVisible(description = "Random seed used to generate the irregular grid")
public long getSeed() {
    return SEED;
}

@AutomationVisible(description = "Number of relaxation iterations performed during grid generation")
public int getRelaxIters() {
    return RELAX_ITERS;
}

@AutomationVisible(description = "Standard deviation of horizontal edge lengths")
public float getHorizontalEdgeStdDev() {
    return grid == null ? 0f : grid.horizontalEdgeStdDev();
}
```

## Troubleshooting

### Member Not Appearing in Export

1. Check that the member is `public`
2. Check that the member has the `@AutomationVisible` annotation
3. Check that the return type is serializable
4. Check that the method has no parameters (for getters)
5. Check that `exclude=false` (or not set)

### Method Invocation Error

If a method throws an exception during invocation, the error will be included in the export:

```json
{
  "sceneState": {
    "state": {
      "problematicGetter": {
        "error": "Method invocation failed: NullPointerException"
      }
    }
  }
}
```

Fix the underlying issue in the getter method.

## API Compatibility

The new reflection-based approach is **backward compatible** with existing automation clients. The `sceneState` object is now included in the response, and the structure is stable and machine-readable for CLI consumption.

Scene authors are responsible for:
- Annotating members they want exported
- Ensuring getters are safe to call (no exceptions, no side effects)
- Documenting exported members via the `description` parameter

The `AutomationRuntime` no longer needs to be updated when scenes add new exported members.
