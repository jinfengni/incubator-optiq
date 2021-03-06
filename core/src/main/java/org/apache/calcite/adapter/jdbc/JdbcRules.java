/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.adapter.jdbc;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Intersect;
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Minus;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalCalc;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalIntersect;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalMinus;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalTableModify;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.rules.EquiJoin;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexLocalRef;
import org.apache.calcite.rex.RexMultisetUtil;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSetOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.trace.CalciteTrace;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Rules and relational operators for
 * {@link JdbcConvention}
 * calling convention.
 */
public class JdbcRules {
  private JdbcRules() {
  }

  protected static final Logger LOGGER = CalciteTrace.getPlannerTracer();

  private static final SqlParserPos POS = SqlParserPos.ZERO;

  public static List<RelOptRule> rules(JdbcConvention out) {
    return ImmutableList.<RelOptRule>of(
        new JdbcToEnumerableConverterRule(out),
        new JdbcJoinRule(out),
        new JdbcCalcRule(out),
        new JdbcProjectRule(out),
        new JdbcFilterRule(out),
        new JdbcAggregateRule(out),
        new JdbcSortRule(out),
        new JdbcUnionRule(out),
        new JdbcIntersectRule(out),
        new JdbcMinusRule(out),
        new JdbcTableModificationRule(out),
        new JdbcValuesRule(out));
  }

  private static void addSelect(
      List<SqlNode> selectList, SqlNode node, RelDataType rowType) {
    String name = rowType.getFieldNames().get(selectList.size());
    String alias = SqlValidatorUtil.getAlias(node, -1);
    if (alias == null || !alias.equals(name)) {
      node = SqlStdOperatorTable.AS.createCall(
          POS, node, new SqlIdentifier(name, POS));
    }
    selectList.add(node);
  }

  private static JdbcImplementor.Result setOpToSql(JdbcImplementor implementor,
      SqlSetOperator operator, JdbcRel rel) {
    List<SqlNode> list = Expressions.list();
    for (Ord<RelNode> input : Ord.zip(rel.getInputs())) {
      final JdbcImplementor.Result result =
          implementor.visitChild(input.i, input.e);
      list.add(result.asSelect());
    }
    final SqlCall node = operator.createCall(new SqlNodeList(list, POS));
    final List<JdbcImplementor.Clause> clauses =
        Expressions.list(JdbcImplementor.Clause.SET_OP);
    return implementor.result(node, clauses, rel);
  }

  private static boolean isStar(List<RexNode> exps, RelDataType inputRowType) {
    int i = 0;
    for (RexNode ref : exps) {
      if (!(ref instanceof RexInputRef)) {
        return false;
      } else if (((RexInputRef) ref).getIndex() != i++) {
        return false;
      }
    }
    return i == inputRowType.getFieldCount();
  }

  private static boolean isStar(RexProgram program) {
    int i = 0;
    for (RexLocalRef ref : program.getProjectList()) {
      if (ref.getIndex() != i++) {
        return false;
      }
    }
    return i == program.getInputRowType().getFieldCount();
  }

  /** Abstract base class for rule that converts to JDBC. */
  abstract static class JdbcConverterRule extends ConverterRule {
    protected final JdbcConvention out;

    public JdbcConverterRule(Class<? extends RelNode> clazz, RelTrait in,
        JdbcConvention out, String description) {
      super(clazz, in, out, description);
      this.out = out;
    }
  }

  /** Rule that converts a join to JDBC. */
  private static class JdbcJoinRule extends JdbcConverterRule {
    private JdbcJoinRule(JdbcConvention out) {
      super(LogicalJoin.class, Convention.NONE, out, "JdbcJoinRule");
    }

    @Override public RelNode convert(RelNode rel) {
      LogicalJoin join = (LogicalJoin) rel;
      List<RelNode> newInputs = new ArrayList<RelNode>();
      for (RelNode input : join.getInputs()) {
        if (!(input.getConvention() == getOutTrait())) {
          input =
              convert(input,
                  input.getTraitSet().replace(out));
        }
        newInputs.add(input);
      }
      final JoinInfo joinInfo =
          JoinInfo.of(newInputs.get(0), newInputs.get(1), join.getCondition());
      if (!joinInfo.isEqui()) {
        // JdbcJoin only supports equi-join
        return null;
      }
      try {
        return new JdbcJoin(
            join.getCluster(),
            join.getTraitSet().replace(out),
            newInputs.get(0),
            newInputs.get(1),
            join.getCondition(),
            joinInfo.leftKeys,
            joinInfo.rightKeys,
            join.getJoinType(),
            join.getVariablesStopped());
      } catch (InvalidRelException e) {
        LOGGER.fine(e.toString());
        return null;
      }
    }
  }

  /** Join operator implemented in JDBC convention. */
  public static class JdbcJoin extends EquiJoin implements JdbcRel {
    protected JdbcJoin(
        RelOptCluster cluster,
        RelTraitSet traits,
        RelNode left,
        RelNode right,
        RexNode condition,
        ImmutableIntList leftKeys,
        ImmutableIntList rightKeys,
        JoinRelType joinType,
        Set<String> variablesStopped)
        throws InvalidRelException {
      super(cluster, traits, left, right, condition, leftKeys, rightKeys,
          joinType, variablesStopped);
    }

    @Override public JdbcJoin copy(RelTraitSet traitSet, RexNode condition,
        RelNode left, RelNode right, JoinRelType joinType,
        boolean semiJoinDone) {
      final JoinInfo joinInfo = JoinInfo.of(left, right, condition);
      assert joinInfo.isEqui();
      try {
        return new JdbcJoin(getCluster(), traitSet, left, right,
            condition, joinInfo.leftKeys, joinInfo.rightKeys, joinType,
            variablesStopped);
      } catch (InvalidRelException e) {
        // Semantic error not possible. Must be a bug. Convert to
        // internal error.
        throw new AssertionError(e);
      }
    }

    @Override public RelOptCost computeSelfCost(RelOptPlanner planner) {
      // We always "build" the
      double rowCount = RelMetadataQuery.getRowCount(this);

      return planner.getCostFactory().makeCost(rowCount, 0, 0);
    }

    @Override public double getRows() {
      final boolean leftKey = left.isKey(ImmutableBitSet.of(leftKeys));
      final boolean rightKey = right.isKey(ImmutableBitSet.of(rightKeys));
      final double leftRowCount = left.getRows();
      final double rightRowCount = right.getRows();
      if (leftKey && rightKey) {
        return Math.min(leftRowCount, rightRowCount);
      }
      if (leftKey) {
        return rightRowCount;
      }
      if (rightKey) {
        return leftRowCount;
      }
      return leftRowCount * rightRowCount;
    }

    public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      final JdbcImplementor.Result leftResult =
          implementor.visitChild(0, left);
      final JdbcImplementor.Result rightResult =
          implementor.visitChild(1, right);
      SqlNode sqlCondition = null;
      final JdbcImplementor.Context leftContext = leftResult.qualifiedContext();
      final JdbcImplementor.Context rightContext =
          rightResult.qualifiedContext();
      for (Pair<Integer, Integer> pair : Pair.zip(leftKeys, rightKeys)) {
        SqlNode x =
            SqlStdOperatorTable.EQUALS.createCall(POS,
                leftContext.field(pair.left),
                rightContext.field(pair.right));
        if (sqlCondition == null) {
          sqlCondition = x;
        } else {
          sqlCondition =
              SqlStdOperatorTable.AND.createCall(POS, sqlCondition, x);
        }
      }
      SqlNode join =
          new SqlJoin(POS,
              leftResult.asFrom(),
              SqlLiteral.createBoolean(false, POS),
              joinType(joinType).symbol(POS),
              rightResult.asFrom(),
              JoinConditionType.ON.symbol(POS),
              sqlCondition);
      return implementor.result(join, leftResult, rightResult);
    }

    private static JoinType joinType(JoinRelType joinType) {
      switch (joinType) {
      case LEFT:
        return JoinType.LEFT;
      case RIGHT:
        return JoinType.RIGHT;
      case INNER:
        return JoinType.INNER;
      case FULL:
        return JoinType.FULL;
      default:
        throw new AssertionError(joinType);
      }
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.logical.LogicalCalc} to an
   * {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcCalc}.
   */
  private static class JdbcCalcRule extends JdbcConverterRule {
    private JdbcCalcRule(JdbcConvention out) {
      super(LogicalCalc.class, Convention.NONE, out, "JdbcCalcRule");
    }

    public RelNode convert(RelNode rel) {
      final LogicalCalc calc = (LogicalCalc) rel;

      // If there's a multiset, let FarragoMultisetSplitter work on it
      // first.
      if (RexMultisetUtil.containsMultiset(calc.getProgram())) {
        return null;
      }

      return new JdbcCalc(rel.getCluster(), rel.getTraitSet().replace(out),
          convert(calc.getInput(), calc.getTraitSet().replace(out)),
          calc.getProgram(), Project.Flags.BOXED);
    }
  }

  /** Calc operator implemented in JDBC convention.
   *
   * @see org.apache.calcite.rel.core.Calc */
  public static class JdbcCalc extends SingleRel implements JdbcRel {
    private final RexProgram program;

    /**
     * Values defined in {@link org.apache.calcite.rel.core.Project.Flags}.
     */
    protected final int flags;

    public JdbcCalc(RelOptCluster cluster,
        RelTraitSet traitSet,
        RelNode child,
        RexProgram program,
        int flags) {
      super(cluster, traitSet, child);
      assert getConvention() instanceof JdbcConvention;
      this.flags = flags;
      this.program = program;
      this.rowType = program.getOutputRowType();
    }

    public RelWriter explainTerms(RelWriter pw) {
      return program.explainCalc(super.explainTerms(pw));
    }

    public double getRows() {
      return LogicalFilter.estimateFilteredRows(getInput(), program);
    }

    public RelOptCost computeSelfCost(RelOptPlanner planner) {
      double dRows = RelMetadataQuery.getRowCount(this);
      double dCpu = RelMetadataQuery.getRowCount(getInput())
          * program.getExprCount();
      double dIo = 0;
      return planner.getCostFactory().makeCost(dRows, dCpu, dIo);
    }

    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
      return new JdbcCalc(getCluster(), traitSet, sole(inputs), program,
          flags);
    }

    public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      JdbcImplementor.Result x = implementor.visitChild(0, getInput());
      final JdbcImplementor.Builder builder =
          program.getCondition() != null
              ? x.builder(this, JdbcImplementor.Clause.FROM,
                  JdbcImplementor.Clause.WHERE)
              : x.builder(this, JdbcImplementor.Clause.FROM);
      if (!isStar(program)) {
        final List<SqlNode> selectList = new ArrayList<SqlNode>();
        for (RexLocalRef ref : program.getProjectList()) {
          SqlNode sqlExpr = builder.context.toSql(program, ref);
          addSelect(selectList, sqlExpr, getRowType());
        }
        builder.setSelect(new SqlNodeList(selectList, POS));
      }
      if (program.getCondition() != null) {
        builder.setWhere(
            builder.context.toSql(program, program.getCondition()));
      }
      return builder.result();
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.logical.LogicalProject} to
   * an {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcProject}.
   */
  private static class JdbcProjectRule extends JdbcConverterRule {
    private JdbcProjectRule(JdbcConvention out) {
      super(LogicalProject.class, Convention.NONE, out, "JdbcProjectRule");
    }

    public RelNode convert(RelNode rel) {
      final LogicalProject project = (LogicalProject) rel;

      return new JdbcProject(
          rel.getCluster(),
          rel.getTraitSet().replace(out),
          convert(
              project.getInput(),
              project.getInput().getTraitSet().replace(out)),
          project.getProjects(),
          project.getRowType(),
          Project.Flags.BOXED);
    }
  }

  /** Implementation of {@link org.apache.calcite.rel.logical.LogicalProject} in
   * {@link JdbcConvention jdbc calling convention}. */
  public static class JdbcProject
      extends Project
      implements JdbcRel {
    public JdbcProject(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        RelNode child,
        List<RexNode> exps,
        RelDataType rowType,
        int flags) {
      super(cluster, traitSet, child, exps, rowType, flags);
      assert getConvention() instanceof JdbcConvention;
    }

    @Override public JdbcProject copy(RelTraitSet traitSet, RelNode input,
        List<RexNode> exps, RelDataType rowType) {
      return new JdbcProject(getCluster(), traitSet, input, exps, rowType,
          flags);
    }

    public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      JdbcImplementor.Result x = implementor.visitChild(0, getInput());
      if (isStar(exps, getInput().getRowType())) {
        return x;
      }
      final JdbcImplementor.Builder builder =
          x.builder(this, JdbcImplementor.Clause.SELECT);
      final List<SqlNode> selectList = new ArrayList<SqlNode>();
      for (RexNode ref : exps) {
        SqlNode sqlExpr = builder.context.toSql(null, ref);
        addSelect(selectList, sqlExpr, getRowType());
      }
      builder.setSelect(new SqlNodeList(selectList, POS));
      return builder.result();
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.logical.LogicalFilter} to
   * an {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcFilter}.
   */
  private static class JdbcFilterRule extends JdbcConverterRule {
    private JdbcFilterRule(JdbcConvention out) {
      super(LogicalFilter.class, Convention.NONE, out, "JdbcFilterRule");
    }

    public RelNode convert(RelNode rel) {
      final LogicalFilter filter = (LogicalFilter) rel;

      return new JdbcFilter(
          rel.getCluster(),
          rel.getTraitSet().replace(out),
          convert(filter.getInput(),
              filter.getInput().getTraitSet().replace(out)),
          filter.getCondition());
    }
  }

  /** Implementation of {@link org.apache.calcite.rel.core.Filter} in
   * {@link JdbcConvention jdbc calling convention}. */
  public static class JdbcFilter extends Filter implements JdbcRel {
    public JdbcFilter(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        RelNode child,
        RexNode condition) {
      super(cluster, traitSet, child, condition);
      assert getConvention() instanceof JdbcConvention;
    }

    public JdbcFilter copy(RelTraitSet traitSet, RelNode input,
        RexNode condition) {
      return new JdbcFilter(getCluster(), traitSet, input, condition);
    }

    public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      JdbcImplementor.Result x = implementor.visitChild(0, getInput());
      final JdbcImplementor.Builder builder =
          x.builder(this, JdbcImplementor.Clause.WHERE);
      builder.setWhere(builder.context.toSql(null, condition));
      return builder.result();
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.logical.LogicalAggregate}
   * to a {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcAggregate}.
   */
  private static class JdbcAggregateRule extends JdbcConverterRule {
    private JdbcAggregateRule(JdbcConvention out) {
      super(LogicalAggregate.class, Convention.NONE, out, "JdbcAggregateRule");
    }

    public RelNode convert(RelNode rel) {
      final LogicalAggregate agg = (LogicalAggregate) rel;
      final RelTraitSet traitSet =
          agg.getTraitSet().replace(out);
      try {
        return new JdbcAggregate(rel.getCluster(), traitSet,
            convert(agg.getInput(), traitSet), agg.indicator, agg.getGroupSet(),
            agg.getGroupSets(), agg.getAggCallList());
      } catch (InvalidRelException e) {
        LOGGER.fine(e.toString());
        return null;
      }
    }
  }

  /** Aggregate operator implemented in JDBC convention. */
  public static class JdbcAggregate extends Aggregate implements JdbcRel {
    public JdbcAggregate(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        RelNode child,
        boolean indicator,
        ImmutableBitSet groupSet,
        List<ImmutableBitSet> groupSets,
        List<AggregateCall> aggCalls)
        throws InvalidRelException {
      super(cluster, traitSet, child, indicator, groupSet, groupSets, aggCalls);
      assert getConvention() instanceof JdbcConvention;
    }

    @Override public JdbcAggregate copy(RelTraitSet traitSet, RelNode input,
        boolean indicator, ImmutableBitSet groupSet,
        List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
      try {
        return new JdbcAggregate(getCluster(), traitSet, input, indicator,
            groupSet, groupSets, aggCalls);
      } catch (InvalidRelException e) {
        // Semantic error not possible. Must be a bug. Convert to
        // internal error.
        throw new AssertionError(e);
      }
    }

    public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      // "select a, b, sum(x) from ( ... ) group by a, b"
      final JdbcImplementor.Result x = implementor.visitChild(0, getInput());
      final JdbcImplementor.Builder builder =
          x.builder(this, JdbcImplementor.Clause.GROUP_BY);
      List<SqlNode> groupByList = Expressions.list();
      final List<SqlNode> selectList = new ArrayList<SqlNode>();
      for (int group : groupSet) {
        final SqlNode field = builder.context.field(group);
        addSelect(selectList, field, getRowType());
        groupByList.add(field);
      }
      for (AggregateCall aggCall : aggCalls) {
        addSelect(selectList, builder.context.toSql(aggCall), rowType);
      }
      builder.setSelect(new SqlNodeList(selectList, POS));
      if (!groupByList.isEmpty() || aggCalls.isEmpty()) {
        // Some databases don't support "GROUP BY ()". We can omit it as long
        // as there is at least one aggregate function.
        builder.setGroupBy(new SqlNodeList(groupByList, POS));
      }
      return builder.result();
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.core.Sort} to an
   * {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcSort}.
   */
  private static class JdbcSortRule extends JdbcConverterRule {
    private JdbcSortRule(JdbcConvention out) {
      super(Sort.class, Convention.NONE, out, "JdbcSortRule");
    }

    public RelNode convert(RelNode rel) {
      final Sort sort = (Sort) rel;
      if (sort.offset != null || sort.fetch != null) {
        // Cannot implement "OFFSET n FETCH n" currently.
        return null;
      }
      final RelTraitSet traitSet = sort.getTraitSet().replace(out);
      return new JdbcSort(rel.getCluster(), traitSet,
          convert(sort.getInput(), traitSet), sort.getCollation());
    }
  }

  /** Sort operator implemented in JDBC convention. */
  public static class JdbcSort
      extends Sort
      implements JdbcRel {
    public JdbcSort(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        RelNode child,
        RelCollation collation) {
      super(cluster, traitSet, child, collation);
      assert getConvention() instanceof JdbcConvention;
      assert getConvention() == child.getConvention();
    }

    @Override public JdbcSort copy(RelTraitSet traitSet, RelNode newInput,
        RelCollation newCollation) {
      return new JdbcSort(getCluster(), traitSet, newInput, newCollation);
    }

    public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      final JdbcImplementor.Result x = implementor.visitChild(0, getInput());
      final JdbcImplementor.Builder builder =
          x.builder(this, JdbcImplementor.Clause.ORDER_BY);
      List<SqlNode> orderByList = Expressions.list();
      for (RelFieldCollation fieldCollation : collation.getFieldCollations()) {
        if (fieldCollation.nullDirection
            != RelFieldCollation.NullDirection.UNSPECIFIED
            && implementor.dialect.getDatabaseProduct()
               == SqlDialect.DatabaseProduct.MYSQL) {
          orderByList.add(
              ISNULL_FUNCTION.createCall(POS,
                  builder.context.field(fieldCollation.getFieldIndex())));
          fieldCollation = new RelFieldCollation(fieldCollation.getFieldIndex(),
              fieldCollation.getDirection());
        }
        orderByList.add(builder.context.toSql(fieldCollation));
      }
      builder.setOrderBy(new SqlNodeList(orderByList, POS));
      return builder.result();
    }
  }

  /** MySQL specific function. */
  private static final SqlFunction ISNULL_FUNCTION =
      new SqlFunction("ISNULL", SqlKind.OTHER_FUNCTION,
          ReturnTypes.BOOLEAN, InferTypes.FIRST_KNOWN,
          OperandTypes.ANY, SqlFunctionCategory.SYSTEM);

  /**
   * Rule to convert an {@link org.apache.calcite.rel.logical.LogicalUnion} to a
   * {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcUnion}.
   */
  private static class JdbcUnionRule extends JdbcConverterRule {
    private JdbcUnionRule(JdbcConvention out) {
      super(LogicalUnion.class, Convention.NONE, out, "JdbcUnionRule");
    }

    public RelNode convert(RelNode rel) {
      final LogicalUnion union = (LogicalUnion) rel;
      final RelTraitSet traitSet =
          union.getTraitSet().replace(out);
      return new JdbcUnion(rel.getCluster(), traitSet,
          convertList(union.getInputs(), out), union.all);
    }
  }

  /** Union operator implemented in JDBC convention. */
  public static class JdbcUnion extends Union implements JdbcRel {
    public JdbcUnion(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        List<RelNode> inputs,
        boolean all) {
      super(cluster, traitSet, inputs, all);
    }

    public JdbcUnion copy(
        RelTraitSet traitSet, List<RelNode> inputs, boolean all) {
      return new JdbcUnion(getCluster(), traitSet, inputs, all);
    }

    @Override public RelOptCost computeSelfCost(RelOptPlanner planner) {
      return super.computeSelfCost(planner).multiplyBy(.1);
    }

    public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      final SqlSetOperator operator = all
          ? SqlStdOperatorTable.UNION_ALL
          : SqlStdOperatorTable.UNION;
      return setOpToSql(implementor, operator, this);
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.logical.LogicalIntersect}
   * to a {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcIntersect}.
   */
  private static class JdbcIntersectRule extends JdbcConverterRule {
    private JdbcIntersectRule(JdbcConvention out) {
      super(LogicalIntersect.class, Convention.NONE, out, "JdbcIntersectRule");
    }

    public RelNode convert(RelNode rel) {
      final LogicalIntersect intersect = (LogicalIntersect) rel;
      if (intersect.all) {
        return null; // INTERSECT ALL not implemented
      }
      final RelTraitSet traitSet =
          intersect.getTraitSet().replace(out);
      return new JdbcIntersect(rel.getCluster(), traitSet,
          convertList(intersect.getInputs(), out), false);
    }
  }

  /** Intersect operator implemented in JDBC convention. */
  public static class JdbcIntersect
      extends Intersect
      implements JdbcRel {
    public JdbcIntersect(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        List<RelNode> inputs,
        boolean all) {
      super(cluster, traitSet, inputs, all);
      assert !all;
    }

    public JdbcIntersect copy(
        RelTraitSet traitSet, List<RelNode> inputs, boolean all) {
      return new JdbcIntersect(getCluster(), traitSet, inputs, all);
    }

    public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return setOpToSql(implementor,
          all
              ? SqlStdOperatorTable.INTERSECT_ALL
              : SqlStdOperatorTable.INTERSECT,
          this);
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.logical.LogicalMinus} to a
   * {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcMinus}.
   */
  private static class JdbcMinusRule extends JdbcConverterRule {
    private JdbcMinusRule(JdbcConvention out) {
      super(LogicalMinus.class, Convention.NONE, out, "JdbcMinusRule");
    }

    public RelNode convert(RelNode rel) {
      final LogicalMinus minus = (LogicalMinus) rel;
      if (minus.all) {
        return null; // EXCEPT ALL not implemented
      }
      final RelTraitSet traitSet =
          rel.getTraitSet().replace(out);
      return new JdbcMinus(rel.getCluster(), traitSet,
          convertList(minus.getInputs(), out), false);
    }
  }

  /** Minus operator implemented in JDBC convention. */
  public static class JdbcMinus extends Minus implements JdbcRel {
    public JdbcMinus(RelOptCluster cluster, RelTraitSet traitSet,
        List<RelNode> inputs, boolean all) {
      super(cluster, traitSet, inputs, all);
      assert !all;
    }

    public JdbcMinus copy(RelTraitSet traitSet, List<RelNode> inputs,
        boolean all) {
      return new JdbcMinus(getCluster(), traitSet, inputs, all);
    }

    public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return setOpToSql(implementor,
          all
              ? SqlStdOperatorTable.EXCEPT_ALL
              : SqlStdOperatorTable.EXCEPT,
          this);
    }
  }

  /** Rule that converts a table-modification to JDBC. */
  public static class JdbcTableModificationRule extends JdbcConverterRule {
    private JdbcTableModificationRule(JdbcConvention out) {
      super(
          LogicalTableModify.class,
          Convention.NONE,
          out,
          "JdbcTableModificationRule");
    }

    @Override public RelNode convert(RelNode rel) {
      final LogicalTableModify modify =
          (LogicalTableModify) rel;
      final ModifiableTable modifiableTable =
          modify.getTable().unwrap(ModifiableTable.class);
      if (modifiableTable == null
          /* || modifiableTable.getExpression(tableInSchema) == null */) {
        return null;
      }
      final RelTraitSet traitSet =
          modify.getTraitSet().replace(out);
      return new JdbcTableModify(
          modify.getCluster(), traitSet,
          modify.getTable(),
          modify.getCatalogReader(),
          convert(modify.getInput(), traitSet),
          modify.getOperation(),
          modify.getUpdateColumnList(),
          modify.isFlattened());
    }
  }

  /** Table-modification operator implemented in JDBC convention. */
  public static class JdbcTableModify extends TableModify implements JdbcRel {
    private final Expression expression;

    public JdbcTableModify(RelOptCluster cluster,
        RelTraitSet traits,
        RelOptTable table,
        Prepare.CatalogReader catalogReader,
        RelNode child,
        Operation operation,
        List<String> updateColumnList,
        boolean flattened) {
      super(cluster, traits, table, catalogReader, child, operation,
          updateColumnList, flattened);
      assert child.getConvention() instanceof JdbcConvention;
      assert getConvention() instanceof JdbcConvention;
      final ModifiableTable modifiableTable =
          table.unwrap(ModifiableTable.class);
      if (modifiableTable == null) {
        throw new AssertionError(); // TODO: user error in validator
      }
      this.expression = table.getExpression(Queryable.class);
      if (expression == null) {
        throw new AssertionError(); // TODO: user error in validator
      }
    }

    @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
      return new JdbcTableModify(
          getCluster(), traitSet, getTable(), getCatalogReader(),
          sole(inputs), getOperation(), getUpdateColumnList(),
          isFlattened());
    }

    public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      throw new AssertionError(); // TODO:
    }
  }

  /** Rule that converts a values operator to JDBC. */
  public static class JdbcValuesRule extends JdbcConverterRule {
    private JdbcValuesRule(JdbcConvention out) {
      super(LogicalValues.class, Convention.NONE, out, "JdbcValuesRule");
    }

    @Override public RelNode convert(RelNode rel) {
      LogicalValues values = (LogicalValues) rel;
      return new JdbcValues(values.getCluster(), values.getRowType(),
          values.getTuples(), values.getTraitSet().replace(out));
    }
  }

  /** Values operator implemented in JDBC convention. */
  public static class JdbcValues extends Values implements JdbcRel {
    JdbcValues(RelOptCluster cluster, RelDataType rowType,
        ImmutableList<ImmutableList<RexLiteral>> tuples, RelTraitSet traitSet) {
      super(cluster, rowType, tuples, traitSet);
    }

    @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
      assert inputs.isEmpty();
      return new JdbcValues(getCluster(), rowType, tuples, traitSet);
    }

    public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      final List<String> fields = getRowType().getFieldNames();
      final List<JdbcImplementor.Clause> clauses = Collections.singletonList(
          JdbcImplementor.Clause.SELECT);
      final JdbcImplementor.Context context =
          implementor.new AliasContext(
              Collections.<Pair<String, RelDataType>>emptyList(), false);
      final List<SqlSelect> selects = new ArrayList<SqlSelect>();
      for (List<RexLiteral> tuple : tuples) {
        final List<SqlNode> selectList = new ArrayList<SqlNode>();
        for (Pair<RexLiteral, String> literal : Pair.zip(tuple, fields)) {
          selectList.add(
              SqlStdOperatorTable.AS.createCall(
                  POS,
                  context.toSql(null, literal.left),
                  new SqlIdentifier(literal.right, POS)));
        }
        selects.add(
            new SqlSelect(POS, SqlNodeList.EMPTY,
                new SqlNodeList(selectList, POS), null, null, null,
                null, null, null, null, null));
      }
      SqlNode query = null;
      for (SqlSelect select : selects) {
        if (query == null) {
          query = select;
        } else {
          query = SqlStdOperatorTable.UNION_ALL.createCall(POS, query,
              select);
        }
      }
      return implementor.result(query, clauses, this);
    }
  }
}

// End JdbcRules.java
