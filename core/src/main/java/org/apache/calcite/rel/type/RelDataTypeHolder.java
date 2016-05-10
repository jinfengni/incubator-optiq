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

package org.apache.calcite.rel.type;

import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Holding the expandable list of fields for dynamic table.
 */
public class RelDataTypeHolder {
  List<RelDataTypeField> fields = new ArrayList<>();

  private RelDataTypeFactory typeFactory;

  public List<RelDataTypeField> getFieldList(RelDataTypeFactory typeFactory) {
    return fields;
  }

  public int getFieldCount() {
    return fields.size();
  }

  /**
   * @param typeFactory
   * @param fieldName
   * @return
   */
  public RelDataTypeField getFieldOrInsert(RelDataTypeFactory typeFactory, String fieldName,
      boolean caseSensitive) {

    /* First check if this field name exists in our field list */
    for (RelDataTypeField f : fields) {
      if (Util.matches(caseSensitive, f.getName(), fieldName)) {
        return f;
      }
    }

    final SqlTypeName typeName = fieldName.startsWith(DynamicRecordType.DYNAMIC_STAR_PREFIX)
        ? SqlTypeName.DYNAMIC_STAR : SqlTypeName.ANY;

    /* This field does not exist in our field list add it */
    RelDataTypeField newField = new RelDataTypeFieldImpl(
        fieldName,
        fields.size(),
        typeFactory.createTypeWithNullability(typeFactory.createSqlType(typeName), true));

    /* Add the name to our list of field names */
    fields.add(newField);

    return newField;
  }

  public List<String> getFieldNames() {
    List<String> fieldNames = new ArrayList<>();
    for (RelDataTypeField f : fields) {
      fieldNames.add(f.getName());
    }

    return fieldNames;
  }

  public void setRelDataTypeFactory(RelDataTypeFactory typeFactory) {
    this.typeFactory = typeFactory;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public boolean equals(Object obj) {
    return this == obj;
  }

}
