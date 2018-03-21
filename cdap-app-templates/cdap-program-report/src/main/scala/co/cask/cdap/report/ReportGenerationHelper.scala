/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package co.cask.cdap.report

import java.io.{IOException, PrintWriter}

import co.cask.cdap.report.ReportGenerationSpark.ReportSparkHandler
import co.cask.cdap.report.proto.ReportGenerationRequest
import co.cask.cdap.report.proto.ReportGenerationRequest.Filter
import co.cask.cdap.report.util.Constants
import com.google.gson._
import org.apache.spark.sql.SparkSession
import org.apache.twill.filesystem.Location
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

/**
  * A helper class for report generation.
  */
object ReportGenerationHelper {

  val GSON = new Gson()
  val LOG = LoggerFactory.getLogger(ReportGenerationHelper.getClass)
  val RECORD_COL = "record"
  val REQUIRED_FIELDS = Seq(Constants.NAMESPACE, Constants.PROGRAM, Constants.RUN)

  /**
    * Generates a report file according to the given request from the given program run meta files.
    * The given program run meta files are first read into a single [[org.apache.spark.sql.DataFrame]].
    * The [[org.apache.spark.sql.DataFrame]] is then grouped by program run ID and aggregated to form
    * a new aggregated [[org.apache.spark.sql.DataFrame]] with a column "run" containing program run ID and a column
    * "record" containing [[Record]] objects as shown below:
    * +---------+----------+
    * |   run   |  record  |
    * +---------+----------+
    * The request is then used to obtain names of the fields in [[Record]] to be included
    * in the final report and the fields that are used for filtering or sorting. New columns containing
    * those fields will be added to the aggregated [[org.apache.spark.sql.DataFrame]] as shown below:
    * +---------+----------+---------------+-----------+-----------------------------------------------------
    * |   run   |  record  |   namespace   |  program  |  [required columns, filter columns, sort columns] ...
    * +---------+----------+---------------+-----------+-----------------------------------------------------
    * After filtering and sorting are done on the [[org.apache.spark.sql.DataFrame]],
    * only the columns required in the report will be kept and the [[org.apache.spark.sql.DataFrame]] as shown below:
    * +---------+---------------+-----------+------------------------
    * |   run   |   namespace   |  program  |  [required columns] ...
    * +---------+---------------+-----------+------------------------
    * will be written to a JSON file at the given output location, with an empty _SUCCESS file indicating success.
    *
    * @param spark the spark session to run report generation with
    * @param request the request containing the requirement for the report
    * @param inputURIs URIs of the avro files containing program run meta records
    * @param outputLocation location of the output directory where the report file and _SUCCESS file will be written
    * @throws java.io.IOException when fails to write to the _SUCCESS file
    */
  @throws(classOf[IOException])
  def generateReport(spark: SparkSession, request: ReportGenerationRequest, inputURIs: java.util.List[String],
                     outputLocation: Location): Unit = {
    import ReportGenerationHelper._
    import spark.implicits._
    val df = spark.read.format("com.databricks.spark.avro").load(inputURIs: _*)
    // Group the program run meta records by program run Id's and aggregate grouped records into a column
    // with data type Record. The aggregated DataFrame aggDf will have two columns: "run" and "record"
    val aggCol = new RecordAgg().toColumn.alias(RECORD_COL).as[Record]
    // TODO: configure partitions. The default number of partitions is 200
    var aggDf = df.groupBy(Constants.RUN).agg(aggCol)
    // Construct a set of fields to be included in the final report with required fields and fields from the request
    val reportFields = collection.mutable.LinkedHashSet(REQUIRED_FIELDS: _*) ++ Option(request.getFields).getOrElse(Nil)
    LOG.debug("reportFields={}", reportFields)
    // Construct a set of additional fields to be included as columns in the aggregated DataFrame
    // with fields used for filtering and sorting
    val additionalFields = collection.mutable.LinkedHashSet(Constants.START, Constants.END)
    val filters = Option(request.getFilters).getOrElse(Nil[Filter[_]])
    if (Option(request.getFilters).isDefined) {
      asScalaBuffer(request.getFilters).foreach(f => additionalFields.add(f.getFieldName))
    }
    if (Option(request.getSort).isDefined) {
      request.getSort.foreach(s => additionalFields.add(s.getFieldName))
    }
    LOG.debug("additionalFields={}", additionalFields)
    // With every unique field in reportFields and additionalFields, construct and add new columns from record column
    // in aggregated DataFrame
    (reportFields ++ additionalFields).foreach(fieldName => {
      aggDf = aggDf.withColumn(fieldName, aggDf(RECORD_COL).getField(fieldName))
    })
    // Construct the filter column starting with condition:
    // aggDf("start") not null AND aggDf("start") < request.getEnd
    //   AND (aggDf("end") is null OR aggDf("end") > request.getStart)
    var filterCol = aggDf(Constants.START).isNotNull && aggDf(Constants.START) < request.getEnd &&
      (aggDf(Constants.END).isNull || aggDf(Constants.END) > request.getStart)
    LOG.info("initial filterCol={}", filterCol)
    // Combine additional filters from the request to the filter column
    if (Option(request.getFilters).isDefined) {
      request.getFilters.foreach(f => {
        val fieldCol = aggDf(f.getFieldName)
        // the filed to be filtered must contain non-null value
        filterCol &&= fieldCol.isNotNull
        // the filter is either a RangeFilter or ValueFilter. Construct the filter according to the filter type
        f match {
          case rangeFilter: ReportGenerationRequest.RangeFilter[_] => {
            LOG.debug("rangeFilter for field {}", f.getFieldName)
            val min = rangeFilter.getRange.getMin
            if (Option(min).isDefined) {
              filterCol &&= fieldCol >= min
            }
            val max = rangeFilter.getRange.getMax
            if (Option(max).isDefined) {
              filterCol &&= fieldCol < max
            }
          }
          case valueFilter: ReportGenerationRequest.ValueFilter[_] => {
            LOG.debug("valueFilter for field {}", f.getFieldName)
            val whitelist = valueFilter.getWhitelist
            if (Option(whitelist).isDefined) {
              filterCol &&= fieldCol.isin(whitelist: _*)
            }
            val blacklist = valueFilter.getBlacklist
            if (Option(blacklist).isDefined) {
              filterCol &&= !fieldCol.isin(blacklist: _*)
            }
          }
        }
      })
    }
    LOG.info("final filterCol={}", filterCol)
    var resultDf = aggDf.filter(filterCol)
    // If sort is specified in the request, apply sorting to the result DataFrame
    if (Option(request.getSort).isDefined) {
      request.getSort.foreach(sort => {
        val sortField = aggDf(sort.getFieldName)
        sort.getOrder match {
          case ReportGenerationRequest.Order.ASCENDING => {
            resultDf = resultDf.sort(sortField.asc)
          }
          case ReportGenerationRequest.Order.DESCENDING => {
            resultDf = resultDf.sort(sortField.desc)
          }
        }
      })
    }
    // drop the columns which should not be included in the report
    resultDf.columns.foreach(col => if (!reportFields.contains(col)) resultDf = resultDf.drop(col))
    resultDf.persist()
    resultDf.coalesce(1).write.option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ").json(outputLocation.toURI.toString)
    val count = resultDf.count
    // Write the total number of records in _SUCCESS file generated after successful report generation
    try {
      val writer = new PrintWriter(outputLocation.append(ReportSparkHandler.SUCCESS_FILE).getOutputStream)
      writer.write(Long.toString(count))
    } catch {
      case e: IOException => {
        LOG.error("Failed to write to {} in {}", ReportSparkHandler.SUCCESS_FILE, reportDir.toURI.toString, e)
        throw e
      }
    } finally if (writer != null) writer.close()
  }
}