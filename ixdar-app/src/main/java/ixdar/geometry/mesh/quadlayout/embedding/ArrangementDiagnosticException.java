package ixdar.geometry.mesh.quadlayout.embedding;

/**
 * A failure of the embedded arrangement that carries the geometry to show: the thrower names the
 * affected faces, paths and vertices once, so no catcher re-derives them from the message.
 */
public final class ArrangementDiagnosticException extends IllegalStateException {

    /** Groups of copy-mesh geometry the failure affects, for rendering. */
    public final transient ArrangementDiagnostic diagnostic;

    /**
     * Wraps a failure message with the geometry groups it is about.
     *
     * @param message    the failure description
     * @param diagnostic groups of copy-mesh geometry the failure affects
     */
    public ArrangementDiagnosticException(String message, ArrangementDiagnostic diagnostic) {
        super(message);
        this.diagnostic = diagnostic;
    }
}
