package unit.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import ixdar.canvas.Canvas3D;
import ixdar.platform.automation.AutomationApiServer;
import ixdar.platform.automation.AutomationRuntime;
import ixdar.platform.automation.PortProber;

public class AutomationRuntimePortBindingTest {

    private AutomationRuntime runtime;
    private AutomationApiServer boundServer;
    private Field serverField;

    @BeforeEach
    public void setUp() throws Exception {
        // Get the singleton instance
        runtime = AutomationRuntime.get();
        
        // Use reflection to access the private server field for testing
        serverField = AutomationRuntime.class.getDeclaredField("server");
        serverField.setAccessible(true);
        
        // Clean up any existing server
        boundServer = (AutomationApiServer) serverField.get(runtime);
        if (boundServer != null) {
            boundServer.stop();
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        boundServer = (AutomationApiServer) serverField.get(runtime);
        if (boundServer != null) {
            boundServer.stop();
        }
        runtime.stop();
    }

    @Test
    public void testGetBoundPortReturnsPort() throws Exception {
        // Start runtime with a specific port
        int testPort = 58888;
        
        // Create server directly to test port binding
        AutomationApiServer testServer = new AutomationApiServer(runtime, testPort);
        testServer.start();
        
        serverField.set(runtime, testServer);
        runtime.started = true;
        
        try {
            assertEquals(testPort, runtime.getBoundPort(), "Bound port should match configured port");
        } finally {
            testServer.stop();
            serverField.set(runtime, null);
            runtime.started = false;
        }
    }

    @Test
    public void testHealthEndpointIncludesPort() throws Exception {
        // Start runtime with a specific port
        int testPort = 58887;
        
        AutomationApiServer testServer = new AutomationApiServer(runtime, testPort);
        testServer.start();
        
        serverField.set(runtime, testServer);
        runtime.started = true;
        
        try {
            JsonObject health = runtime.health();
            assertEquals(testPort, health.get("port").getAsInt(), "Health endpoint should include bound port");
            assertEquals("ok", health.get("status").getAsString(), "Health status should be ok");
        } finally {
            testServer.stop();
            serverField.set(runtime, null);
            runtime.started = false;
        }
    }

    @Test
    public void testPortBindingUsesFallbackWhenPreferredInUse() throws Exception {
        int preferredPort = 58886;
        int fallbackStart = 58887;
        int fallbackEnd = 58896;
        
        // Bind to the preferred port to simulate it being in use
        try (java.net.ServerSocket occupiedSocket = new java.net.ServerSocket()) {
            occupiedSocket.setReuseAddress(true);
            occupiedSocket.bind(new java.net.InetSocketAddress("127.0.0.1", preferredPort));
            
            // Find available port should fall back
            int availablePort = PortProber.findAvailablePort(preferredPort, fallbackStart, fallbackEnd);
            
            assertTrue(availablePort >= fallbackStart && availablePort <= fallbackEnd,
                    "Should fall back to port in range when preferred port is in use");
        }
    }

    @Test
    public void testPortBindingUsesPreferredWhenAvailable() throws Exception {
        int preferredPort = 58885;
        int fallbackStart = 58886;
        int fallbackEnd = 58895;
        
        // Preferred port should be used if available
        int availablePort = PortProber.findAvailablePort(preferredPort, fallbackStart, fallbackEnd);
        assertEquals(preferredPort, availablePort, "Should use preferred port when available");
    }

    @Test
    public void testGetBoundPortReturnsNegativeWhenNotStarted() {
        // Ensure runtime is stopped
        runtime.stop();
        
        assertEquals(-1, runtime.getBoundPort(), "Bound port should be -1 when server is not started");
    }
}
