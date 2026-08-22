/**
 * `AutomationRuntime`: the singleton owning the canvas, recorder, replay engine, and the render-
 * thread marshalling every endpoint uses (anything touching GL goes through its callable queue).
 * Plus `/health` and `/shutdown`.
 */
package ixdar.platform.automation.endpoints;
