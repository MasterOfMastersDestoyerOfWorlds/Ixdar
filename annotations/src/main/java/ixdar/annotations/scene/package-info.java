/**
 * Scene registration: `@SceneAnnotation` over `SceneDrawable`, whose `initGL`/`paintGL` default to
 * throwing rather than being abstract; a scene missing an override compiles and fails at first
 * frame.
 */
package ixdar.annotations.scene;
