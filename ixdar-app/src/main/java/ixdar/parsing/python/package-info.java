/**
 * Lexer and recursive-descent parser for the Python-flavored mesh DSL, producing the AST
 * `NodeGraphRuntime` executes. Deliberately lenient: unknown characters are skipped so LLM-
 * generated DSL stays parseable; malformed input degrades silently.
 */
package ixdar.parsing.python;
