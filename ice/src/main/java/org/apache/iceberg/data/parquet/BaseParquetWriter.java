/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.data.parquet;

import java.util.List;
import java.util.Optional;
import org.apache.iceberg.parquet.ParquetTypeVisitor;
import org.apache.iceberg.parquet.ParquetValueWriter;
import org.apache.iceberg.parquet.ParquetValueWriters;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

/**
 * @deprecated since 1.8.0, will be made package-private in 1.9.0
 */
@Deprecated
public abstract class BaseParquetWriter<T> {

  @SuppressWarnings("unchecked")
  protected ParquetValueWriter<T> createWriter(MessageType type) {
    return (ParquetValueWriter<T>) ParquetTypeVisitor.visit(type, new WriteBuilder(type));
  }

  protected abstract ParquetValueWriters.StructWriter<T> createStructWriter(
      List<ParquetValueWriter<?>> writers);

  protected ParquetValueWriter<?> fixedWriter(ColumnDescriptor desc) {
    return new GenericParquetWriter.FixedWriter(desc);
  }

  protected ParquetValueWriter<?> dateWriter(ColumnDescriptor desc) {
    return new GenericParquetWriter.DateWriter(desc);
  }

  protected ParquetValueWriter<?> timeWriter(ColumnDescriptor desc) {
    return new GenericParquetWriter.TimeWriter(desc);
  }

  protected ParquetValueWriter<?> timestampWriter(ColumnDescriptor desc, boolean isAdjustedToUTC) {
    if (isAdjustedToUTC) {
      return new GenericParquetWriter.TimestamptzWriter(desc);
    } else {
      return new GenericParquetWriter.TimestampWriter(desc);
    }
  }

  private class WriteBuilder extends ParquetTypeVisitor<ParquetValueWriter<?>> {
    private final MessageType type;

    private WriteBuilder(MessageType type) {
      this.type = type;
    }

    @Override
    public ParquetValueWriter<?> message(
        MessageType message, List<ParquetValueWriter<?>> fieldWriters) {

      return struct(message.asGroupType(), fieldWriters);
    }

    @Override
    public ParquetValueWriter<?> struct(
        GroupType struct, List<ParquetValueWriter<?>> fieldWriters) {
      List<Type> fields = struct.getFields();
      List<ParquetValueWriter<?>> writers = Lists.newArrayListWithExpectedSize(fieldWriters.size());
      for (int i = 0; i < fields.size(); i += 1) {
        Type fieldType = struct.getType(i);
        int fieldD = type.getMaxDefinitionLevel(path(fieldType.getName()));
        writers.add(ParquetValueWriters.option(fieldType, fieldD, fieldWriters.get(i)));
      }

      return createStructWriter(writers);
    }

    @Override
    public ParquetValueWriter<?> list(GroupType array, ParquetValueWriter<?> elementWriter) {
      GroupType repeated = array.getFields().get(0).asGroupType();
      String[] repeatedPath = currentPath();

      int repeatedD = type.getMaxDefinitionLevel(repeatedPath);
      int repeatedR = type.getMaxRepetitionLevel(repeatedPath);

      Type elementType = repeated.getType(0);
      int elementD = type.getMaxDefinitionLevel(path(elementType.getName()));

      return ParquetValueWriters.collections(
          repeatedD, repeatedR, ParquetValueWriters.option(elementType, elementD, elementWriter));
    }

    @Override
    public ParquetValueWriter<?> map(
        GroupType map, ParquetValueWriter<?> keyWriter, ParquetValueWriter<?> valueWriter) {
      GroupType repeatedKeyValue = map.getFields().get(0).asGroupType();
      String[] repeatedPath = currentPath();

      int repeatedD = type.getMaxDefinitionLevel(repeatedPath);
      int repeatedR = type.getMaxRepetitionLevel(repeatedPath);

      Type keyType = repeatedKeyValue.getType(0);
      int keyD = type.getMaxDefinitionLevel(path(keyType.getName()));
      Type valueType = repeatedKeyValue.getType(1);
      int valueD = type.getMaxDefinitionLevel(path(valueType.getName()));

      return ParquetValueWriters.maps(
          repeatedD,
          repeatedR,
          ParquetValueWriters.option(keyType, keyD, keyWriter),
          ParquetValueWriters.option(valueType, valueD, valueWriter));
    }

    @Override
    public ParquetValueWriter<?> primitive(PrimitiveType primitive) {
      ColumnDescriptor desc = type.getColumnDescription(currentPath());
      LogicalTypeAnnotation logicalType = primitive.getLogicalTypeAnnotation();
      if (logicalType != null) {
        Optional<ParquetValueWriter<?>> writer =
            logicalType.accept(new LogicalTypeWriterVisitor(desc));
        if (writer.isPresent()) {
          return writer.get();
        }
      }

      switch (primitive.getPrimitiveTypeName()) {
        case FIXED_LEN_BYTE_ARRAY:
          return fixedWriter(desc);
        case BINARY:
          return ParquetValueWriters.byteBuffers(desc);
        case BOOLEAN:
          return ParquetValueWriters.booleans(desc);
        case INT32:
          return ParquetValueWriters.ints(desc);
        case INT64:
          return ParquetValueWriters.longs(desc);
        case FLOAT:
          return ParquetValueWriters.floats(desc);
        case DOUBLE:
          return ParquetValueWriters.doubles(desc);
        default:
          throw new UnsupportedOperationException("Unsupported type: " + primitive);
      }
    }
  }

  private class LogicalTypeWriterVisitor
      implements LogicalTypeAnnotation.LogicalTypeAnnotationVisitor<ParquetValueWriter<?>> {
    private final ColumnDescriptor desc;

    private LogicalTypeWriterVisitor(ColumnDescriptor desc) {
      this.desc = desc;
    }

    @Override
    public Optional<ParquetValueWriter<?>> visit(
        LogicalTypeAnnotation.StringLogicalTypeAnnotation stringType) {
      return Optional.of(ParquetValueWriters.strings(desc));
    }

    @Override
    public Optional<ParquetValueWriter<?>> visit(
        LogicalTypeAnnotation.EnumLogicalTypeAnnotation enumType) {
      return Optional.of(ParquetValueWriters.strings(desc));
    }

    @Override
    public Optional<ParquetValueWriter<?>> visit(
        LogicalTypeAnnotation.DecimalLogicalTypeAnnotation decimalType) {
      switch (desc.getPrimitiveType().getPrimitiveTypeName()) {
        case INT32:
          return Optional.of(
              ParquetValueWriters.decimalAsInteger(
                  desc, decimalType.getPrecision(), decimalType.getScale()));
        case INT64:
          return Optional.of(
              ParquetValueWriters.decimalAsLong(
                  desc, decimalType.getPrecision(), decimalType.getScale()));
        case BINARY:
        case FIXED_LEN_BYTE_ARRAY:
          return Optional.of(
              ParquetValueWriters.decimalAsFixed(
                  desc, decimalType.getPrecision(), decimalType.getScale()));
      }
      return Optional.empty();
    }

    @Override
    public Optional<ParquetValueWriter<?>> visit(
        LogicalTypeAnnotation.DateLogicalTypeAnnotation dateType) {
      return Optional.of(dateWriter(desc));
    }

    @Override
    public Optional<ParquetValueWriter<?>> visit(
        LogicalTypeAnnotation.TimeLogicalTypeAnnotation timeType) {
      Preconditions.checkArgument(
          LogicalTypeAnnotation.TimeUnit.MICROS.equals(timeType.getUnit()),
          "Cannot write time in %s, only MICROS is supported",
          timeType.getUnit());
      return Optional.of(timeWriter(desc));
    }

    @Override
    public Optional<ParquetValueWriter<?>> visit(
        LogicalTypeAnnotation.TimestampLogicalTypeAnnotation timestampType) {
      Preconditions.checkArgument(
          LogicalTypeAnnotation.TimeUnit.MICROS.equals(timestampType.getUnit()),
          "Cannot write timestamp in %s, only MICROS is supported",
          timestampType.getUnit());
      return Optional.of(timestampWriter(desc, timestampType.isAdjustedToUTC()));
    }

    @Override
    public Optional<ParquetValueWriter<?>> visit(
        LogicalTypeAnnotation.IntLogicalTypeAnnotation intType) {
      /*
              Preconditions.checkArgument(
                intType.isSigned() || intType.getBitWidth() < 64,
                "Cannot read uint64: not a supported Java type");
      */
      if (intType.getBitWidth() < 64) {
        return Optional.of(ParquetValueWriters.ints(desc));
      } else {
        return Optional.of(ParquetValueWriters.longs(desc));
      }
    }

    @Override
    public Optional<ParquetValueWriter<?>> visit(
        LogicalTypeAnnotation.JsonLogicalTypeAnnotation jsonLogicalType) {
      return Optional.of(ParquetValueWriters.strings(desc));
    }

    @Override
    public Optional<ParquetValueWriter<?>> visit(
        LogicalTypeAnnotation.BsonLogicalTypeAnnotation bsonType) {
      return Optional.of(ParquetValueWriters.byteBuffers(desc));
    }

    @Override
    public Optional<ParquetValueWriter<?>> visit(
        LogicalTypeAnnotation.UUIDLogicalTypeAnnotation uuidLogicalType) {
      return Optional.of(ParquetValueWriters.uuids(desc));
    }
  }
}
