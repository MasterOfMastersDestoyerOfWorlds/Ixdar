cd qhull-2020.2/src/libqhull
make
#compiling all of the JNI files in the resources folder of the project
swig -java -I/usr/include -package resources -outdir ../../../../resources qhull.i
# compiling the wrapper code with the correct JNI resources need to 
#change darwin to win32 or idk what for linux
gcc -c global.c libqhull.c qhull_wrap.c -I$JAVA_HOME/include -I$JAVA_HOME/include/darwin
# compiling the shared library may need to change the output filename based 
# on the system so for linux dylib for mac and dll for windows
# can check System.mapLibraryName to figure this out some of them need lib in front
gcc -O3 -I../../src -shared unix.o libqhull.o geom.o poly.o qset.o mem.o random.o usermem.o userprintf.o io.o user.o global.o stat.o geom2.o poly2.o merge.o rboxlib.o userprintf_rbox.o qhull_wrap.o -Wl -o libqhull.dylib
mkdir ~/Library/Java/Extensions
#need to include this folder in native libbrary for the build path
mv libqhull.dylib ~/Library/Java/Extensions
