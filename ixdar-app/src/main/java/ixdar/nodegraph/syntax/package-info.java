/**
 * Lexer and recursive-descent parser for the Python-flavored mesh DSL, producing the syntax
 * `GroupLibrary` loads into node groups and the source tools read and rewrite. Deliberately
 * lenient: unknown characters are skipped so LLM-generated DSL stays parseable; malformed input
 * degrades silently.
 */
package ixdar.nodegraph.syntax;
