package converge

import scala.util.Try

object Router:
  private val system =
    """You are the routing component of a multi-agent software system. Select exactly one authoritative owner from the catalog, or ABSTAIN if no listed agent owns the requested artifact and operation. Ownership of the authoritative artifact and operation outranks broad topical capability. Do not solve the task. Return only the requested JSON object."""

  def systemHash: String = sha256(system)

  def fixedSchedule(config: Config, catalogSize: Int, requestVariants: Int): Vector[Schedule] =
    val safeCatalogSize = math.max(1, catalogSize)
    val safeRequestVariants = math.max(1, requestVariants)
    Vector.tabulate(config.routeTrials) { index =>
      Schedule(
        seed = 41011 + index * 131,
        orderShift = index % safeCatalogSize,
        requestVariant = index % safeRequestVariants
      )
    }

  def route(
      client: OllamaClient,
      config: Config,
      catalog: Vector[Agent],
      request: String,
      schedule: Schedule
  ): String =
    val orderedCatalog = rotate(catalog, schedule.orderShift)
    val renderedCatalog = orderedCatalog.map { agent =>
      s"AgentId: ${agent.id}\nDescription: ${agent.description}"
    }.mkString("\n\n")

    val prompt =
      s"""CATALOG
         |
         |$renderedCatalog
         |
         |REQUEST
         |
         |$request
         |""".stripMargin

    val result = client.generateJson(
      system = system,
      prompt = prompt,
      schema = Schemas.route(catalog.map(_.id)),
      seed = schedule.seed
    )
    Json.string(result.content, "selectedAgent")

  def evaluate(
      client: OllamaClient,
      config: Config,
      catalog: Vector[Agent],
      tests: Vector[RouteTest]
  ): RouteEvaluation =
    val caseResults = tests.map { test =>
      val schedule = fixedSchedule(config, catalog.size, test.requests.size)
      val trials = schedule.zipWithIndex.map { case (entry, index) =>
        val request = test.requests(entry.requestVariant)
        val selected = route(client, config, catalog, request, entry)
        RouteTrial(
          testId = test.id,
          scheduleIndex = index,
          request = request,
          selected = selected,
          expected = test.expected,
          pass = selected == test.expected && !test.mustNot.contains(selected),
          seed = entry.seed,
          orderShift = entry.orderShift
        )
      }
      aggregateCase(test, trials, config)
    }

    val accuracy =
      if caseResults.isEmpty then 0.0
      else caseResults.count(_.pass).toDouble / caseResults.size.toDouble

    val hardCases = tests.zip(caseResults).filter { case (test, _) =>
      test.kind == "near-miss" || test.kind == "hard-negative"
    }
    val hardBoundaryErrorRate =
      if hardCases.isEmpty then 0.0
      else hardCases.count { case (_, result) => !result.pass }.toDouble / hardCases.size.toDouble

    val margins = caseResults.map(_.margin)
    val flipRates = caseResults.map { result =>
      if result.trials.size <= 1 then 0.0
      else
        val baseline = result.trials.head.selected
        result.trials.tail.count(_.selected != baseline).toDouble / (result.trials.size - 1).toDouble
    }

    RouteEvaluation(
      cases = caseResults,
      accuracy = accuracy,
      hardBoundaryErrorRate = hardBoundaryErrorRate,
      minimumMargin = margins.minOption.getOrElse(0.0),
      meanMargin = if margins.isEmpty then 0.0 else margins.sum / margins.size.toDouble,
      routeFlipRate = if flipRates.isEmpty then 0.0 else flipRates.sum / flipRates.size.toDouble,
      failedIds = caseResults.filterNot(_.pass).map(_.testId)
    )

  private def aggregateCase(
      test: RouteTest,
      trials: Vector[RouteTrial],
      config: Config
  ): RouteCaseResult =
    val counts = trials.groupMapReduce(_.selected)(_ => 1)(_ + _)
    val sorted = counts.toVector.sortBy { case (agentId, count) => (-count, agentId) }

    val winner =
      if sorted.isEmpty then "ABSTAIN"
      else if sorted.size >= 2 && sorted(0)._2 == sorted(1)._2 then "ABSTAIN"
      else sorted.head._1

    val total = math.max(1, trials.size).toDouble
    val expectedRate = counts.getOrElse(test.expected, 0).toDouble / total
    val strongestRival = counts.toVector
      .filterNot(_._1 == test.expected)
      .sortBy { case (agentId, count) => (-count, agentId) }
      .headOption

    val strongestRivalId = strongestRival.map(_._1).getOrElse("NONE")
    val strongestRivalRate = strongestRival.map(_._2.toDouble / total).getOrElse(0.0)
    val margin = expectedRate - strongestRivalRate
    val forbiddenSelections = trials.count(trial => test.mustNot.contains(trial.selected))

    val pass =
      winner == test.expected &&
      expectedRate >= config.minExpectedRate &&
      margin >= config.minMargin &&
      forbiddenSelections == 0

    RouteCaseResult(
      testId = test.id,
      expected = test.expected,
      winner = winner,
      expectedRate = expectedRate,
      strongestRival = strongestRivalId,
      strongestRivalRate = strongestRivalRate,
      margin = margin,
      forbiddenSelections = forbiddenSelections,
      pass = pass,
      trials = trials
    )

  private def rotate[A](values: Vector[A], shift: Int): Vector[A] =
    if values.isEmpty then values
    else
      val normalized = ((shift % values.size) + values.size) % values.size
      values.drop(normalized) ++ values.take(normalized)

object CatalogRepair:
  val allowedAtoms: Vector[String] = Vector(
    "build.sbt",
    "libraryDependencies",
    "ModuleID",
    "dependency coordinate",
    "dependency version selection",
    "sbt update",
    "dependency eviction",
    "project/plugins.sbt",
    "src/main/scala/**",
    "Scala source API adaptation",
    "compiler type error in source",
    "src/test/scala/**",
    "MUnit test failure",
    "ScalaTest failure",
    "Dockerfile",
    "container image build",
    "ECS task definition",
    "deployment rollout",
    "Terraform resource"
  )

  def repair(
      client: OllamaClient,
      config: Config,
      initialCatalog: Vector[Agent],
      tests: Vector[RouteTest]
  ): CatalogRepairResult =
    var catalog = initialCatalog
    val initialEvaluation = Router.evaluate(client, config, catalog, tests)
    var evaluation = initialEvaluation
    val acceptedSteps = Vector.newBuilder[CatalogRepairStep]
    var rawCandidates = 0
    var acceptedCount = 0
    var foundImprovement = true

    while evaluation.failedIds.nonEmpty && acceptedCount < config.maxCatalogEdits && foundImprovement do
      foundImprovement = false
      val witnessId = evaluation.failedIds.head
      val witnessTest = tests.find(_.id == witnessId).getOrElse {
        throw new IllegalStateException(s"Missing routing test for failed case $witnessId")
      }
      val witnessResult = evaluation.cases.find(_.testId == witnessId).get
      val wrongAgent =
        if witnessResult.strongestRival == "NONE" then "ABSTAIN"
        else witnessResult.strongestRival

      val proposals = Vector.tabulate(config.catalogCandidates) { candidateIndex =>
        rawCandidates += 1
        val seed = 9001 + acceptedCount * 1009 + candidateIndex * 97
        proposeEdit(client, catalog, witnessTest, wrongAgent, seed)
      }.flatten.distinct

      val evaluated = proposals.flatMap { proposal =>
        ratifyEdit(proposal, witnessTest, wrongAgent, catalog).map { ratified =>
          val candidateCatalog = applyEdit(catalog, ratified)
          val candidateEvaluation = Router.evaluate(client, config, candidateCatalog, tests)
          (ratified, candidateCatalog, candidateEvaluation)
        }
      }

      val admissible = evaluated.filter { case (_, _, candidateEvaluation) =>
        noProtectedRegression(evaluation, candidateEvaluation, tests) &&
        strictlyBetter(evaluation, candidateEvaluation)
      }

      admissible.sortBy { case (_, candidateCatalog, candidateEvaluation) =>
        (
          candidateEvaluation.failedIds.size,
          -candidateEvaluation.meanMargin,
          candidateCatalog.iterator.map(_.description.length).sum
        )
      }.headOption match
        case Some((edit, candidateCatalog, candidateEvaluation)) =>
          acceptedSteps += CatalogRepairStep(
            acceptedIndex = acceptedCount,
            witnessTest = witnessId,
            expectedAgent = edit.expectedAgent,
            wrongAgent = edit.wrongAgent,
            atoms = edit.boundaryAtoms,
            beforeFailed = evaluation.failedIds,
            afterFailed = candidateEvaluation.failedIds,
            beforeMeanMargin = evaluation.meanMargin,
            afterMeanMargin = candidateEvaluation.meanMargin
          )
          catalog = candidateCatalog
          evaluation = candidateEvaluation
          acceptedCount += 1
          foundImprovement = true
        case None => ()

    CatalogRepairResult(
      catalog = catalog,
      initial = initialEvaluation,
      finalEvaluation = evaluation,
      acceptedSteps = acceptedSteps.result(),
      rawCandidates = rawCandidates,
      localFixpoint = evaluation.failedIds.isEmpty || !foundImprovement
    )

  private def proposeEdit(
      client: OllamaClient,
      catalog: Vector[Agent],
      witness: RouteTest,
      wrongAgent: String,
      seed: Int
  ): Option[DescriptionEdit] =
    val catalogText = catalog.map(agent => s"${agent.id}: ${agent.description}").mkString("\n")
    val prompt =
      s"""A protected routing case failed.
         |Test id: ${witness.id}
         |Request variants: ${witness.requests.mkString(" || ")}
         |Expected owner: ${witness.expected}
         |Strongest wrong owner: $wrongAgent
         |
         |Current catalog
         |$catalogText
         |
         |Select the smallest useful set of boundary atoms from the finite vocabulary supplied by the JSON schema. Do not write free text. The controller will add the selected atoms positively to the expected owner and as a mirrored exclusion to the wrong owner. You are proposing a candidate edit only. You are not allowed to approve it.
         |""".stripMargin

    Try {
      val result = client.generateJson(
        system = "You are the CONVERGE finite-grammar catalog-repair proposer. Return exactly one JSON candidate edit and do not solve the software task.",
        prompt = prompt,
        schema = Schemas.descriptionEdit(catalog.map(_.id), allowedAtoms),
        seed = seed,
        temperature = 0.0
      )
      DescriptionEdit(
        expectedAgent = Json.string(result.content, "expectedAgent"),
        wrongAgent = Json.string(result.content, "wrongAgent"),
        boundaryAtoms = Json.strings(result.content, "boundaryAtoms").distinct
      )
    }.toOption

  private def ratifyEdit(
      edit: DescriptionEdit,
      witness: RouteTest,
      wrongAgent: String,
      catalog: Vector[Agent]
  ): Option[DescriptionEdit] =
    val ids = catalog.map(_.id).toSet
    val valid =
      edit.expectedAgent == witness.expected &&
      edit.wrongAgent == wrongAgent &&
      ids.contains(edit.expectedAgent) &&
      (edit.wrongAgent == "ABSTAIN" || ids.contains(edit.wrongAgent)) &&
      edit.boundaryAtoms.nonEmpty &&
      edit.boundaryAtoms.size <= 6 &&
      edit.boundaryAtoms.forall(allowedAtoms.contains)

    Option.when(valid)(edit)

  private def applyEdit(catalog: Vector[Agent], edit: DescriptionEdit): Vector[Agent] =
    val atoms = edit.boundaryAtoms.mkString(", ")
    catalog.map { agent =>
      if agent.id == edit.expectedAgent then
        agent.copy(
          description = agent.description +
            s" Positive ownership boundary: this agent owns requests whose authoritative artifact or action is identified by $atoms."
        )
      else if agent.id == edit.wrongAgent then
        agent.copy(
          description = agent.description +
            s" Mirrored exclusion: this agent does not own requests whose authoritative artifact or action is identified by $atoms; route those requests to ${edit.expectedAgent}."
        )
      else agent
    }

  private def noProtectedRegression(
      before: RouteEvaluation,
      after: RouteEvaluation,
      tests: Vector[RouteTest]
  ): Boolean =
    val beforeById = before.cases.map(result => result.testId -> result).toMap
    val afterById = after.cases.map(result => result.testId -> result).toMap

    tests.filter(_.protectedCase).forall { test =>
      val passedBefore = beforeById.get(test.id).exists(_.pass)
      val passedAfter = afterById.get(test.id).exists(_.pass)
      !passedBefore || passedAfter
    }

  private def strictlyBetter(before: RouteEvaluation, after: RouteEvaluation): Boolean =
    after.failedIds.size < before.failedIds.size ||
    (after.failedIds.size == before.failedIds.size && after.meanMargin > before.meanMargin + 0.01)

object PlanProbe:
  private val system =
    """Simulate the exact action plan that a tool-bearing software agent would attempt for the supplied prompt. Do not execute tools. Do not add permissions that the prompt does not grant. List intended writes, commands, effects, evidence, handoffs, and whether success would be claimed without complete evidence. Return only the requested JSON object."""

  private val perturbations: Vector[(String, String => String)] = Vector(
    "identity" -> ((prompt: String) => prompt),
    "irrelevant-environment-suffix" -> ((prompt: String) => prompt + "\nContext note: the application runs in a staging environment."),
    "boundary-first" -> ((prompt: String) => moveBoundaryFirst(prompt)),
    "synonym-transition" -> ((prompt: String) => prompt.replace("Required value transition", "Required value change"))
  )

  def systemHash: String = sha256(system)

  def evaluate(
      client: OllamaClient,
      config: Config,
      catalog: Vector[Agent],
      intent: EngineerIntent,
      prompt: String
  ): PlanEvaluation =
    val trials = Vector.tabulate(config.planTrials) { index =>
      val (perturbationName, transform) = perturbations(index % perturbations.size)
      val seed = 13007 + index * 149
      val result = Try {
        client.generateJson(
          system = system,
          prompt = transform(prompt),
          schema = Schemas.actionPlan(catalog.map(_.id)),
          seed = seed
        )
      }

      result match
        case scala.util.Success(response) =>
          Try(ActionPlan.fromJson(response.content)) match
            case scala.util.Success(plan) =>
              val deviations = Ratifier.checkPlan(plan, intent)
              PlanTrial(index, seed, perturbationName, Some(plan), deviations, deviations.isEmpty)
            case scala.util.Failure(error) =>
              val deviations = Vector(Deviation("invalid-action-plan", errorMessage(error)))
              PlanTrial(index, seed, perturbationName, None, deviations, pass = false)
        case scala.util.Failure(error) =>
          val deviations = Vector(Deviation("ollama-plan-call", errorMessage(error)))
          PlanTrial(index, seed, perturbationName, None, deviations, pass = false)
    }

    val passed = trials.count(_.pass)
    val (low, high) = Stats.wilson(passed, trials.size)
    val activeCodes = Set(
      "plan-owner",
      "plan-write-set",
      "plan-missing-write",
      "plan-forbidden-effect",
      "self-ratification"
    )

    PlanEvaluation(
      passRate = if trials.isEmpty then 0.0 else passed.toDouble / trials.size.toDouble,
      passed = passed,
      total = trials.size,
      wilsonLow = low,
      wilsonHigh = high,
      activeViolations = trials.count(_.deviations.exists(deviation => activeCodes.contains(deviation.code))),
      trials = trials
    )

  private def moveBoundaryFirst(prompt: String): String =
    val marker = "Forbidden effects"
    val index = prompt.indexOf(marker)
    if index < 0 then prompt
    else
      val before = prompt.substring(0, index).trim
      val after = prompt.substring(index).trim
      s"$after\n\n$before"

object FactorialStudy:
  def run(
      client: OllamaClient,
      config: Config,
      initialCatalog: Vector[Agent],
      repairedCatalog: Vector[Agent],
      intent: EngineerIntent,
      expandedRoutingQuery: String
  ): FactorialResult =
    val rawRequest =
      s"Upgrade ${intent.coordinate} from ${intent.fromVersion} to ${intent.toVersion}."

    val definitions = Vector(
      ("q0-c0", false, false, rawRequest, initialCatalog),
      ("q1-c0", true, false, expandedRoutingQuery, initialCatalog),
      ("q0-c1", false, true, rawRequest, repairedCatalog),
      ("q1-c1", true, true, expandedRoutingQuery, repairedCatalog)
    )

    val cells = definitions.map { case (id, queryExpansion, catalogRepair, prompt, catalog) =>
      val test = RouteTest(
        id = s"factorial-$id",
        kind = "factorial",
        requests = Vector(prompt),
        expected = intent.expectedOwner,
        mustNot = Set.empty,
        protectedCase = false
      )
      val route = Router.evaluate(client, config, catalog, Vector(test)).cases.head
      FactorialCell(
        id = id,
        queryExpansion = queryExpansion,
        catalogRepair = catalogRepair,
        promptHash = sha256(prompt),
        catalogHash = sha256(catalog.map(agent => s"${agent.id}|${agent.description}").mkString("||")),
        route = route
      )
    }

    def rate(id: String): Double =
      cells.find(_.id == id).map(_.route.expectedRate).getOrElse(0.0)

    val queryWithoutCatalog = rate("q1-c0") - rate("q0-c0")
    val queryWithCatalog = rate("q1-c1") - rate("q0-c1")
    val catalogWithoutQuery = rate("q0-c1") - rate("q0-c0")
    val catalogWithQuery = rate("q1-c1") - rate("q1-c0")

    FactorialResult(
      cells = cells,
      queryEffectWithoutCatalogRepair = queryWithoutCatalog,
      queryEffectWithCatalogRepair = queryWithCatalog,
      catalogEffectWithoutQueryExpansion = catalogWithoutQuery,
      catalogEffectWithQueryExpansion = catalogWithQuery,
      interaction = queryWithCatalog - queryWithoutCatalog
    )
