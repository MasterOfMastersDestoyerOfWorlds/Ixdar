package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.List;

import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

/**
 * ojAlgo-backed {@link IntegerProgram}. The only class in the quantization package that names
 * ojAlgo; reached solely through the desktop and headless platforms.
 */
public final class OjAlgoIntegerProgram implements IntegerProgram {

    /** The model being assembled. */
    public final ExpressionsBasedModel model = new ExpressionsBasedModel();

    /** Variables in creation order, which is the index space the interface exposes. */
    public final List<Variable> variables = new ArrayList<>();

    /** {@inheritDoc}. */
    @Override
    public void addVariable(String name, double weight) {
        variables.add(model.newVariable(name).lower(0).integer(true).weight(weight));
    }

    /** {@inheritDoc}. */
    @Override
    public IntegerProgramExpression newExpression(String name) {
        return new OjAlgoExpression(model.newExpression(name), variables);
    }

    /** {@inheritDoc}. */
    @Override
    public IntegerProgramSolution minimise() {
        Optimisation.Result result = model.minimise();
        double[] values = new double[variables.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = result.doubleValue(index);
        }
        return new IntegerProgramSolution(result.getState().isFeasible(), result.getState().isOptimal(),
                result.getState().toString(), result.getValue(), values);
    }
}
