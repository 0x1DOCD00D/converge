package converge

import scala.util.Try

object CandidatePhraseCollector:
  private val ControlTerms = Vector("version", "package", "restaurant")

  def collect(
      client: OllamaClient,
      config: Config,
      intent: EngineerIntent,
      catalog: Vector[Agent]
  ): (Vector[CandidatePhrase], Vector[String]) =
    val targetAgent = catalog.find(_.id == intent.expectedOwner).getOrElse {
      throw new IllegalArgumentException(s"Unknown target agent ${intent.expectedOwner}")
    }

    val engineerPositive = intent.positiveAnchors.map { text =>
      CandidatePhrase(text, "engineer-intent-positive", eligibleForPositiveSelection = true)
    }

    val engineerNegative = intent.negativeAnchors.map { text =>
      CandidatePhrase(text, "engineer-intent-negative", eligibleForPositiveSelection = false)
    }

    val targetProfile = (targetAgent.ownedArtifacts ++ targetAgent.writablePaths).map { text =>
      CandidatePhrase(text, s"target-agent-profile:${targetAgent.id}", eligibleForPositiveSelection = true)
    }

    val rivalProfiles = catalog.filterNot(_.id == targetAgent.id).flatMap { agent =>
      (agent.ownedArtifacts ++ agent.writablePaths).map { text =>
        CandidatePhrase(text, s"rival-agent-profile:${agent.id}", eligibleForPositiveSelection = false)
      }
    }

    val controls = ControlTerms.map { text =>
      CandidatePhrase(text, "diagnostic-control", eligibleForPositiveSelection = false)
    }

    val generated =
      if config.generateCandidatePhrases && config.candidateSuggestions > 0 then
        proposeAdditional(client, config, intent, catalog)
      else Vector.empty

    val generatedCandidates = generated.map { text =>
      CandidatePhrase(text, s"model-hypothesis:${config.model}", eligibleForPositiveSelection = true)
    }

    val all =
      (engineerPositive ++ engineerNegative ++ targetProfile ++ rivalProfiles ++ controls ++ generatedCandidates)
        .map(candidate => candidate.copy(text = candidate.text.trim))
        .filter(_.text.nonEmpty)
        .groupBy(candidate => normalize(candidate.text))
        .values
        .map { duplicates =>
          duplicates.toVector.sortBy(candidate => sourcePriority(candidate.source)).head
        }
        .toVector
        .sortBy(candidate => (sourcePriority(candidate.source), candidate.text.toLowerCase))

    (all, generated)

  def referenceTexts(
      catalog: Vector[Agent],
      developmentTests: Vector[RouteTest]
  ): Map[String, Vector[String]] =
    catalog.map { agent =>
      val labeledRequests = developmentTests
        .filter(_.expected == agent.id)
        .flatMap(_.requests)

      val texts =
        (Vector(agent.description) ++ agent.ownedArtifacts ++ agent.writablePaths ++ labeledRequests)
          .map(_.trim)
          .filter(_.nonEmpty)
          .distinct

      agent.id -> texts
    }.toMap

  private def proposeAdditional(
      client: OllamaClient,
      config: Config,
      intent: EngineerIntent,
      catalog: Vector[Agent]
  ): Vector[String] =
    val catalogSummary = catalog.map { agent =>
      s"${agent.id}: ${agent.description}"
    }.mkString("\n")

    val prompt =
      s"""Authoritative EngineerIntent
         |Goal: ${intent.normalizedGoal}
         |Artifact class: ${intent.artifactClass}
         |Artifact locators: ${intent.artifactLocators.mkString(", ")}
         |Operation: ${intent.operation}
         |Coordinate: ${intent.coordinate}
         |Expected owner: ${intent.expectedOwner}
         |Known positive terminology: ${intent.positiveAnchors.mkString(", ")}
         |Known negative terminology: ${intent.negativeAnchors.mkString(", ")}
         |
         |Current agent catalog
         |$catalogSummary
         |
         |Propose at most ${config.candidateSuggestions} short technical phrases that could help distinguish the expected owner's exact artifact and operation from its neighboring agents. Each phrase must be at most eight words. Do not propose new goals, artifacts, effects, permissions, owners, versions, or validation commands. Return phrases only through the requested JSON schema.
         |""".stripMargin

    Try {
      val response = client.generateJson(
        system = "You are a bounded CONVERGE candidate-vocabulary proposer. You propose short routing phrases only. You do not change EngineerIntent and you do not decide whether a phrase is valid.",
        prompt = prompt,
        schema = Schemas.candidatePhrases(config.candidateSuggestions),
        seed = 61001,
        temperature = 0.0
      )
      Json.strings(response.content, "phrases")
        .map(_.trim)
        .filter(validGeneratedPhrase)
        .distinct
        .take(config.candidateSuggestions)
    }.getOrElse(Vector.empty)

  private def validGeneratedPhrase(value: String): Boolean =
    val words = value.split("\\s+").count(_.nonEmpty)
    value.nonEmpty && value.length <= 120 && words <= 8 && !value.contains("\n") && !value.contains("\r")

  private def sourcePriority(source: String): Int =
    if source == "engineer-intent-positive" then 0
    else if source.startsWith("target-agent-profile:") then 1
    else if source.startsWith("model-hypothesis:") then 2
    else if source == "engineer-intent-negative" then 3
    else if source.startsWith("rival-agent-profile:") then 4
    else 5
