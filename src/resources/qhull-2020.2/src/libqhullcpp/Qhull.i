 %module example
 %{
 /* Includes the header in the wrapper code */
 #include "RboxPoints.h"
 #include "QhullQh.h"
 #include "QhullFacet.h"
 #include "QhullFacetList.h"
 #include "QhullFacetSet.h"
 #include "QhullLinkedList.h"
 #include "QhullPoint.h"
 #include "QhullUser.h"
 #include "QhullVertex.h"
 #include "QhullVertexSet.h"
 #include "Qhull.h"
 %}
 
 /* Parse the header file to generate wrappers */
  %include "RboxPoints.h"
 %include "QhullQh.h"
 %include "QhullFacet.h"
 %include "QhullFacetList.h"
 %include "QhullFacetSet.h"
 %include "QhullLinkedList.h"
 %include "QhullPoint.h"
 %include "QhullUser.h"
 %include "QhullVertex.h"
 %include "QhullVertexSet.h"
 %include "Qhull.h"
