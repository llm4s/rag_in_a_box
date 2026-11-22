package org.llm4s.ragbox.service

/**
 * Two-sample significance tests for experiment results, from summary statistics (count, mean,
 * sample variance; or count and successes), so they run on what SQL aggregates return.
 *
 * The distributions are computed directly: the Student t CDF through the regularized incomplete
 * beta function (continued fraction, as in Numerical Recipes `betacf`), the normal CDF through
 * the complementary error function (Chebyshev fit, fractional error below 1.2e-7).
 */
object Statistics {

  /** A test result: the statistic and its two-sided p-value. */
  final case class TestResult(statistic: Double, pValue: Double)

  /**
   * Welch's unequal-variances t-test of `mean2 - mean1`.
   *
   * @return None when either group has fewer than two values or both variances are zero (the
   *         test is undefined)
   */
  def welchTTest(n1: Long, mean1: Double, variance1: Double, n2: Long, mean2: Double, variance2: Double): Option[TestResult] =
    if (n1 < 2 || n2 < 2) None
    else {
      val a = variance1 / n1
      val b = variance2 / n2
      val se2 = a + b
      if (!(se2 > 0)) None
      else {
        val t = (mean2 - mean1) / math.sqrt(se2)
        // Welch-Satterthwaite, (a + b)^2 / (a^2 / (n1 - 1) + b^2 / (n2 - 1)), on the shares of
        // `se2` so that tiny variances cannot underflow it to 0 / 0 (#66)
        val (sa, sb) = (a / se2, b / se2)
        val df = 1.0 / (sa * sa / (n1 - 1) + sb * sb / (n2 - 1))
        Some(TestResult(t, studentTTwoSidedP(t, df)))
      }
    }

  /**
   * Pooled two-proportion z-test of `successes2 / n2 - successes1 / n1` (no continuity
   * correction; `z * z` is the chi-squared statistic of the 2x2 table).
   *
   * @return None when a group is empty or every outcome is the same (the test is undefined)
   */
  def twoProportionZTest(successes1: Long, n1: Long, successes2: Long, n2: Long): Option[TestResult] =
    if (n1 <= 0 || n2 <= 0) None
    else {
      val pooled = (successes1 + successes2).toDouble / (n1 + n2)
      val se = math.sqrt(pooled * (1 - pooled) * (1.0 / n1 + 1.0 / n2))
      if (!(se > 0)) None
      else {
        val z = (successes2.toDouble / n2 - successes1.toDouble / n1) / se
        Some(TestResult(z, normalTwoSidedP(z)))
      }
    }

  /** Two-sided p-value of `t` under Student's t with `df` degrees of freedom. */
  def studentTTwoSidedP(t: Double, df: Double): Double =
    // A |t| so large that t * t overflows is as significant as an infinite one
    if ((t * t).isInfinite) 0.0
    else probability(regularizedIncompleteBeta(df / (df + t * t), df / 2, 0.5))

  /** Two-sided p-value of `z` under the standard normal distribution. */
  def normalTwoSidedP(z: Double): Double = probability(erfc(math.abs(z) / math.sqrt(2.0)))

  /** Standard normal CDF. */
  def normalCdf(z: Double): Double = probability(0.5 * erfc(-z / math.sqrt(2.0)))

  /** `p` within [0, 1]: the approximations above can overshoot by their error (erfc(0) is 1 + 3e-8). */
  private def probability(p: Double): Double = math.min(1.0, math.max(0.0, p))

  /** Complementary error function (Numerical Recipes `erfcc`, fractional error < 1.2e-7). */
  def erfc(x: Double): Double = {
    val z = math.abs(x)
    val t = 1.0 / (1.0 + 0.5 * z)
    val r = t * math.exp(
      -z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 +
        t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277))))))))
    )
    if (x >= 0) r else 2.0 - r
  }

  /** Natural log of the gamma function (Lanczos approximation, g = 7). */
  def logGamma(x: Double): Double =
    if (x < 0.5) math.log(math.Pi / math.abs(math.sin(math.Pi * x))) - logGamma(1 - x)
    else {
      val g = 7.0
      val coefficients = Array(
        0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
        -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
        1.5056327351493116e-7
      )
      val xm = x - 1
      var a = coefficients(0)
      val t = xm + g + 0.5
      var i = 1
      while (i < coefficients.length) {
        a += coefficients(i) / (xm + i)
        i += 1
      }
      0.5 * math.log(2 * math.Pi) + (xm + 0.5) * math.log(t) - t + math.log(a)
    }

  /** The regularized incomplete beta function I_x(a, b). */
  def regularizedIncompleteBeta(x: Double, a: Double, b: Double): Double =
    if (x <= 0) 0.0
    else if (x >= 1) 1.0
    else {
      val front = math.exp(logGamma(a + b) - logGamma(a) - logGamma(b) + a * math.log(x) + b * math.log(1 - x))
      // The continued fraction converges fast for x < (a + 1) / (a + b + 2); use the symmetry otherwise
      if (x < (a + 1) / (a + b + 2)) front * betaContinuedFraction(x, a, b) / a
      else 1.0 - front * betaContinuedFraction(1 - x, b, a) / b
    }

  private def betaContinuedFraction(x: Double, a: Double, b: Double): Double = {
    val MaxIterations = 300
    val Epsilon = 1e-15
    val Tiny = 1e-300
    val qab = a + b
    val qap = a + 1
    val qam = a - 1
    var c = 1.0
    var d = 1.0 - qab * x / qap
    if (math.abs(d) < Tiny) d = Tiny
    d = 1.0 / d
    var h = d
    var m = 1
    var done = false
    while (m <= MaxIterations && !done) {
      val m2 = 2 * m
      var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
      d = 1.0 + aa * d
      if (math.abs(d) < Tiny) d = Tiny
      c = 1.0 + aa / c
      if (math.abs(c) < Tiny) c = Tiny
      d = 1.0 / d
      h *= d * c
      aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
      d = 1.0 + aa * d
      if (math.abs(d) < Tiny) d = Tiny
      c = 1.0 + aa / c
      if (math.abs(c) < Tiny) c = Tiny
      d = 1.0 / d
      val delta = d * c
      h *= delta
      if (math.abs(delta - 1.0) < Epsilon) done = true
      m += 1
    }
    h
  }
}
