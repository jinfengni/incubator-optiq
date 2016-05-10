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

import org.apache.calcite.sql.type.SqlTypeExplicitPrecedenceList;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.Collections;
import java.util.List;

/**
 * Implementation of RelDataType for dynamic table. It's used in
 * Sql validation phase, where field list is mutable for getField() call.
 *
 * After Sql validation, a normal RelDataTypeImpl with immutable field list
 * would take place of DynamicRecordTypeImpl instance for dynamic table.
 */
public class DynamicRecordTypeImpl extends DynamicRecordType {

  private final RelDataTypeFactory typeFactory;
  private final RelDataTypeHolder holder;

  public DynamicRecordTypeImpl(RelDataTypeFactory typeFactory) {
    this.typeFactory = typeFactory;
    this.holder = new RelDataTypeHolder();
    this.holder.setRelDataTypeFactory(typeFactory);
    computeDigest();
  }

  @Override
  public List<RelDataTypeField> getFieldList() {
    return holder.getFieldList(typeFactory);
  }

  @Override
  public int getFieldCount() {
    return holder.getFieldCount();
  }

  @Override
  public RelDataTypeField getField(String fieldName, boolean caseSensitive, boolean elideRecord) {
    return holder.getFieldOrInsert(typeFactory, fieldName, caseSensitive);
  }

  @Override
  public List<String> getFieldNames() {
    return holder.getFieldNames();
  }

  @Override
  public SqlTypeName getSqlTypeName() {
    return SqlTypeName.ROW;
  }

  @Override
  public RelDataTypePrecedenceList getPrecedenceList() {
    return new SqlTypeExplicitPrecedenceList(Collections.<SqlTypeName>emptyList());
  }

  @Override
  protected void generateTypeString(StringBuilder sb, boolean withDetail) {
    sb.append("(DynamicRecordRow" + getFieldNames() + ")");
  }

  @Override
  public boolean isStruct() {
    return true;
  }

  @Override
  public int hashCode() {
    return holder == null ? 0 : holder.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!super.equals(obj)) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    DynamicRecordTypeImpl other = (DynamicRecordTypeImpl) obj;
    if (holder == null) {
      if (other.holder != null) {
        return false;
      }
    } else if (!holder.equals(other.holder)) {
      return false;
    }
    return true;
  }

  @Override
  public RelDataTypeFamily getFamily() {
    return getSqlTypeName().getFamily();
  }

}
