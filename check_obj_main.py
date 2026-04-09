import os
files = os.listdir('/Users/acw28/Code/Ixdar/ixdar-app')
print([f for f in files if f.endswith('.obj')])
