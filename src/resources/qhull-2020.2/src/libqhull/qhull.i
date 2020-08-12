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

%}
// Make SWIG look into this header:
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
