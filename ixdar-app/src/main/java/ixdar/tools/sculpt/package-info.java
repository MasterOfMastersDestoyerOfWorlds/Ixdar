/**
 * The mesh viewer's stroke-sculpt tool: `StrokeSculptTool` draws weighted strokes on the live view
 * into the sculpt's one `stroke_sculpt` pass with undo and redo, and saves it as a DSL statement
 * plus its SVG; `SculptPass` is that pass, the tool's item and the unit its snapshots share.
 */
package ixdar.tools.sculpt;
