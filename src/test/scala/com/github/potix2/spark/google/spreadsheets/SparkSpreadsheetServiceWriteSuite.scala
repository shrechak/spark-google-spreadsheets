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
import java.util.Base64

import com.github.potix2.spark.google.spreadsheets.SparkSpreadsheetService.SparkSpreadsheet
import com.github.potix2.spark.google.spreadsheets.util.Credentials
import com.google.api.services.sheets.v4.model.{CellData, ExtendedValue, RowData}
import org.apache.spark.sql.types.{DataTypes, StructField, StructType}
import org.scalatest.{BeforeAndAfter, FlatSpec}

import scala.collection.JavaConverters._

class SparkSpreadsheetServiceWriteSuite extends FlatSpec with BeforeAndAfter {
  private val serviceAccountId = "test-359@test-creds-167111.iam.gserviceaccount.com"
  private val testCredentialPath = "src/test/resources/test-creds-7db3916c0235.p12"
  private val TEST_SPREADSHEET_NAME = "SpreadsheetSuite"
  private val TEST_SPREADSHEET_ID = "1q9eV4faHjcYB-xn1OAz31ByK7Ntr2ShWHQ3LgbTjNTE"

  private val key: PrivateKey =  Credentials.getPrivateKeyFromInputStream(
    new FileInputStream(new File(testCredentialPath)))

  private val context: SparkSpreadsheetService.SparkSpreadsheetContext =
    SparkSpreadsheetService.SparkSpreadsheetContext(serviceAccountId, key)

  var spreadsheet: SparkSpreadsheet = _
  var worksheetName: String = ""

  def definedSchema: StructType = {
    new StructType()
      .add(new StructField("col_1", DataTypes.StringType))
      .add(new StructField("col_2", DataTypes.LongType))
      .add(new StructField("col_3", DataTypes.StringType))
  }

  case class Elem(col_1: String, col_2: Long, col_3: String)

  def extractor(e: Elem): RowData =
    new RowData().setValues(
      List(
        new CellData().setUserEnteredValue(
          new ExtendedValue().setStringValue(e.col_1)
        ),
        new CellData().setUserEnteredValue(
          new ExtendedValue().setNumberValue(e.col_2.toDouble)
        ),
        new CellData().setUserEnteredValue(
          new ExtendedValue().setStringValue(e.col_3)
        )
      ).asJava
    )

  before {
    spreadsheet = context.findSpreadsheet(TEST_SPREADSHEET_ID)
    worksheetName = scala.util.Random.alphanumeric.take(16).mkString
    val data = List(
      Elem("a", 1L, "x"),
      Elem("b", 2L, "y"),
      Elem("c", 3L, "z")
    )

    spreadsheet.addWorksheet(worksheetName, definedSchema, data, extractor)
  }

  after {
    spreadsheet.deleteWorksheet(worksheetName)
  }

  behavior of "A Spreadsheet"
  it should "find the new worksheet" in {
    val newWorksheet = spreadsheet.findWorksheet(worksheetName)
    assert(newWorksheet.isDefined)
    assert(newWorksheet.get.name == worksheetName)
    assert(newWorksheet.get.headers == Seq("col_1", "col_2", "col_3"))

    val rows = newWorksheet.get.rows
    assert(rows.head == Map("col_1" -> "a", "col_2" -> "1", "col_3" -> "x"))
  }

  behavior of "SparkWorksheet#updateCells"
  it should "update values in a worksheet" in {
    val newWorksheet = spreadsheet.findWorksheet(worksheetName)
    assert(newWorksheet.isDefined)

    val newData = List(
      Elem("f", 5L, "yy"),
      Elem("e", 4L, "xx"),
      Elem("c", 3L, "z"),
      Elem("b", 2L, "y"),
      Elem("a", 1L, "x")
    )

    newWorksheet.get.updateCells(definedSchema, newData, extractor)

    val rows = newWorksheet.get.rows
    assert(rows.head == Map("col_1" -> "f", "col_2" -> "5", "col_3" -> "yy"))
    assert(rows.last == Map("col_1" -> "a", "col_2" -> "1", "col_3" -> "x"))
  }
}
