# Automation Server Port Binding

## Overview

The Ixdar automation server provides a resilient port binding strategy that ensures the automation API remains available even when the default port is occupied. This is essential for:

- **Local development**: Multiple instances can run in parallel without port conflicts
- **CI/CD pipelines**: Automated tests can run concurrently without port reservation
- **Deterministic testing**: Optional fixed port configuration for reproducible test runs

## Port Binding Strategy

When the automation server starts, it follows this port selection algorithm:

1. **Configured port** (optional): Check for `ixdar.automation.port` system property
2. **Fallback range**: Try ports in the range `[47833, 47932]` (100 ports)
3. **Ephemeral port**: Fall back to OS-assigned ephemeral port (port 0)

### Configuration

#### Default Behavior

```bash
# No configuration - uses default port 47832 with fallback
java -jar Project-Gordian.jar
```

If port 47832 is occupied, the server will automatically bind to the next available port in the fallback range.

#### Fixed Port (Deterministic Tests)

```bash
# Force a specific port for reproducible tests
java -Dixdar.automation.port=9999 -jar Project-Gordian.jar
```

When a fixed port is specified via the system property, the server will:
- Use that port if available
- Fall back to the fallback range if the port is occupied
- Log a warning message when falling back

## Health Endpoint

The `/health` endpoint includes the actual bound port:

```json
{
  "status": "ok",
  "timestamp": "2024-01-15T10:30:00Z",
  "recording": false,
  "replaying": false,
  "port": 47832
}
```

This allows clients to discover the actual port even when fallback binding occurs.

## CLI Client Behavior

The Python automation CLI client (`ixdar_automation_cli/automation_client.py`) uses `DEFAULT_BASE_URL = "http://127.0.0.1:47832"` by default.

### Dynamic Port Discovery

To use the automation client with a fallback-bound port, query the health endpoint first:

```python
from automation_client import AutomationClient

# Query health to get actual port
client = AutomationClient(base_url="http://127.0.0.1:47832")
health = client.health()
actual_port = health['port']

# Reconfigure client with actual port
client.base_url = f"http://127.0.0.1:{actual_port}"
```

### Alternative: Environment Variable

Set `IXDAR_AUTOMATION_PORT` environment variable to override the default:

```bash
export IXDAR_AUTOMATION_PORT=9999
python cli_commands/my_command.py
```

(Implementation note: CLI can be extended to read this environment variable)

## Parallel Execution

The port binding strategy enables parallel automation test execution:

```bash
# Terminal 1
java -jar Project-Gordian.jar &
# Server binds to 47832

# Terminal 2 (started while Terminal 1 is running)
java -jar Project-Gordian.jar &
# Server binds to 47833 (next available in fallback range)

# Terminal 3
java -jar Project-Gordian.jar &
# Server binds to 47834
```

## Error Handling

If all ports in the fallback range are exhausted, the server falls back to an ephemeral port (OS-assigned). If even ephemeral binding fails (extremely rare), the server logs an error and does not start.

```
[Automation] Listening on http://127.0.0.1:47832
[Automation] Note: Bound to fallback port 47833 (configured port 47832 was in use)
```

## API Reference

### PortProber Utility

```java
public final class PortProber {
    // Check if a port is available
    public static boolean isPortAvailable(int port) throws IOException
    
    // Find available port with fallback strategy
    public static int findAvailablePort(int preferred, int fallbackStart, int fallbackEnd) throws IOException
    
    // Find available port in a specific range
    public static int findAvailablePortInRange(int start, int end) throws IOException
    
    // Get an ephemeral port
    public static int findEphemeralPort() throws IOException
}
```

### AutomationRuntime

```java
public class AutomationRuntime {
    // Get the bound port (-1 if server not started)
    public int getBoundPort()
    
    // Health endpoint data
    public JsonObject health()
    
    // Start automation server with automatic port binding
    public void start(Canvas3D canvas)
}
```

## Testing

Unit tests verify:
- Port availability detection
- Fallback to range when preferred port is occupied
- Ephemeral port fallback when range is exhausted
- Health endpoint includes correct port
- CLI client can discover actual port

Run tests:
```bash
cd ixdar-app
mvn test -Dtest=PortProberTest,AutomationRuntimePortBindingTest
```

## Related Files

- `ixdar-app/src/main/java/ixdar/platform/automation/PortProber.java` - Port probing utility
- `ixdar-app/src/main/java/ixdar/platform/automation/AutomationRuntime.java` - Runtime with port binding
- `ixdar-app/src/main/java/ixdar/platform/automation/AutomationApiServer.java` - HTTP server
- `ixdar-app/test/unit/automation/PortProberTest.java` - Port probing tests
- `ixdar-app/test/unit/automation/AutomationRuntimePortBindingTest.java` - Port binding tests
- `ixdar_automation_cli/automation_client.py` - Python CLI client
