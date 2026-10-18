/**
 * File access helpers: `FileManagement` resolves solution and asset paths (the asset repo via
 * `IXDAR_ASSET_REPO_ROOT`) and appends lines, delegating raw I/O to the `Platform`; `TextFile` is
 * the logical file. The `.ix` format itself lives in `geometry/tsp`.
 */
package ixdar.platform.file;
