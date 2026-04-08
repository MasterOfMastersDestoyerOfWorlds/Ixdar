package ixdar.platform.automation;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Utility for probing available loopback ports.
 * 
 * This class provides methods to find available ports on localhost,
 * supporting fallback strategies for resilient automation server startup.
 */
public final class PortProber {

    private PortProber() {
        // Utility class - prevent instantiation
    }

    /**
     * Finds an available port on localhost.
     * 
     * Tries the specified port first, then falls back to ports in the range
     * [fallbackStart, fallbackEnd], and finally attempts to bind to port 0
     * for an ephemeral port assignment.
     * 
     * @param preferredPort The preferred port to try first
     * @param fallbackStart Start of the fallback port range (inclusive)
     * @param fallbackEnd End of the fallback port range (inclusive)
     * @return An available port number
     * @throws IOException If no port is available after exhausting all options
     */
    public static int findAvailablePort(int preferredPort, int fallbackStart, int fallbackEnd) throws IOException {
        // Try preferred port first
        if (isPortAvailable(preferredPort)) {
            return preferredPort;
        }

        // Try ports in the fallback range
        for (int port = fallbackStart; port <= fallbackEnd; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }

        // Try ephemeral port (port 0)
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /**
     * Checks if a specific port is available on localhost.
     * 
     * @param port The port number to check
     * @return true if the port is available, false otherwise
     * @throws IOException If an error occurs while checking
     */
    public static boolean isPortAvailable(int port) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            // Port is in use or cannot be bound
            return false;
        }
    }

    /**
     * Finds an available port in a bounded range.
     * 
     * @param start Start of the port range (inclusive)
     * @param end End of the port range (inclusive)
     * @return An available port number
     * @throws IOException If no port is available in the range
     */
    public static int findAvailablePortInRange(int start, int end) throws IOException {
        for (int port = start; port <= end; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        throw new IOException("No available ports in range [" + start + ", " + end + "]");
    }

    /**
     * Finds an ephemeral port assigned by the OS.
     * 
     * @return An ephemeral port number
     * @throws IOException If no ephemeral port can be assigned
     */
    public static int findEphemeralPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
