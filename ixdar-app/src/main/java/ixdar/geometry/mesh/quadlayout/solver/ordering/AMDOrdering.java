package ixdar.geometry.mesh.quadlayout.solver.ordering;

import java.util.Arrays;
import java.util.HashSet;

import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;

/*
 * AMD/Source/amd_1: construct input matrix and then order with amd_2 AMD,
 * Copyright (c) 1996-2022, Timothy A. Davis, Patrick R. Amestoy, and Iain S.
 * Duff. All Rights Reserved. SPDX-License-Identifier: BSD-3-clause
 *
 * Modified to work in Java; see original source and license at:
 * https://github.com/DrTimothyAldenDavis/SuiteSparse/blob/dev/AMD/README.txt
 */

/**
 * Approximate-minimum-degree fill-reducing ordering, a Java port of SuiteSparse
 * AMD_1 followed by AMD_2. The input pattern is taken as unsymmetric; A+A' is
 * built internally with duplicates and diagonal entries dropped.
 */
public class AMDOrdering {

    public static final int EMPTY = -1;
    public static final double AMD_DEFAULT_DENSE = 10.0;
    public static final boolean AMD_DEFAULT_AGGRESSIVE = true;
    public static final String COMMA = ",";
    public int n; /* n > 0 */
    public int[] columnStart; /* input of size n+1, not modified */
    public int[] rowIndex; /* input of size nz = Ap [n], not modified */
    public int[] permutation; /* size n output permutation */
    public int[] inversePermutation; /* size n output inverse permutation */
    public int[] nodeListLength; /* size n input, undefined on output */
    public int slen; /*
                      * slen >= sum (Len [0..n-1]) + 7n, ideally slen = 1.2 * sum (Len) + 8n
                      */
    public int pfree;
    public int iwlen;
    public int[] workspace;
    public int[] elementPointer;
    public int[] superSize;
    public int[] degreeHead;
    public int[] elementCount;
    public int[] degree;
    public int[] flag;
    public int[] lastDegree;
    public int[] nextDegree;

    /**
     * Compute the approximate-minimum-degree fill-reducing ordering of the
     * matrix's symmetric sparsity pattern (AMD_1: build A+A' without
     * duplicates/diagonal, then run AMD_2). Results land in
     * {@link #permutation} / {@link #inversePermutation}; the matrix itself is
     * left unpermuted, and callers needing a permuted copy build one through the
     * {@link NormalMatrix} permuting constructor.
     *
     * @param matrix symmetric system matrix to order; not modified
     */
    public void order(NormalMatrix matrix) {
        n = matrix.variableCount;
        slen = matrix.rowColumn.length + matrix.rowColumn.length / 5 + 7 * n;
        iwlen = slen - 6 * n;
        columnStart = new int[n + 1];
        int[] sortedColumns = matrix.rowColumn.clone();
        int[] columnDegree = new int[n];
        for (int column = 0; column < n; column++) {
            Arrays.sort(sortedColumns, matrix.rowStart[column], matrix.rowStart[column + 1]);
            int uniqueCount = 0;
            int previousRow = -1;
            for (int cursor = matrix.rowStart[column]; cursor < matrix.rowStart[column + 1]; cursor++) {
                int row = sortedColumns[cursor];
                if (row != column && row != previousRow) {
                    uniqueCount++;
                    previousRow = row;
                }
            }
            columnDegree[column] = uniqueCount;
        }

        for (int column = 0; column < n; column++) {
            columnStart[column + 1] = columnStart[column] + columnDegree[column];
        }
        int numOffDiagonalEntries = columnStart[n];
        rowIndex = new int[numOffDiagonalEntries];
        permutation = new int[n];
        inversePermutation = new int[n];
        nodeListLength = new int[n];
        workspace = new int[iwlen];
        elementPointer = new int[n];
        superSize = new int[n];
        degreeHead = new int[n];
        elementCount = new int[n];
        degree = new int[n];
        flag = new int[n];
        lastDegree = new int[n];
        nextDegree = new int[n];

        for (int column = 0; column < n; column++) {
            int writeIndex = columnStart[column];
            int previousRow = -1;
            for (int cursor = matrix.rowStart[column]; cursor < matrix.rowStart[column + 1]; cursor++) {
                int row = sortedColumns[cursor];
                if (row != column && row != previousRow) {
                    rowIndex[writeIndex++] = row;
                    previousRow = row;
                }
            }
            nodeListLength[column] = writeIndex - columnStart[column];
        }

        /* construct the pointers for A+A' */
        int[] scatterPointer = superSize; /* use Nv and W as workspace for Sp and Tp [ */
        int[] transposeCursor = flag;
        pfree = 0;
        for (int j = 0; j < n; j++) {
            elementPointer[j] = pfree;
            scatterPointer[j] = pfree;
            pfree += nodeListLength[j];
        }

        for (int k = 0; k < n; k++) {
            int p1 = columnStart[k];
            int p2 = columnStart[k + 1];

            int scanValueP = p1;
            /* construct A+A' */
            for (int p = scanValueP; p < p2; p++) {
                /* scan the upper triangular part of A */
                int j = rowIndex[p];
                if (j < k) {
                    /* entry A (j,k) in the strictly upper triangular part */
                    workspace[scatterPointer[j]++] = k;
                    workspace[scatterPointer[k]++] = j;
                    scanValueP++;
                } else if (j == k) {
                    /* skip the diagonal */
                    scanValueP++;
                    break;
                } else /* j > k */
                {
                    /* first entry below the diagonal */
                    break;
                }
                /*
                 * scan lower triangular part of A, in column j until reaching row k. Start
                 * where last scan left off.
                 */
                int pj2 = columnStart[j + 1];
                int scanValue = transposeCursor[j];
                for (int pj = scanValue; pj < pj2; pj++) {
                    int i = rowIndex[pj];
                    if (i < k) {
                        workspace[scatterPointer[i]++] = j;
                        workspace[scatterPointer[j]++] = i;
                        scanValue++;
                    } else if (i == k) {
                        /* entry A (k,j) in lower part and A (j,k) in upper */
                        scanValue++;
                        break;
                    } else /* i > k */
                    {
                        /* consider this entry later, when k advances to i */
                        break;
                    }
                }
                transposeCursor[j] = scanValue;
            }
            transposeCursor[k] = scanValueP;
        }

        /* clean up, for remaining mismatched entries */
        for (int j = 0; j < n; j++) {
            for (int pj = transposeCursor[j]; pj < columnStart[j + 1]; pj++) {
                int i = rowIndex[pj];
                workspace[scatterPointer[i]++] = j;
                workspace[scatterPointer[j]++] = i;
            }
        }

        /* Tp and Sp no longer needed ] */

        /* --------------------------------------------------------------------- */
        /* order the matrix */
        /* --------------------------------------------------------------------- */

        orderMatrix();

        System.arraycopy(lastDegree, 0, permutation, 0, n);
        System.arraycopy(nextDegree, 0, inversePermutation, 0, n);
    }

    private void orderMatrix() {
        int me = EMPTY;
        boolean aggressive = AMD_DEFAULT_AGGRESSIVE;
        /* Note: if alpha is NaN, this is undefined: */
        int dense;
        double alpha = AMD_DEFAULT_DENSE;
        if (alpha < 0) {
            /* only remove completely dense rows/columns */
            dense = n - 2;
        } else {
            dense = (int) (alpha * Math.sqrt((double) n));
        }
        dense = Math.max(16, dense);
        dense = Math.min(n, dense);

        for (int i = 0; i < n; i++) {
            lastDegree[i] = EMPTY;
            degreeHead[i] = EMPTY;
            nextDegree[i] = EMPTY;
            /*
             * if separate Hhead array is used for hash buckets: * Hhead [i] = EMPTY ;
             */
            superSize[i] = 1;
            flag[i] = 1;
            elementCount[i] = 0;
            degree[i] = nodeListLength[i];
        }

        /* --------------------------------------------------------------------- */
        /* initialize degree lists and eliminate dense and empty rows */
        /* --------------------------------------------------------------------- */

        int deg;
        int nel = 0;
        for (int i = 0; i < n; i++) {
            deg = degree[i];

            if (deg == 0) {

                /*
                 * ------------------------------------------------------------- we have a
                 * variable that can be eliminated at once because there is no off-diagonal
                 * non-zero in its row. Note that Nv [i] = 1 for an empty variable i. It is
                 * treated just the same as an eliminated element i.
                 * -------------------------------------------------------------
                 */

                elementCount[i] = FLIP(1);
                nel++;
                elementPointer[i] = EMPTY;
                flag[i] = 0;

            } else if (deg > dense) {

                /*
                 * ------------------------------------------------------------- Dense variables
                 * are not treated as elements, but as unordered, non-principal variables that
                 * have no parent. They do not take part in the postorder, since Nv [i] = 0.
                 * Note that the Fortran version does not have this option.
                 * -------------------------------------------------------------
                 */
                superSize[i] = 0; /* do not postorder this node */
                elementCount[i] = EMPTY;
                nel++;
                elementPointer[i] = EMPTY;

            } else {

                /*
                 * ------------------------------------------------------------- place i in the
                 * degree list corresponding to its degree
                 * -------------------------------------------------------------
                 */

                int inext = degreeHead[deg];

                if (inext != EMPTY)
                    lastDegree[inext] = i;
                nextDegree[i] = inext;
                degreeHead[deg] = i;

            }
        }

        /* ========================================================================= */
        /* GET PIVOT OF MINIMUM DEGREE */
        /* ========================================================================= */

        int mindeg = 0;
        /* initialize wflg */
        int wbig = Integer.MAX_VALUE - n;
        int wflg = clear_flag(0, wbig, flag, n);
        int lemax = 0;
        while (nel < n) {

            /* ----------------------------------------------------------------- */
            /* find next supervariable for elimination */
            /* ----------------------------------------------------------------- */

            for (deg = mindeg; deg < n; deg++) {
                me = degreeHead[deg];
                if (me != EMPTY)
                    break;
            }
            mindeg = deg;

            /* ----------------------------------------------------------------- */
            /* remove chosen variable from link list */
            /* ----------------------------------------------------------------- */

            int inext = nextDegree[me];
            if (inext != EMPTY)
                lastDegree[inext] = EMPTY;
            degreeHead[deg] = inext;

            /* ----------------------------------------------------------------- */
            /* me represents the elimination of pivots nel to nel+Nv[me]-1. */
            /* place me itself as the first in this set. */
            /* ----------------------------------------------------------------- */

            int elenme = elementCount[me];
            int nvpiv = superSize[me];

            nel += nvpiv;

            /* ========================================================================= */
            /* CONSTRUCT NEW ELEMENT */
            /* ========================================================================= */

            /*
             * ----------------------------------------------------------------- At this
             * point, me is the pivotal supervariable. It will be converted into the current
             * element. Scan list of the pivotal supervariable, me, setting tree pointers
             * and constructing new list of supervariables for the new element, me. p is a
             * pointer to the current position in the old list.
             * -----------------------------------------------------------------
             */

            /* flag the variable "me" as being in Lme by negating Nv [me] */
            superSize[me] = -nvpiv;
            int degme = 0;
            int pme1;
            int pme2;
            if (elenme == 0) {

                /* ------------------------------------------------------------- */
                /* construct the new element in place */
                /* ------------------------------------------------------------- */

                pme1 = elementPointer[me];
                pme2 = pme1 - 1;

                for (int p = pme1; p <= pme1 + nodeListLength[me] - 1; p++) {
                    int i = workspace[p];

                    int nvi = superSize[i];
                    if (nvi > 0) {

                        /* ----------------------------------------------------- */
                        /* i is a principal variable not yet placed in Lme. */
                        /* store i in new list */
                        /* ----------------------------------------------------- */

                        /* flag i as being in Lme by negating Nv [i] */
                        degme += nvi;
                        superSize[i] = -nvi;
                        workspace[++pme2] = i;

                        /* ----------------------------------------------------- */
                        /* remove variable i from degree list. */
                        /* ----------------------------------------------------- */

                        int ilast = lastDegree[i];
                        int jnext = nextDegree[i];
                        if (jnext != EMPTY)
                            lastDegree[jnext] = ilast;
                        if (ilast != EMPTY) {
                            nextDegree[ilast] = jnext;
                        } else {
                            /* i is at the head of the degree list */
                            degreeHead[degree[i]] = jnext;
                        }
                    }
                }
            } else {

                /* ------------------------------------------------------------- */
                /* construct the new element in empty space, Iw [pfree ...] */
                /* ------------------------------------------------------------- */

                int p = elementPointer[me];
                pme1 = pfree;
                int slenme = nodeListLength[me] - elenme;

                for (int knt1 = 1; knt1 <= elenme + 1; knt1++) {
                    int e;
                    int pj;
                    int ln;
                    if (knt1 > elenme) {
                        /* search the supervariables in me. */
                        e = me;
                        pj = p;
                        ln = slenme;

                    } else {
                        /* search the elements in me. */
                        e = workspace[p++];

                        pj = elementPointer[e];
                        ln = nodeListLength[e];

                    }

                    /*
                     * --------------------------------------------------------- search for
                     * different supervariables and add them to the new list, compressing when
                     * necessary. this loop is executed once for each element in the list and once
                     * for all the supervariables in the list.
                     * ---------------------------------------------------------
                     */

                    for (int knt2 = 1; knt2 <= ln; knt2++) {
                        int i = workspace[pj++];

                        int nvi = superSize[i];

                        if (nvi > 0) {

                            /* ------------------------------------------------- */
                            /* compress Iw, if necessary */
                            /* ------------------------------------------------- */

                            if (pfree >= iwlen) {

                                /*
                                 * prepare for compressing Iw by adjusting pointers and lengths so that the
                                 * lists being searched in the inner and outer loops contain only the remaining
                                 * entries.
                                 */

                                elementPointer[me] = p;
                                nodeListLength[me] -= knt1;
                                /* check if nothing left of supervariable me */
                                if (nodeListLength[me] == 0)
                                    elementPointer[me] = EMPTY;
                                elementPointer[e] = pj;
                                nodeListLength[e] = ln - knt2;
                                /* nothing left of element e */
                                if (nodeListLength[e] == 0)
                                    elementPointer[e] = EMPTY;

                                /* store first entry of each object in Pe */
                                /* FLIP the first entry in each object */
                                for (int j = 0; j < n; j++) {
                                    int pn = elementPointer[j];
                                    if (pn >= 0) {

                                        elementPointer[j] = workspace[pn];
                                        workspace[pn] = FLIP(j);
                                    }
                                }

                                /* psrc/pdst point to source/destination */
                                int psrc = 0;
                                int pdst = 0;
                                int pend = pme1 - 1;

                                while (psrc <= pend) {
                                    /* search for next FLIP'd entry */
                                    int j = FLIP(workspace[psrc++]);
                                    if (j >= 0) {

                                        workspace[pdst] = elementPointer[j];
                                        elementPointer[j] = pdst++;
                                        int lenj = nodeListLength[j];
                                        /* copy from source to destination */
                                        for (int knt3 = 0; knt3 <= lenj - 2; knt3++) {
                                            workspace[pdst++] = workspace[psrc++];
                                        }
                                    }
                                }

                                /* move the new partially-constructed element */
                                int p1 = pdst;
                                for (int source = pme1; source <= pfree - 1; source++) {
                                    workspace[pdst++] = workspace[source];
                                }
                                pme1 = p1;
                                pfree = pdst;
                                pj = elementPointer[e];
                                p = elementPointer[me];

                            }

                            /* ------------------------------------------------- */
                            /* i is a principal variable not yet placed in Lme */
                            /* store i in new list */
                            /* ------------------------------------------------- */

                            /* flag i as being in Lme by negating Nv [i] */
                            degme += nvi;
                            superSize[i] = -nvi;
                            workspace[pfree++] = i;

                            /* ------------------------------------------------- */
                            /* remove variable i from degree link list */
                            /* ------------------------------------------------- */

                            int ilast = lastDegree[i];
                            int jnext = nextDegree[i];

                            if (jnext != EMPTY)
                                lastDegree[jnext] = ilast;
                            if (ilast != EMPTY) {
                                nextDegree[ilast] = jnext;
                            } else {
                                /* i is at the head of the degree list */

                                degreeHead[degree[i]] = jnext;
                            }
                        }
                    }

                    if (e != me) {
                        /*
                         * set tree pointer and flag to indicate element e is absorbed into new element
                         * me (the parent of e is me)
                         */

                        elementPointer[e] = FLIP(me);
                        flag[e] = 0;
                    }
                }

                pme2 = pfree - 1;
            }

            /* ----------------------------------------------------------------- */
            /* me has now been converted into an element in Iw [pme1..pme2] */
            /* ----------------------------------------------------------------- */

            /* degme holds the external degree of new element */
            degree[me] = degme;
            elementPointer[me] = pme1;
            nodeListLength[me] = pme2 - pme1 + 1;

            elementCount[me] = FLIP(nvpiv + degme);
            /*
             * FLIP (Elen (me)) is now the degree of pivot (including diagonal part).
             */

            /* ----------------------------------------------------------------- */
            /* make sure that wflg is not too large. */
            /* ----------------------------------------------------------------- */

            /*
             * With the current value of wflg, wflg+n must not cause integer overflow
             */
            wflg = clear_flag(wflg, wbig, flag, n);

            /* ========================================================================= */
            /* COMPUTE (W [e] - wflg) = |Le\Lme| FOR ALL ELEMENTS */
            /* ========================================================================= */

            /*
             * ----------------------------------------------------------------- Scan 1:
             * compute the external degrees of previous elements with respect to the current
             * element. That is: (W [e] - wflg) = |Le \ Lme| for each element e that appears
             * in any supervariable in Lme. The notation Le refers to the pattern (list of
             * supervariables) of a previous element e, where e is not yet absorbed, stored
             * in Iw [Pe [e] + 1 ... Pe [e] + Len [e]]. The notation Lme refers to the
             * pattern of the current element (stored in Iw [pme1..pme2]). If aggressive
             * absorption is enabled, and (W [e] - wflg) becomes zero, then the element e
             * will be absorbed in Scan 2.
             * -----------------------------------------------------------------
             */

            for (int pme = pme1; pme <= pme2; pme++) {
                int i = workspace[pme];

                int eln = elementCount[i];

                if (eln > 0) {
                    /* note that Nv [i] has been negated to denote i in Lme: */
                    int nvi = -superSize[i];

                    int wnvi = wflg - nvi;
                    for (int p = elementPointer[i]; p <= elementPointer[i] + eln - 1; p++) {
                        int e = workspace[p];

                        int we = flag[e];

                        if (we >= wflg) {
                            /* unabsorbed element e has been seen in this loop */

                            we -= nvi;
                        } else if (we != 0) {
                            /* e is an unabsorbed element */
                            /* this is the first we have seen e in all of Scan 1 */

                            we = degree[e] + wnvi;
                        }

                        flag[e] = we;
                    }
                }
            }

            /* ========================================================================= */
            /* DEGREE UPDATE AND ELEMENT ABSORPTION */
            /* ========================================================================= */

            /*
             * ----------------------------------------------------------------- Scan 2: for
             * each i in Lme, sum up the degree of Lme (which is degme), plus the sum of the
             * external degrees of each Le for the elements e appearing within i, plus the
             * supervariables in i. Place i in hash list.
             * -----------------------------------------------------------------
             */

            for (int pme = pme1; pme <= pme2; pme++) {
                int i = workspace[pme];

                int p1 = elementPointer[i];
                int p2 = p1 + elementCount[i] - 1;
                int pn = p1;
                int hash = 0;
                deg = 0;

                /* ------------------------------------------------------------- */
                /* scan the element list associated with supervariable i */
                /* ------------------------------------------------------------- */

                /* UMFPACK/MA38-style approximate degree: */
                if (aggressive) {
                    for (int p = p1; p <= p2; p++) {
                        int e = workspace[p];

                        int we = flag[e];
                        if (we != 0) {
                            /* e is an unabsorbed element */
                            /* dext = | Le \ Lme | */
                            int dext = we - wflg;
                            if (dext > 0) {
                                deg += dext;
                                workspace[pn++] = e;
                                hash += e;

                            } else {
                                /* external degree of e is zero, absorb e into me */

                                elementPointer[e] = FLIP(me);
                                flag[e] = 0;
                            }
                        }
                    }
                } else {
                    for (int p = p1; p <= p2; p++) {
                        int e = workspace[p];

                        int we = flag[e];
                        if (we != 0) {
                            /* e is an unabsorbed element */
                            int dext = we - wflg;

                            deg += dext;
                            workspace[pn++] = e;
                            hash += e;

                        }
                    }
                }

                /* count the number of elements in i (including me): */
                elementCount[i] = pn - p1 + 1;

                /* ------------------------------------------------------------- */
                /* scan the supervariables in the list associated with i */
                /* ------------------------------------------------------------- */

                /*
                 * The bulk of the AMD run time is typically spent in this loop, particularly if
                 * the matrix has many dense rows that are not removed prior to ordering.
                 */
                int p3 = pn;
                int p4 = p1 + nodeListLength[i];
                for (int p = p2 + 1; p < p4; p++) {
                    int j = workspace[p];

                    int nvj = superSize[j];
                    if (nvj > 0) {
                        /* j is unabsorbed, and not in Lme. */
                        /* add to degree and add to new list */
                        deg += nvj;
                        workspace[pn++] = j;
                        hash += j;

                    }
                }

                /* ------------------------------------------------------------- */
                /* update the degree and check for mass elimination */
                /* ------------------------------------------------------------- */

                /*
                 * with aggressive absorption, deg==0 is identical to the Elen [i] == 1 && p3 ==
                 * pn test, below.
                 */

                if (elementCount[i] == 1 && p3 == pn) {

                    /* --------------------------------------------------------- */
                    /* mass elimination */
                    /* --------------------------------------------------------- */

                    /*
                     * There is nothing left of this node except for an edge to the current pivot
                     * element. Elen [i] is 1, and there are no variables adjacent to node i. Absorb
                     * i into the current pivot element, me. Note that if there are two or more mass
                     * eliminations, fillin due to mass elimination is possible within the
                     * nvpiv-by-nvpiv pivot block. It is this step that causes AMD's analysis to be
                     * an upper bound.
                     *
                     * The reason is that the selected pivot has a lower approximate degree than the
                     * true degree of the two mass eliminated nodes. There is no edge between the
                     * two mass eliminated nodes. They are merged with the current pivot anyway.
                     *
                     * No fillin occurs in the Schur complement, in any case, and this effect does
                     * not decrease the quality of the ordering itself, just the quality of the
                     * nonzero and flop count analysis. It also means that the post-ordering is not
                     * an exact elimination tree post-ordering.
                     */

                    elementPointer[i] = FLIP(me);
                    int nvi = -superSize[i];
                    degme -= nvi;
                    nvpiv += nvi;
                    nel += nvi;
                    superSize[i] = 0;
                    elementCount[i] = EMPTY;

                } else {

                    /* --------------------------------------------------------- */
                    /* update the upper-bound degree of i */
                    /* --------------------------------------------------------- */

                    /*
                     * the following degree does not yet include the size of the current element,
                     * which is added later:
                     */

                    degree[i] = Math.min(degree[i], deg);

                    /* --------------------------------------------------------- */
                    /* add me to the list for i */
                    /* --------------------------------------------------------- */

                    /* move first supervariable to end of list */
                    workspace[pn] = workspace[p3];
                    /* move first element to end of element part of list */
                    workspace[p3] = workspace[p1];
                    /* add new element, me, to front of list. */
                    workspace[p1] = me;
                    /* store the new length of the list in Len [i] */
                    nodeListLength[i] = pn - p1 + 1;

                    /* --------------------------------------------------------- */
                    /* place in hash bucket. Save hash key of i in Last [i]. */
                    /* --------------------------------------------------------- */

                    /*
                     * NOTE: this can fail if hash is negative, because the ANSI C standard does not
                     * define a % b when a and/or b are negative. That's why hash is defined as an
                     * unsigned Int, to avoid this problem.
                     */
                    hash = hash % n;

                    /* if the Hhead array is not used: */
                    int j = degreeHead[hash];
                    if (j <= EMPTY) {
                        /* degree list is empty, hash head is FLIP (j) */
                        nextDegree[i] = FLIP(j);
                        degreeHead[hash] = FLIP(i);
                    } else {
                        /*
                         * degree list is not empty, use Last [Head [hash]] as hash head.
                         */
                        nextDegree[i] = lastDegree[j];
                        lastDegree[j] = i;
                    }

                    /*
                     * if a separate Hhead array is used: * Next [i] = Hhead [hash] ; Hhead [hash] =
                     * i ;
                     */

                    lastDegree[i] = hash;
                }
            }

            degree[me] = degme;

            /* ----------------------------------------------------------------- */
            /* Clear the counter array, W [...], by incrementing wflg. */
            /* ----------------------------------------------------------------- */

            /* make sure that wflg+n does not cause integer overflow */
            lemax = Math.max(lemax, degme);
            wflg += lemax;
            wflg = clear_flag(wflg, wbig, flag, n);
            /* at this point, W [0..n-1] < wflg holds */

            /* ========================================================================= */
            /* SUPERVARIABLE DETECTION */
            /* ========================================================================= */

            for (int pme = pme1; pme <= pme2; pme++) {
                int i = workspace[pme];

                if (superSize[i] < 0) {
                    /* i is a principal variable in Lme */

                    /*
                     * --------------------------------------------------------- examine all hash
                     * buckets with 2 or more variables. We do this by examing all unique hash keys
                     * for supervariables in the pattern Lme of the current element, me
                     * ---------------------------------------------------------
                     */

                    /* let i = head of hash bucket, and empty the hash bucket */

                    int hash = lastDegree[i];

                    /* if Hhead array is not used: */
                    int j = degreeHead[hash];
                    if (j == EMPTY) {
                        /* hash bucket and degree list are both empty */
                        i = EMPTY;
                    } else if (j < EMPTY) {
                        /* degree list is empty */
                        i = FLIP(j);
                        degreeHead[hash] = EMPTY;
                    } else {
                        /* degree list is not empty, restore Last [j] of head j */
                        i = lastDegree[j];
                        lastDegree[j] = EMPTY;
                    }

                    /*
                     * if separate Hhead array is used: * i = Hhead [hash] ; Hhead [hash] = EMPTY ;
                     */

                    while (i != EMPTY && nextDegree[i] != EMPTY) {

                        /*
                         * ----------------------------------------------------- this bucket has one or
                         * more variables following i. scan all of them to see if i can absorb any
                         * entries that follow i in hash bucket. Scatter i into w.
                         * -----------------------------------------------------
                         */

                        int ln = nodeListLength[i];
                        int eln = elementCount[i];

                        /* do not flag the first element in the list (me) */
                        for (int p = elementPointer[i] + 1; p <= elementPointer[i] + ln - 1; p++) {

                            flag[workspace[p]] = wflg;
                        }

                        /* ----------------------------------------------------- */
                        /* scan every other entry j following i in bucket */
                        /* ----------------------------------------------------- */

                        int jlast = i;
                        j = nextDegree[i];

                        while (j != EMPTY) {
                            /* ------------------------------------------------- */
                            /* check if j and i have identical nonzero pattern */
                            /* ------------------------------------------------- */

                            /* check if i and j have the same Len and Elen */

                            boolean ok = (nodeListLength[j] == ln) && (elementCount[j] == eln);
                            /* skip the first element in the list (me) */
                            for (int p = elementPointer[j] + 1; ok && p <= elementPointer[j] + ln - 1; p++) {

                                if (flag[workspace[p]] != wflg) {
                                    ok = false;
                                }
                            }
                            if (ok) {
                                /* --------------------------------------------- */
                                /* found it! j can be absorbed into i */
                                /* --------------------------------------------- */

                                elementPointer[j] = FLIP(i);
                                /* both Nv [i] and Nv [j] are negated since they */
                                /* are in Lme, and the absolute values of each */
                                /* are the number of variables in i and j: */
                                superSize[i] += superSize[j];
                                superSize[j] = 0;
                                elementCount[j] = EMPTY;
                                /* delete j from hash bucket */

                                j = nextDegree[j];
                                nextDegree[jlast] = j;

                            } else {
                                /* j cannot be absorbed into i */
                                jlast = j;

                                j = nextDegree[j];
                            }

                        }

                        /*
                         * ----------------------------------------------------- no more variables can
                         * be absorbed into i go to next i in bucket and clear flag array
                         * -----------------------------------------------------
                         */

                        wflg++;
                        i = nextDegree[i];

                    }
                }
            }

            /* ========================================================================= */
            /* RESTORE DEGREE LISTS AND REMOVE NONPRINCIPAL SUPERVARIABLES FROM ELEMENT */
            /* ========================================================================= */

            int p = pme1;
            int nleft = n - nel;
            for (int pme = pme1; pme <= pme2; pme++) {
                int i = workspace[pme];

                int nvi = -superSize[i];

                if (nvi > 0) {
                    /* i is a principal variable in Lme */
                    /* restore Nv [i] to signify that i is principal */
                    superSize[i] = nvi;

                    /* --------------------------------------------------------- */
                    /* compute the external degree (add size of current element) */
                    /* --------------------------------------------------------- */

                    deg = degree[i] + degme - nvi;
                    deg = Math.min(deg, nleft - nvi);

                    /* --------------------------------------------------------- */
                    /* place the supervariable at the head of the degree list */
                    /* --------------------------------------------------------- */

                    inext = degreeHead[deg];

                    if (inext != EMPTY)
                        lastDegree[inext] = i;
                    nextDegree[i] = inext;
                    lastDegree[i] = EMPTY;
                    degreeHead[deg] = i;

                    /* --------------------------------------------------------- */
                    /* save the new degree, and find the minimum degree */
                    /* --------------------------------------------------------- */

                    mindeg = Math.min(mindeg, deg);
                    degree[i] = deg;

                    /* --------------------------------------------------------- */
                    /* place the supervariable in the element pattern */
                    /* --------------------------------------------------------- */

                    workspace[p++] = i;

                }
            }

            /* ========================================================================= */
            /* FINALIZE THE NEW ELEMENT */
            /* ========================================================================= */

            superSize[me] = nvpiv;
            /* save the length of the list for the new element me */
            nodeListLength[me] = p - pme1;
            if (nodeListLength[me] == 0) {
                /* there is nothing left of the current pivot element */
                /* it is a root of the assembly tree */
                elementPointer[me] = EMPTY;
                flag[me] = 0;
            }
            if (elenme != 0) {
                /* element was not constructed in place: deallocate part of */
                /* it since newly nonprincipal variables may have been removed */
                pfree = p;
            }
        }

        /* ========================================================================= */
        /* POST-ORDERING */
        /* ========================================================================= */

        /*
         * -------------------------------------------------------------------------
         * Variables at this point:
         *
         * Pe: holds the elimination tree. The parent of j is FLIP (Pe [j]), or EMPTY if
         * j is a root. The tree holds both elements and non-principal (unordered)
         * variables absorbed into them. Dense variables are non-principal and
         * unordered.
         *
         * Elen: holds the size of each element, including the diagonal part. FLIP (Elen
         * [e]) > 0 if e is an element. For unordered variables i, Elen [i] is EMPTY.
         *
         * Nv: Nv [e] > 0 is the number of pivots represented by the element e. For
         * unordered variables i, Nv [i] is zero.
         *
         * Contents no longer needed: W, Iw, Len, Degree, Head, Next, Last.
         *
         * The matrix itself has been destroyed.
         *
         * n: the size of the matrix. No other scalars needed (pfree, iwlen, etc.)
         * -------------------------------------------------------------------------
         */

        /* restore Pe */
        for (int i = 0; i < n; i++) {
            elementPointer[i] = FLIP(elementPointer[i]);
        }

        /* restore Elen, for output information, and for postordering */
        for (int i = 0; i < n; i++) {
            elementCount[i] = FLIP(elementCount[i]);
        }

        /* ========================================================================= */
        /* compress the paths of the variables */
        /* ========================================================================= */

        for (int i = 0; i < n; i++) {
            if (superSize[i] == 0) {

                /*
                 * ------------------------------------------------------------- i is an
                 * un-ordered row. Traverse the tree from i until reaching an element, e. The
                 * element, e, was the principal supervariable of i and all nodes in the path
                 * from i to when e was selected as pivot.
                 * -------------------------------------------------------------
                 */

                int j = elementPointer[i];

                if (j == EMPTY) {
                    /* Skip a dense variable. It has no parent. */

                    continue;
                }

                /* while (j is a variable) */
                while (superSize[j] == 0) {

                    j = elementPointer[j];

                }
                /* got to an element e */
                int e = j;

                /*
                 * ------------------------------------------------------------- traverse the
                 * path again from i to e, and compress the path (all nodes point to e). Path
                 * compression allows this code to compute in O(n) time.
                 * -------------------------------------------------------------
                 */

                int k = i;
                /* while (j is a variable) */
                while (superSize[k] == 0) {
                    int knext = elementPointer[k];

                    elementPointer[k] = e;
                    k = knext;

                }
            }
        }

        /* ========================================================================= */
        /* postorder the assembly tree */
        /* ========================================================================= */

        AMD_postorder(n, elementPointer, superSize, elementCount,
                flag, /* output order */
                degreeHead, nextDegree, lastDegree); /* workspace */

        /* ========================================================================= */
        /* compute output permutation and inverse permutation */
        /* ========================================================================= */

        /*
         * W [e] = k means that element e is the kth element in the new order. e is in
         * the range 0 to n-1, and k is in the range 0 to the number of elements. Use
         * Head for inverse order.
         */

        for (int k = 0; k < n; k++) {
            degreeHead[k] = EMPTY;
            nextDegree[k] = EMPTY;
        }
        for (int e = 0; e < n; e++) {
            int k = flag[e];

            if (k != EMPTY) {

                degreeHead[k] = e;
            }
        }

        /*
         * construct output inverse permutation in Next, and permutation in Last
         */
        nel = 0;
        for (int k = 0; k < n; k++) {
            int e = degreeHead[k];
            if (e == EMPTY)
                break;

            nextDegree[e] = nel;
            nel += superSize[e];
        }

        /* order non-principal variables (dense, & those merged into supervar's) */
        for (int i = 0; i < n; i++) {
            if (superSize[i] == 0) {
                int e = elementPointer[i];

                if (e != EMPTY) {
                    /*
                     * This is an unordered variable that was merged into element e via supernode
                     * detection or mass elimination of i when e became the pivot element. Place i
                     * in order just before e.
                     */

                    nextDegree[i] = nextDegree[e];
                    nextDegree[e]++;
                } else {
                    /*
                     * This is a dense unordered variable, with no parent. Place it last in the
                     * output order.
                     */
                    nextDegree[i] = nel++;
                }
            }
        }

        for (int i = 0; i < n; i++) {
            int k = nextDegree[i];

            lastDegree[k] = i;

        }
    }

    private void AMD_postorder(int nn, int[] Parent, int[] Nv, int[] Fsize, int[] Order, int[] Child, int[] Sibling,
            int[] Stack) {
        for (int j = 0; j < nn; j++) {
            Child[j] = EMPTY;
            Sibling[j] = EMPTY;
        }
        /* --------------------------------------------------------------------- */
        /* place the children in link lists - bigger elements tend to be last */
        /* --------------------------------------------------------------------- */

        for (int j = nn - 1; j >= 0; j--) {
            if (Nv[j] > 0) {
                /* this is an element */
                int parent = Parent[j];
                if (parent != EMPTY) {
                    /* place the element in link list of the children its parent */
                    /* bigger elements will tend to be at the end of the list */
                    Sibling[j] = Child[parent];
                    Child[parent] = j;
                }
            }
        }

        /* --------------------------------------------------------------------- */
        /* place the largest child last in the list of children for each node */
        /* --------------------------------------------------------------------- */

        for (int i = 0; i < nn; i++) {
            if (Nv[i] > 0 && Child[i] != EMPTY) {
                /* find the biggest element in the child list */
                int fprev = EMPTY;
                int maxfrsize = EMPTY;
                int bigfprev = EMPTY;
                int bigf = EMPTY;
                for (int f = Child[i]; f != EMPTY; f = Sibling[f]) {
                    int frsize = Fsize[f];
                    if (frsize >= maxfrsize) {
                        /* this is the biggest seen so far */
                        maxfrsize = frsize;
                        bigfprev = fprev;
                        bigf = f;
                    }
                    fprev = f;
                }

                int fnext = Sibling[bigf];

                if (fnext != EMPTY) {
                    /* if fnext is EMPTY then bigf is already at the end of list */

                    if (bigfprev == EMPTY) {
                        /* delete bigf from the element of the list */
                        Child[i] = fnext;
                    } else {
                        /* delete bigf from the middle of the list */
                        Sibling[bigfprev] = fnext;
                    }

                    /* put bigf at the end of the list */
                    Sibling[bigf] = EMPTY;
                    Sibling[fprev] = bigf;
                }

            }
        }

        /* --------------------------------------------------------------------- */
        /* postorder the assembly tree */
        /* --------------------------------------------------------------------- */

        for (int i = 0; i < nn; i++) {
            Order[i] = EMPTY;
        }

        int k = 0;

        for (int i = 0; i < nn; i++) {
            if (Parent[i] == EMPTY && Nv[i] > 0) {
                k = AMD_post_tree(i, k, Child, Sibling, Order, Stack);
            }
        }
    }

    private int AMD_post_tree(int root, int k, int[] Child, int[] Sibling, int[] Order, int[] Stack) {

        /* push root on the stack */
        int head = 0;
        Stack[0] = root;

        while (head >= 0) {
            /* get head of stack */
            int i = Stack[head];

            if (Child[i] != EMPTY) {
                /* the children of i are not yet ordered */
                /* push each child onto the stack in reverse order */
                /* so that small ones at the head of the list get popped first */
                /* and the biggest one at the end of the list gets popped last */
                for (int f = Child[i]; f != EMPTY; f = Sibling[f]) {
                    head++;
                }
                int h = head;
                for (int f = Child[i]; f != EMPTY; f = Sibling[f]) {
                    Stack[h--] = f;
                }

                /* delete child list so that i gets ordered next time we see it */
                Child[i] = EMPTY;
            } else {
                /* the children of i (if there were any) are already ordered */
                /* remove i from the stack and order it. Front i is kth front */
                head--;
                Order[i] = k++;
            }

        }
        return k;
    }

    private int clear_flag(int wflg, int wbig, int[] W, int n) {
        if (wflg < 2 || wflg >= wbig) {
            for (int x = 0; x < n; x++) {
                if (W[x] != 0)
                    W[x] = 1;
            }
            wflg = 2;
        }
        /* at this point, W [0..n-1] < wflg holds */
        return (wflg);
    }

    private int FLIP(int i) {
        return -(i) - 2;
    }

    /**
     * AMD requires a structurally symmetric, in-range pattern (the AMD_valid step
     * the C code assumes was already run). NormalMatrix is supposed to be
     * symmetric; if it isn't, Len is wrong and the A+A' build corrupts.
     *
     * @param m the matrix to validate
     */
    private void validateSymmetricPattern(NormalMatrix m) {
        int variableCount = m.variableCount;
        HashSet<Long> entries = new HashSet<>();
        for (int row = 0; row < variableCount; row++) {
            for (int c = m.rowStart[row]; c < m.rowStart[row + 1]; c++) {
                int col = m.rowColumn[c];
                if (col < 0 || col >= variableCount) {
                    throw new IllegalStateException(
                            "AMD: column index " + col + " out of range [0," + variableCount + ") in row " + row);
                }
                if (col != row)
                    entries.add(((long) row << 32) | (col & 0xFFFFFFFFL));
            }
        }
        for (long key : entries) {
            int row = (int) (key >>> 32), col = (int) (key & 0xFFFFFFFFL);
            long mirror = ((long) col << 32) | (row & 0xFFFFFFFFL);
            if (!entries.contains(mirror)) {
                throw new IllegalStateException(
                        "AMD: pattern not structurally symmetric — (" + row + COMMA + col
                                + ") present but (" + col + COMMA + row + ") missing. "
                                + "assemble() must mirror every off-diagonal.");
            }
        }
    }
}