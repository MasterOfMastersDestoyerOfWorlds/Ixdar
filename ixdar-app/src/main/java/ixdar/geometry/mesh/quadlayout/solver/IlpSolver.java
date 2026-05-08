package ixdar.geometry.mesh.quadlayout.solver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

/**
 * Facade over ojAlgo's {@link ExpressionsBasedModel} + {@code IntegerSolver} for
 * mixed-integer linear programs. Exposes a numpy-flavoured Java API that the
 * rest of the QGP pipeline (period-jump assignment, motorcycle T-mesh integer
 * rounding) can call without touching ojAlgo types directly.
 *
 * <pre>
 *   IlpSolver s = new IlpSolver();
 *   int x = s.addIntegerVar("x", 0, 10);
 *   int y = s.addIntegerVar("y", 0, 10);
 *   s.setObjective(new double[]{ 3.0, 2.0 }, IlpSolver.Sense.MAXIMIZE);
 *   s.addLinearConstraint(new double[]{ 1.0, 1.0 }, IlpSolver.Op.LEQ, 4.0);
 *   double[] x = s.solve();   // [4, 0] etc.
 * </pre>
 */
public final class IlpSolver {
    public static final String COEFFICIENTS_LENGTH = "coefficients length (";
    public static final String MUST_EQUAL_VARIABLE_COUNT = ") must equal variable count (";
    public static final String STR = ")";

    private final ExpressionsBasedModel model = new ExpressionsBasedModel();
    private final List<Variable> vars = new ArrayList<>();
    private double[] objectiveCoeffs;
    private Sense sense = Sense.MINIMIZE;

    /**
     * Add an integer variable. Either bound may be null for unbounded, but ojAlgo's
     * MIP solver works much better with finite bounds; the QGP pipeline can always
     * supply finite bounds from problem geometry.
     *
     * @param name       variable label for diagnostics
     * @param lowerBound inclusive lower bound, or {@code null} for unbounded below
     * @param upperBound inclusive upper bound, or {@code null} for unbounded above
     * @return zero-based index of the new variable (matches future coefficient slots)
     */
    public int addIntegerVar(String name, Long lowerBound, Long upperBound) {
        Variable v = model.addVariable(name).integer(true);
        if (lowerBound != null) v.lower(BigDecimal.valueOf(lowerBound));
        if (upperBound != null) v.upper(BigDecimal.valueOf(upperBound));
        vars.add(v);
        return vars.size() - 1;
    }

    /**
     * Add a continuous (real-valued) variable.
     *
     * @param name       variable label for diagnostics
     * @param lowerBound inclusive lower bound, or {@code null} for unbounded below
     * @param upperBound inclusive upper bound, or {@code null} for unbounded above
     * @return zero-based index of the new variable
     */
    public int addContinuousVar(String name, Double lowerBound, Double upperBound) {
        Variable v = model.addVariable(name);
        if (lowerBound != null) v.lower(BigDecimal.valueOf(lowerBound));
        if (upperBound != null) v.upper(BigDecimal.valueOf(upperBound));
        vars.add(v);
        return vars.size() - 1;
    }

    /**
     * Add a binary (0/1) variable.
     *
     * @param name variable label for diagnostics
     * @return zero-based index of the new variable
     */
    public int addBinaryVar(String name) {
        Variable v = model.addVariable(name).binary();
        vars.add(v);
        return vars.size() - 1;
    }

    /**
     * Add a linear constraint Σ c_i x_i (op) rhs. The coefficients array length
     * must equal the number of variables added so far.
     *
     * @param coefficients per-variable coefficient vector (length must equal {@link #variableCount()})
     * @param op           comparison operator against {@code rhs}
     * @param rhs          right-hand-side scalar
     * @throws IllegalArgumentException if {@code coefficients.length} does not match the variable count
     */
    public void addLinearConstraint(double[] coefficients, Op op, double rhs) {
        if (coefficients.length != vars.size()) {
            throw new IllegalArgumentException(COEFFICIENTS_LENGTH + coefficients.length
                    + MUST_EQUAL_VARIABLE_COUNT + vars.size() + STR);
        }
        Expression e = model.addExpression("c" + model.countExpressions());
        for (int i = 0; i < coefficients.length; i++) {
            if (coefficients[i] != 0.0) {
                e.set(vars.get(i), coefficients[i]);
            }
        }
        BigDecimal r = BigDecimal.valueOf(rhs);
        switch (op) {
            case LEQ -> e.upper(r);
            case GEQ -> e.lower(r);
            case EQ  -> e.level(r);
        }
    }

    /**
     * Convenience overload that defaults to {@link Sense#MINIMIZE}.
     *
     * @param coefficients per-variable objective coefficients
     */
    public void setObjective(double[] coefficients) {
        setObjective(coefficients, Sense.MINIMIZE);
    }

    /**
     * Set the linear objective Σ c_i x_i and the optimization sense.
     *
     * @param coefficients per-variable objective coefficients (length must equal {@link #variableCount()})
     * @param sense        whether to minimize or maximize
     * @throws IllegalArgumentException if {@code coefficients.length} does not match the variable count
     */
    public void setObjective(double[] coefficients, Sense sense) {
        if (coefficients.length != vars.size()) {
            throw new IllegalArgumentException(COEFFICIENTS_LENGTH + coefficients.length
                    + MUST_EQUAL_VARIABLE_COUNT + vars.size() + STR);
        }
        this.objectiveCoeffs = coefficients.clone();
        this.sense = sense;
        for (int i = 0; i < coefficients.length; i++) {
            vars.get(i).weight(BigDecimal.valueOf(coefficients[i]));
        }
    }

    /**
     * Solve with no time limit.
     *
     * @return optimal variable values, indexed in the order returned by the {@code add*Var} calls
     */
    public double[] solve() {
        return solveWithTimeLimit(0L);
    }

    /**
     * Solve with a wall-clock time limit (milliseconds). On expiry, ojAlgo
     * returns the best feasible incumbent found so far if any, else throws.
     * Pass 0 for no limit.
     *
     * @param timeoutMillis wall-clock budget in milliseconds; pass 0 for unlimited
     * @throws IllegalStateException if {@link #setObjective(double[])} was not called or the solver returned an infeasible state
     * @return optimal (or best feasible incumbent on timeout) variable values
     */
    public double[] solveWithTimeLimit(long timeoutMillis) {
        if (objectiveCoeffs == null) {
            throw new IllegalStateException("setObjective() not called");
        }
        if (timeoutMillis > 0) {
            // ojAlgo: configure both abort (terminate) and suffice (accept
            // incumbent) windows to the same wall-clock bound.
            org.ojalgo.type.CalendarDateUnit u = org.ojalgo.type.CalendarDateUnit.MILLIS;
            model.options.time_abort = timeoutMillis;
            model.options.time_suffice = timeoutMillis;
        }
        Optimisation.Result result = (sense == Sense.MAXIMIZE) ? model.maximise() : model.minimise();
        if (!result.getState().isFeasible()) {
            throw new IllegalStateException("ILP infeasible or solver failed: " + result.getState());
        }
        double[] out = new double[vars.size()];
        for (int i = 0; i < vars.size(); i++) {
            out[i] = result.doubleValue(i);
        }
        return out;
    }

    /**
     * Get the objective value of the last solve.
     *
     * @return objective value at the optimum, or {@link Double#NaN} if no objective was set
     */
    public double objectiveValue() {
        if (objectiveCoeffs == null) return Double.NaN;
        Optimisation.Result result = (sense == Sense.MAXIMIZE) ? model.maximise() : model.minimise();
        return result.getValue();
    }

    /**
     * Number of variables registered with this solver so far.
     *
     * @return number of variables registered via the {@code add*Var} methods
     */
    public int variableCount() { return vars.size(); }

    public enum Op { LEQ, EQ, GEQ }
    public enum Sense { MINIMIZE, MAXIMIZE }
}
