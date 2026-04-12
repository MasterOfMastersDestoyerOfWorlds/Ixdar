import os
import sys

path = '/Users/acw28/Code/Ixdar/ixdar-app/src/main/java/ixdar/geometry/mesh/data/MeshSkeletonExtractor.java'
print(f"File exists: {os.path.exists(path)}")
print(f"Is file: {os.path.isfile(path)}")
if os.path.exists(path):
    print(f"Is symlink: {os.path.islink(path)}")
    if os.path.islink(path):
        print(f"Symlink target: {os.readlink(path)}")
