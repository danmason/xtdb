package xtdb.calcite;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import com.google.common.collect.ImmutableList;
import clojure.lang.Keyword;
import clojure.lang.IFn;

import java.util.List;
import java.util.Map;

public class XtdbProject extends Project implements XtdbRel {
    private final IFn projectFn;
    private final List<? extends RexNode> projects;

    public XtdbProject(RelOptCluster cluster, RelTraitSet traitSet, RelNode input,
                       List<? extends RexNode> projects, RelDataType rowType) {
        super(cluster, traitSet, ImmutableList.of(), input, projects, rowType);
        assert getConvention() == XtdbRel.CONVENTION;
        this.projects = projects;
        this.projectFn = XtdbUtils.resolveWithErrorLogging("xtdb.calcite/enrich-project");
    }

    @Override public Project copy(RelTraitSet traitSet, RelNode input, List<RexNode> projects,
                                  RelDataType rowType) {
        return new XtdbProject(input.getCluster(), traitSet, input, projects, rowType);
    }

    @SuppressWarnings("unchecked")
    @Override public void implement(Implementor implementor) {
        implementor.visitChild(0, getInput());
        implementor.schema = (Map<Keyword, Object>) projectFn.invoke(implementor.schema, getNamedProjects());
    }

    @Override public RelOptTable getTable() {
        return getInput().getTable();
    }
}
