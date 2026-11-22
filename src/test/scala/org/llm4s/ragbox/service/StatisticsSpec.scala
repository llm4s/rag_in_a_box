package org.llm4s.ragbox.service

import org.llm4s.ragbox.model.ExperimentVariantMetrics
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The tests against values from statistical tables and R. */
class StatisticsSpec extends AnyFlatSpec with Matchers {

  private def meanAndVariance(xs: Seq[Double]): (Long, Double, Double) = {
    val mean = xs.sum / xs.size
    (xs.size.toLong, mean, xs.map(x => (x - mean) * (x - mean)).sum / (xs.size - 1))
  }

  "The normal distribution" should "match the standard table" in {
    Statistics.normalCdf(0) shouldBe 0.5 +- 1e-7
    Statistics.normalCdf(1) shouldBe 0.8413447 +- 1e-6
    Statistics.normalTwoSidedP(1.959964) shouldBe 0.05 +- 1e-6
    Statistics.normalTwoSidedP(2.575829) shouldBe 0.01 +- 1e-6
  }

  "Student's t distribution" should "give the tabulated two-sided critical values" in {
    Statistics.studentTTwoSidedP(12.7062, 1) shouldBe 0.05 +- 1e-5
    Statistics.studentTTwoSidedP(2.228139, 10) shouldBe 0.05 +- 1e-6
    Statistics.studentTTwoSidedP(3.169273, 10) shouldBe 0.01 +- 1e-6
    Statistics.studentTTwoSidedP(1.812461, 10) shouldBe 0.10 +- 1e-6
    Statistics.studentTTwoSidedP(1.983972, 100) shouldBe 0.05 +- 1e-6
    Statistics.studentTTwoSidedP(0, 5) shouldBe 1.0 +- 1e-12
  }

  "Welch's t-test" should "match R's t.test(extra ~ group, data = sleep)" in {
    // R: t = -1.8608, df = 17.776, p-value = 0.07939
    val group1 = Seq(0.7, -1.6, -0.2, -1.2, -0.1, 3.4, 3.7, 0.8, 0.0, 2.0)
    val group2 = Seq(1.9, 0.8, 1.1, 0.1, -0.1, 4.4, 5.5, 1.6, 4.6, 3.4)
    val (n1, m1, v1) = meanAndVariance(group1)
    val (n2, m2, v2) = meanAndVariance(group2)
    val result = Statistics.welchTTest(n2, m2, v2, n1, m1, v1).get
    result.statistic shouldBe -1.8608 +- 1e-4
    result.pValue shouldBe 0.07939 +- 1e-5
  }

  it should "be undefined without two values in each group or any variance" in {
    Statistics.welchTTest(1, 3.0, 0.0, 10, 4.0, 1.0) shouldBe None
    Statistics.welchTTest(5, 3.0, 0.0, 5, 4.0, 0.0) shouldBe None
  }

  "The two-proportion z-test" should "match R's prop.test(c(45, 30), c(100, 100), correct = FALSE)" in {
    // R: X-squared = 4.8, p-value = 0.02846
    val result = Statistics.twoProportionZTest(45, 100, 30, 100).get
    (result.statistic * result.statistic) shouldBe 4.8 +- 1e-9
    result.statistic should be < 0.0
    result.pValue shouldBe 0.02846 +- 1e-5
  }

  it should "be undefined for an empty group or a single outcome" in {
    Statistics.twoProportionZTest(0, 0, 3, 10) shouldBe None
    Statistics.twoProportionZTest(0, 10, 0, 10) shouldBe None
  }

  "Experiment analysis" should "report a test per metric with enough data, and none without" in {
    def stats(queries: Int, failed: Int, latency: Double, latencyVar: Double, rated: Int, rating: Double, ratingVar: Double, positive: Int) =
      ExperimentService.GroupStats(
        ExperimentVariantMetrics(
          queryCount = queries, avgLatencyMs = latency, avgRating = Option.when(rated > 0)(rating), ratedCount = rated,
          avgChunksUsed = 3, avgChunksRetrieved = 3, failedCount = failed,
          errorRate = failed.toDouble / queries, positiveCount = positive
        ),
        succeededCount = queries - failed,
        latencyVariance = Some(latencyVar),
        ratingVariance = Option.when(rated > 1)(ratingVar)
      )

    val analysis = ExperimentService.analyze(
      stats(queries = 100, failed = 30, latency = 200, latencyVar = 400, rated = 50, rating = 3.0, ratingVar = 1.0, positive = 15),
      stats(queries = 100, failed = 45, latency = 210, latencyVar = 400, rated = 50, rating = 3.6, ratingVar = 1.0, positive = 30)
    )
    analysis.tests.map(t => t.metric -> t.test) shouldBe Seq(
      "latencyMs" -> "welch-t", "rating" -> "welch-t", "errorRate" -> "two-proportion-z", "positiveRate" -> "two-proportion-z"
    )
    val errorRate = analysis.tests.find(_.metric == "errorRate").get
    errorRate.pValue shouldBe 0.02846 +- 1e-5
    errorRate.significant shouldBe true
    analysis.errorRateChange shouldBe "+50.0%"
    // Two groups of rating 3.0 vs 3.6 (sd 1, n 50): t = 3, df = 98
    analysis.tests.find(_.metric == "rating").get.statistic shouldBe 3.0 +- 1e-9

    val tooFew = ExperimentService.analyze(
      stats(queries = 1, failed = 0, latency = 200, latencyVar = 0, rated = 0, rating = 0, ratingVar = 0, positive = 0),
      stats(queries = 1, failed = 0, latency = 210, latencyVar = 0, rated = 0, rating = 0, ratingVar = 0, positive = 0)
    )
    tooFew.tests shouldBe empty
  }
}
