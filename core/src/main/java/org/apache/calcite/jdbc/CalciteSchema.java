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
package org.apache.calcite.jdbc;

import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.materialize.Lattice;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.MaterializedViewTable;
import org.apache.calcite.schema.impl.StarTable;
import org.apache.calcite.util.Pair;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;

/**
 * Schema.
 *
 * <p>Wrapper around user-defined schema used internally.</p>
 */
public abstract class CalciteSchema {
  protected final CalciteSchema parent;
  public final Schema schema;
  public final String name;
  private ImmutableList<ImmutableList<String>> path;

  public CalciteSchema(CalciteSchema parent, final Schema schema, String name) {
    this.parent = parent;
    this.schema = schema;
    this.name = name;
  }


  /** Creates a TableEntryImpl with no SQLs. */
  private TableEntryImpl tableEntry(String name, Table table) {
    return new TableEntryImpl(this, name, table, ImmutableList.<String>of());
  }

  /** Defines a table within this schema. */
  public TableEntry add(String tableName, Table table) {
    return add(tableName, table, ImmutableList.<String>of());
  }

  /** Defines a table within this schema. */
  public abstract TableEntry add(String tableName, Table table,
      ImmutableList<String> sqls);

  public abstract CalciteSchema getSubSchema(String schemaName,
                                             boolean caseSensitive);

  /** Adds a child schema of this schema. */
  public abstract CalciteSchema add(String name, Schema schema);

  /** Returns a table that materializes the given SQL statement. */
  public abstract Pair<String, Table> getTableBySql(String sql);

  /** Returns a table with the given name. Does not look for views. */
  public abstract Pair<String, Table> getTable(String tableName,
                                               boolean caseSensitive);

  public CalciteSchema root() {
    for (CalciteSchema schema = this;;) {
      if (schema.parent == null) {
        return (CalciteSchema) schema;
      }
      schema = schema.parent;
    }
  }

  /** Returns the path of an object in this schema. */
  public List<String> path(String name) {
    final List<String> list = new ArrayList<String>();
    if (name != null) {
      list.add(name);
    }
    for (CalciteSchema s = this; s != null; s = s.parent) {
      if (s.parent != null || !s.name.equals("")) {
        // Omit the root schema's name from the path if it's the empty string,
        // which it usually is.
        list.add(s.name);
      }
    }
    return ImmutableList.copyOf(Lists.reverse(list));
  }

  public String getName() {
    return name;
  }

  public SchemaPlus plus() {
    return new SchemaPlusImpl();
  }

  public static CalciteSchema from(SchemaPlus plus) {
    return ((SchemaPlusImpl) plus).calciteSchema();
  }

  /** Returns the default path resolving functions from this schema.
   *
   * <p>The path consists is a list of lists of strings.
   * Each list of strings represents the path of a schema from the root schema.
   * For example, [[], [foo], [foo, bar, baz]] represents three schemas: the
   * root schema "/" (level 0), "/foo" (level 1) and "/foo/bar/baz" (level 3).
   *
   * @return Path of this schema; never null, may be empty
   */
  public List<? extends List<String>> getPath() {
    if (path != null) {
      return path;
    }
    // Return a path consisting of just this schema.
    return ImmutableList.of(path(null));
  }


  /** Returns a collection of sub-schemas, both explicit (defined using
   * {@link #add(String, org.apache.calcite.schema.Schema)}) and implicit
   * (defined using {@link org.apache.calcite.schema.Schema#getSubSchemaNames()}
   * and {@link Schema#getSubSchema(String)}). */
  public abstract NavigableMap<String, CalciteSchema> getSubSchemaMap();

  /** Returns a collection of lattices.
   *
   * <p>All are explicit (defined using {@link #add(String, Lattice)}). */
  public abstract NavigableMap<String, LatticeEntry> getLatticeMap();

  /** Returns the set of all table names. Includes implicit and explicit tables
   * and functions with zero parameters. */
  public abstract NavigableSet<String> getTableNames();

  /** Returns a collection of all functions, explicit and implicit, with a given
   * name. Never null. */
  public abstract Collection<Function> getFunctions(String name,
                                                    boolean caseSensitive);

  /** Returns the list of function names in this schema, both implicit and
   * explicit, never null. */
  public abstract NavigableSet<String> getFunctionNames();

  /** Returns tables derived from explicit and implicit functions
   * that take zero parameters. */
  public abstract NavigableMap<String, Table>
  getTablesBasedOnNullaryFunctions();

  /** Returns a tables derived from explicit and implicit functions
   * that take zero parameters. */
  public abstract Pair<String, Table> getTableBasedOnNullaryFunction(
      String tableName, boolean caseSensitive);

  protected abstract void setCache(boolean cache);

  protected abstract FunctionEntry add(String name, Function function);

  protected abstract LatticeEntry add(String name, Lattice lattice);

  protected abstract boolean isCacheEnabled();
    /**
     * Entry in a schema, such as a table or sub-schema.
     *
     * <p>Each object's name is a property of its membership in a schema;
     * therefore in principle it could belong to several schemas, or
     * even the same schema several times, with different names. In this
     * respect, it is like an inode in a Unix file system.</p>
     *
     * <p>The members of a schema must have unique names.
     */
  public abstract static class Entry {
    public final CalciteSchema schema;
    public final String name;

    public Entry(CalciteSchema schema, String name) {
      this.schema = Preconditions.checkNotNull(schema);
      this.name = Preconditions.checkNotNull(name);
    }

    /** Returns this object's path. For example ["hr", "emps"]. */
    public final List<String> path() {
      return schema.path(name);
    }
  }

  /** Membership of a table in a schema. */
  public abstract static class TableEntry extends Entry {
    public final List<String> sqls;

    public TableEntry(CalciteSchema schema, String name,
        ImmutableList<String> sqls) {
      super(schema, name);
      this.sqls = Preconditions.checkNotNull(sqls);
    }

    public abstract Table getTable();
  }

  /** Membership of a function in a schema. */
  public abstract static class FunctionEntry extends Entry {
    public FunctionEntry(CalciteSchema schema, String name) {
      super(schema, name);
    }

    public abstract Function getFunction();

    /** Whether this represents a materialized view. (At a given point in time,
     * it may or may not be materialized as a table.) */
    public abstract boolean isMaterialization();
  }

  /** Membership of a lattice in a schema. */
  public abstract static class LatticeEntry extends Entry {
    public LatticeEntry(CalciteSchema schema, String name) {
      super(schema, name);
    }

    public abstract Lattice getLattice();

    public abstract TableEntry getStarTable();
  }

  /** Implementation of {@link SchemaPlus} based on a
   * {@link org.apache.calcite.jdbc.CalciteSchema}. */
  private class SchemaPlusImpl implements SchemaPlus {
    CalciteSchema calciteSchema() {
      return CalciteSchema.this;
    }

    public SchemaPlus getParentSchema() {
      return parent == null ? null : parent.plus();
    }

    public String getName() {
      return CalciteSchema.this.getName();
    }

    public boolean isMutable() {
      return schema.isMutable();
    }

    public void setCacheEnabled(boolean cache) {
      CalciteSchema.this.setCache(cache);
    }

    public boolean isCacheEnabled() {
      return CalciteSchema.this.isCacheEnabled();
    }

    public boolean contentsHaveChangedSince(long lastCheck, long now) {
      return schema.contentsHaveChangedSince(lastCheck, now);
    }

    public Expression getExpression(SchemaPlus parentSchema, String name) {
      return schema.getExpression(parentSchema, name);
    }

    public Table getTable(String name) {
      final TableEntry entry = CalciteSchema.this.getTable(name, true);
      return entry == null ? null : entry.getTable();
    }

    public NavigableSet<String> getTableNames() {
      return CalciteSchema.this.getTableNames();
    }

    public Collection<Function> getFunctions(String name) {
      return CalciteSchema.this.getFunctions(name, true);
    }

    public NavigableSet<String> getFunctionNames() {
      return CalciteSchema.this.getFunctionNames();
    }

    public SchemaPlus getSubSchema(String name) {
      final CalciteSchema subSchema =
          CalciteSchema.this.getSubSchema(name, true);
      return subSchema == null ? null : subSchema.plus();
    }

    public Set<String> getSubSchemaNames() {
      return CalciteSchema.this.getSubSchemaMap().keySet();
    }

    public SchemaPlus add(String name, Schema schema) {
      final CalciteSchema calciteSchema = CalciteSchema.this.add(name, schema);
      return calciteSchema.plus();
    }

    public <T> T unwrap(Class<T> clazz) {
      if (clazz.isInstance(this)) {
        return clazz.cast(this);
      }
      if (clazz.isInstance(CalciteSchema.this)) {
        return clazz.cast(CalciteSchema.this);
      }
      if (clazz.isInstance(CalciteSchema.this.schema)) {
        return clazz.cast(CalciteSchema.this.schema);
      }
      throw new ClassCastException("not a " + clazz);
    }

    public void setPath(ImmutableList<ImmutableList<String>> path) {
      CalciteSchema.this.path = path;
    }

    public void add(String name, Table table) {
      CalciteSchema.this.add(name, table);
    }

    public void add(String name, Function function) {
      CalciteSchema.this.add(name, function);
    }

    public void add(String name, Lattice lattice) {
      CalciteSchema.this.add(name, lattice);
    }
  }

  /**
   * Implementation of {@link CalciteSchema.TableEntry}
   * where all properties are held in fields.
   */
  public static class TableEntryImpl extends TableEntry {
    private final Table table;

    /** Creates a TableEntryImpl. */
    public TableEntryImpl(CalciteSchema schema, String name, Table table,
        ImmutableList<String> sqls) {
      super(schema, name, sqls);
      assert table != null;
      this.table = Preconditions.checkNotNull(table);
    }

    public Table getTable() {
      return table;
    }
  }

  /**
   * Implementation of {@link FunctionEntry}
   * where all properties are held in fields.
   */
  public static class FunctionEntryImpl extends FunctionEntry {
    private final Function function;

    /** Creates a FunctionEntryImpl. */
    public FunctionEntryImpl(CalciteSchema schema, String name,
        Function function) {
      super(schema, name);
      this.function = function;
    }

    public Function getFunction() {
      return function;
    }

    public boolean isMaterialization() {
      return function
          instanceof MaterializedViewTable.MaterializedViewTableMacro;
    }
  }

  /**
   * Implementation of {@link LatticeEntry}
   * where all properties are held in fields.
   */
  public static class LatticeEntryImpl extends LatticeEntry {
    private final Lattice lattice;
    private final CalciteSchema.TableEntry starTableEntry;

    /** Creates a LatticeEntryImpl. */
    public LatticeEntryImpl(CalciteSchema schema, String name,
        Lattice lattice) {
      super(schema, name);
      this.lattice = lattice;

      // Star table has same name as lattice and is in same schema.
      final StarTable starTable = lattice.createStarTable();
      starTableEntry = schema.add(name, starTable);
    }

    public Lattice getLattice() {
      return lattice;
    }

    public TableEntry getStarTable() {
      return starTableEntry;
    }
  }


}

// End CalciteSchema.java
