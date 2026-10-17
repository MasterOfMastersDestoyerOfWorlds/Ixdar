# A cube with configurable subdivision levels.
def subdivided_cube(size: FLOAT, levels: INT) -> MESH:
    c = cube(size=size)
    s = subdivide_mesh(mesh=c.mesh, levels=levels)
end
