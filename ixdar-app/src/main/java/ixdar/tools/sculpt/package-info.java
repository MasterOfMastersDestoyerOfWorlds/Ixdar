/**
 * The mesh viewer's stroke-sculpt tool: `StrokeSculptTool` draws weighted strokes on the live view
 * into a chain of `stroke_sculpt` passes with undo and redo, and saves them as DSL statements plus
 * one SVG per pass; `SculptPass` is one pass, the tool's item and the unit its snapshots share.
 */
package ixdar.tools.sculpt;
