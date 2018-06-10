package org.lzy.kaggle.JDataByLeaner

import ml.dmlc.xgboost4j.scala.spark.XGBoostModel
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.ml.tuning.TrainValidationSplitModel
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

/**
  * Created by Administrator on 2018/6/3
  * spark-submit --master yarn-client --queue lzy --driver-memory 2g --conf spark.driver.maxResultSize=2g  \
  * --num-executors 12 --executor-cores 4 --executor-memory 7g --jars \
  * /root/lzy/xgboost/jvm-packages/xgboost4j-spark/target/xgboost4j-spark-0.8-SNAPSHOT-jar-with-dependencies.jar \
  * --class org.lzy.kaggle.JDataByLeaner.TrainModels SparkML.jar
  */
object TrainModels {

  //  val basePath = "E:\\dataset\\JData_UserShop\\"
  val basePath = "hdfs://10.95.3.172:9000/user/lzy/JData_UserShop/"

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("names3")
      //            .master("local[*]")
      .getOrCreate()
    val sc = spark.sparkContext
    val config = sc.getConf
    //    config.set("spark.driver.maxResultSize","0")
    config.set("spark.debug.maxToStringFields", "100")
    config.set("spark.shuffle.io.maxRetries", "60")
    config.set("spark.default.parallelism", "54")
    config.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    spark.sparkContext.setLogLevel("WARN")
    val util = new Util(spark)
    val trainModel = new TrainModels(spark, basePath)

    /*
    获取训练数据
     */
    val data04_df = spark.read.parquet(basePath + "cache/trainMonth/04")
    val data03_df = spark.read.parquet(basePath + "cache/trainMonth/03")
    val data02_df = spark.read.parquet(basePath + "cache/trainMonth/02")
    val data01_df = spark.read.parquet(basePath + "cache/trainMonth/01")
    val data12_df = spark.read.parquet(basePath + "cache/trainMonth/12")
    val data11_df = spark.read.parquet(basePath + "cache/trainMonth/11")
    val data10_df = spark.read.parquet(basePath + "cache/trainMonth/10")
    val data09_df = spark.read.parquet(basePath + "cache/trainMonth/09")

    //训练模型
    val valiTrain_df = data09_df.union(data10_df).union(data11_df).union(data12_df).union(data01_df).union(data02_df).repartition(200)
    val valiTest_df = data03_df
    trainModel.trainAndSaveModel("vali", valiTrain_df)
    trainModel.varifyModel("vali", valiTest_df)


    //验证结果模型
    val testTrain_df = data10_df.union(data11_df).union(data12_df).union(data01_df).union(data02_df).union(data03_df).repartition(200)
    val testTest_df = data04_df
    trainModel.trainAndSaveModel("test", testTrain_df)
    trainModel.varifyModel("test", testTest_df)
    trainModel.getResult("test", testTest_df)


  }
}

class TrainModels(spark: SparkSession, basePath: String) {

  import spark.implicits._

  /*
  分数检验
   */
  def score(result_df: DataFrame) = {
    //    *   people.select(when(people("gender") === "male", 0)
    //      *     .when(people("gender") === "female", 1)
    //    *     .otherwise(2))
    val udf_getWeight = udf { index: Int => 1.0 / (1 + math.log(index)) }
    val udf_binary = udf { label_1: Int => if (label_1 > 0) 1.0 else 0.0 }


    val weight_df = result_df.sort($"o_num".desc).limit(50000)
      .withColumn("label_binary", when($"label_1" > 0, 1.0).otherwise(0.0))
      //      .withColumn("label_binary", udf_binary($"label_1"))
      .withColumn("index", monotonically_increasing_id + 1)
      .withColumn("weight", udf_getWeight($"index"))
    val s1 = weight_df.select($"label_binary".as[Double], $"weight".as[Double]).map(tuple => tuple._1 * tuple._2).collect().sum / 4674.32357
    //1 to 50000 map(i=>1.0/(1+math.log(i)))
    //计算s2
    val weightEqual1_df = result_df.filter($"label_1" > 0)
    val s2 = weight_df.filter($"label_1" > 0).select($"label_2".as[Double], $"pred_date".as[Double]).collect().map { case (label_2, pred_date) =>
      10.0 / (math.pow(label_2 - math.round(pred_date), 2) + 10)
    }.sum / weightEqual1_df.count().toDouble
    println(s"s1 score is $s1 ,s2 score is $s2 , S is ${0.4 * s1 + 0.6 * s2}")
  }


  /** *
    * 获取最终的计算结果。
    *
    * @return
    */
  def getResult(dataType: String = "test", test: DataFrame) = {
    //    val test = spark.read.parquet(basePath + s"cache/${dataType}_test_start12")
    val dropColumns: Array[String] = Array("user_id", "label_1", "label_2")
    val featureColumns: Array[String] = test.columns.filterNot(dropColumns.contains(_))
    val selecter = new VectorAssembler().setInputCols(featureColumns).setOutputCol("features")
    val test_df = selecter.transform(test)

    //    val s1_Model = XGBoostModel.read.load(basePath + s"model/s1_${dataType}_Model/bestModel")
    val s1_Model = TrainValidationSplitModel.read.load(basePath + s"model/s1_${dataType}_Model").bestModel
    //    val s2_Model = XGBoostModel.read.load(basePath + s"model/s2_${dataType}_Model/bestModel")
    val s2_Model = TrainValidationSplitModel.read.load(basePath + s"model/s2_${dataType}_Model").bestModel
    val labelCol = "label_1"
    val predictCol = "o_num"
    val labelCol2 = "label_2"
    val predictCol2 = "pred_date"

    val s1_df = s1_Model.transform(test_df.withColumnRenamed(labelCol, "label"))
      .withColumnRenamed("label", labelCol)
      .withColumnRenamed("prediction", predictCol)
      .select("user_id", labelCol, predictCol)
    val s2_df = s2_Model.transform(test_df.withColumnRenamed(labelCol2, "label"))
      .withColumnRenamed("label", labelCol2)
      .withColumnRenamed("prediction", predictCol2)
      .select("user_id", labelCol2, predictCol2)

    val result = s1_df.join(s2_df, "user_id")
    score(result)
    val udf_predDateToDate = udf { (pred_date: Double) => {
      val days = math.round(pred_date)
      s"2017-05-${days}"
    }
    }
    result.filter($"user_id" === 42463).show(false)
    val submission: DataFrame = result.filter($"pred_date" < 32).sort($"o_num".desc).limit(50000)
      .withColumn("result_date", udf_predDateToDate($"pred_date"))
      .select($"user_id", to_date($"result_date").as("pred_date"))
    submission.show(20, false)
    val errorData = submission.filter($"pred_date".isNull)
    errorData.show(false)
    println("数据不合法" + errorData.count())
    submission.coalesce(1).write
      .option("header", "true")
      .mode(SaveMode.Overwrite)
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
      //          .option("nullValue", "NA")
      .csv(basePath + "sub/result")
    //
    //    submission.write.mode(SaveMode.Overwrite).parquet((basePath+"sub/result_parquet"))
    //submission.rdd.coalesce(1).saveAsTextFile(basePath+"sub/result")
    submission
  }


  /**
    * 训练并保存数据
    * dataType为test或者vali
    */
  def trainAndSaveModel(dataType: String = "test", train: DataFrame) = {
    //    val train = spark.read.parquet(basePath + s"cache/${dataType}_train_start12")
    val dropColumns: Array[String] = Array("user_id", "label_1", "label_2")
    val featureColumns: Array[String] = train.columns.filterNot(dropColumns.contains(_))
    val selecter = new VectorAssembler().setInputCols(featureColumns).setOutputCol("features")
    val train_df = selecter.transform(train)
    //为resul通过label_1来计算 添加o_num列，
    val s1_Model: TrainValidationSplitModel = Model.fitPredict(train_df, "label_1", "o_num")
    s1_Model.write.overwrite().save(basePath + s"model/s1_${dataType}_Model")

    //为result通过label_2来计算添加pred_date
    val s2_Model = Model.fitPredict(train_df, "label_2", "pred_date")
    s2_Model.write.overwrite().save(basePath + s"model/s2_${dataType}_Model")
  }


  /**
    * 检验模型准确性
    */
  def varifyModel(dataType: String = "test", test: DataFrame) = {
    //    val test = spark.read.parquet(basePath + s"cache/${dataType}_test_start12")
    val dropColumns: Array[String] = Array("user_id", "label_1", "label_2")
    val featureColumns: Array[String] = test.columns.filterNot(dropColumns.contains(_))
    val selecter = new VectorAssembler().setInputCols(featureColumns).setOutputCol("features")
    val test_df = selecter.transform(test)

    //    val s1_Model = XGBoostModel.read.load(basePath + s"model/s1_${dataType}_Model/bestModel")
    val s1_Model = TrainValidationSplitModel.read.load(basePath + s"model/s1_${dataType}_Model").bestModel
    //    val s2_Model = XGBoostModel.read.load(basePath + s"model/s2_${dataType}_Model/bestModel")
    val s2_Model = TrainValidationSplitModel.read.load(basePath + s"model/s2_${dataType}_Model").bestModel
    val labelCol = "label_1"
    val predictCol = "o_num"
    val labelCol2 = "label_2"
    val predictCol2 = "pred_date"

    val s1_df = s1_Model.transform(test_df.withColumnRenamed(labelCol, "label"))
      .withColumnRenamed("label", labelCol)
      .withColumnRenamed("prediction", predictCol)
      .select("user_id", labelCol, predictCol)
    val s2_df = s2_Model.transform(test_df.withColumnRenamed(labelCol2, "label"))
      .withColumnRenamed("label", labelCol2)
      .withColumnRenamed("prediction", predictCol2)
      .select("user_id", labelCol2, predictCol2)

    val result = s1_df.join(s2_df, "user_id")
    score(result)
  }


  def showModelParams() = {
    val s1_Model = XGBoostModel.read.load(basePath + s"model/s1_train_Model/bestModel")
    //    val bestmodel = s1_Model.asInstanceOf[PipelineModel]
    //    val lrModel:Transformer=bestmodel.stages(2)
    //    println(lrModel.explainParam(XGBoostModel.regParam))
    //    println(lrModel.explainParam(XGBoost.elasticNetParam))
  }
}
