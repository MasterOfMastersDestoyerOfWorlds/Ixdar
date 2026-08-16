package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.List;

import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.Variable;

/**
 * ojAlgo-backed {@link IntegerProgramExpression}. Reached only through {@link OjAlgoIntegerProgram},
 * so the browser build never links ojAlgo.
 */
public final class OjAlgoExpression implements IntegerProgramExpression {

    /** The ojAlgo row this wraps. */
    public final Expression expression;

    /** Variables in creation order, so callers can address them by index. */
    public final List<Variable> variables;

    /**
     * Wrap one ojAlgo expression.
     *
     * @param expression the ojAlgo row being built
     * @param variables the program's variables, in creation order
     */
    public OjAlgoExpression(Expression expression, List<Variable> variables) {
        this.expression = expression;
        this.variables = variables;
    }

    /** {@inheritDoc}. */
    @Override
    public void set(int variableIndex, double coefficient) {
        expression.set(variables.get(variableIndex), coefficient);
    }

    /** {@inheritDoc}. */
    @Override
    public void lower(double bound) {
        expression.lower(bound);
    }

    /** {@inheritDoc}. */
    @Override
    public void level(double value) {
        expression.level(value);
    }
}
