/**
 * The loopback HTTP automation server that lets `ixdar-cli` and agents drive the editor, and
 * `AutomationRuntime`, which owns it and marshals endpoint work onto the render thread. Desktop
 * only: the browser platform's `Platform.automation()` answers null.
 */
package ixdar.automation;
