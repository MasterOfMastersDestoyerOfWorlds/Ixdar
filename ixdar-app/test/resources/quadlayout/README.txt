--------------------------------------------------------------------
Integer-Grid Maps for Reliable Quad Meshing – Supplementary Material
--------------------------------------------------------------------

Authors : David Bommes, Marcel Campen, Hans-Christian Ebke,
          Pierre Alliez, Leif Kobbelt

WWW:      http://www.rwth-graphics.de

----------------------------------------
File Naming Scheme
----------------------------------------

File names have the form (as a Perl regexp):

    (.*)_(in|out)_(cf|tri|quad)(_scaled)?(_enhanced)?.(ndf|off)

The first capture is the model name, the second capture specifies whether the
file acts as input or as output to the algorithm (cross fields and triangle
meshes are input, quad meshes are output). The third capture specifies the
type of data (cf = cross field, tri = triangle mesh, quad = quad mesh).  The
optional fourth capture is present in quad meshes obtained from the
parametrization scaled by a factor of 10 (see below). The optional fifth
capture is present in the enhanced cross fields from Figure 8 as well as in
the output quad meshes corresponding to these cross fields.

Note that all coarse results where obtained without up-sampling (see Section
3.4).  Consequently, artifacts of the decimated input geometry are visible in
some of the results, especially in those obtained from scaled
parametrizations.  Of course for quad layout construction in practice one
would compute a new distortion reduced map that keeps the layout fix but
subdivides non-uniformly into rectangles, as could easily be done.

For all provided results, the target edge length h used to generate the
results was chosen as specified in the paper.

All meshes are represented by OFF files. All cross fields are represented by
NDF files, the format of which is specified below.

----------------------------------------
Quad Meshes from Scaled Parametrizations
----------------------------------------

Those quad meshes with a "_scaled" in their file name were obtained from the
same parametrization as their non-scaled pendant except the parametrization
was scaled by a factor of 10 prior to the quad mesh extraction. Thus, they
basically represent a subdivided version of the non-scaled quad mesh. For very
coarse quad meshes the scaled representations better approximate the original
surface and thus are better suited for some visualization tasks.

----------------------------------------
The .ndf Format for Cross Fields
----------------------------------------

The cross fields are stored in a simple ini-file format. It can easily be
parsed using e.g. QSettings from Nokias Qt-framework.

A .ndf file usually contains 6 sections:

[Comments]
Here user comments and any other free text can be stored.

[Information]
Here general information about the mesh, e.g. #edges can be stored.

[Pjumps]
Refers to the integer jumps along an edge (see [1] Section 4.1 Equation (1)).
The "values" string contains the integer jumps for all the edges in the mesh
delimited by semi-colons and ordered in the same ordering as the edges.  That
is, the first integer corresponds to the first edge of the mesh and so on.

[Theta]
Refers to the angle \theta of a face (see [1] Section 4.1). The reference edge
for the angle(s) is the first half-edge of the respective face.  The angles in
the "values" string are delimited by semi-colons, and ordered wrt the faces of
the mesh. Hence, the first value corresponds to the angle in the first face of
the mesh and so on.

[Singularities]
Similar to the section above, here the "indices" string refers to vertex
indices of the mesh and "singularities" holds the index of the (singularitiy
at the) respective vertex.

Note: Our implementation uses www.openmesh.org as mesh datastructure.  For
reference half-edge of each face, we use the start half-edge of the
FaceHalfedgeIter (fh_iter).  For orientation between two faces we use the
half-edges of the common edge, for example:
    face0 = face_handle( halfedge_handle( common_edge, 0))
    face1 = face_handle( halfedge_handle( common_edge, 1))

[1] D. Bommes, H. Zimmer, and L. Kobbelt, “Mixed-integer quadrangulation,” in
ACM SIGGRAPH 2009 papers, New York, NY, USA, 2009, pp. 77:1–77:10.
