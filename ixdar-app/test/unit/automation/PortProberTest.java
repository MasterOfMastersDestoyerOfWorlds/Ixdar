package unit.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import ixdar.platform.automation.PortProber;

public class PortProberTest {

    @Test
    public void testIsPortAvailableOnUnusedPort() throws Exception {
        // Port 0 is ephemeral, so we use a high port that's unlikely to be in use
        int testPort = 59999;
        assertTrue(PortProber.isPortAvailable(testPort), "Port " + testPort + " should be available");
    }

    @Test
    public void testIsPortAvailableOnUsedPort() throws Exception {
        // Bind a socket to a port and verify it's detected as unavailable
        int testPort = 59998;
        try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", testPort));
            assertFalse(PortProber.isPortAvailable(testPort), "Port " + testPort + " should be unavailable when bound");
        }
    }

    @Test
    public void testFindAvailablePortInRange() throws Exception {
        int start = 59990;
        int end = 59999;
        int availablePort = PortProber.findAvailablePortInRange(start, end);
        assertTrue(availablePort >= start && availablePort <= end,
                "Found port " + availablePort + " should be in range [" + start + ", " + end + "]");
        assertTrue(PortProber.isPortAvailable(availablePort), "Found port should be available");
    }

    @Test
    public void testFindEphemeralPort() throws Exception {
        int ephemeralPort = PortProber.findEphemeralPort();
        // Ephemeral ports are typically in the range 49152-65535
        assertTrue(ephemeralPort >= 49152 && ephemeralPort <= 65535,
                "Ephemeral port " + ephemeralPort + " should be in ephemeral range");
        assertTrue(PortProber.isPortAvailable(ephemeralPort), "Ephemeral port should be available");
    }

    @Test
    public void testFindAvailablePortWithPreferredFallback() throws Exception {
        int preferredPort = 59980;
        int fallbackStart = 59981;
        int fallbackEnd = 59990;

        // First, bind to the preferred port to make it unavailable
        try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", preferredPort));

            // Find available port should fall back to the range
            int availablePort = PortProber.findAvailablePort(preferredPort, fallbackStart, fallbackEnd);
            assertTrue(availablePort >= fallbackStart && availablePort <= fallbackEnd,
                    "Found port " + availablePort + " should be in fallback range after preferred port is in use");
        }
    }

    @Test
    public void testFindAvailablePortUsesPreferredWhenAvailable() throws Exception {
        int preferredPort = 59970;
        int fallbackStart = 59971;
        int fallbackEnd = 59980;

        // Preferred port should be used if available
        int availablePort = PortProber.findAvailablePort(preferredPort, fallbackStart, fallbackEnd);
        assertEquals(preferredPort, availablePort, "Preferred port should be used when available");
    }

    @Test
    public void testFindAvailablePortFallsBackToEphemeral() throws Exception {
        int preferredPort = 59960;
        int fallbackStart = 59961;
        int fallbackEnd = 59969;

        // Bind to preferred and all fallback ports
        try (java.net.ServerSocket preferredSocket = new java.net.ServerSocket()) {
            preferredSocket.setReuseAddress(true);
            preferredSocket.bind(new java.net.InetSocketAddress("127.0.0.1", preferredPort));

            try (java.net.ServerSocket fallbackSocket = new java.net.ServerSocket()) {
                fallbackSocket.setReuseAddress(true);
                fallbackSocket.bind(new java.net.InetSocketAddress("127.0.0.1", fallbackEnd));

                // Should fall back to ephemeral port
                int availablePort = PortProber.findAvailablePort(preferredPort, fallbackStart, fallbackEnd);
                assertTrue(availablePort >= 49152 && availablePort <= 65535,
                        "Should fall back to ephemeral port when preferred and fallback range are exhausted");
            }
        }
    }
}
