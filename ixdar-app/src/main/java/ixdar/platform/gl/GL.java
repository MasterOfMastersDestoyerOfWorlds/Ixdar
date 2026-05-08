package ixdar.platform.gl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.input.MouseButtons;

/**
 * Minimum GL surface used by the renderer, abstracting LWJGL (desktop / headless) and WebGL
 * behind the same call shape. Method names mirror the underlying {@code glXxx} entry points;
 * the all-caps zero-arg accessors return platform-specific GL enum values
 * (e.g. {@link #COLOR_BUFFER_BIT()}, {@link #TRIANGLES()}).
 */
public interface GL {
    /**
     * Set the GL viewport rectangle.
     *
     * @param x lower-left x in pixels
     * @param y lower-left y in pixels
     * @param w width in pixels
     * @param h height in pixels
     */
    void viewport(int x, int y, int w, int h);

    /**
     * Set the colour the framebuffer is cleared to.
     *
     * @param r red component, 0..1
     * @param g green component, 0..1
     * @param b blue component, 0..1
     * @param a alpha component, 0..1
     */
    void clearColor(float r, float g, float b, float a);

    /**
     * Set the colour the framebuffer is cleared to.
     *
     * @param c color to clear the framebuffer to
     */
    void clearColor(Color c);

    /**
     * Clear the buffers indicated by {@code mask} (bitwise OR of {@link #COLOR_BUFFER_BIT()},
     * {@link #DEPTH_BUFFER_BIT()}).
     *
     * @param mask buffer-bit mask
     */
    void clear(int mask);

    /**
     * Create a new program object.
     *
     * @return platform-specific program ID
     */
    int createProgram();

    /**
     * Create a shader object of the given stage.
     *
     * @param type {@link #VERTEX_SHADER()} or {@link #FRAGMENT_SHADER()}
     * @return platform-specific shader ID
     */
    int createShader(int type);

    /**
     * Set the GLSL source for a shader.
     *
     * @param shader shader ID returned by {@link #createShader(int)}
     * @param src full source text
     */
    void shaderSource(int shader, String src);

    /**
     * Compile a shader whose source has been set.
     *
     * @param shader shader ID
     */
    void compileShader(int shader);

    /**
     * Read a single integer parameter from a shader.
     *
     * @param shader shader ID
     * @param pname parameter name (e.g. {@link #COMPILE_STATUS()})
     * @return the parameter value
     */
    int getShaderiv(int shader, int pname);

    /**
     * @param shader shader ID
     * @return compiler info log (empty string when there are no diagnostics)
     */
    String getShaderInfoLog(int shader);

    /**
     * Attach {@code shader} to {@code program}.
     *
     * @param program program ID
     * @param shader shader ID
     */
    void attachShader(int program, int shader);

    /**
     * Link a program after its shaders have been attached.
     *
     * @param program program ID
     */
    void linkProgram(int program);

    /**
     * Read a single integer parameter from a program.
     *
     * @param program program ID
     * @param pname parameter name (e.g. {@link #LINK_STATUS()})
     * @return parameter value
     */
    int getProgramiv(int program, int pname);

    /**
     * @param program program ID
     * @return linker info log (empty when no diagnostics)
     */
    String getProgramInfoLog(int program);

    /**
     * Make {@code program} the active program for subsequent draw calls.
     *
     * @param program program ID
     */
    void useProgram(int program);

    /**
     * Delete a shader object.
     *
     * @param shader shader ID
     */
    void deleteShader(int shader);

    /**
     * Delete a program object.
     *
     * @param program program ID
     */
    void deleteProgram(int program);

    /**
     * Generate one buffer object.
     *
     * @return new buffer ID
     */
    int genBuffer();

    /**
     * Bind a buffer to the {@link #ARRAY_BUFFER()} target.
     *
     * @param buffer buffer ID
     */
    void bindArrayBuffer(int buffer);

    /**
     * Upload {@code data} to the currently bound array buffer.
     *
     * @param data source buffer
     * @param usage usage hint (e.g. {@link #STATIC_DRAW()}, {@link #DYNAMIC_DRAW()})
     */
    void bufferDataArray(IxBuffer data, int usage);

    /**
     * Upload a float array to the currently bound array buffer.
     *
     * @param data source array
     * @param usage usage hint
     */
    void bufferDataArray(float[] data, int usage);

    /**
     * Enable a generic vertex attribute slot.
     *
     * @param index attribute location
     */
    void enableVertexAttribArray(int index);

    /**
     * Describe an attribute's layout within the bound array buffer.
     *
     * @param index attribute location
     * @param size component count (1..4)
     * @param type element type (e.g. {@link #FLOAT()})
     * @param normalized true to normalize integer types into [0,1]/[-1,1]
     * @param stride byte stride between successive vertices
     * @param pointer byte offset of the first component
     */
    void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int pointer);

    /**
     * Generate one VAO.
     *
     * @return new VAO ID
     */
    int genVertexArray();

    /**
     * Bind a VAO ({@code 0} unbinds).
     *
     * @param vao VAO ID
     */
    void bindVertexArray(int vao);

    /**
     * Issue a non-indexed draw.
     *
     * @param mode primitive mode (e.g. {@link #TRIANGLES()}, {@link #LINES()})
     * @param first starting vertex index
     * @param count vertex count
     */
    void drawArrays(int mode, int first, int count);
    /**
     * Issue an indexed draw using the bound element-array buffer.
     *
     * @param mode primitive mode
     * @param count index count
     * @param type index element type (e.g. {@link #UNSIGNED_INT()})
     * @param indicesOffsetBytes byte offset into the element-array buffer
     */
    void drawElements(int mode, int count, int type, int indicesOffsetBytes);

    /**
     * Look up a uniform location.
     *
     * @param program program ID
     * @param name uniform name as declared in GLSL
     * @return location ID, or {@code -1} if not found / inactive
     */
    int getUniformLocation(int program, String name);

    /**
     * Set a {@code float} uniform.
     *
     * @param loc uniform location
     * @param v value
     */
    void uniform1f(int loc, float v);

    /**
     * Set an {@code int} (or sampler) uniform.
     *
     * @param loc uniform location
     * @param v value
     */
    void uniform1i(int loc, int v);

    /**
     * Upload one or more {@code vec2}s from a flat float buffer.
     *
     * @param loc uniform location
     * @param buffer values, packed xy
     */
    void uniform2fv(int loc, IxBuffer buffer);

    /**
     * Upload one or more {@code vec3}s from a flat float buffer.
     *
     * @param loc uniform location
     * @param buffer values, packed xyz
     */
    void uniform3fv(int loc, IxBuffer buffer);

    /**
     * Upload one or more {@code vec4}s from a flat float buffer.
     *
     * @param loc uniform location
     * @param buffer values, packed xyzw
     */
    void uniform4fv(int loc, IxBuffer buffer);

    /**
     * Upload one or more {@code mat4}s from a flat float buffer.
     *
     * @param loc uniform location
     * @param transpose true to transpose during upload
     * @param buffer 16 floats per matrix
     */
    void uniformMatrix4fv(int loc, boolean transpose, IxBuffer buffer);

    /**
     * Generate one texture object.
     *
     * @return new texture ID
     */
    int genTexture();
    /**
     * Delete a texture object.
     *
     * @param id texture ID
     */
    void deleteTexture(int id);

    /**
     * Bind a texture to {@link #TEXTURE_2D()} on the active texture unit.
     *
     * @param id texture ID
     */
    void bindTexture2D(int id);

    /**
     * Set an integer texture parameter.
     *
     * @param target texture target (e.g. {@link #TEXTURE_2D()})
     * @param pname parameter name
     * @param param parameter value
     */
    void texParameteri(int target, int pname, int param);

    /**
     * Upload a 2D texture image.
     *
     * @param target texture target
     * @param level mipmap level (0 for base)
     * @param internalFormat GPU-side format
     * @param width texel width
     * @param height texel height
     * @param border must be 0 in core profile
     * @param format pixel format of {@code data}
     * @param type pixel type of {@code data}
     * @param data raw pixel bytes (may be {@code null} to allocate without uploading)
     */
    void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type,
            ByteBuffer data);

    /**
     * Generate the mipmap chain for the bound texture.
     *
     * @param target texture target
     */
    void generateMipmap(int target);

    /**
     * @return GL constant for the colour buffer bit
     */
    int COLOR_BUFFER_BIT();

    /**
     * @return GL constant for the depth buffer bit
     */
    int DEPTH_BUFFER_BIT();

    /**
     * @return primitive mode for triangle lists
     */
    int TRIANGLES();

    /**
     * @return GL_ARRAY_BUFFER target
     */
    int ARRAY_BUFFER();
    /**
     * @return GL_ELEMENT_ARRAY_BUFFER target
     */
    int ELEMENT_ARRAY_BUFFER();

    /**
     * @return GL_STATIC_DRAW usage hint
     */
    int STATIC_DRAW();

    /**
     * @return GL_FLOAT element type
     */
    int FLOAT();

    /**
     * @return GL_FRAGMENT_SHADER stage
     */
    int FRAGMENT_SHADER();

    /**
     * @return GL_VERTEX_SHADER stage
     */
    int VERTEX_SHADER();

    /**
     * @return GL_TEXTURE_2D target
     */
    int TEXTURE_2D();

    /**
     * @return GL_RGBA pixel format
     */
    int RGBA();

    /**
     * @return GL_RGBA8 sized internal format
     */
    int RGBA8();

    /**
     * @return GL_UNSIGNED_BYTE element type
     */
    int UNSIGNED_BYTE();
    /**
     * @return GL_UNSIGNED_INT element type
     */
    int UNSIGNED_INT();

    /**
     * @return GL_TEXTURE_WRAP_S parameter
     */
    int TEXTURE_WRAP_S();

    /**
     * @return GL_TEXTURE_WRAP_T parameter
     */
    int TEXTURE_WRAP_T();

    /**
     * @return GL_TEXTURE_MIN_FILTER parameter
     */
    int TEXTURE_MIN_FILTER();

    /**
     * @return GL_TEXTURE_MAG_FILTER parameter
     */
    int TEXTURE_MAG_FILTER();

    /**
     * @return primitive mode for line lists
     */
    int LINES();

    /**
     * Set the rasterized line width.
     *
     * @param width width in pixels
     */
    void lineWidth(float width);

    /**
     * @return GL_LINEAR filter
     */
    int LINEAR();

    /**
     * @return GL_REPEAT wrap mode
     */
    int REPEAT();

    /**
     * Poll a mouse-button state for the given window handle.
     *
     * @param window platform window handle (GLFW; ignored on web)
     * @param mouseButtonLeft button to query
     * @return true while the button is currently down
     */
    boolean getMouseButton(long window, MouseButtons mouseButtonLeft);

    /**
     * @return GL_SRC_ALPHA blend factor
     */
    int SRC_ALPHA();

    /**
     * @return GL_ONE_MINUS_SRC_ALPHA blend factor
     */
    int ONE_MINUS_SRC_ALPHA();

    /**
     * @return GL_BLEND capability
     */
    int BLEND();

    /**
     * Configure source/destination blend factors.
     *
     * @param SRC_ALPHA source factor
     * @param ONE_MINUS_SRC_ALPHA destination factor
     */
    void blendFunc(int SRC_ALPHA, int ONE_MINUS_SRC_ALPHA);

    /**
     * Enable a GL capability (e.g. {@link #BLEND()}, {@link #DEPTH_TEST()}).
     *
     * @param blend capability constant
     */
    void enable(int blend);

    /**
     * Disable a GL capability.
     *
     * @param depthTest capability constant
     */
    void disable(int depthTest);

    /**
     * Enable or disable depth-buffer writes.
     *
     * @param flag true allows writes
     */
    void depthMask(boolean flag);

    /**
     * Bind GL function pointers (LWJGL {@code GL.createCapabilities}); a no-op on WebGL where
     * the context is the function table.
     */
    void createCapabilities();

    /**
     * @return GL_DEPTH_TEST capability
     */
    int DEPTH_TEST();

    /**
     * Set the platform window title (desktop) or document/tab title (web).
     *
     * @param string new title
     */
    void setWindowTitle(String string);

    /**
     * Generate one VAO (LWJGL-style alias for {@link #genVertexArray()}).
     *
     * @return VAO ID
     */
    int genVertexArrays();

    /**
     * Delete a VAO.
     *
     * @param id VAO ID
     */
    void deleteVertexArrays(int id);

    /**
     * Generate one buffer object (LWJGL-style alias for {@link #genBuffer()}).
     *
     * @return buffer ID
     */
    int genBuffers();

    /**
     * Bind {@code id} to {@code target}.
     *
     * @param target buffer target ({@link #ARRAY_BUFFER()} / {@link #ELEMENT_ARRAY_BUFFER()})
     * @param id buffer ID
     */
    void bindBuffer(int target, int id);

    /**
     * Upload float data to the buffer bound at {@code target}.
     *
     * @param target buffer target
     * @param data source buffer
     * @param usage usage hint
     */
    void bufferData(int target, IxBuffer data, int usage);

    /**
     * Upload a float array to the buffer bound at {@code target}.
     *
     * @param target buffer target
     * @param data source data
     * @param usage usage hint
     */
    void bufferData(int target, float[] data, int usage);

    /**
     * Allocate uninitialized storage of {@code size} bytes for the buffer bound at {@code target}.
     *
     * @param target buffer target
     * @param size byte size
     * @param usage usage hint
     */
    void bufferData(int target, long size, int usage);

    /**
     * Update a sub-range of the buffer bound at {@code target}.
     *
     * @param target buffer target
     * @param offset byte offset into the buffer
     * @param data source values
     */
    void bufferSubData(int target, long offset, IxBuffer data);

    /**
     * Upload integer data (typically index data) to the buffer bound at {@code target}.
     *
     * @param target buffer target
     * @param data integer values from current position to limit
     * @param usage usage hint
     */
    void bufferData(int target, IntBuffer data, int usage);

    /**
     * Delete a buffer object.
     *
     * @param id buffer ID
     */
    void deleteBuffers(int id);

    /**
     * Look up a vertex attribute location.
     *
     * @param iD program ID
     * @param name attribute name as declared in GLSL
     * @return location, or {@code -1} if not found
     */
    int getAttribLocation(int iD, CharSequence name);

    /**
     * @return GL_DYNAMIC_DRAW usage hint
     */
    int DYNAMIC_DRAW();

    /**
     * Bind a fragment-shader output to a colour attachment (desktop only; no-op on WebGL).
     *
     * @param iD program ID
     * @param i colour number
     * @param string output variable name
     */
    void bindFragDataLocation(int iD, int i, String string);

    /**
     * Select an active texture unit ({@link #TEXTURE0()} + offset).
     *
     * @param i texture unit constant
     */
    void activeTexture(int i);

    /**
     * Detach a previously attached shader from a program.
     *
     * @param iD program ID
     * @param fragmentShader shader ID
     */
    void detachShader(int iD, int fragmentShader);

    /**
     * Set shader source from concatenated chunks.
     *
     * @param fragmentShader shader ID
     * @param fragmentShaderSource source fragments (concatenated in order)
     */
    void shaderSource(int fragmentShader, CharSequence[] fragmentShaderSource);

    /**
     * @return GL_LINK_STATUS parameter name
     */
    int LINK_STATUS();

    /**
     * Read a program parameter into {@code success.put(0, ...)}.
     *
     * @param shader program ID
     * @param link_STATUS parameter name
     * @param success destination buffer; first element receives the value
     */
    void getProgramiv(int shader, int link_STATUS, IntBuffer success);

    /**
     * @return GL_COMPILE_STATUS parameter name
     */
    int COMPILE_STATUS();

    /**
     * Read a shader parameter into {@code success.put(0, ...)}.
     *
     * @param shader shader ID
     * @param compile_STATUS parameter name
     * @param success destination buffer
     */
    void getShaderiv(int shader, int compile_STATUS, IntBuffer success);

    /**
     * Set a {@code vec3} uniform from a packed buffer (Integer-keyed overload for callers that
     * carry locations boxed).
     *
     * @param integer uniform location
     * @param vec3 packed xyz values
     */
    void uniform3fv(Integer integer, IxBuffer vec3);

    /**
     * Read pixels back from the bound framebuffer into a packed RGBA int array.
     *
     * @param i lower-left x
     * @param j lower-left y
     * @param width width in pixels
     * @param height height in pixels
     * @param rgba pixel format (typically {@link #RGBA()})
     * @param unsigned_BYTE pixel type
     * @param fb unused (kept for signature parity across backends)
     * @return one int per pixel, packed (a/r/g/b layout depends on backend)
     */
    int[] readPixels(int i, int j, int width, int height, int rgba, int unsigned_BYTE, int fb);

    /**
     * @return GL_TEXTURE0 unit constant
     */
    int TEXTURE0();

    /**
     * Touch off-thread native stacks once at startup so the first real frame doesn't pay the
     * lazy-init cost (LWJGL-only; no-op on web).
     */
    void coldStartStack();

    /**
     * @return shader programs registered via {@link #addShader(ShaderProgram)}
     */
    ArrayList<ShaderProgram> getShaders();

    /**
     * Register a shader so the renderer can iterate over all shaders for hot-reload, etc.
     *
     * @param shader program wrapper to register
     */
    void addShader(ShaderProgram shader);

    /**
     * @return platform ID assigned via {@link #setPlatformID(Integer)}
     */
    int getPlatformID();

    /**
     * Read the {@code GL_ATTACHED_SHADERS} count of {@code shader} into {@code success.put(0, ...)}.
     *
     * @param shader program ID
     * @param success destination buffer
     */
    void getAttachedShaders(int shader, IntBuffer success);

    /**
     * Read the {@link #ACTIVE_UNIFORMS()} count of {@code shader} into {@code success.put(0, ...)}.
     *
     * @param shader program ID
     * @param success destination buffer
     */
    void getActiveUniforms(int shader, IntBuffer success);

    /**
     * @return GL_ACTIVE_UNIFORMS parameter name
     */
    int ACTIVE_UNIFORMS();

    /**
     * Look up an active uniform's metadata by index.
     *
     * @param iD program ID
     * @param i uniform index
     * @param sizeBuffer receives the array size (may be ignored by some backends)
     * @param typeBuffer receives the GL type (may be ignored by some backends)
     * @return the uniform name
     */
    String getActiveUniform(int iD, int i, IntBuffer sizeBuffer, IntBuffer typeBuffer);

    /**
     * @return GL_FLOAT_VEC2 type constant
     */
    int FLOAT_VEC2();

    /**
     * @return GL_FLOAT_VEC4 type constant
     */
    int FLOAT_VEC4();

    /**
     * @return GL_SAMPLER_2D type constant
     */
    int SAMPLER_2D();

    /**
     * Read the current value of a float uniform back into {@code val}.
     *
     * @param iD program ID
     * @param location uniform location
     * @param val destination buffer
     */
    void getUniformfv(int iD, int location, IxBuffer val);

    /**
     * Stamp this GL with its owning platform ID (used by {@link Platforms} to route inputs
     * across multiple canvases on web).
     *
     * @param p platform ID
     */
    void setPlatformID(Integer p);

    /**
     * @return GL_LINEAR_MIPMAP_LINEAR filter
     */
    int LINEAR_MIPMAP_LINEAR();

    /**
     * When true, shader sources are left as GLSL ES ({@code #version 300 es}). When false, shared
     * sources are adapted for desktop OpenGL 3.3 core ({@link GlslSource}).
     *
     * @return true on WebGL backends, false on desktop / headless
     */
    default boolean usesWebGlsl() {
        return false;
    }
}
