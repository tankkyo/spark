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

package org.apache.spark.sql.streaming

import java.io.File
import java.util.UUID

import scala.util.Random
import scala.util.control.NonFatal

import org.apache.spark.sql.{ContinuousQuery, ContinuousQueryException, StreamTest}
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.util.Utils

/**
 * A stress test for streaming queries that read and write files.  This test consists of
 * two threads:
 *  - one that writes out `numRecords` distinct integers to files of random sizes (the total
 *    number of records is fixed but each files size / creation time is random).
 *  - another that continually restarts a buggy streaming query (i.e. fails with 5% probability on
 *    any partition).
 *
 * At the end, the resulting files are loaded and the answer is checked.
 */
class FileStressSuite extends StreamTest with SharedSQLContext {
  import testImplicits._

  testQuietly("fault tolerance stress test - unpartitioned output") {
    stressTest(partitionWrites = false)
  }

  testQuietly("fault tolerance stress test - partitioned output") {
    stressTest(partitionWrites = true)
  }

  def stressTest(partitionWrites: Boolean): Unit = {
    val numRecords = 10000
    val inputDir = Utils.createTempDir(namePrefix = "stream.input").getCanonicalPath
    val stagingDir = Utils.createTempDir(namePrefix = "stream.staging").getCanonicalPath
    val outputDir = Utils.createTempDir(namePrefix = "stream.output").getCanonicalPath
    val checkpoint = Utils.createTempDir(namePrefix = "stream.checkpoint").getCanonicalPath

    @volatile
    var continue = true
    @volatile
    var stream: ContinuousQuery = null

    val writer = new Thread("stream writer") {
      override def run(): Unit = {
        var i = numRecords
        while (i > 0) {
          val count = Random.nextInt(100)
          var j = 0
          var string = ""
          while (j < count && i > 0) {
            if (i % 10000 == 0) { logError(s"Wrote record $i") }
            string = string + i + "\n"
            j += 1
            i -= 1
          }

          val uuid = UUID.randomUUID().toString
          val fileName = new File(stagingDir, uuid)
          stringToFile(fileName, string)
          fileName.renameTo(new File(inputDir, uuid))
          val sleep = Random.nextInt(100)
          Thread.sleep(sleep)
        }

        logError("== DONE WRITING ==")
        var done = false
        while (!done) {
          try {
            stream.processAllAvailable()
            done = true
          } catch {
            case NonFatal(_) =>
          }
        }

        continue = false
        stream.stop()
      }
    }
    writer.start()

    val input = sqlContext.read.format("text").stream(inputDir)

    def startStream(): ContinuousQuery = {
      val output = input
        .repartition(5)
        .as[String]
        .mapPartitions { iter =>
          val rand = Random.nextInt(100)
          if (rand < 10) {
            sys.error("failure")
          }
          iter.map(_.toLong)
        }
        .map(x => (x % 400, x.toString))
        .toDF("id", "data")

      if (partitionWrites) {
        output
          .write
          .partitionBy("id")
          .format("parquet")
          .option("checkpointLocation", checkpoint)
          .startStream(outputDir)
      } else {
        output
          .write
          .format("parquet")
          .option("checkpointLocation", checkpoint)
          .startStream(outputDir)
      }
    }

    var failures = 0
    val streamThread = new Thread("stream runner") {
      while (continue) {
        if (failures % 10 == 0) { logError(s"Query restart #$failures") }
        stream = startStream()

        try {
          stream.awaitTermination()
        } catch {
          case ce: ContinuousQueryException =>
            failures += 1
        }
      }
    }

    streamThread.join()

    logError(s"Stream restarted $failures times.")
    assert(sqlContext.read.parquet(outputDir).distinct().count() == numRecords)
  }
}
