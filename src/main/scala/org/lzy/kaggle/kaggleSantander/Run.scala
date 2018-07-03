package org.lzy.kaggle.kaggleSantander

import common.{FeatureUtils, Utils}
import org.apache.spark.ml.Pipeline
import org.apache.spark.sql.SparkSession

/**
  * Created by Administrator on 2018/7/3.

* Created by Administrator on 2018/6/3
 spark-submit --master yarn-client --queue lzy --driver-memory 6g --conf spark.driver.maxResultSize=5g  \
 --num-executors 12 --executor-cores 4 --executor-memory 7g --jars \
 --class org.lzy.kaggle.kaggleSantander.Run SparkML.jar
  * */
object Run{
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("names")
//            .master("local[*]")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    val conf = spark.conf
    val sc = spark.sparkContext
    val config = sc.getConf
    //    config.set("spark.driver.maxResultSize","0")
    config.set("spark.debug.maxToStringFields", "100")
    config.set("spark.shuffle.io.maxRetries", "60")
    config.set("spark.default.parallelism", "54")
    config.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    val utils=new Utils(spark)
    val models=new Models(spark)
    val train_df=utils.readToCSV(Constant.basePath+"AData/train.csv").repartition(100).cache()
    val test_df=utils.readToCSV(Constant.basePath+"AData/test.csv").repartition(100).cache()

    val featureFilterColumns_arr=Array("id","target")
    val featureColumns_arr=train_df.columns.filterNot(column=>featureFilterColumns_arr.contains(column.toLowerCase))
    val stages=FeatureUtils.vectorAssemble(featureColumns_arr)

    val pipeline=new Pipeline().setStages(stages)
    val train_willFit_df=pipeline.fit(train_df).transform(train_df).select("ID","target","features")
    val test_willFit_df=pipeline.fit(test_df).transform(test_df).select("id","features")

    val lr_model=models.LR_TranAndSave(train_willFit_df,"target")
    val result_df=lr_model.transform(test_willFit_df).select("id","prediction")
    utils.writeToCSV(result_df,Constant.basePath+s"submission/lr_${System.currentTimeMillis()}.csv")
  }
  def run(): Unit ={

  }
}
class Run {

}
