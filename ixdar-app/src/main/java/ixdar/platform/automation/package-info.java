/**
 * The in-process HTTP automation server (127.0.0.1:47832, `ixdar.automation.port` to override) that
 * lets `ixdar-cli` and agents drive the editor. Routes come from the annotation-generated registry.
 * Desktop-only (`com.sun.net.httpserver`, `javax.imageio`). `AutomationInputBinder` tees platform
 * input callbacks into the recorder.
 */
package ixdar.platform.automation;
