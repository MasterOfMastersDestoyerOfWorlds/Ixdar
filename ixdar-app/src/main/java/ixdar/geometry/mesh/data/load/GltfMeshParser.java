package ixdar.geometry.mesh.data.load;

import java.io.IOException;
import java.nio.IntBuffer;
import java.util.Map;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMatrix4x4;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.system.MemoryUtil;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.nodes.api.Vector3Field;

/**
 * Loads glTF 2.0 ({@code .glb} / {@code .gltf}) files through Assimp into the same triangle
 * {@link ArrayMesh} shape {@link ObjMeshParser} produces, with {@code TEXCOORD_0} riding the
 * bundle as {@link MeshLoader#UV_SLOT}. Desktop-only: {@link MeshLoader} reaches it by reflection.
 */
public final class GltfMeshParser {


    /**
     * Assimp post-processing: fan-triangulate polygons only. glTF is already indexed, and
     * {@code aiProcess_JoinIdenticalVertices} would weld the handful of fully duplicated vertices
     * the scans contain, so the vertex count would no longer match the file's accessors.
     */
    public static final int IMPORT_FLAGS = Assimp.aiProcess_Triangulate;

    public static final int FLOATS_PER_VERTEX = 3;
    public static final int VERTICES_PER_TRIANGLE = 3;

    private int totalVertexCount;
    private int totalTriangleCount;
    private float[] positions;
    private float[] normals;
    private float[] textureCoordinates;
    private int[] triangleIndices;
    private int vertexCursor;
    private int indexCursor;
    private boolean anyNormals;
    private boolean anyTextureCoordinates;
    private final Matrix4f localTransform = new Matrix4f();
    private final Matrix4f worldTransform = new Matrix4f();
    private final Matrix3f normalTransform = new Matrix3f();
    private final Vector3f scratch = new Vector3f();

    private GltfMeshParser() {
    }

    /**
     * Import {@code path} with Assimp and flatten every mesh instance in the node tree, baked into
     * world space, into one triangle bundle carrying {@link MeshLoader#UV_SLOT}.
     *
     * @param path filesystem path of a {@code .glb} or {@code .gltf} file, absolute or relative to
     *             the working directory
     * @throws IOException if Assimp rejects the file or it contains no triangles
     * @return bundle whose mesh is a triangle {@link ArrayMesh}; {@link MeshLoader#UV_SLOT} holds one
     *         {@code (u, v, 0)} per vertex and is absent when no mesh carried texture coordinates
     */
    public static GeometryBundle load(String path) throws IOException {
        AIScene scene = Assimp.aiImportFile(path, IMPORT_FLAGS);
        if (scene == null) {
            throw new IOException("Assimp failed to import " + path + ": " + Assimp.aiGetErrorString());
        }
        try {
            return new GltfMeshParser().flatten(scene, path);
        } finally {
            Assimp.aiReleaseImport(scene);
        }
    }

    /**
     * Two passes over the node tree: the first sizes the flat arrays so nothing is boxed or grown,
     * the second copies each mesh instance through its world transform.
     *
     * @param scene imported scene, still owned by Assimp
     * @param path file name for error messages
     * @throws IOException if the scene has no root node or no triangles
     * @return the assembled bundle
     */
    private GeometryBundle flatten(AIScene scene, String path) throws IOException {
        AINode root = scene.mRootNode();
        PointerBuffer meshPointers = scene.mMeshes();
        if (root == null || meshPointers == null || scene.mNumMeshes() == 0) {
            throw new IOException("Imported scene has no meshes: " + path);
        }
        countInstances(root, meshPointers);
        if (totalTriangleCount == 0 || totalVertexCount == 0) {
            throw new IOException("Imported scene has no triangles: " + path);
        }
        positions = new float[totalVertexCount * FLOATS_PER_VERTEX];
        normals = new float[totalVertexCount * FLOATS_PER_VERTEX];
        textureCoordinates = new float[totalVertexCount * FLOATS_PER_VERTEX];
        triangleIndices = new int[totalTriangleCount * VERTICES_PER_TRIANGLE];
        worldTransform.identity();
        copyInstances(root, meshPointers, worldTransform);

        ArrayMesh mesh = new ArrayMesh(positions, normals, triangleIndices, VERTICES_PER_TRIANGLE);
        if (!anyNormals) {
            mesh.computeNormals();
        }
        if (!anyTextureCoordinates) {
            return GeometryBundle.ofMesh(mesh);
        }
        return new GeometryBundle(mesh, Map.of(MeshLoader.UV_SLOT, new Vector3Field(textureCoordinates)));
    }

    private void countInstances(AINode node, PointerBuffer meshPointers) {
        IntBuffer meshIndices = node.mMeshes();
        if (meshIndices != null) {
            for (int slot = 0; slot < node.mNumMeshes(); slot++) {
                AIMesh mesh = AIMesh.create(meshPointers.get(meshIndices.get(slot)));
                totalVertexCount += mesh.mNumVertices();
                totalTriangleCount += triangleCount(mesh);
            }
        }
        PointerBuffer children = node.mChildren();
        if (children != null) {
            for (int child = 0; child < node.mNumChildren(); child++) {
                countInstances(AINode.create(children.get(child)), meshPointers);
            }
        }
    }

    private static int triangleCount(AIMesh mesh) {
        AIFace.Buffer faces = mesh.mFaces();
        int triangles = 0;
        for (int faceIndex = 0; faceIndex < mesh.mNumFaces(); faceIndex++) {
            if (faces.get(faceIndex).mNumIndices() == VERTICES_PER_TRIANGLE) {
                triangles++;
            }
        }
        return triangles;
    }

    private void copyInstances(AINode node, PointerBuffer meshPointers, Matrix4fc parentTransform) {
        Matrix4f world = new Matrix4f(parentTransform).mul(toJoml(node.mTransformation(), localTransform));
        IntBuffer meshIndices = node.mMeshes();
        if (meshIndices != null) {
            for (int slot = 0; slot < node.mNumMeshes(); slot++) {
                copyMesh(AIMesh.create(meshPointers.get(meshIndices.get(slot))), world);
            }
        }
        PointerBuffer children = node.mChildren();
        if (children != null) {
            for (int child = 0; child < node.mNumChildren(); child++) {
                copyInstances(AINode.create(children.get(child)), meshPointers, world);
            }
        }
    }

    /**
     * Append one mesh instance: bulk-copy the tightly packed Assimp vertex streams straight into
     * the flat arrays, then bake {@code world} in place when it is not the identity.
     *
     * @param mesh Assimp mesh to append
     * @param world accumulated node transform of this instance
     */
    private void copyMesh(AIMesh mesh, Matrix4f world) {
        int vertexCount = mesh.mNumVertices();
        int floatCount = vertexCount * FLOATS_PER_VERTEX;
        int floatOffset = vertexCursor * FLOATS_PER_VERTEX;

        AIVector3D.Buffer vertices = mesh.mVertices();
        MemoryUtil.memFloatBuffer(vertices.address(), floatCount).get(positions, floatOffset, floatCount);

        AIVector3D.Buffer meshNormals = mesh.mNormals();
        if (meshNormals != null) {
            MemoryUtil.memFloatBuffer(meshNormals.address(), floatCount).get(normals, floatOffset, floatCount);
            anyNormals = true;
        }

        AIVector3D.Buffer meshTextureCoordinates = mesh.mTextureCoords(0);
        if (meshTextureCoordinates != null) {
            MemoryUtil.memFloatBuffer(meshTextureCoordinates.address(), floatCount)
                    .get(textureCoordinates, floatOffset, floatCount);
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                textureCoordinates[floatOffset + vertex * FLOATS_PER_VERTEX + 2] = 0f;
            }
            anyTextureCoordinates = true;
        }

        world.determineProperties();
        if ((world.properties() & Matrix4fc.PROPERTY_IDENTITY) == 0) {
            world.normal(normalTransform);
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                int offset = floatOffset + vertex * FLOATS_PER_VERTEX;
                scratch.set(positions[offset], positions[offset + 1], positions[offset + 2]);
                world.transformPosition(scratch);
                positions[offset] = scratch.x;
                positions[offset + 1] = scratch.y;
                positions[offset + 2] = scratch.z;
                if (meshNormals != null) {
                    scratch.set(normals[offset], normals[offset + 1], normals[offset + 2]);
                    normalTransform.transform(scratch).normalize();
                    normals[offset] = scratch.x;
                    normals[offset + 1] = scratch.y;
                    normals[offset + 2] = scratch.z;
                }
            }
        }

        AIFace.Buffer faces = mesh.mFaces();
        for (int faceIndex = 0; faceIndex < mesh.mNumFaces(); faceIndex++) {
            AIFace face = faces.get(faceIndex);
            if (face.mNumIndices() != VERTICES_PER_TRIANGLE) {
                continue;
            }
            IntBuffer faceIndices = face.mIndices();
            triangleIndices[indexCursor++] = vertexCursor + faceIndices.get(0);
            triangleIndices[indexCursor++] = vertexCursor + faceIndices.get(1);
            triangleIndices[indexCursor++] = vertexCursor + faceIndices.get(2);
        }
        vertexCursor += vertexCount;
    }

    /**
     * Assimp matrices are row-major ({@code a1..a4} is the first row); JOML's 16-float setter
     * takes columns, so each Assimp row lands as a JOML column argument.
     *
     * @param source Assimp node transform
     * @param destination JOML matrix to overwrite
     * @return {@code destination}
     */
    private static Matrix4f toJoml(AIMatrix4x4 source, Matrix4f destination) {
        return destination.set(
                source.a1(), source.b1(), source.c1(), source.d1(),
                source.a2(), source.b2(), source.c2(), source.d2(),
                source.a3(), source.b3(), source.c3(), source.d3(),
                source.a4(), source.b4(), source.c4(), source.d4());
    }
}
