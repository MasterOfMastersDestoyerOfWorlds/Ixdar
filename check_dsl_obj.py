import os
files = os.listdir('/Users/acw28/Code/Ixdar/.worktrees/daud-dsl-9/ixdar-app/src/main/resources/dsl')
print([f for f in files if '.obj' in f])
