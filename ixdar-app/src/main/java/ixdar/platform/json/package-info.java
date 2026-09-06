/**
 * The platform-neutral JSON tree {@code Platform.parseJson} returns. {@link
 * ixdar.platform.json.JsonValue} is the only type callers touch; {@link
 * ixdar.platform.json.GsonJsonTree} builds it for the desktop platforms, and the web platform
 * builds the same shape from the browser's {@code JSON.parse}.
 */
package ixdar.platform.json;
