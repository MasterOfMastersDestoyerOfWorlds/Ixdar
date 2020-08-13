%module qhull

// Make mylib_wrap.cxx include this header:
%{
#include "user.h"
#include "libqhull.h"
#include "qhull_a.h"
#include "geom.h"	
#include "io.h"
#include "mem.h"
#include "merge.h"
#include "poly.h"
#include "random.h"
#include "qset.h"
#include "stat.h"
#include <assert.h>
#include <jni.h>
%}
// Make SWIG look into this header:
%include cpointer.i 

%pointer_functions(int, intp);


//https://stackoverflow.com/questions/8320605/swig-configuration-to-handle-a-file-c-input-parameter-in-java
%typemap(jni) FILE* "jobject"
%typemap(jstype) FILE* "java.io.FileOutputStream"
%typemap(jtype) FILE* "java.io.FileDescriptor"
%typemap(in) (FILE*) {
  jfieldID field_fd;
  jclass class_fdesc;
  int rawfd;
  class_fdesc = (*jenv)->FindClass(jenv, "java/io/FileDescriptor");
  assert(class_fdesc);
  field_fd = (*jenv)->GetFieldID(jenv, class_fdesc, "fd", "I");
  assert(field_fd);
  rawfd = (*jenv)->GetIntField(jenv, $input, field_fd);
  $1 = fdopen(rawfd, "w");
}
%typemap(javain, pre="    retainFD = $javainput;",
         throws="java.io.IOException") FILE* "$javainput.getFD()"
%pragma(java) modulecode=%{
  private static java.io.FileOutputStream retainFD;
%}

%typemap(jni) boolT "jboolean"
%typemap(jstype) boolT "java.lang.Boolean"
%typemap(jtype) boolT "boolean"
%typemap(in) boolT %{ 
   $1 = $input;
%}
%typemap(javain) boolT "$javainput"

%typemap(jni) coordT "jfloat"
%typemap(jstype) coordT "java.lang.Float"
%typemap(jtype) coordT "float"

%typemap(javain) coordT "$javainput"

%include carrays.i

%array_functions(coordT, coordT_array )


%pointer_functions(char, String);
%pointer_functions(char*, Stringp);

%include user.h
%include qhull_a.h
%include geom.h	
%include io.h
%include mem.h
%include merge.h
%include poly.h
%include random.h
%include qset.h
%include stat.h
