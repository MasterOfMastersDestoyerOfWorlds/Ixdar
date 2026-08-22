/**
 * The fan-out/join seam: `WorkerPool` with a threaded desktop implementation and an inline web one.
 * `ThreadWorkerPool` is deliberately the only class in the codebase naming `java.util.concurrent`
 * executors; a second reference elsewhere breaks the web build.
 */
package ixdar.platform.concurrent;
