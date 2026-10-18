/**
 * Node-graph runtime: `NodeGraphRuntime` parses and executes .dsl sources against the one node
 * lookup (`REGISTERED_NODES`, the generated registries behind the `Class.forName` desktop
 * firewall), keeping each run as its node tree under `root` with a `revision` counter; node
 * groups, the group library and the heap sampler.
 */
package ixdar.nodegraph.runtime;
