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

    public enum Op { LEQ, EQ, GEQ }
    public enum Sense { MINIMIZE, MAXIMIZE }

    private final ExpressionsBasedModel model = new ExpressionsBasedModel();
    private final List<Variable> vars = new ArrayList<>();
    private double[] objectiveCoeffs;
    private Sense sense = Sense.MINIMIZE;

    /**
     * Add an integer variable. Either bound may be null for unbounded, but ojAlgo's
     * MIP solver works much better with finite bounds; the QGP pipeline can always
     * supply finite bounds from problem geometry.
     */
    public int addIntegerVar(String name, Long lowerBound, Long upperBound) {
        Variable v = model.addVariable(name).integer(true);
        if (lowerBound != null) v.lower(BigDecimal.valueOf(lowerBound));
        if (upperBound != null) v.upper(BigDecimal.valueOf(upperBound));
        vars.add(v);
        return vars.size() - 1;
    }

    public int addContinuousVar(String name, Double lowerBound, Double upperBound) {
        Variable v = model.addVariable(name);
        if (lowerBound != null) v.lower(BigDecimal.valueOf(lowerBound));
        if (upperBound != null) v.upper(BigDecimal.valueOf(upperBound));
        vars.add(v);
        return vars.size() - 1;
    }

    public int addBinaryVar(String name) {
        Variable v = model.addVariable(name).binary();
        vars.add(v);
        return vars.size() - 1;
    }

    /**
     * Add a linear constraint Σ c_i x_i (op) rhs. The coefficients array length
     * must equal the number of variables added so far.
     */
    public void addLinearConstraint(double[] coefficients, Op op, double rhs) {
        if (coefficients.length != vars.size()) {
            throw new IllegalArgumentException("coefficients length (" + coefficients.length
                    + ") must equal variable count (" + vars.size() + ")");
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

    public void setObjective(double[] coefficients) {
        setObjective(coefficients, Sense.MINIMIZE);
    }

    public void setObjective(double[] coefficients, Sense sense) {
        if (coefficients.length != vars.size()) {
            throw new IllegalArgumentException("coefficients length (" + coefficients.length
                    + ") must equal variable count (" + vars.size() + ")");
        }
        this.objectiveCoeffs = coefficients.clone();
        this.sense = sense;
        for (int i = 0; i < coefficients.length; i++) {
            vars.get(i).weight(BigDecimal.valueOf(coefficients[i]));
        }
    }

    public double[] solve() {
        if (objectiveCoeffs == null) {
            throw new IllegalStateException("setObjective() not called");
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

    /** Get the objective value of the last solve. */
    public double objectiveValue() {
        if (objectiveCoeffs == null) return Double.NaN;
        Optimisation.Result result = (sense == Sense.MAXIMIZE) ? model.maximise() : model.minimise();
        return result.getValue();
    }

    public int variableCount() { return vars.size(); }
}
