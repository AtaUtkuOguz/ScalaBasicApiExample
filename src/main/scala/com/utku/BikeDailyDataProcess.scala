package com.utku

import org.apache.log4j.{Level, LogManager, Logger}
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.{DataFrame, Dataset, Row, SparkSession}
import requests.Response
import ujson.Value

object BikeDailyDataProcess {

  val rootLogger: Logger = LogManager.getRootLogger
  rootLogger.setLevel(Level.ERROR)

  def main(args: Array[String]) = {

    val spark: SparkSession = SparkSession
      .builder()
      .master("local")
      //.enableHiveSupport()
      .getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")

    println("Hello World")

    //val dwhDate: String = args(0) //Daily operation Date
    val r: Response = requests.get("https://bikewise.org/api/v2/incidents")
    val text = r.text

    writeAsJson(text)
    val newDataDf: DataFrame = parseIncidentJson(text,spark)
    //insertNewRawData(newDataDf, dwhDate, spark)
    //addNewDataToIncidentHistoryTable(dwhDate,spark)


  }

  def writeAsJson( text: String): Unit ={
    println( text)

    import java.io.PrintWriter
    new PrintWriter("filename") { write(text); close }
    println("Written Somewhere!")
  }

  def parseIncidentJson(text: String,spark: SparkSession): DataFrame ={
    import spark.sqlContext.implicits._
    import org.apache.spark.sql.functions._

    val rawDf: DataFrame = spark.read.json(Seq(text).toDS)
    rawDf.show()
    rawDf.printSchema()

    val mainDf = rawDf.select(explode(col("incidents")).alias("incidents"))
    mainDf.show()
    mainDf.printSchema()


    val mainDf2: DataFrame = mainDf.select("incidents.*")
    mainDf2.show()
    mainDf2.printSchema()

    val mainDf3 = mainDf2.select("id","occurred_at","updated_at","address","description","location_description","location_type","occurred_at","title","type","type_properties","updated_at","url","media.image_url","media.image_url_thumb","source.api_url","source.html_url","source.name" )
      .withColumnRenamed("image_url","media_image_url")
      .withColumnRenamed("image_url_thumb","media_image_url_thumb")
      .withColumnRenamed("api_url","source_api_url")
      .withColumnRenamed("html_url","source_html_url")
      .withColumnRenamed("name", "source_name")
    mainDf3.show()
    mainDf3.printSchema()
    println(mainDf3.count())

    mainDf3

  }

    def createTable(spark: SparkSession): Unit ={
    spark.sql( """CREATE TABLE rawIncidents (address string,
        description string,
        id int,
        location_description string,
        location_type string,
        media_image_url string,
        media_image_url_thumb string,
        occurred_at int,
        source_api_url string,
        source_html_url string,
        source_name string,
        title string,
      type string ,
      type_properties string,
      updated_at int,
      url string )
      PARTITIONED BY (P INT)
      stored as orc
      LOCATION   'hdfs://dlk-ns/data/DEV/rawIncidents'
      ;"""
    )
  }
  def insertNewRawData(newDf: DataFrame, dwhDate: String, spark: SparkSession): Unit ={
    //By Using dynamic partitioning in hadoop
    newDf.createGlobalTempView("newTv")
    spark.sql(s"insert overwrite  table rawIncidents partition(P=$dwhDate) select * from newTv")

  }

  def addNewDataToIncidentHistoryTable( dwhDate: String, spark: SparkSession): Unit ={

    val incidentsHistoryDf = spark.sql("select * from incidentsHistory")
    incidentsHistoryDf.createOrReplaceTempView("incidentHistTv")
    val rawDf: DataFrame = spark.sql(s"select * from rawIncidents where p=$dwhDate").drop("p")
    rawDf.createGlobalTempView("rawTv")

    val dataProcessDf = spark.sql("select * from  rawTv union all select * from incidentHistTv ")
    val dataProcessDf2 = dataProcessDf.distinct()
    val dataProcessDf3 = dataProcessDf2.orderBy(col("id"))

    spark.sql(s"DROP TABLE IF EXISTS incidentsHistory  ")
    dataProcessDf3.write.saveAsTable("incidentsHistory  ")

  }



}
