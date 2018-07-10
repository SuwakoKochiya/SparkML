package org.lzy.kaggle.kaggleSantander

import common.{FeatureUtils, Utils}
import org.apache.spark.ml.classification.GBTClassificationModel
import org.apache.spark.ml.feature.ChiSqSelectorModel
import org.apache.spark.ml.{Pipeline, PipelineStage}
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.ml.linalg
import org.apache.spark.ml.regression.{GBTRegressionModel, LinearRegressionModel}
import org.apache.spark.ml.tuning.TrainValidationSplitModel
import org.apache.spark.ml.util.MLReadable

/**
  * Created by Administrator on 2018/7/3.
  */
object TrainModel {
    def main(args: Array[String]): Unit = {

    }


}

class TrainModel(spark: SparkSession) {

    import spark.implicits._


    /** *
      * 功能实现:使用卡方检验进行特征选择
      *
      * Author: Lzy
      * Date: 2018/7/9 9:20
      * Param: [train_df_source, fdr]
      * Return: void
      */
    def testSelectorChiSq(train_df_source: DataFrame, type_num: Int = 0, arg: Double) = {
        //    1、最大数量，0错误率上限
        val models = new Models(spark)

        val train_df = train_df_source.withColumn("target", log1p($"target"))

        val featureColumns_arr = train_df.columns.filterNot(column => Constant.featureFilterColumns_arr.contains(column.toLowerCase))
        var stages: Array[PipelineStage] = FeatureUtils.vectorAssemble(featureColumns_arr, "assmbleFeatures")
        val chiSqSelector_pipstage: PipelineStage = type_num match {
            case 0 => FeatureUtils.chiSqSelectorByfdr("target", "assmbleFeatures", "features", arg)
            case 1 => FeatureUtils.chiSqSelector("target", "assmbleFeatures", "features", arg.toInt)
        }
        stages = stages :+ chiSqSelector_pipstage

        val pipeline = new Pipeline().setStages(stages)
        val pipelin_model = pipeline.fit(train_df)
        val train_willFit_df = pipelin_model.transform(train_df).select("ID", "target", "features")
        //          .withColumn("target",$"target"/10000d)
        val ChiSqSelectNums = train_willFit_df.select("features").take(1)(0).getAs[linalg.Vector](0).size
        val score = models.evaluateGDBT(train_willFit_df, "target")
        println(s"当前错误率上限/特征数量：${arg},已选择特征数量：$ChiSqSelectNums,score：${score}")
    }

    /** *
      * 功能实现:使用随机森林进行特征 选择测试
      *
      * Author: Lzy
      * Date: 2018/7/9 9:30
      * Param: [train_df_source, num]
      * Return: void
      */
    def testSelectorRF(train_df_source: DataFrame, num: Int) = {
        val models = new Models(spark)
        val featureExact = new FeatureExact(spark)

        val train_df = train_df_source.withColumn("target", log1p($"target"))

        val featureColumns_arr = featureExact.selectFeaturesByRF(train_df, num)
        var stages: Array[PipelineStage] = FeatureUtils.vectorAssemble(featureColumns_arr, "features")
        val pipeline = new Pipeline().setStages(stages)

        val pipelin_model = pipeline.fit(train_df)
        val train_willFit_df = pipelin_model.transform(train_df).select("ID", "target", "features")
        //          .withColumn("target",$"target"/10000d)
        val ChiSqSelectNums = train_willFit_df.select("features").take(1)(0).getAs[linalg.Vector](0).size
        val score = models.evaluateGDBT(train_willFit_df, "target")
        println(s"当前特征数量：${num},已选择特征数量：$ChiSqSelectNums,score：${score}")
    }

    def testSelectorGBDT(train_df_source: DataFrame, num: Int) = {
        val models = new Models(spark)
        val featureExact = new FeatureExact(spark)

        val train_df = train_df_source.withColumn("target", log1p($"target"))

        val featureColumns_arr = featureExact.selectFeaturesByGBDT(train_df, num)
        var stages: Array[PipelineStage] = FeatureUtils.vectorAssemble(featureColumns_arr, "features")
        val pipeline = new Pipeline().setStages(stages)

        val pipelin_model = pipeline.fit(train_df)
        val train_willFit_df = pipelin_model.transform(train_df).select("ID", "target", "features")
        //          .withColumn("target",$"target"/10000d)
        val ChiSqSelectNums = train_willFit_df.select("features").take(1)(0).getAs[linalg.Vector](0).size
        val score = models.evaluateGDBT(train_willFit_df, "target")
        println(s"当前特征数量：${num},已选择特征数量：$ChiSqSelectNums,score：${score}")
    }

    /** *
      * 功能实现:使用GBDT进行训练模型，并导出结果数据
      *
      * Author: Lzy
      * Date: 2018/7/9 9:30
      * Param: [train_df_source, test_df, fdr, num]
      * Return: void
      */
    def fitByGBDT(train_df_source: DataFrame, test_df: DataFrame, fdr: Double, num: Int = 1000) = {
        val models = new Models(spark)
        val featureExact = new FeatureExact(spark)
        val utils = new Utils(spark)

        val train_df = train_df_source.withColumn("target", log1p($"target"))

        val featureColumns_arr = featureExact.selectFeaturesByRF(train_df, num)
        //        val featureColumns_arr = featureExact.selectFeaturesByGBDT(train_df, num)
        var stages: Array[PipelineStage] = FeatureUtils.vectorAssemble(featureColumns_arr, "assmbleFeatures")

        //    stages=stages:+  FeatureUtils.chiSqSelectorByfdr("target","assmbleFeatures","features",fdr)
        //        stages = stages :+ FeatureUtils.chiSqSelector("target", "assmbleFeatures", "features", num)
        val pipeline = new Pipeline().setStages(stages)

        val pipelin_model = pipeline.fit(train_df)
        //        val train_willFit_df = pipelin_model.transform(train_df).select("ID", "target", "features")
        //增加pca100
        val train_willFitToPCA_df = pipelin_model.transform(train_df).select("ID", "assmbleFeatures", "target").withColumn("type", lit(0))
        val test_willFitToPCA_df = pipelin_model.transform(test_df).select("ID", "assmbleFeatures").withColumn(Constant.lableCol, lit(0d)).withColumn("type", lit(1))

        val tmp_df = train_willFitToPCA_df.union(test_willFitToPCA_df)

        val tmp_pca_df = featureExact.joinWithPCA(tmp_df, 100, "assmbleFeatures", "features")
        val train_willFit_df = tmp_pca_df.filter($"type" === 0).select("ID", "target", "features")
        val test_willFit_df = tmp_pca_df.filter($"type" === 1).select("ID", "features")

        val lr_model = models.GBDT_TrainAndSave(train_willFit_df, "target")


        val format_udf = udf { prediction: Double =>
            "%08.9f".format(prediction)
        }
        val result_df = lr_model.transform(test_willFit_df)
//            .withColumn("target", format_udf(abs($"prediction" * 10000d)))
                .withColumn("target", format_udf(expm1(abs($"prediction"))))
                .select("id", "target")
        utils.writeToCSV(result_df, Constant.basePath + s"submission/lr_${System.currentTimeMillis()}")
    }

    /** *
      * 功能实现:
      * 使用分桶来划分数据，并将结果按照log1p进行四舍五入，做分类。
      * Author: Lzy
      * Date: 2018/7/10 19:25
      * Param: [train_df_source, ColumnNum]
      * Return: org.apache.spark.ml.classification.GBTClassificationModel
      */
    def fitByGBDTAndBucket(all_df_source: DataFrame, ColumnNum: Int = 1000): Unit = {
        val models = new Models(spark)
        val featureExact = new FeatureExact(spark)

        val all_df = all_df_source.withColumn("target", round(log1p($"target")))

        val featureColumns_arr = all_df.columns.filterNot(column => Constant.featureFilterColumns_arr.contains(column.toLowerCase))

        val all_feaPro_df = featureExact.featureBucketzer(all_df, featureColumns_arr, "features")
        all_feaPro_df.write.mode(SaveMode.Overwrite).parquet(Constant.basePath+"cache/all_bucket_df")
        val (train_df,test_df)=FeatureUtils.splitTrainAndTest(all_feaPro_df)
        val train=train_df.select("id", "target", "features")
        val test=test_df.select("id",  "features")
        val gbdt_model = models.GBDTClassic_TrianAndSave(train, Constant.lableCol, "features")
//        gbdt_model
        val result_df=gbdt_model.transform(test)
        writeSub(result_df)
    }

    def transformAndExplot_GBDTBucket(test_df: DataFrame, modelPath: String) = {
        val result_df = GBTClassificationModel.load(modelPath).transform(test_df)
        writeSub(result_df)

    }

    /** *
      * 功能实现:加载GBDT模型，并训练结果文件，
      *
      * Author: Lzy
      * Date: 2018/7/9 9:36
      * Param: [test_df, modelPath]
      * Return: void
      */
    def transformAndExplot_GBDT(test_df: DataFrame, modelPath: String) = {
        val model = GBTRegressionModel.load(modelPath)
        val result_df = model.transform(test_df)
        writeSub(result_df)
    }


    /** *
      * 功能实现:加载GBDT模型，并训练结果文件，
      *
      * Author: Lzy
      * Date: 2018/7/9 9:36
      * Param: [test_df, modelPath]
      * Return: void
      */
    def transformAndExplot_TV(test_df: DataFrame, modelPath: String) = {
        val model = TrainValidationSplitModel.load(modelPath)
        val result_df = model.transform(test_df)
        writeSub(result_df)

    }

    /** *
      * 功能实现:将数据写出到结果文件
      *
      * Author: Lzy
      * Date: 2018/7/10 19:24
      * Param: [df]
      * Return: void
      */
    def writeSub(df: DataFrame) = {
        val utils=new Utils(spark)
        val format_udf = udf { prediction: Double =>
            "%08.9f".format(prediction)
        }
        val result_df = df.withColumn("target", format_udf(expm1(abs($"prediction"))))
                .select("id", Constant.lableCol)
        val subName = s"sub_${System.currentTimeMillis()}"
        println(s"当前结果文件：${subName}")
        utils.writeToCSV(result_df, Constant.basePath + s"submission/$subName")
        println("action:?"+result_df.count())
    }
}
