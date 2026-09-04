package ixdar.graphics.render.model;

import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

/**
 * One vertex array with its vertex buffer and optional element buffer. Zero handles mean
 * nothing is uploaded; {@link #upload} frees whatever was there before.
 */
public final class VertexBuffer {

    public int vao;
    public int vbo;
    public int ebo;
    public int vertexCount;
    public int indexCount;

    /**
     * Replace the buffer contents; an empty vertex array just frees the old buffers.
     *
     * @param layout   attribute format of {@code vertices}
     * @param vertices interleaved vertex floats
     * @param indices  element indices, or {@code null} for a non-indexed buffer
     */
    public void upload(VertexLayout layout, float[] vertices, int[] indices) {
        delete();
        if (vertices.length == 0) {
            return;
        }
        GL gl = Platforms.gl();
        vao = gl.genVertexArrays();
        vbo = gl.genBuffers();
        gl.bindVertexArray(vao);
        gl.bindBuffer(gl.ARRAY_BUFFER(), vbo);
        gl.bufferData(gl.ARRAY_BUFFER(), vertices, gl.STATIC_DRAW());
        int strideBytes = layout.floatsPerVertex * Float.BYTES;
        int offset = 0;
        for (int attribute = 0; attribute < layout.locations.length; attribute++) {
            gl.vertexAttribPointer(layout.locations[attribute], layout.sizes[attribute], gl.FLOAT(),
                    false, strideBytes, offset * Float.BYTES);
            gl.enableVertexAttribArray(layout.locations[attribute]);
            offset += layout.sizes[attribute];
        }
        vertexCount = vertices.length / layout.floatsPerVertex;
        if (indices == null) {
            return;
        }
        ebo = gl.genBuffers();
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), ebo);
        IntBuffer elements = BufferUtils.createIntBuffer(indices.length);
        elements.put(indices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), elements, gl.STATIC_DRAW());
        indexCount = indices.length;
    }

    /** Free the buffers and zero every handle and count. */
    public void delete() {
        GL gl = Platforms.gl();
        if (vao != 0) {
            gl.deleteVertexArrays(vao);
        }
        if (vbo != 0) {
            gl.deleteBuffers(vbo);
        }
        if (ebo != 0) {
            gl.deleteBuffers(ebo);
        }
        vao = 0;
        vbo = 0;
        ebo = 0;
        vertexCount = 0;
        indexCount = 0;
    }
}
