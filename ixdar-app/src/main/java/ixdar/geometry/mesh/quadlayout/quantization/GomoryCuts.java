package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.List;

/**
 * Gomory mixed-integer cuts (Gomory 1960; Cornuéjols 2008, §3.3) from the root relaxation's
 * optimal tableau: row {@code x_p + Σ a_j d_j = b} gives {@code Σ g_j d_j ≥ 1}.
 */
final class GomoryCuts {

    private static final double FRACTIONALITY_TOLERANCE = 1e-6;

    private static final double COEFFICIENT_TOLERANCE = 1e-12;

    private static final double VALIDITY_MARGIN = 1e-9;

    private GomoryCuts() {
    }

    /**
     * Rounds of cuts at a solved root relaxation, one per fractional basic structural column,
     * re-solved warm until a round yields no cut, no longer raises the bound, or the cuts
     * outnumber the root's own rows (quantization programs stop under a third; sts45 never stops).
     *
     * @param root           the solved root relaxation
     * @param integerSlots   slots below this index are integer: the structural columns and the
     *                       slacks of the integer rows; cut slacks above it are continuous
     * @param boundTolerance smallest bound gain that earns another round
     * @return the root with every round's cuts appended, solved, or {@code null} when the cuts prove
     *         the relaxation infeasible
     */
    public static SparseDualSimplex separate(SparseDualSimplex root, int integerSlots, double boundTolerance) {
        SparseDualSimplex current = root;
        double bound = current.objective();
        while (true) {
            SparseDualSimplex.Matrix matrix = current.matrix;
            int structurals = matrix.structuralTotal;
            List<int[]> cutColumns = new ArrayList<>();
            List<double[]> cutCoefficients = new ArrayList<>();
            List<Double> cutLower = new ArrayList<>();
            double[] dense = new double[structurals];
            for (int position = 0; position < matrix.rowTotal; position++) {
                double value = current.basic[position];
                double fraction = value - Math.floor(value);
                if (current.basisSlot[position] >= structurals || fraction < FRACTIONALITY_TOLERANCE
                        || fraction > 1.0 - FRACTIONALITY_TOLERANCE) {
                    continue;
                }
                double[] row = current.tableauRow(position);
                double right = 1.0;
                for (int slot = 0; slot < matrix.slotTotal; slot++) {
                    double entry = row[slot];
                    if (entry == 0.0 || current.isBasic(slot) || current.lower[slot] == current.upper[slot]) {
                        continue;
                    }
                    boolean fromUpper = current.atUpper[slot];
                    double coefficient = fromUpper ? entry : -entry;
                    double gain;
                    if (slot < integerSlots) {
                        double part = coefficient - Math.floor(coefficient);
                        gain = Math.min(part / fraction, (1.0 - part) / (1.0 - fraction));
                    } else {
                        gain = coefficient >= 0.0 ? coefficient / fraction : -coefficient / (1.0 - fraction);
                    }
                    if (gain == 0.0) {
                        continue;
                    }
                    double signedGain = fromUpper ? -gain : gain;
                    right += signedGain * (fromUpper ? current.upper[slot] : current.lower[slot]);
                    if (slot < structurals) {
                        dense[slot] += signedGain;
                    } else {
                        int source = slot - structurals;
                        for (int at = matrix.rowStart[source]; at < matrix.rowStart[source + 1]; at++) {
                            dense[matrix.rowColumn[at]] += signedGain * matrix.rowValue[at];
                        }
                    }
                }
                int support = 0;
                double scale = 0.0;
                for (double coefficient : dense) {
                    if (Math.abs(coefficient) > COEFFICIENT_TOLERANCE) {
                        support++;
                        scale = Math.max(scale, Math.abs(coefficient));
                    }
                }
                int[] columns = new int[support];
                double[] coefficients = new double[support];
                for (int column = 0, next = 0; column < structurals; column++) {
                    double coefficient = dense[column];
                    dense[column] = 0.0;
                    if (Math.abs(coefficient) > COEFFICIENT_TOLERANCE) {
                        columns[next] = column;
                        coefficients[next++] = coefficient / scale;
                    } else if (coefficient != 0.0) {
                        right -= coefficient * (coefficient < 0.0 ? current.lower[column] : current.upper[column]);
                    }
                }
                right /= scale;
                if (support == 0 || Double.isInfinite(right)) {
                    continue;
                }
                cutColumns.add(columns);
                cutCoefficients.add(coefficients);
                cutLower.add(right - VALIDITY_MARGIN * (1.0 + Math.abs(right)));
            }
            if (cutColumns.isEmpty()) {
                return current;
            }
            double[] lower = new double[cutLower.size()];
            for (int cut = 0; cut < lower.length; cut++) {
                lower[cut] = cutLower.get(cut);
            }
            SparseDualSimplex extended = current.withRows(cutColumns.toArray(new int[0][]),
                    cutCoefficients.toArray(new double[0][]), lower);
            if (!extended.solve()) {
                return null;
            }
            double raised = extended.objective();
            current = extended;
            if (raised - bound <= boundTolerance || current.matrix.rowTotal >= 2 * root.matrix.rowTotal) {
                return current;
            }
            bound = raised;
        }
    }
}
