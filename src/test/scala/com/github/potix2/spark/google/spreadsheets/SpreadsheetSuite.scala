/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.potix2.spark.google.spreadsheets

import java.io.{File, FileInputStream}
import java.security.PrivateKey

import com.github.potix2.spark.google.spreadsheets.SparkSpreadsheetService.SparkSpreadsheetContext
import com.github.potix2.spark.google.spreadsheets.util.Credentials
import org.apache.spark.SparkContext
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Row, SQLContext}
import org.scalatest.{BeforeAndAfter, FlatSpec}

import scala.util.Random

class SpreadsheetSuite extends FlatSpec with BeforeAndAfter{
  private val serviceAccountId = "test-359@test-creds-167111.iam.gserviceaccount.com"
  private val TEST_SPREADSHEET_NAME = "SpreadsheetSuite"
  private val TEST_SPREADSHEET_ID = "1q9eV4faHjcYB-xn1OAz31ByK7Ntr2ShWHQ3LgbTjNTE"
  val testCredentialPath = "src/test/resources/test-creds-7db3916c0235.p12"

  private val key: PrivateKey =  Credentials.getPrivateKeyFromInputStream(
    new FileInputStream(new File(testCredentialPath)))

  private val context: SparkSpreadsheetService.SparkSpreadsheetContext =
    SparkSpreadsheetService.SparkSpreadsheetContext(serviceAccountId, key)
  private val spreadsheet: SparkSpreadsheetService.SparkSpreadsheet =
    context.findSpreadsheet(TEST_SPREADSHEET_ID)

  private var sqlContext: SQLContext = _
  before {
    sqlContext = new SQLContext(new SparkContext("local[2]", "SpreadsheetSuite"))
  }

  after {
    sqlContext.sparkContext.stop()
  }

  private[spreadsheets] def deleteWorksheet(spreadSheetName: String, worksheetName: String)
                                           (implicit spreadSheetContext: SparkSpreadsheetContext): Unit = {
    SparkSpreadsheetService
      .findSpreadsheet(spreadSheetName)
      .foreach(_.deleteWorksheet(worksheetName))
  }

  def withNewEmptyWorksheet(testCode:(String) => Any): Unit = {
    implicit val spreadSheetContext = SparkSpreadsheetService(serviceAccountId,key)
    val spreadsheet = SparkSpreadsheetService.findSpreadsheet(TEST_SPREADSHEET_ID)
    spreadsheet.foreach { s =>
      val workSheetName = Random.alphanumeric.take(16).mkString
      s.addWorksheet(workSheetName, 1000, 1000)
      try {
        testCode(workSheetName)
      }
      finally {
        s.deleteWorksheet(workSheetName)
      }
    }
  }

  def withEmptyWorksheet(testCode:(String) => Any): Unit = {
    implicit val spreadSheetContext = SparkSpreadsheetService(serviceAccountId, key)
    val workSheetName = Random.alphanumeric.take(16).mkString
    try {
      testCode(workSheetName)
    }
    finally {
      deleteWorksheet(TEST_SPREADSHEET_ID, workSheetName)
    }
  }

  behavior of "A sheet"

  it should "behave as a DataFrame" in {
    val results = sqlContext.read
      .option("serviceAccountId", serviceAccountId)
      .option("privateKeyFile", testCredentialPath)
      .spreadsheet(s"$TEST_SPREADSHEET_ID/case1")
      .select("col1")
      .collect()

    assert(results.size === 15)
  }

  "sample" should "have a `long` value" in {
    val schema = StructType(Seq(
      StructField("col1", DataTypes.LongType),
      StructField("col2", DataTypes.StringType),
      StructField("col3", DataTypes.StringType)
    ))

    val results = sqlContext.read
      .option("serviceAccountId", serviceAccountId)
      .option("privateKeyFile", testCredentialPath)
      .schema(schema)
      .spreadsheet(s"$TEST_SPREADSHEET_ID/case1")
      .select("col1", "col2", "col3")
      .collect()

    assert(results.head.getLong(0) === 1L)
    assert(results.head.getString(1) === "name1")
    assert(results.head.getString(2) === "age1")
  }

  trait PersonDataFrame {
    val personsSchema = StructType(List(
      StructField("id", IntegerType, true),
      StructField("firstname", StringType, true),
      StructField("lastname", StringType, true)))
    val personsRows = Seq(Row(1, "Kathleen", "Cole"), Row(2, "Julia", "Richards"), Row(3, "Terry", "Black"))
    val personsRDD = sqlContext.sparkContext.parallelize(personsRows)
    val personsDF = sqlContext.createDataFrame(personsRDD, personsSchema)
  }

  "A DataFrame" should "be saved as a sheet" in new PersonDataFrame {

    import com.github.potix2.spark.google.spreadsheets._

    withEmptyWorksheet { workSheetName =>
      personsDF.write
        .option("serviceAccountId", serviceAccountId)
        .option("privateKeyFile", testCredentialPath)
        .spreadsheet(s"$TEST_SPREADSHEET_ID/$workSheetName")

      val result = sqlContext.read
        .option("serviceAccountId", serviceAccountId)
        .option("privateKeyFile", testCredentialPath)
        .spreadsheet(s"$TEST_SPREADSHEET_ID/$workSheetName")
        .collect()

      assert(result.size == 3)
      assert(result(0).getString(0) == "1")
      assert(result(0).getString(1) == "Kathleen")
      assert(result(0).getString(2) == "Cole")
    }
  }

  "A table" should "be created from DDL with schema" in {
    withNewEmptyWorksheet { worksheetName =>
      sqlContext.sql(
        s"""
           |CREATE TEMPORARY TABLE people
           |(id int, firstname string, lastname string)
           |USING com.github.potix2.spark.google.spreadsheets
           |OPTIONS (path "$TEST_SPREADSHEET_ID/$worksheetName", serviceAccountId "$serviceAccountId", privateKeyFile "$testCredentialPath")
       """.stripMargin.replaceAll("\n", " "))

      assert(sqlContext.sql("SELECT * FROM people").collect().size == 0)
    }
  }

  it should "be created from DDL with inferred schema" in {
    sqlContext.sql(
      s"""
         |CREATE TEMPORARY TABLE SpreadsheetSuite
         |USING com.github.potix2.spark.google.spreadsheets
         |OPTIONS (path "$TEST_SPREADSHEET_ID/case2", serviceAccountId "$serviceAccountId", privateKeyFile "$testCredentialPath")
       """.stripMargin.replaceAll("\n", " "))

    assert(sqlContext.sql("SELECT id, firstname, lastname FROM SpreadsheetSuite").collect().size == 1)
  }

  it should "be inserted from sql" in {
    withNewEmptyWorksheet { worksheetName =>
      sqlContext.sql(
        s"""
           |CREATE TEMPORARY TABLE accesslog
           |(id string, firstname string, lastname string, email string, country string, ipaddress string)
           |USING com.github.potix2.spark.google.spreadsheets
           |OPTIONS (path "$TEST_SPREADSHEET_ID/$worksheetName", serviceAccountId "$serviceAccountId", privateKeyFile "$testCredentialPath")
       """.stripMargin.replaceAll("\n", " "))

      sqlContext.sql(
        s"""
           |CREATE TEMPORARY TABLE SpreadsheetSuite
           |USING com.github.potix2.spark.google.spreadsheets
           |OPTIONS (path "$TEST_SPREADSHEET_ID/case2", serviceAccountId "$serviceAccountId", privateKeyFile "$testCredentialPath")
       """.stripMargin.replaceAll("\n", " "))

      sqlContext.sql("INSERT OVERWRITE TABLE accesslog SELECT * FROM SpreadsheetSuite")
      assert(sqlContext.sql("SELECT id, firstname, lastname FROM accesslog").collect().size == 1)
    }
  }

  trait UnderscoreDataFrame {
    val aSchema = StructType(List(
      StructField("foo_bar", IntegerType, true)))
    val aRows = Seq(Row(1), Row(2), Row(3))
    val aRDD = sqlContext.sparkContext.parallelize(aRows)
    val aDF = sqlContext.createDataFrame(aRDD, aSchema)
  }

  "The underscore" should "be used in a column name" in new UnderscoreDataFrame {
    import com.github.potix2.spark.google.spreadsheets._
    withEmptyWorksheet { workSheetName =>
      aDF.write
        .option("serviceAccountId", serviceAccountId)
        .option("privateKeyFile", testCredentialPath)
        .spreadsheet(s"$TEST_SPREADSHEET_ID/$workSheetName")

      val result = sqlContext.read
        .option("serviceAccountId", serviceAccountId)
        .option("privateKeyFile", testCredentialPath)
        .spreadsheet(s"$TEST_SPREADSHEET_ID/$workSheetName")
        .collect()

      assert(result.size == 3)
      assert(result(0).getString(0) == "1")
    }
  }

  "Util" should "convert all datatypes to corresponding strings" in {
    val personsSchema2 = StructType(List(
      StructField("id", IntegerType, true),
      StructField("firstname", ArrayType(MapType(StringType, StringType, true), true), true),
      StructField("something", MapType(StringType, ArrayType(StringType, true), true), true),
      StructField("lastname", StringType, true)))
    val personsRows2 = Seq(Row(1, Array(Map("1"->"1"), Map("2"->"2")), Map("s" -> Array("s", "S")), "Cole"))
    val personsRDD2 = sqlContext.sparkContext.parallelize(personsRows2)
    sqlContext.createDataFrame(personsRDD2, personsSchema2).toJSON
    val personsDF2 = sqlContext.createDataFrame(personsRDD2, personsSchema2).na.fill("nullValue").collect().toList
    assert(Util.typeConverter(IntegerType, personsRows2.head.get(0)).getNumberValue == 1.0)
  }
}
