package org.lzy.kaggle.JDataByLeaner

import ml.dmlc.xgboost4j.scala.spark.{XGBoost, XGBoostEstimator, XGBoostModel}
import org.apache.spark.ml
import org.apache.spark.ml.{PipelineModel, Transformer}
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder, TrainValidationSplit}
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.sql.{DataFrame, SparkSession}

/**
  * Created by Administrator on 2018/6/5.
  */
object Model {
  val basePath = "hdfs://10.95.3.172:9000/user/lzy/JData_UserShop/"
  case class datas(features:ml.linalg.Vector, label:Double)
  def main(args: Array[String]): Unit = {
    val Array(round,nWorkers)=args
    val spark=SparkSession.builder()
//      .master("local[*]")
      .appName("model").getOrCreate()
    val config=spark.sparkContext.getConf
    config.set("spark.debug.maxToStringFields","100")
    config.set("spark.shuffle.io.maxRetries","60")
    config.set("spark.default.parallelism","54")
import spark.implicits._
val data:DataFrame =MLUtils.loadLibSVMFile(spark.sparkContext,basePath+"linear_regression_data.txt")
        .map(line=>datas(line.features.asML,line.label)).toDF("features","labels")
    data.show(false)
    val Array(train,test)=data.randomSplit(Array(0.8,0.2))
  }



  def fitPredict(train_df:DataFrame,labelCol:String,predictCol:String,round:Int)={
val useExternalMemory=true
    val xgboostParam=Map(
      "booster"->"gbtree",
      "objection"->"reg:linear",
      "eval_metric"->"rmse",
      "max_depth"->5,
      "eta"->0.05,
      "colsample_bytree"->0.9,
      "subsample"->0.8,
      "verbose_eval"->0
    )

    val xgbEstimator = new XGBoostEstimator(xgboostParam)
//            .setPredictionCol(predictCol)

    val paramGrid = new ParamGridBuilder()
            .addGrid(xgbEstimator.round, Array(round))
//            .addGrid(xgbEstimator.eta, Array(0.01,0.05))
        .addGrid(xgbEstimator.nWorkers,Array(12))
//        .addGrid(xgbEstimator.subSample,Array(0.5))
            .build()
    val tv=new TrainValidationSplit()
            .setEstimator(xgbEstimator)
            .setEvaluator(new RegressionEvaluator())
            .setEstimatorParamMaps(paramGrid)
            .setTrainRatio(0.8)
    val tvModel=tv.fit(train_df.withColumnRenamed(labelCol,"label"))
      tvModel
  }

    def fitPredictByCross(train_df:DataFrame,labelCol:String,predictCol:String,round:Int)={
        val xgboostParam=Map(
            "booster"->"gbtree",
            "objection"->"reg:linear",
            "eval_metric"->"rmse",
            "max_depth"->5,
            "eta"->0.05,
            "colsample_bytree"->0.9,
            "subsample"->0.8,
            "verbose_eval"->0
        )

        val xgbEstimator = new XGBoostEstimator(xgboostParam)
        val paramGrid = new ParamGridBuilder()
                .addGrid(xgbEstimator.round, Array(round))
//            .addGrid(xgbEstimator.eta, Array(0.01,0.05))
                .addGrid(xgbEstimator.nWorkers,Array(12))
//        .addGrid(xgbEstimator.subSample,Array(0.5))
                .build()
        val tv=new CrossValidator()
                .setEstimator(xgbEstimator)
                .setEvaluator(new RegressionEvaluator())
                .setEstimatorParamMaps(paramGrid)
                .setNumFolds(3)
        val tvModel=tv.fit(train_df.withColumnRenamed(labelCol,"label"))
        tvModel
    }

    def fitPredictByCrossClassic(train_df:DataFrame,labelCol:String,predictCol:String,round:Int)={
        val xgboostParam=Map(
            "booster"->"gbtree",
            "objection"->"reg:logistic",
            "eval_metric"->"auc",
            "max_depth"->5,
            "eta"->0.05,
            "colsample_bytree"->0.9,
            "subsample"->0.8,
            "verbose_eval"->0
        )
        val xgbEstimator = new XGBoostEstimator(xgboostParam)
        val paramGrid = new ParamGridBuilder()
                .addGrid(xgbEstimator.round, Array(round))
//            .addGrid(xgbEstimator.eta, Array(0.01,0.05))
                .addGrid(xgbEstimator.nWorkers,Array(12))
//        .addGrid(xgbEstimator.subSample,Array(0.5))
                .build()
        val tv=new CrossValidator()
                .setEstimator(xgbEstimator)
                .setEvaluator(new RegressionEvaluator())
                .setEstimatorParamMaps(paramGrid)
                .setNumFolds(3)
        val tvModel=tv.fit(train_df.withColumnRenamed(labelCol,"label"))
        tvModel
    }
}
