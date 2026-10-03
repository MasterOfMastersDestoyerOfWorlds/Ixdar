package ixdar.geometry.mesh.data;

import ixdar.geometry.mesh.nodes.api.IntField;

/**
 * The materials one bundle's faces draw from, so a mesh assembled out of several textured sources
 * keeps each source's own material. The per-face index rides {@link #FACE_MATERIAL_SLOT}; a
 * single-material bundle uses {@link MaterialData#SLOT} instead.
 */
public final class MaterialSet {

    public static final String SLOT = "_materials";

    public static final String FACE_MATERIAL_SLOT = "_face_material";

    public static final int NO_MATERIAL = -1;

    /** The materials, in the order faces index them. */
    public final MaterialData[] materials;

    /**
     * Wrap a material list, taken as given.
     *
     * @param materials materials a face index addresses, none of them null
     * @throws IllegalArgumentException when the list is null or holds a null material
     */
    public MaterialSet(MaterialData[] materials) {
        if (materials == null) {
            throw new IllegalArgumentException("materials must not be null");
        }
        for (MaterialData material : materials) {
            if (material == null) {
                throw new IllegalArgumentException(
                        "a face with no material indexes " + NO_MATERIAL + ", not a null entry");
            }
        }
        this.materials = materials;
    }

    /**
     * The material a face index names.
     *
     * @param index index into {@link #materials}, or {@link #NO_MATERIAL}
     * @return the material, or null for {@link #NO_MATERIAL} and any index out of range
     */
    public MaterialData get(int index) {
        return index < 0 || index >= materials.length ? null : materials[index];
    }

    /**
     * The material list a bundle carries.
     *
     * @param bundle bundle to read, may be null
     * @return the set in {@link #SLOT}, or null when the bundle has none
     */
    public static MaterialSet of(GeometryBundle bundle) {
        if (bundle == null) {
            return null;
        }
        return bundle.slots().get(SLOT) instanceof MaterialSet set ? set : null;
    }

    /**
     * Every material a bundle's faces can draw from: the {@link MaterialSet} when it carries one,
     * else its single {@link MaterialData}, so a face index addresses the result either way.
     *
     * @param bundle bundle to read, may be null
     * @return the materials, empty when the bundle has none
     */
    public static MaterialData[] materialsOf(GeometryBundle bundle) {
        MaterialSet set = of(bundle);
        if (set != null) {
            return set.materials;
        }
        MaterialData single = MaterialData.of(bundle);
        return single == null ? new MaterialData[0] : new MaterialData[] {single};
    }

    /**
     * The per-face material index a bundle carries.
     *
     * @param bundle bundle to read, may be null
     * @return the field in {@link #FACE_MATERIAL_SLOT}, or null when the bundle has none
     */
    public static IntField faceMaterialOf(GeometryBundle bundle) {
        if (bundle == null) {
            return null;
        }
        return bundle.slots().get(FACE_MATERIAL_SLOT) instanceof IntField field ? field : null;
    }
}
