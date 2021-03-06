/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.json

import java.io.InputStream
import java.net.URI

import com.fasterxml.jackson.core.{JsonFactory, JsonParser}
import com.google.common.io.ByteStreams
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat

import org.apache.spark.TaskContext
import org.apache.spark.input.{PortableDataStream, StreamInputFormat}
import org.apache.spark.rdd.{BinaryFileRDD, RDD}
import org.apache.spark.sql.{AnalysisException, Dataset, Encoders, SparkSession}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.json.{CreateJacksonParser, JacksonParser, JSONOptions}
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.datasources.text.TextFileFormat
import org.apache.spark.sql.types.StructType
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.Utils

/**
 * Common functions for parsing JSON files
 */
abstract class JsonDataSource extends Serializable {
  def isSplitable: Boolean

  /**
   * Parse a [[PartitionedFile]] into 0 or more [[InternalRow]] instances
   */
  def readFile(
    conf: Configuration,
    file: PartitionedFile,
    parser: JacksonParser,
    schema: StructType): Iterator[InternalRow]

  final def inferSchema(
      sparkSession: SparkSession,
      inputPaths: Seq[FileStatus],
      parsedOptions: JSONOptions): Option[StructType] = {
    // 获取JsonSchema
    if (inputPaths.nonEmpty) {
      // 调用具体实现类的infer方法
      Some(infer(sparkSession, inputPaths, parsedOptions))
    } else {
      None
    }
  }

  protected def infer(
      sparkSession: SparkSession,
      inputPaths: Seq[FileStatus],
      parsedOptions: JSONOptions): StructType
}

object JsonDataSource {
  def apply(options: JSONOptions): JsonDataSource = {
    if (options.multiLine) {
      MultiLineJsonDataSource
    } else {
      TextInputJsonDataSource
    }
  }
}

object TextInputJsonDataSource extends JsonDataSource {
  override val isSplitable: Boolean = {
    // splittable if the underlying source is
    true
  }

  override def infer(
      sparkSession: SparkSession,
      inputPaths: Seq[FileStatus],
      parsedOptions: JSONOptions): StructType = {
    // 根据路径创建BaseDataSet
    val json: Dataset[String] = createBaseDataset(sparkSession, inputPaths)
    // 从BaseDataSet中获取Json的Schema
    inferFromDataset(json, parsedOptions)
  }

  def inferFromDataset(json: Dataset[String], parsedOptions: JSONOptions): StructType = {
    // 从BaseDataSet中取样，运行时看到的是取全部
    val sampled: Dataset[String] = JsonUtils.sample(json, parsedOptions)
    // 使用queryExecution转化为rdd, 这里把先把plan优化了
    val rdd: RDD[UTF8String] = sampled.queryExecution.toRdd.map(_.getUTF8String(0))
    JsonInferSchema.infer(rdd, parsedOptions, CreateJacksonParser.utf8String)
  }

  private def createBaseDataset(
      sparkSession: SparkSession,
      inputPaths: Seq[FileStatus]): Dataset[String] = {
    val paths = inputPaths.map(_.getPath.toString)
    // 再一次创建DataSource，这一次将checkFileExist设为false
    // 因为在第一次创建DataSource的时候已经检查过路径
    // 在这里resolveRelation获取到了TextFileFormat的Schema并从
    // baseRelation中构造出DataFrame，将value全部提取出来
    // 构造DataFrame使用了DataSet的ofRow方法会对plan进行优化
    sparkSession.baseRelationToDataFrame(
      DataSource.apply(
        sparkSession,
        paths = paths,
        // 将SourceProvider的类设为TextFileFormat
        className = classOf[TextFileFormat].getName
      ).resolveRelation(checkFilesExist = false))
      // 调用select将DataSet的泛型类型变成Encoders.STRING
      .select("value").as(Encoders.STRING)
  }

  override def readFile(
      conf: Configuration,
      file: PartitionedFile,
      parser: JacksonParser,
      schema: StructType): Iterator[InternalRow] = {
    val linesReader = new HadoopFileLinesReader(file, conf)
    Option(TaskContext.get()).foreach(_.addTaskCompletionListener(_ => linesReader.close()))
    val safeParser = new FailureSafeParser[Text](
      input => parser.parse(input, CreateJacksonParser.text, textToUTF8String),
      parser.options.parseMode,
      schema,
      parser.options.columnNameOfCorruptRecord)
    linesReader.flatMap(safeParser.parse)
  }

  private def textToUTF8String(value: Text): UTF8String = {
    UTF8String.fromBytes(value.getBytes, 0, value.getLength)
  }
}

object MultiLineJsonDataSource extends JsonDataSource {
  override val isSplitable: Boolean = {
    false
  }

  override def infer(
      sparkSession: SparkSession,
      inputPaths: Seq[FileStatus],
      parsedOptions: JSONOptions): StructType = {
    val json: RDD[PortableDataStream] = createBaseRdd(sparkSession, inputPaths)
    val sampled: RDD[PortableDataStream] = JsonUtils.sample(json, parsedOptions)
    JsonInferSchema.infer(sampled, parsedOptions, createParser)
  }

  private def createBaseRdd(
      sparkSession: SparkSession,
      inputPaths: Seq[FileStatus]): RDD[PortableDataStream] = {
    val paths = inputPaths.map(_.getPath)
    val job = Job.getInstance(sparkSession.sessionState.newHadoopConf())
    val conf = job.getConfiguration
    val name = paths.mkString(",")
    FileInputFormat.setInputPaths(job, paths: _*)
    new BinaryFileRDD(
      sparkSession.sparkContext,
      classOf[StreamInputFormat],
      classOf[String],
      classOf[PortableDataStream],
      conf,
      sparkSession.sparkContext.defaultMinPartitions)
      .setName(s"JsonFile: $name")
      .values
  }

  private def createParser(jsonFactory: JsonFactory, record: PortableDataStream): JsonParser = {
    val path = new Path(record.getPath())
    CreateJacksonParser.inputStream(
      jsonFactory,
      CodecStreams.createInputStreamWithCloseResource(record.getConfiguration, path))
  }

  override def readFile(
      conf: Configuration,
      file: PartitionedFile,
      parser: JacksonParser,
      schema: StructType): Iterator[InternalRow] = {
    def partitionedFileString(ignored: Any): UTF8String = {
      Utils.tryWithResource {
        CodecStreams.createInputStreamWithCloseResource(conf, new Path(new URI(file.filePath)))
      } { inputStream =>
        UTF8String.fromBytes(ByteStreams.toByteArray(inputStream))
      }
    }

    val safeParser = new FailureSafeParser[InputStream](
      input => parser.parse(input, CreateJacksonParser.inputStream, partitionedFileString),
      parser.options.parseMode,
      schema,
      parser.options.columnNameOfCorruptRecord)

    safeParser.parse(
      CodecStreams.createInputStreamWithCloseResource(conf, new Path(new URI(file.filePath))))
  }
}
