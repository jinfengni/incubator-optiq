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
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TableMacro;
import org.apache.calcite.util.Compatible;
import org.apache.calcite.util.Pair;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * CachingCalciteSchema maintains a set of Navigable maps for each entity type.
 */
public class CachingCalciteSchema extends CalciteSchema {
  /** Comparator that compares all strings differently, but if two strings are
   * equal in case-insensitive match they are right next to each other. In a
   * collection sorted on this comparator, we can find case-insensitive matches
   * for a given string using a range scan between the upper-case string and
   * the lower-case string. */
  private static final Comparator<String> COMPARATOR =
      new Comparator<String>() {
        public int compare(String o1, String o2) {
          int c = o1.compareToIgnoreCase(o2);
          if (c == 0) {
            c = o1.compareTo(o2);
          }
          return c;
        }
      };
  /** Tables explicitly defined in this schema. Does not include tables in
   *  {@link #schema}. */
  public final NavigableMap<String, TableEntry> tableMap =
      new TreeMap<String, TableEntry>(COMPARATOR);
  private final Multimap<String, FunctionEntry> functionMap =
      LinkedListMultimap.create();
  private final NavigableMap<String, LatticeEntry> latticeMap =
      new TreeMap<String, LatticeEntry>(COMPARATOR);
  private final NavigableSet<String> functionNames =
      new TreeSet<String>(COMPARATOR);
  private final NavigableMap<String, FunctionEntry> nullaryFunctionMap =
      new TreeMap<String, FunctionEntry>(COMPARATOR);
  private final NavigableMap<String, CalciteSchema> subSchemaMap =
      new TreeMap<String, CalciteSchema>(COMPARATOR);

  private final Cached<SubSchemaCache> implicitSubSchemaCache;
  private final Cached<NavigableSet<String>> implicitTableCache;
  private final Cached<NavigableSet<String>> implicitFunctionCache;

  private boolean cache = true;

  public CachingCalciteSchema(CalciteSchema parent, final Schema schema,
                            String name) {
    super(parent, schema, name);
    assert (parent == null) == (this instanceof CalciteRootSchema);
    this.implicitSubSchemaCache =
        new AbstractCached<SubSchemaCache>() {
          public SubSchemaCache build() {
            return new SubSchemaCache(CachingCalciteSchema.this,
                Compatible.INSTANCE.navigableSet(
                    ImmutableSortedSet.copyOf(COMPARATOR,
                        schema.getSubSchemaNames())));
          }
        };
    this.implicitTableCache =
        new AbstractCached<NavigableSet<String>>() {
          public NavigableSet<String> build() {
            return Compatible.INSTANCE.navigableSet(
                ImmutableSortedSet.copyOf(COMPARATOR,
                    schema.getTableNames()));
          }
        };
    this.implicitFunctionCache =
        new AbstractCached<NavigableSet<String>>() {
          public NavigableSet<String> build() {
            return Compatible.INSTANCE.navigableSet(
                ImmutableSortedSet.copyOf(COMPARATOR,
                    schema.getFunctionNames()));
          }
        };

  }

  /** Defines a table within this schema. */
  public TableEntry add(String tableName, Table table,
                                 ImmutableList<String> sqls) {
    final TableEntryImpl entry =
        new TableEntryImpl(this, tableName, table, sqls);
    tableMap.put(tableName, entry);
    return entry;
  }

  protected FunctionEntry add(String name, Function function) {
    final FunctionEntryImpl entry =
        new FunctionEntryImpl(this, name, function);
    functionMap.put(name, entry);
    functionNames.add(name);
    if (function.getParameters().isEmpty()) {
      nullaryFunctionMap.put(name, entry);
    }
    return entry;
  }

  protected LatticeEntry add(String name, Lattice lattice) {
    if (latticeMap.containsKey(name)) {
      throw new RuntimeException("Duplicate lattice '" + name + "'");
    }
    final LatticeEntryImpl entry = new LatticeEntryImpl(this, name, lattice);
    latticeMap.put(name, entry);
    return entry;
  }

  protected void setCache(boolean cache) {
    if (cache == this.cache) {
      return;
    }
    final long now = System.currentTimeMillis();
    implicitSubSchemaCache.enable(now, cache);
    implicitTableCache.enable(now, cache);
    implicitFunctionCache.enable(now, cache);
    this.cache = cache;
  }

  protected boolean isCacheEnabled() {
    return this.cache;
  }

  /** Creates a root schema. When <code>addMetadataSchema</code> argument is
   * true a "metadata" schema containing definitions of tables, columns etc. is
   * added to root schema. */
  public static CalciteSchema createRootSchema(boolean addMetadataSchema) {
    CachingCalciteRootSchema rootSchema =
        new CachingCalciteRootSchema(new CalciteConnectionImpl.RootSchema());
    if (addMetadataSchema) {
      rootSchema.add("metadata", MetadataSchema.INSTANCE);
    }
    return rootSchema;
  }

  public final CalciteSchema getSubSchema(String schemaName,
                                          boolean caseSensitive) {
    if (caseSensitive) {
      // Check explicit schemas, case-sensitive.
      final CalciteSchema entry = subSchemaMap.get(schemaName);
      if (entry != null) {
        return entry;
      }
      // Check implicit schemas, case-sensitive.
      final long now = System.currentTimeMillis();
      final SubSchemaCache subSchemaCache = implicitSubSchemaCache.get(now);
      if (subSchemaCache.names.contains(schemaName)) {
        return subSchemaCache.cache.getUnchecked(schemaName);
      }
      return null;
    } else {
      // Check explicit schemas, case-insensitive.
      //noinspection LoopStatementThatDoesntLoop
      for (Map.Entry<String, CalciteSchema> entry
          : find(subSchemaMap, schemaName).entrySet()) {
        return entry.getValue();
      }
      // Check implicit schemas, case-insensitive.
      final long now = System.currentTimeMillis();
      final SubSchemaCache subSchemaCache =
          implicitSubSchemaCache.get(now);
      final String schemaName2 = subSchemaCache.names.floor(schemaName);
      if (schemaName2 != null) {
        return subSchemaCache.cache.getUnchecked(schemaName2);
      }
      return null;
    }
  }


  /** Adds a child schema of this schema. */
  public CalciteSchema add(String name, Schema schema) {
    final CalciteSchema calciteSchema =
        new CachingCalciteSchema(this, schema, name);
    subSchemaMap.put(name, calciteSchema);
    return calciteSchema;
  }

  /** Returns a table that materializes the given SQL statement. */
  public final Pair<String, Table> getTableBySql(String sql) {
    for (TableEntry tableEntry : tableMap.values()) {
      if (tableEntry.sqls.contains(sql)) {
        return Pair.of(tableEntry.name, tableEntry.getTable());
      }
    }
    return null;
  }

  /** Returns a table with the given name. Does not look for views. */
  public final Pair<String, Table> getTable(String tableName,
                                            boolean caseSensitive) {
    if (caseSensitive) {
      // Check explicit tables, case-sensitive.
      final TableEntry entry = tableMap.get(tableName);
      if (entry != null) {
        return Pair.of(tableName, entry.getTable());
      }
      // Check implicit tables, case-sensitive.
      final long now = System.currentTimeMillis();
      if (implicitTableCache.get(now).contains(tableName)) {
        final Table table = schema.getTable(tableName);
        if (table != null) {
          return Pair.of(tableName, table);
        }
      }
      return null;
    } else {
      // Check explicit tables, case-insensitive.
      //noinspection LoopStatementThatDoesntLoop
      for (Map.Entry<String, TableEntry> entry
          : find(tableMap, tableName).entrySet()) {
        return Pair.of(entry.getKey(), entry.getValue().getTable());
      }
      // Check implicit tables, case-insensitive.
      final long now = System.currentTimeMillis();
      final NavigableSet<String> implicitTableNames =
          implicitTableCache.get(now);
      final String tableName2 = implicitTableNames.floor(tableName);
      if (tableName2 != null) {
        final Table table = schema.getTable(tableName2);
        if (table != null) {
          return Pair.of(tableName2, table);
        }
      }
      return null;
    }
  }

  /** Returns a collection of sub-schemas, both explicit (defined using
   * {@link #add(String, org.apache.calcite.schema.Schema)}) and implicit
   * (defined using {@link org.apache.calcite.schema.Schema#getSubSchemaNames()}
   * and {@link Schema#getSubSchema(String)}). */
  public NavigableMap<String, CalciteSchema> getSubSchemaMap() {
    // Build a map of implicit sub-schemas first, then explicit sub-schemas.
    // If there are implicit and explicit with the same name, explicit wins.
    final ImmutableSortedMap.Builder<String, CalciteSchema> builder =
        new ImmutableSortedMap.Builder<String, CalciteSchema>(COMPARATOR);
    final long now = System.currentTimeMillis();
    final SubSchemaCache subSchemaCache = implicitSubSchemaCache.get(now);
    for (String name : subSchemaCache.names) {
      builder.put(name, subSchemaCache.cache.getUnchecked(name));
    }
    builder.putAll(subSchemaMap);
    return Compatible.INSTANCE.navigableMap(builder.build());
  }

  /** Returns a collection of lattices.
   *
   * <p>All are explicit (defined using {@link #add(String, Lattice)}). */
  public NavigableMap<String, LatticeEntry> getLatticeMap() {
    return Compatible.INSTANCE.immutableNavigableMap(latticeMap);
  }

  /** Returns the set of all table names. Includes implicit and explicit tables
   * and functions with zero parameters. */
  public NavigableSet<String> getTableNames() {
    final ImmutableSortedSet.Builder<String> builder =
        new ImmutableSortedSet.Builder<String>(COMPARATOR);
    // Add explicit tables, case-sensitive.
    builder.addAll(tableMap.keySet());
    // Add implicit tables, case-sensitive.
    builder.addAll(implicitTableCache.get(System.currentTimeMillis()));
    return Compatible.INSTANCE.navigableSet(builder.build());
  }

  /** Returns a collection of all functions, explicit and implicit, with a given
   * name. Never null. */
  public Collection<Function> getFunctions(String name, boolean caseSensitive) {
    final ImmutableList.Builder<Function> builder = ImmutableList.builder();

    if (caseSensitive) {
      // Add explicit functions, case-sensitive.
      final Collection<FunctionEntry> functionEntries = functionMap.get(name);
      if (functionEntries != null) {
        for (FunctionEntry functionEntry : functionEntries) {
          builder.add(functionEntry.getFunction());
        }
      }
      // Add implicit functions, case-sensitive.
      final Collection<Function> functions = schema.getFunctions(name);
      if (functions != null) {
        builder.addAll(functions);
      }
    } else {
      // Add explicit functions, case-insensitive.
      for (String name2 : find(functionNames, name)) {
        final Collection<FunctionEntry> functionEntries =
            functionMap.get(name2);
        if (functionEntries != null) {
          for (FunctionEntry functionEntry : functionEntries) {
            builder.add(functionEntry.getFunction());
          }
        }
      }
      // Add implicit functions, case-insensitive.
      for (String name2
          : find(implicitFunctionCache.get(System.currentTimeMillis()), name)) {
        final Collection<Function> functions = schema.getFunctions(name2);
        if (functions != null) {
          builder.addAll(functions);
        }
      }
    }
    return builder.build();
  }

  /** Returns the list of function names in this schema, both implicit and
   * explicit, never null. */
  public NavigableSet<String> getFunctionNames() {
    final ImmutableSortedSet.Builder<String> builder =
        new ImmutableSortedSet.Builder<String>(COMPARATOR);
    // Add explicit functions, case-sensitive.
    builder.addAll(functionMap.keySet());
    // Add implicit functions, case-sensitive.
    builder.addAll(implicitFunctionCache.get(System.currentTimeMillis()));
    return Compatible.INSTANCE.navigableSet(builder.build());
  }

  /** Returns tables derived from explicit and implicit functions
   * that take zero parameters. */
  public NavigableMap<String, Table> getTablesBasedOnNullaryFunctions() {
    ImmutableSortedMap.Builder<String, Table> builder =
        new ImmutableSortedMap.Builder<String, Table>(COMPARATOR);
    for (Map.Entry<String, FunctionEntry> s : nullaryFunctionMap.entrySet()) {
      final Function function = s.getValue().getFunction();
      if (function instanceof TableMacro) {
        assert function.getParameters().isEmpty();
        final Table table = ((TableMacro) function).apply(ImmutableList.of());
        builder.put(s.getKey(), table);
      }
    }
    for (String s : implicitFunctionCache.get(System.currentTimeMillis())) {
      for (Function function : schema.getFunctions(s)) {
        if (function instanceof TableMacro
            && function.getParameters().isEmpty()) {
          final Table table = ((TableMacro) function).apply(ImmutableList.of());
          builder.put(s, table);
        }
      }
    }
    return Compatible.INSTANCE.navigableMap(builder.build());
  }

  /** Returns a tables derived from explicit and implicit functions
   * that take zero parameters. */
  public Pair<String, Table> getTableBasedOnNullaryFunction(
      String tableName,
      boolean caseSensitive) {
    if (caseSensitive) {
      final FunctionEntry functionEntry = nullaryFunctionMap.get(tableName);
      if (functionEntry != null) {
        final Function function = functionEntry.getFunction();
        if (function instanceof TableMacro) {
          assert function.getParameters().isEmpty();
          final Table table = ((TableMacro) function).apply(ImmutableList.of());
          return Pair.of(tableName, table);
        }
      }
      for (Function function : schema.getFunctions(tableName)) {
        if (function instanceof TableMacro
            && function.getParameters().isEmpty()) {
          final Table table = ((TableMacro) function).apply(ImmutableList.of());
          return Pair.of(tableName, table);
        }
      }
    } else {
      for (Map.Entry<String, FunctionEntry> entry
          : find(nullaryFunctionMap, tableName).entrySet()) {
        final Function function = entry.getValue().getFunction();
        if (function instanceof TableMacro) {
          assert function.getParameters().isEmpty();
          final Table table = ((TableMacro) function).apply(ImmutableList.of());
          return Pair.of(entry.getKey(), table);
        }
      }
      final NavigableSet<String> set =
          implicitFunctionCache.get(System.currentTimeMillis());
      for (String s : find(set, tableName)) {
        for (Function function : schema.getFunctions(s)) {
          if (function instanceof TableMacro
              && function.getParameters().isEmpty()) {
            final Table table =
                ((TableMacro) function).apply(ImmutableList.of());
            return Pair.of(s, table);
          }
        }
      }
    }
    return null;
  }

  /** Returns a subset of a map whose keys match the given string
   * case-insensitively. */
  private static <V> NavigableMap<String, V> find(NavigableMap<String, V> map,
                                                  String s) {
    assert map.comparator() == COMPARATOR;
    return map.subMap(s.toUpperCase(), true, s.toLowerCase(), true);
  }

  /** Returns a subset of a set whose values match the given string
   * case-insensitively. */
  private static Iterable<String> find(NavigableSet<String> set, String name) {
    assert set.comparator() == COMPARATOR;
    return set.subSet(name.toUpperCase(), true, name.toLowerCase(), true);
  }


  /** Strategy for caching the value of an object and re-creating it if its
   * value is out of date as of a given timestamp.
   *
   * @param <T> Type of cached object
   */
  private interface Cached<T> {
    /** Returns the value; uses cached value if valid. */
    T get(long now);

    /** Creates a new value. */
    T build();

    /** Called when CalciteSchema caching is enabled or disabled. */
    void enable(long now, boolean enabled);
  }

  /** Implementation of {@link CachingCalciteSchema.Cached}
   * that drives from {@link CachingCalciteSchema#cache}. */
  private abstract class AbstractCached<T> implements Cached<T> {
    T t;
    long checked = Long.MIN_VALUE;

    public T get(long now) {
      if (!CachingCalciteSchema.this.cache) {
        return build();
      }
      if (checked == Long.MIN_VALUE
          || schema.contentsHaveChangedSince(checked, now)) {
        t = build();
      }
      checked = now;
      return t;
    }

    public void enable(long now, boolean enabled) {
      if (!enabled) {
        t = null;
      }
      checked = Long.MIN_VALUE;
    }
  }

  /** Information about the implicit sub-schemas of an {@link CalciteSchema}. */
  private static class SubSchemaCache {
    /** The names of sub-schemas returned from the {@link Schema} SPI. */
    final NavigableSet<String> names;
    /** Cached {@link CalciteSchema} wrappers. It is
     * worth caching them because they contain maps of their own sub-objects. */
    final LoadingCache<String, CalciteSchema> cache;

    private SubSchemaCache(final CalciteSchema calciteSchema,
                           NavigableSet<String> names) {
      this.names = names;
      this.cache = CacheBuilder.newBuilder().build(
          new CacheLoader<String, CalciteSchema>() {
            @SuppressWarnings("NullableProblems")
            @Override public CalciteSchema load(String schemaName) {
              final Schema subSchema =
                  calciteSchema.schema.getSubSchema(schemaName);
              if (subSchema == null) {
                throw new RuntimeException("sub-schema " + schemaName
                    + " not found");
              }
              return new CachingCalciteSchema(calciteSchema,
                  subSchema, schemaName);
            }
          });
    }
  }
}
