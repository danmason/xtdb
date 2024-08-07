package xtdb.calcite;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.tools.RelBuilderFactory;

import java.util.function.Predicate;

public class XtdbToEnumerableConverterRule extends ConverterRule {
    static final ConverterRule INSTANCE =
        new XtdbToEnumerableConverterRule(RelFactories.LOGICAL_BUILDER);

    private XtdbToEnumerableConverterRule(RelBuilderFactory relBuilderFactory) {
        super(RelNode.class, (Predicate<RelNode>) r -> true,
              XtdbRel.CONVENTION, EnumerableConvention.INSTANCE,
              relBuilderFactory, "XtdbToEnumerableConverterRule");
    }

    @Override public RelNode convert(RelNode relNode) {
        RelTraitSet newTraitSet = relNode.getTraitSet().replace(getOutConvention());
        return new XtdbToEnumerableConverter(relNode.getCluster(), newTraitSet, relNode);
    }
}
