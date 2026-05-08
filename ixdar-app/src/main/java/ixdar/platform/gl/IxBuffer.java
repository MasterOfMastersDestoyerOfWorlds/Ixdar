package ixdar.platform.gl;


public interface IxBuffer {

    /**
     * TODO: document {@code flip}.
     */
    void flip();

    /**
     * TODO: document {@code clear}.
     */
    void clear();

    /**
     * TODO: document {@code remaining}.
     *
     * @return TODO: describe
     */
    int remaining();

    /**
     * TODO: document {@code put}.
     *
     * @param x1 TODO: describe
     * @return TODO: describe
     */
    IxBuffer put(float x1);

    /**
     * TODO: document {@code capacity}.
     *
     * @return TODO: describe
     */
    int capacity();

    /**
     * TODO: document {@code get}.
     *
     * @param i TODO: describe
     * @return TODO: describe
     */
    Float get(int i);
}
