package ixdar.graphics.render.model;

import org.joml.Vector3f;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AssimpModelImporter {
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final int NUM_3 = 3;
    public static final float NUM_0_5 = 0.5f;
    public static final int NUM_8 = 8;
    public static final float NUM_0_001 = 0.001f;

    /**
     * TODO: document {@code importFromFile}.
     *
     * @param absoluteModelPath TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
     */
    public ImportedModelData importFromFile(String absoluteModelPath) throws IOException {
        int flags = Assimp.aiProcess_Triangulate
                | Assimp.aiProcess_JoinIdenticalVertices
                | Assimp.aiProcess_GenSmoothNormals;
        AIScene scene = Assimp.aiImportFile(absoluteModelPath, flags);
        if (scene == null || scene.mMeshes() == null || scene.mNumMeshes() == 0) {
            throw new IOException("Assimp failed to import model: " + Assimp.aiGetErrorString());
        }

        List<Float> vertexData = new ArrayList<>();
        List<Integer> indexData = new ArrayList<>();
        boolean hasTexCoords = false;
        int baseVertex = 0;
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);

        try {
            for (int meshIndex = 0; meshIndex < scene.mNumMeshes(); meshIndex++) {
                AIMesh mesh = AIMesh.create(scene.mMeshes().get(meshIndex));
                AIVector3D.Buffer verts = mesh.mVertices();
                if (verts == null) {
                    continue;
                }
                AIVector3D.Buffer normals = mesh.mNormals();
                AIVector3D.Buffer texCoords = mesh.mTextureCoords(0);

                for (int i = 0; i < verts.remaining(); i++) {
                    AIVector3D p = verts.get(i);
                    Vector3f v = new Vector3f(p.x(), p.y(), p.z());
                    vertexData.add(v.x);
                    vertexData.add(v.y);
                    vertexData.add(v.z);

                    if (normals != null && i < normals.remaining()) {
                        AIVector3D n = normals.get(i);
                        vertexData.add(n.x());
                        vertexData.add(n.y());
                        vertexData.add(n.z());
                    } else {
                        vertexData.add(NUM_0);
                        vertexData.add(NUM_0);
                        vertexData.add(NUM_1);
                    }

                    if (texCoords != null && i < texCoords.remaining()) {
                        AIVector3D t = texCoords.get(i);
                        vertexData.add(t.x());
                        vertexData.add(t.y());
                        hasTexCoords = true;
                    } else {
                        vertexData.add(NUM_0);
                        vertexData.add(NUM_0);
                    }

                    min.min(v);
                    max.max(v);
                }

                for (int faceIndex = 0; faceIndex < mesh.mNumFaces(); faceIndex++) {
                    AIFace face = mesh.mFaces().get(faceIndex);
                    IntBuffer faceIndices = face.mIndices();
                    if (faceIndices == null || faceIndices.remaining() < NUM_3) {
                        continue;
                    }
                    indexData.add(baseVertex + faceIndices.get(0));
                    indexData.add(baseVertex + faceIndices.get(1));
                    indexData.add(baseVertex + faceIndices.get(2));
                }
                baseVertex += verts.remaining();
            }
        } finally {
            Assimp.aiReleaseImport(scene);
        }

        if (vertexData.isEmpty() || indexData.isEmpty()) {
            throw new IOException("Imported model has no geometry: " + Path.of(absoluteModelPath).getFileName());
        }

        float[] verts = new float[vertexData.size()];
        for (int i = 0; i < vertexData.size(); i++) {
            verts[i] = vertexData.get(i);
        }
        int[] indices = new int[indexData.size()];
        for (int i = 0; i < indexData.size(); i++) {
            indices[i] = indexData.get(i);
        }

        Vector3f center = new Vector3f(min).add(max).mul(NUM_0_5);
        float radius = NUM_0;
        for (int i = 0; i < verts.length; i += NUM_8) {
            radius = Math.max(radius, new Vector3f(verts[i], verts[i + 1], verts[i + 2]).sub(center).length());
        }
        if (radius < NUM_0_001) {
            radius = NUM_1;
        }

        return new ImportedModelData(verts, indices, verts.length / NUM_8, hasTexCoords, center, radius);
    }
}
