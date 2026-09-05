package converge

object Geometry:
  def dot(a: Vector[Double], b: Vector[Double]): Double =
    require(a.size == b.size, s"Vector dimension mismatch: ${a.size} versus ${b.size}")
    a.lazyZip(b).map(_ * _).sum

  def norm(vector: Vector[Double]): Double =
    math.sqrt(dot(vector, vector))

  def normalize(vector: Vector[Double]): Vector[Double] =
    val length = norm(vector)
    if length == 0.0 then vector else vector.map(_ / length)

  def centroid(vectors: Vector[Vector[Double]]): Vector[Double] =
    require(vectors.nonEmpty, "Cannot compute a centroid from an empty vector set")
    val dimension = vectors.head.size
    require(vectors.forall(_.size == dimension), "All embeddings in a centroid must have the same dimension")

    val mean = Vector.tabulate(dimension) { index =>
      vectors.iterator.map(_(index)).sum / vectors.size.toDouble
    }
    normalize(mean)

  def cosine(a: Vector[Double], b: Vector[Double]): Double =
    val denominator = norm(a) * norm(b)
    if denominator == 0.0 then 0.0 else dot(a, b) / denominator

object Stats:
  def wilson(successes: Int, total: Int, z: Double = 1.959963984540054): (Double, Double) =
    if total <= 0 then (0.0, 1.0)
    else
      val n = total.toDouble
      val p = successes.toDouble / n
      val z2 = z * z
      val denominator = 1.0 + z2 / n
      val center = (p + z2 / (2.0 * n)) / denominator
      val halfWidth = z * math.sqrt((p * (1.0 - p) + z2 / (4.0 * n)) / n) / denominator
      (math.max(0.0, center - halfWidth), math.min(1.0, center + halfWidth))
