package ixdar.geometry.mesh.quadlayout.crossfield;

/**
 * Closed-form per-(reference-)triangle entries of the finite-element connection
 * Laplacian and mass matrix from Knoeppel et al., "Globally Optimal Direction
 * Fields" (SIGGRAPH 2013). Ported from the reference implementation's
 * SectionIntegrals.cpp: the stiffness and mass off-diagonals are functions of
 * the triangle holonomy, evaluated by Chebyshev series for |holonomy| <= pi and
 * by a direct formula beyond that, so they stay accurate as a triangle flattens
 * (where naive evaluation divides by holonomy^4).
 *
 * <p>All entries are for a unit-area reference triangle: the caller multiplies
 * the mass entries by the triangle area and divides the stiffness entries by it,
 * then applies the edge transport coefficient. Complex results are returned as
 * {@code double[]{ real, imaginary }}.
 *
 * <p>The Chebyshev coefficient tables are numeric constants (computed by the
 * paper's authors); the evaluation routines are reimplemented here.
 */
public final class SectionIntegrals {

    private static final double[] STIFF_FIRST_REAL = {
        0.0448875760891932036595562553276,
        0.0278480909574822965157922173757,
        0.00394490790249120295818107628687,
        -0.00157697939158619172562804651751,
        -0.0000886578217796691901712579357311,
        0.0000301708056772263120428135787035,
        9.521839632337438230089618156e-7,
        -3.00028307455805582080773625835e-7,
        -6.14917009583473496433650831019e-9,
        1.85133588988085286010092653662e-9,
        2.67848449041765751590373973224e-11,
        -7.82394575359355297437491915705e-12,
        -8.44240072511090922609176843848e-14,
        2.41333276776166240844516922196e-14,
        2.02015531985181413114834031833e-16,
        -5.68171271075270422851146478874e-17,
        -3.80082421064644521052871349836e-19,
        1.05551739229841670238163200361e-19,
        5.7758422925275435667221605993e-22,
        -1.58774695838716531303310462626e-22,
        -7.24181766014636685673730787292e-25
    };

    private static final double[] STIFF_FIRST_IMAG = {
        0.100116671557942715638078149123,
        0.0429600096728215971268270800599,
        -0.00799014859477407505770275088389,
        -0.000664114111384495427035329182866,
        0.000240714510952202000864758517061,
        9.89085259369337382687437812294e-6,
        -3.22040860178194578481012477174e-6,
        -8.08401148192350365282200249069e-8,
        2.48351290049260966544658921605e-8,
        4.24154988067028660399867468349e-10,
        -1.25611378629704490237955971836e-10,
        -1.56053077919196502557674988724e-12,
        4.50565044006801278137904597946e-13,
        4.2641179237225098728291226479e-15,
        -1.2084245714879456268965803807e-15,
        -9.01338537885038989528688031325e-18,
        2.5180796700698002962991581923e-18,
        1.51955263898294940481729370636e-20,
        -4.19737873024216866691628952458e-21,
        -2.092488792285595339755624521e-23,
        5.72708467031136321701747126611e-24
    };

    private static final double[] STIFF_SECOND_REAL = {
        -0.376145877558191778393359413441,
        0.0775244431850198578126067647425,
        0.0120396593748540634695397747695,
        -0.00385683684390247509721340352427,
        -0.000232359275790231209370627606991,
        0.0000697318379146209092637310696007,
        2.32354473986257272021507575389e-6,
        -6.71692140309360615694979580992e-7,
        -1.43946361256617673523038166877e-8,
        4.06087820907414336567714443732e-9,
        6.10183339004616075548375321861e-11,
        -1.69196418769523832825063863136e-11,
        -1.88669746820541798989965091628e-13,
        5.16473095452962111184823547686e-14,
        4.45066881692009291504139737861e-16,
        -1.20625107617859803735741992452e-16,
        -8.28193837331508300767103116139e-19,
        2.22680015825230528892642524445e-19,
        1.24755889505424049389100515561e-21,
        -3.33254971913153176741833960484e-22,
        -1.55307002839777371508497520751e-24
    };

    private static final double[] STIFF_SECOND_IMAG = {
        0.0527472790869782317601048210983,
        0.00823962722148093961886198320927,
        -0.0205185842051817330153151013327,
        -0.00184683218270819613487368071941,
        0.000569681886932212757533488372406,
        0.0000248774530818801164177266528608,
        -7.31121019876580624171992432347e-6,
        -1.92744564223806538367454388776e-7,
        5.49794278719049727550379096876e-8,
        9.78237385539447442446850072421e-10,
        -2.7341624177723508216430132999e-10,
        -3.51839815887772323640101921381e-12,
        9.68934411607055794052256859665e-13,
        9.45703963505047353201918875825e-15,
        -2.57516976113400217760868402425e-15,
        -1.97419921753098238455550504742e-17,
        5.32820017906655555903355375475e-18,
        3.29581793797656865402793252539e-20,
        -8.83137325823594007269279476114e-21,
        -4.50279718100548728336329365981e-23,
        1.19941679774924468309434420379e-23
    };

    private static final double[] MASS_REAL = {
        0.148523151773238914750879360089,
        -0.0117856118001224048185631301904,
        -0.00248887208039014371691400683052,
        0.000250045060357076469386198883676,
        0.0000227217776065076434637230864113,
        -2.48764935230787745662127026799e-6,
        -1.32138506847814502856384193414e-7,
        1.50966754393693942843767293542e-8,
        5.3472999553162661403204445045e-10,
        -6.26136041009708550772228055719e-11,
        -1.59574066624737000616598104732e-12,
        1.89788785691219687197167013023e-13,
        3.66030609080549274006207730375e-15,
        -4.39955659500182569051978906011e-16,
        -6.65848768159000092224193226014e-18,
        8.06343127453005031535923212263e-19,
        9.84397490339224661524630997726e-21,
        -1.19869887155210161836484730378e-21,
        -1.20634550494837590549640883469e-23,
        1.47512193662595435067359954287e-24,
        1.24549093756962710863096766634e-26
    };

    private static final double[] MASS_IMAG = {
        -0.0454399665519585306943416687117,
        -0.0210517666740874019203591488894,
        0.00194647501081621201871675259482,
        0.000253466068123907163346571754613,
        -0.0000268083453427538717591876419304,
        -1.82138740336918117478832696004e-6,
        2.04357511048425337951376869602e-7,
        8.75944656915074206478854298947e-9,
        -1.01466837126303146739791005703e-9,
        -3.02573132377805421636557302451e-11,
        3.57358222114420372764650037191e-12,
        7.88121312149152771558608913996e-14,
        -9.42758576193708862552405242331e-15,
        -1.60439904050827900099939709069e-16,
        1.93624791035947590366500765061e-17,
        2.62394448214143482490534256935e-19,
        -3.18700789496399461681365308408e-20,
        -3.52400207248027768109209530864e-22,
        4.30074555255053206057921088056e-23,
        3.95655079023456015736315286131e-25,
        -4.84642137915095135859812028886e-26
    };

    private SectionIntegrals() {
    }

    /**
     * Diagonal mass entry for a unit-area triangle (multiply by area).
     *
     * @return the reference-triangle diagonal mass value {@code 1/6}
     */
    public static double massDiagonal() {
        return 1.0 / 6.0;
    }

    /**
     * Diagonal stiffness entry (real). Arguments are the two edge vectors at this
     * vertex: squared length of the first, their dot product, squared length of
     * the second. Divide the result by area.
     *
     * @param holonomy       triangle holonomy of the connection over this triangle
     * @param lengthSqFirst  squared length of the first edge vector at the vertex
     * @param edgeDot        dot product of the two edge vectors at the vertex
     * @param lengthSqSecond squared length of the second edge vector at the vertex
     * @return real diagonal stiffness entry for a unit-area triangle
     */
    public static double stiffnessDiagonal(double holonomy,
            double lengthSqFirst, double edgeDot, double lengthSqSecond) {
        return 0.25 * ((lengthSqFirst - 2.0 * edgeDot + lengthSqSecond)
                + holonomy * holonomy * (lengthSqFirst + edgeDot + lengthSqSecond) / 90.0);
    }

    /**
     * Off-diagonal stiffness entry (complex). Arguments are the two edge vectors
     * at the vertex opposite this edge: squared length, dot product, squared
     * length. Divide the result by area, then multiply by the transport.
     *
     * @param holonomy       triangle holonomy of the connection over this triangle
     * @param lengthSqFirst  squared length of the edge vector to the first endpoint
     * @param edgeDot        dot product of the two opposite-vertex edge vectors
     * @param lengthSqSecond squared length of the edge vector to the second endpoint
     * @return {@code double[]{ real, imaginary }} off-diagonal stiffness entry
     */
    public static double[] stiffnessOffDiagonal(double holonomy,
            double lengthSqFirst, double edgeDot, double lengthSqSecond) {
        if (Math.abs(holonomy) > Math.PI) {
            return stiffnessOffDiagonalDirect(holonomy, lengthSqFirst, edgeDot, lengthSqSecond);
        }
        boolean positive = holonomy > 0;
        double t = (positive ? holonomy : -holonomy) * 2.0 / Math.PI - 1.0;
        double re = (lengthSqFirst + lengthSqSecond) * chebyshev(t, STIFF_FIRST_REAL)
                + edgeDot * chebyshev(t, STIFF_SECOND_REAL);
        double im = (lengthSqFirst + lengthSqSecond) * chebyshev(t, STIFF_FIRST_IMAG)
                + edgeDot * chebyshev(t, STIFF_SECOND_IMAG);
        return new double[] { re, positive ? -im : im };
    }

    /**
     * Off-diagonal mass entry (complex) for a unit-area triangle. Multiply by area
     * and the transport.
     *
     * @param holonomy triangle holonomy of the connection over this triangle
     * @return {@code double[]{ real, imaginary }} off-diagonal mass entry
     */
    public static double[] massOffDiagonal(double holonomy) {
        if (Math.abs(holonomy) > Math.PI) {
            return massOffDiagonalDirect(holonomy);
        }
        boolean positive = holonomy > 0;
        double t = (positive ? holonomy : -holonomy) / Math.PI * 2.0 - 1.0;
        double re = chebyshev(t, MASS_REAL);
        double im = chebyshev(t, MASS_IMAG);
        return new double[] { re, positive ? -im : im };
    }

    /**
     * Direct (non-Chebyshev) off-diagonal stiffness evaluation for
     * {@code |holonomy| > pi}, where the series is out of range but the closed
     * form no longer suffers catastrophic cancellation.
     */
    private static double[] stiffnessOffDiagonalDirect(double s,
            double gii, double gij, double gjj) {
        double s2 = s * s;
        double s4 = s2 * s2;
        double a = 3 * gii + 4 * gij + 3 * gjj;
        double b = gii + gij + gjj;
        double d = 2 * gii + 3 * gij + 2 * gjj;
        double e = gii + 2 * gij + gjj;
        double f = gii - 2 * gij + gjj;
        double cosS = Math.cos(s);
        double sinS = Math.sin(s);
        double mulRe = -a + s2 * e / 2.0;
        double mulIm = s * d;
        double prodRe = cosS * mulRe - sinS * mulIm;
        double prodIm = cosS * mulIm + sinS * mulRe;
        double termRe = a + prodRe;
        double termIm = s * b - s2 * s * gij / 6.0 + prodIm;
        return new double[] { termRe / s4 + f / 24.0, termIm / s4 - s * f / 60.0 };
    }

    /**
     * Direct (non-Chebyshev) off-diagonal mass evaluation for
     * {@code |holonomy| > pi}.
     */
    private static double[] massOffDiagonalDirect(double s) {
        double s2 = s * s;
        double s4 = s2 * s2;
        double denom = 3 * s4;
        double re = 6 * Math.cos(s) - 6 + 3 * s2;
        double im = 6 * Math.sin(s) - 6 * s + s * s2;
        return new double[] { re / denom, im / denom };
    }

    /** Evaluate a Chebyshev series at x in [-1, 1]. */
    private static double chebyshev(double x, double[] coefficients) {
        double b2 = 0, b1 = 0, b0 = 0;
        double twoX = 2 * x;
        for (int i = coefficients.length - 1; i >= 0; i--) {
            b2 = b1;
            b1 = b0;
            b0 = twoX * b1 - b2 + coefficients[i];
        }
        return (b0 - b2) / 2.0;
    }
}
