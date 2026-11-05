# A cube with configurable subdivision levels.
def subdivided_cube(size: FLOAT, levels: INT) -> MESH:
    c = cube(size=size)
    s = subdivision_surface(mesh=c.mesh, levels=levels, smooth=false)
end
