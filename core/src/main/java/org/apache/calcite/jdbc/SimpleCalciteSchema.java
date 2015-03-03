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

import org.apache.calcite.materialize.Lattice;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.util.Compatible;
import org.apache.calcite.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;

/**
 * An {@link org.apache.calcite.jdbc.CalciteSchema} implementation that
 * maintains minimal state.
 */
public class SimpleCalciteSchema extends CalciteSchema {

  private Map<String, SimpleCalciteSchema> subSchemas = Maps.newHashMap();
  private Map<String, TableEntry> tables = Maps.newHashMap();

  public SimpleCalciteSchema(CalciteSchema parent, Schema schema, String name) {
    super(parent, schema, name);
  }

  @Override
  public TableEntry add(String tableName, Table table) {
    TableEntry e = new TableEntryImpl(this, tableName, table,
        ImmutableList.<String>of());
    tables.put(tableName, e);
    return e;
  }

  @Override
  public CalciteSchema getSubSchema(String schemaName, boolean caseSensitive) {
    Schema s = schema.getSubSchema(schemaName);
    if (s != null) {
      return new SimpleCalciteSchema(this, s, schemaName);
    }
    return subSchemas.get(schemaName);
  }

  @Override
  public CalciteSchema add(String name, Schema schema) {
    SimpleCalciteSchema s = new SimpleCalciteSchema(this, schema, name);
    subSchemas.put(name, s);
    return s;
  }

  @Override
  protected void setCache(boolean cache) {
    throw new UnsupportedOperationException();
  }

  @Override
  public NavigableMap<String, LatticeEntry> getLatticeMap() {
    return null;
  }

  @Override
  protected boolean isCacheEnabled() {
    return false;
  }

  @Override
  protected LatticeEntry add(String name, Lattice lattice) {
    return null;
  }

  @Override
  public NavigableMap<String, CalciteSchema> getSubSchemaMap() {
    return Compatible.INSTANCE.navigableMap(
        ImmutableSortedMap.<String, CalciteSchema>copyOf(subSchemas));
  }

  @Override
  public TableEntry add(String tableName,
                        Table table,
                        ImmutableList<String> sqls) {
    return null;
  }

  @Override
  public Pair<String, Table> getTableBySql(String sql) {
    return null;
  }

  @Override
  public Pair<String, Table> getTable(String tableName, boolean caseSensitive) {
    Table t = schema.getTable(tableName);
    if (t == null) {
      TableEntry e = tables.get(tableName);
      if (e != null) {
        t = e.getTable();
      }
    }
    if (t != null) {
      return new Pair<String, Table>(tableName, t);
    }

    return null;
  }

//  @SuppressWarnings("unchecked")
//  @Override
//  public Collection<TableEntry> getTableEntries() {
//    return Collections.EMPTY_LIST;
//  }
//
//  @Override
//  public Set<String> getSubSchemaNames() {
//    return Sets.union(schema.getSubSchemaNames(), subSchemas.keySet());
//  }

//  @Override
//  public Collection<CalciteSchema> getSubSchemas() {
//    List<CalciteSchema> schemas = Lists.newLinkedList();
//    schemas.addAll(subSchemas.values());
//    for (String name : schema.getSubSchemaNames()) {
//      schemas.add(getSubSchema(name, true));
//    }
//    return schemas;
//  }

  @Override
  public NavigableSet<String> getTableNames() {
    return Compatible.INSTANCE.navigableSet(
        ImmutableSortedSet.copyOf(
            Sets.union(schema.getTableNames(), tables.keySet())));
  }

  @SuppressWarnings("unchecked")
  @Override
  //TODO
  public Collection<Function> getFunctions(String name, boolean caseSensitive) {
    return Collections.EMPTY_LIST;
  }

  @SuppressWarnings("unchecked")
  @Override
  //TODO
  public NavigableSet<String> getFunctionNames() {
    return Compatible.INSTANCE.navigableSet(ImmutableSortedSet.<String>of());
  }

  @SuppressWarnings("unchecked")
  @Override
  //TODO
  public NavigableMap<String, Table> getTablesBasedOnNullaryFunctions() {
    return Compatible.INSTANCE.navigableMap(
        ImmutableSortedMap.<String, Table>of());
  }

  @Override
  //TODO
  public Pair<String, Table> getTableBasedOnNullaryFunction(
      String tableName,
      boolean caseSensitive) {
    return null;
  }

  @Override
  protected FunctionEntry add(String name, Function function) {
    throw new UnsupportedOperationException();
  }

  public static SchemaPlus createRootSchema(boolean addMetadataSchema) {
    SimpleCalciteRootSchema rootSchema =
        new SimpleCalciteRootSchema(new CalciteConnectionImpl.RootSchema());
    if (addMetadataSchema) {
      rootSchema.add("metadata", MetadataSchema.INSTANCE);
    }
    return rootSchema.plus();
  }
}
