# Test: DSL function syntax
# Defines a function that takes a mesh and subdivides it, then calls it

def subdivided_cube(size: FLOAT, levels: INT) -> GEOMETRY_BUNDLE:
    c = cube(size=size)
    s = subdivide_mesh(mesh=c.mesh, levels=levels)
end

result = subdivided_cube(size=2.0, levels=2)
