package com.utku

import org.apache.log4j.{Level, LogManager, Logger}
import org.apache.spark.sql.{DataFrame, SparkSession}

import java.io.File

object SparkSql {

  val rootLogger: Logger = LogManager.getRootLogger
  rootLogger.setLevel(Level.ERROR)



  def readCsv(path:String,oneTable: String, spark: SparkSession ): DataFrame ={

    val pathWithSubFolder = path + oneTable
    println(s"Reading For $oneTable" )
    val df: DataFrame = spark.read.option("header",true).csv(pathWithSubFolder)
    df.printSchema()
    df.show()
    df
  }

  def getListOfSubDirectories(dir: File): Seq[String] = {
    val result: Seq[String] = dir.listFiles
      .filter(_.isDirectory)
      .map(_.getName)
      .toList

    println(result)
    return result
  }

  def main(args: Array[String]) = {
    System.setProperty("hadoop.home.dir", "C:\\bin")
    val spark: SparkSession = SparkSession
      .builder()
      .master("local")
      //.enableHiveSupport()
      .getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")


    val path = ".\\src\\resources\\tables\\"


    val allTables: Seq[String] = getListOfSubDirectories(new File(path))

    val allDf: Seq[DataFrame] = allTables.map(oneTable =>  readCsv(path, oneTable,spark) )
    //Map(oneTable ->
    val myTables: Map[String, DataFrame] = (allTables zip allDf).toMap
    myTables foreach {case (key, value) => value.createOrReplaceTempView(key+"Tv")}
    val pixDf = spark.sql(
      """select trunc(time.action_timestamp,'Month') as snpst_dt,
        |pix.id, pix.account_id,
        |case when pix.in_or_out = "pix_out" then pix_amount else pix_amount *-1 end signed_amount,
        |time.action_timestamp as completedTS
        |from pix_movementsTv pix
        |left join d_timeTv  time on
        | pix.pix_completed_at = time.time_id
        | where pix.status = 'completed' """.stripMargin)

    pixDf.createOrReplaceTempView("pixTv")
    pixDf.show()

    val df =  spark.sql(
      """select snpst_dt,account_id, sum(signed_amount) as monthly_balance
        |from pixTv group by snpst_dt,account_id""".stripMargin).cache()



    df.createOrReplaceTempView("Tv")
    val df2 = spark.sql(
      """select snpst_dt,
        | account_id,
        | monthly_balance,
        | sum(monthly_balance) over (partition by account_id
        | order by snpst_dt, account_id rows unbounded preceding) as runningsum
        | from Tv """.stripMargin)

    df2.repartition(1).write.mode("overwrite").csv(".\\src\\resources\\output\\out2.csv")





    println("Entering to Debug Mode2")

  }
}
