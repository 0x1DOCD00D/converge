package converge

object AnchorDiscovery:
  def discover(
      client: OllamaClient,
      config: Config,
      catalog: Vector[Agent],
      intent: EngineerIntent,
      developmentTests: Vector[RouteTest]
  ): AnchorDiscoveryResult =
    val (candidates, generatedCandidates) = CandidatePhraseCollector.collect(client, config, intent, catalog)
    val references = CandidatePhraseCollector.referenceTexts(catalog, developmentTests)

    val allTexts = (references.values.flatten ++ candidates.map(_.text)).toVector.distinct
    val allVectors = client.embed(allTexts)
    val vectorByText = allTexts.zip(allVectors).toMap

    val centroids = references.map { case (agentId, texts) =>
      val vectors = texts.map(text => vectorByText(text))
      agentId -> Geometry.centroid(vectors)
    }

    val positiveRequests = developmentTests
      .filter(_.expected == intent.expectedOwner)
      .flatMap(_.requests)
      .distinct match
        case values if values.nonEmpty => values
        case _ => Vector(defaultPositiveRequest(intent))

    val negativeRequests = developmentTests
      .filterNot(_.expected == intent.expectedOwner)
      .flatMap(_.requests)
      .distinct match
        case values if values.nonEmpty => values
        case _ => Vector(defaultNearMissRequest(intent))

    val baselinePositiveMass = routeMass(client, config, catalog, positiveRequests, intent.expectedOwner)
    val baselineNegativeMass = routeMass(client, config, catalog, negativeRequests, intent.expectedOwner)

    val rawEvaluations = candidates.map { candidate =>
      val vector = vectorByText(candidate.text)
      val similarities = centroids.toVector.map { case (agentId, centroid) =>
        agentId -> Geometry.cosine(vector, centroid)
      }

      val targetSimilarity = similarities.find(_._1 == intent.expectedOwner).map(_._2).getOrElse(0.0)
      val strongestRival = similarities
        .filterNot(_._1 == intent.expectedOwner)
        .maxByOption(_._2)
        .getOrElse("NONE" -> 0.0)

      val geometricMargin = targetSimilarity - strongestRival._2
      val positiveWithAnchor = positiveRequests.map(request => inject(request, candidate.text))
      val negativeWithAnchor = negativeRequests.map(request => inject(request, candidate.text))
      val candidatePositiveMass = routeMass(client, config, catalog, positiveWithAnchor, intent.expectedOwner)
      val candidateNegativeMass = routeMass(client, config, catalog, negativeWithAnchor, intent.expectedOwner)
      val targetMassGain = candidatePositiveMass - baselinePositiveMass
      val spill = math.max(0.0, candidateNegativeMass - baselineNegativeMass)
      val behavioralScore = targetMassGain - config.anchorGamma * spill

      AnchorResult(
        phrase = candidate,
        targetSimilarity = targetSimilarity,
        strongestRival = strongestRival._1,
        rivalSimilarity = strongestRival._2,
        geometricMargin = geometricMargin,
        targetMassGain = targetMassGain,
        spill = spill,
        discriminativeScore = behavioralScore,
        selected = false
      )
    }

    val rankedEligible = rawEvaluations
      .filter(_.phrase.eligibleForPositiveSelection)
      .filter(_.geometricMargin >= config.minGeometricMargin)
      .filter(_.discriminativeScore >= config.minBehavioralScore)
      .filter(result => result.targetMassGain > 0.0)
      .sortBy(result => (-result.discriminativeScore, -result.geometricMargin, result.phrase.text.toLowerCase))

    val selected = selectNonRedundant(
      rankedEligible,
      vectorByText,
      config.anchorRedundancyThreshold,
      config.maxSelectedAnchors
    )
    val selectedKeys = selected.map(result => normalize(result.phrase.text)).toSet

    val evaluations = rawEvaluations
      .map(result => result.copy(selected = selectedKeys.contains(normalize(result.phrase.text))))
      .sortBy(result => (if result.selected then 0 else 1, -result.discriminativeScore, -result.geometricMargin, result.phrase.text.toLowerCase))

    AnchorDiscoveryResult(
      evaluations = evaluations,
      selectedPositive = selected.map(_.phrase.text),
      generatedCandidates = generatedCandidates
    )

  private def selectNonRedundant(
      ranked: Vector[AnchorResult],
      vectorByText: Map[String, Vector[Double]],
      redundancyThreshold: Double,
      limit: Int
  ): Vector[AnchorResult] =
    ranked.foldLeft(Vector.empty[AnchorResult]) { (selected, candidate) =>
      if selected.size >= limit then selected
      else
        val candidateVector = vectorByText(candidate.phrase.text)
        val redundant = selected.exists { existing =>
          val existingVector = vectorByText(existing.phrase.text)
          Geometry.cosine(candidateVector, existingVector) >= redundancyThreshold
        }
        if redundant then selected else selected :+ candidate
    }

  private def routeMass(
      client: OllamaClient,
      config: Config,
      catalog: Vector[Agent],
      requests: Vector[String],
      target: String
  ): Double =
    if requests.isEmpty then 0.0
    else
      val schedule = Router.fixedSchedule(config, catalog.size, requests.size)
      val hits = schedule.count { entry =>
        val request = requests(entry.requestVariant)
        Router.route(client, config, catalog, request, entry) == target
      }
      hits.toDouble / schedule.size.toDouble

  private def inject(request: String, phrase: String): String =
    s"$request\nTechnical routing coordinate: $phrase."

  private def defaultPositiveRequest(intent: EngineerIntent): String =
    s"${intent.normalizedGoal}. Change ${intent.coordinate} from ${intent.fromVersion} to ${intent.toVersion} in ${intent.artifactLocators.mkString(", ")}."

  private def defaultNearMissRequest(intent: EngineerIntent): String =
    s"Do not modify ${intent.artifactLocators.mkString(", ")}. If work is required outside the declared write set, hand it to ${intent.handoffOwner}."
