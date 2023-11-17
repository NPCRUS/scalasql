package scalasql.dialects

import scalasql._
import scalasql.query.{Aggregatable, AscDesc, CompoundSelect, Expr, From, GroupBy, InsertValues, Join, JoinNullable, JoinOps, Joinable, LateralJoinOps, Nulls, OrderBy, Query, TableRef, Update, WithExpr}
import scalasql.renderer.SqlStr.{Renderable, SqlStringSyntax, optSeq}
import scalasql.renderer.{Context, ExprsToSql, JoinsToSql, SqlStr}
import scalasql.utils.OptionPickler

import scala.reflect.ClassTag

trait MySqlDialect extends Dialect {
  def castParams = false

  override implicit def ExprOpsConv(v: Expr[_]): MySqlDialect.ExprOps =
    new MySqlDialect.ExprOps(v)

  override implicit def ExprTypedOpsConv[T: ClassTag](v: Expr[T]): operations.ExprTypedOps[T] =
    new MySqlDialect.ExprTypedOps(v)

  override implicit def ExprStringOpsConv(v: Expr[String]): MySqlDialect.ExprStringOps =
    new MySqlDialect.ExprStringOps(v)

  override implicit def TableOpsConv[V[_[_]]](t: Table[V]): scalasql.operations.TableOps[V] =
    new MySqlDialect.TableOps(t)

  implicit def OnConflictableUpdate[Q, R](
      query: InsertValues[Q, R]
  ): MySqlDialect.OnConflictable[Q, Int] =
    new MySqlDialect.OnConflictable[Q, Int](query, WithExpr.get(query), query.table)

  override def values[T: TypeMapper](ts: Seq[T]) = new MySqlDialect.Values(ts)

  implicit def LateralJoinOpsConv[C[_, _], Q, R](wrapped: JoinOps[C, Q, R] with Joinable[Q, R])(
      implicit qr: Queryable.Row[Q, R]
  ) = new LateralJoinOps(wrapped)

  implicit def AggExprOpsConv[T](v: Aggregatable[Expr[T]]): operations.AggExprOps[T] = new MySqlDialect.AggExprOps(v)
}

object MySqlDialect extends MySqlDialect {
  class AggExprOps[T](v: Aggregatable[Expr[T]]) extends scalasql.operations.AggExprOps[T](v) {
    def mkString(sep: Expr[String] = null)(implicit tm: TypeMapper[T]): Expr[String] = {
      val sepRender = Option(sep).getOrElse(sql"''")
      v.queryExpr(expr => implicit ctx => sql"GROUP_CONCAT(CONCAT($expr, '') SEPARATOR ${sepRender})")
    }
  }
  class ExprOps(val v: Expr[_]) extends operations.ExprOps(v) {
    override def cast[V: TypeMapper]: Expr[V] = Expr { implicit ctx =>
      val s = implicitly[TypeMapper[V]] match {
        case TypeMapper.ByteType | TypeMapper.ShortType | TypeMapper.IntType |
            TypeMapper.LongType =>
          "SIGNED"
        case TypeMapper.StringType => "CHAR"
        case TypeMapper.LocalDateTimeType | TypeMapper.InstantType => "DATETIME"
        case s => s.typeString
      }

      sql"CAST($v AS ${SqlStr.raw(s)})"
    }
  }

  class ExprTypedOps[T: ClassTag](v: Expr[T]) extends operations.ExprTypedOps(v) {

    /** Equals to */
    override def ===[V: ClassTag](x: Expr[V]): Expr[Boolean] = Expr { implicit ctx =>
      (isNullable[T], isNullable[V]) match {
        case (true, true) => sql"($v <=> $x)"
        case _ => sql"($v = $x)"
      }
    }

    /** Not equal to */
    override def !==[V: ClassTag](x: Expr[V]): Expr[Boolean] = Expr { implicit ctx =>
      (isNullable[T], isNullable[V]) match {
        case (true, true) => sql"(NOT ($v <=> $x))"
        case _ => sql"($v <> $x)"
      }
    }

  }

  class ExprStringOps(val v: Expr[String]) extends operations.ExprStringOps(v) with PadOps {
    override def +(x: Expr[String]): Expr[String] = Expr { implicit ctx => sql"CONCAT($v, $x)" }

    def indexOf(x: Expr[String]): Expr[Int] = Expr { implicit ctx => sql"POSITION($x IN $v)" }
    def reverse: Expr[String] = Expr { implicit ctx => sql"REVERSE($v)" }
  }

  class TableOps[V[_[_]]](t: Table[V]) extends scalasql.operations.TableOps[V](t) {
    override def update(
        filter: V[Column.ColumnExpr] => Expr[Boolean]
    ): Update[V[Column.ColumnExpr], V[Id]] = {
      val ref = t.tableRef
      new Update(t.metadata.vExpr(ref), ref, Nil, Nil, Seq(filter(t.metadata.vExpr(ref))))(
        t.containerQr
      )
    }

    protected override def joinableSelect: Select[V[Expr], V[Id]] = {
      val ref = t.tableRef
      new SimpleSelect(t.metadata.vExpr(ref).asInstanceOf[V[Expr]], None, Seq(ref), Nil, Nil, None)(
        t.containerQr
      )
    }
  }

  class Update[Q, R](
      expr: Q,
      table: TableRef,
      set0: Seq[Column.Assignment[_]],
      joins: Seq[Join],
      where: Seq[Expr[_]]
  )(implicit qr: Queryable.Row[Q, R])
      extends scalasql.query.Update.Impl[Q, R](expr, table, set0, joins, where) {

    protected override def copy[Q, R](
        expr: Q = this.expr,
        table: TableRef = this.table,
        set0: Seq[Column.Assignment[_]] = this.set0,
        joins: Seq[Join] = this.joins,
        where: Seq[Expr[_]] = this.where
    )(implicit qr: Queryable.Row[Q, R]) = new Update(expr, table, set0, joins, where)

    protected override def renderToSql(ctx: Context) = {
      new UpdateRenderer(this.joins, this.table, this.set0, this.where, ctx).render()
    }

  }

  class UpdateRenderer(
      joins0: Seq[Join],
      table: TableRef,
      set0: Seq[Column.Assignment[_]],
      where0: Seq[Expr[_]],
      prevContext: Context
  ) extends scalasql.query.Update.Renderer(joins0, table, set0, where0, prevContext) {
    override lazy val updateList = set0.map { case assign =>
      val colStr = SqlStr.raw(prevContext.config.columnNameMapper(assign.column.name))
      sql"$tableName.$colStr = ${assign.value}"
    }

    lazy val whereAll = ExprsToSql.booleanExprs(sql" WHERE ", where0)
    override lazy val joinOns = joins0
      .map(_.from.map(_.on.map(t => SqlStr.flatten(Renderable.renderToSql(t)))))

    override lazy val joins = optSeq(joins0)(JoinsToSql.joinsToSqlStr(_, renderedFroms, joinOns))
    override def render() = sql"UPDATE $tableName" + joins + sql" SET " + sets + whereAll
  }

  class OnConflictable[Q, R](val query: Query[R], expr: Q, table: TableRef) {

    def onConflictUpdate(c2: Q => Column.Assignment[_]*): OnConflictUpdate[Q, R] =
      new OnConflictUpdate(this, c2.map(_(expr)), table)
  }

  class OnConflictUpdate[Q, R](
      insert: OnConflictable[Q, R],
      updates: Seq[Column.Assignment[_]],
      table: TableRef
  ) extends Query[R] {

    override def queryIsExecuteUpdate = true
    protected def queryWalkExprs() = Query.getWalkExprs(insert.query)

    protected def queryIsSingleRow = Query.getIsSingleRow(insert.query)

    protected def queryValueReader = Query.getValueReader(insert.query)

    protected def renderToSql(ctx: Context) = {
      implicit val implicitCtx = Context.compute(ctx, Nil, Some(table))
      val str = Renderable.renderToSql(insert.query)

      val updatesStr = SqlStr.join(
        updates.map { case assign => SqlStr.raw(assign.column.name) + sql" = ${assign.value}" },
        sql", "
      )
      str + sql" ON DUPLICATE KEY UPDATE $updatesStr"
    }
    protected def queryTypeMappers() = {
      Query.getTypeMappers(insert.query)
    }
  }

  trait Select[Q, R] extends scalasql.query.Select[Q, R] {
    override def newCompoundSelect[Q, R](
        lhs: scalasql.query.SimpleSelect[Q, R],
        compoundOps: Seq[CompoundSelect.Op[Q, R]],
        orderBy: Seq[OrderBy],
        limit: Option[Int],
        offset: Option[Int]
    )(implicit qr: Queryable.Row[Q, R]): scalasql.query.CompoundSelect[Q, R] = {
      new CompoundSelect(lhs, compoundOps, orderBy, limit, offset)
    }

    override def newSimpleSelect[Q, R](
        expr: Q,
        exprPrefix: Option[String],
        from: Seq[From],
        joins: Seq[Join],
        where: Seq[Expr[_]],
        groupBy0: Option[GroupBy]
    )(implicit qr: Queryable.Row[Q, R]): scalasql.query.SimpleSelect[Q, R] = {
      new SimpleSelect(expr, exprPrefix, from, joins, where, groupBy0)
    }
  }

  class SimpleSelect[Q, R](
      expr: Q,
      exprPrefix: Option[String],
      from: Seq[From],
      joins: Seq[Join],
      where: Seq[Expr[_]],
      groupBy0: Option[GroupBy]
  )(implicit qr: Queryable.Row[Q, R])
      extends scalasql.query.SimpleSelect(expr, exprPrefix, from, joins, where, groupBy0)
      with Select[Q, R] {
    override def outerJoin[Q2, R2](other: Joinable[Q2, R2])(on: (Q, Q2) => Expr[Boolean])(
        implicit joinQr: Queryable.Row[Q2, R2]
    ): scalasql.query.Select[(JoinNullable[Q], JoinNullable[Q2]), (Option[R], Option[R2])] = {
      leftJoin(other)(on)
        .map { case (l, r) => (JoinNullable(l), r) }
        .union(rightJoin(other)(on).map { case (l, r) =>
          (l, JoinNullable(r))
        })
    }
  }

  class CompoundSelect[Q, R](
      lhs: scalasql.query.SimpleSelect[Q, R],
      compoundOps: Seq[scalasql.query.CompoundSelect.Op[Q, R]],
      orderBy: Seq[OrderBy],
      limit: Option[Int],
      offset: Option[Int]
  )(implicit qr: Queryable.Row[Q, R])
      extends scalasql.query.CompoundSelect(lhs, compoundOps, orderBy, limit, offset)
      with Select[Q, R] {
    protected override def getRenderer(prevContext: Context) =
      new CompoundSelectRenderer(this, prevContext)
  }

  class CompoundSelectRenderer[Q, R](
      query: scalasql.query.CompoundSelect[Q, R],
      prevContext: Context
  ) extends scalasql.query.CompoundSelect.Renderer(query, prevContext) {

    override lazy val limitOpt = SqlStr
      .flatten(CompoundSelectRendererForceLimit.limitToSqlStr(query.limit, query.offset))

    override def orderToSqlStr(newCtx: Context) = {
      SqlStr.optSeq(query.orderBy) { orderBys =>
        val orderStr = SqlStr.join(
          orderBys.map { orderBy =>
            val exprStr = Renderable.renderToSql(orderBy.expr)(newCtx)

            (orderBy.ascDesc, orderBy.nulls) match {
              case (Some(AscDesc.Asc), None | Some(Nulls.First)) => sql"$exprStr ASC"
              case (Some(AscDesc.Desc), Some(Nulls.First)) =>
                sql"$exprStr IS NULL DESC, $exprStr DESC"
              case (Some(AscDesc.Asc), Some(Nulls.Last)) => sql"$exprStr IS NULL ASC, $exprStr ASC"
              case (Some(AscDesc.Desc), None | Some(Nulls.Last)) => sql"$exprStr DESC"
              case (None, None) => exprStr
              case (None, Some(Nulls.First)) => sql"$exprStr IS NULL DESC, $exprStr"
              case (None, Some(Nulls.Last)) => sql"$exprStr IS NULL ASC, $exprStr"
            }
          },
          sql", "
        )

        sql" ORDER BY $orderStr"
      }
    }
  }

  class Values[T: TypeMapper](ts: Seq[T]) extends scalasql.query.Values[T](ts) {
    override protected def getRenderer(prevContext: Context) =
      new ValuesRenderer[T](this)(implicitly, prevContext)
    override protected def columnName = "column_0"
  }
  class ValuesRenderer[T: TypeMapper](v: Values[T])(implicit ctx: Context)
      extends scalasql.query.Values.Renderer[T](v) {
    override def wrapRow(t: T) = sql"ROW($t)"
  }

}
