/**
 * The in-process HTTP automation server (loopback; a free port, published to
 * {@code tmp/automation.port} under the launching checkout) that
 * lets `ixdar-cli` and agents drive the editor. Routes come from the annotation-generated registry.
 * Desktop-only (`com.sun.net.httpserver`). `AutomationInputBinder` tees platform
 * input callbacks into the recorder.
 */
package ixdar.platform.automation;
