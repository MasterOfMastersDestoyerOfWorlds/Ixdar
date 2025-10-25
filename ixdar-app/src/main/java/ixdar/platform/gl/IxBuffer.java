package ixdar.platform.gl;


public interface IxBuffer {

    void flip();

    void clear();

    int remaining();

    IxBuffer put(float x1);

    int capacity();

    Float get(int i);
}
