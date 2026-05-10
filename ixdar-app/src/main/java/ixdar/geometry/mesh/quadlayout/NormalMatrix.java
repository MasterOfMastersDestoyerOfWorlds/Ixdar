package ixdar.geometry.mesh.quadlayout;

public final class NormalMatrix {
    public static final double HALF = 0.5;
    final int variableCount;
    final double[] diag;
    final double[] rhs;
    final int[] rowStart;
    final int[] rowCol;
    final double[] rowVal;

    NormalMatrix(int faceCount, int chordCount, int rowCount, int[] rowFaceI, int[] rowFaceJ, int[] rowEdgeAi,
            int[] chordOfEdge, int[] periodValue, float[] rowKappa) {
        variableCount = faceCount + chordCount;
        diag = new double[variableCount];
        rhs = new double[variableCount];

        int[] degree = new int[variableCount];
        for (int r = 0; r < rowCount; r++) {
            int fi = rowFaceI[r];
            int fj = rowFaceJ[r];
            int chord = chordOfEdge[rowEdgeAi[r]];
            if (chord >= 0) {
                int pe = faceCount + chord;
                degree[fi] += 2;
                degree[fj] += 2;
                degree[pe] += 2;
            } else {
                degree[fi] += 1;
                degree[fj] += 1;
            }
        }

        rowStart = new int[variableCount + 1];
        for (int i = 0; i < variableCount; i++) {
            rowStart[i + 1] = rowStart[i] + degree[i];
        }
        rowCol = new int[rowStart[variableCount]];
        rowVal = new double[rowStart[variableCount]];
        int[] cursor = rowStart.clone();
        double halfPi = Math.PI * HALF;

        for (int r = 0; r < rowCount; r++) {
            int fi = rowFaceI[r];
            int fj = rowFaceJ[r];
            int eAi = rowEdgeAi[r];
            int chord = chordOfEdge[eAi];
            int pe = chord >= 0 ? faceCount + chord : -1;
            double k = rowKappa[r] + (chord < 0 ? halfPi * periodValue[eAi] : 0.0);

            diag[fi] += 1.0;
            diag[fj] += 1.0;

            addOffDiagonal(cursor, fi, fj, -1.0);
            addOffDiagonal(cursor, fj, fi, -1.0);

            rhs[fi] -= k;
            rhs[fj] += k;
            if (pe >= 0) {
                diag[pe] += halfPi * halfPi;

                addOffDiagonal(cursor, fi, pe, halfPi);
                addOffDiagonal(cursor, pe, fi, halfPi);
                addOffDiagonal(cursor, fj, pe, -halfPi);
                addOffDiagonal(cursor, pe, fj, -halfPi);

                rhs[pe] -= halfPi * k;
            }
        }
    }

    /**
     * Append one off-diagonal entry into the row's CSR slot, advancing the row's
     * write cursor in {@code cursor}.
     *
     * @param cursor per-row write cursor (mutated)
     * @param row    row index of the new entry
     * @param col    column index of the new entry
     * @param value  coefficient
     */
    public void addOffDiagonal(int[] cursor, int row, int col, double value) {
        int i = cursor[row]++;
        rowCol[i] = col;
        rowVal[i] = value;
    }

    public int size() {
        return variableCount;
    }

    public double diag(int row) {
        return diag[row];
    }

    public int rowStart(int row) {
        return rowStart[row];
    }

    public int rowEnd(int row) {
        return rowStart[row + 1];
    }

    public int column(int cursor) {
        return rowCol[cursor];
    }

    public double value(int cursor) {
        return rowVal[cursor];
    }
}