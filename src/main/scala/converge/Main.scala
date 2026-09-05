package converge

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try
import ujson.Value

object ConcreteOracle:
  def run(config: Config, intent: EngineerIntent): ConcreteOracleResult =
    if !config.runConcreteOracle then
      ConcreteOracleResult(
        attempted = false,
        repository = config.repository.map(_.toString),
        changedPaths = Vector.empty,
        writeSetPass = false,
        targetCoordinatePass = false,
        commandRun = false,
        commandExit = None,
        commandOutputHash = None,
        error = None
      )
    else
      val repository = config.repository.get
      Try(runChecked(repository, intent)).recover { case error =>
        ConcreteOracleResult(
          attempted = true,
          repository = Some(repository.toString),
          changedPaths = Vector.empty,
          writeSetPass = false,
          targetCoordinatePass = false,
          commandRun = false,
          commandExit = None,
          commandOutputHash = None,
          error = Some(errorMessage(error))
        )
      }.get

  private def runChecked(repository: Path, intent: EngineerIntent): ConcreteOracleResult =
    if !Files.isDirectory(repository) then
      throw new IllegalStateException(s"Target repository does not exist or is not a directory: $repository")

    val (gitExit, gitOutput) = runProcess(Vector("git", "diff", "--name-only"), repository)
    if gitExit != 0 then
      throw new IllegalStateException(s"git diff failed in $repository: ${gitOutput.take(1000)}")

    val changedPaths = gitOutput.linesIterator.map(_.trim).filter(_.nonEmpty).toVector.distinct
    val writeSetPass =
      changedPaths.nonEmpty &&
      changedPaths.forall(path => intent.allowedWrites.exists(pattern => PathPolicy.matches(pattern, path))) &&
      intent.allowedWrites.forall(required => changedPaths.exists(path => PathPolicy.matches(required, path) || PathPolicy.matches(path, required)))

    val primaryArtifact = repository.resolve(intent.artifactLocators.head).normalize()
    if !primaryArtifact.startsWith(repository.normalize()) then
      throw new IllegalStateException(s"Artifact locator escapes the target repository: ${intent.artifactLocators.head}")

    val targetCoordinatePass =
      Files.isRegularFile(primaryArtifact) && coordinateTransitionPresent(primaryArtifact, intent)

    val command = intent.validationCommands.head
    val (commandExit, commandOutput) = runShell(command, repository)

    ConcreteOracleResult(
      attempted = true,
      repository = Some(repository.toString),
      changedPaths = changedPaths,
      writeSetPass = writeSetPass,
      targetCoordinatePass = targetCoordinatePass,
      commandRun = true,
      commandExit = Some(commandExit),
      commandOutputHash = Some(sha256(commandOutput)),
      error = None
    )


  private def coordinateTransitionPresent(path: Path, intent: EngineerIntent): Boolean =
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    val tokens = "[A-Za-z0-9_.-]+".r.findAllIn(intent.coordinate).toVector.filter(_.length >= 2)

    def belongsToCoordinate(line: String): Boolean =
      val normalizedLine = line.toLowerCase
      tokens.nonEmpty && tokens.forall(token => normalizedLine.contains(token.toLowerCase))

    val coordinateLines = lines.asScala.toVector.filter(belongsToCoordinate)
    coordinateLines.exists(_.contains(intent.toVersion)) && !coordinateLines.exists(_.contains(intent.fromVersion))

  private def runProcess(command: Vector[String], cwd: Path): (Int, String) =
    val builder = new ProcessBuilder(command.toArray*)
      .directory(cwd.toFile)
      .redirectErrorStream(true)
    val process = builder.start()
    val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val exit = process.waitFor()
    (exit, output)

  private def runShell(command: String, cwd: Path): (Int, String) =
    val windows = System.getProperty("os.name", "").toLowerCase.contains("win")
    val invocation =
      if windows then Vector("cmd.exe", "/c", command)
      else Vector("/bin/sh", "-lc", command)
    runProcess(invocation, cwd)

object Main:
  private val HarnessVersion = "0.4.0"

  def main(args: Array[String]): Unit =
    if args.contains("--help") || args.contains("-h") then
      printHelp()
      return

    val config = Config.fromEnvironment()
    val inputs = ExperimentInputs.load(config.configDir, config.testsDir)

    if args.contains("--validate-only") then
      println("CONVERGE configuration validation passed.")
      println(s"EngineerIntent owner: ${inputs.intent.expectedOwner}")
      println(s"Development routing cases: ${inputs.developmentTests.size}")
      println(s"Held-out routing cases: ${inputs.confirmationTests.size}")
      println(s"Config directory: ${config.configDir}")
      println(s"Tests directory: ${config.testsDir}")
      return

    runExperiment(config, inputs)

  private def runExperiment(config: Config, inputs: ExperimentInputs): Unit =
    Files.createDirectories(config.outputDir)
    val started = Instant.now()
    val intent = inputs.intent
    val client = new OllamaClient(config)

    println(s"CONVERGE Offline $HarnessVersion")
    println(s"Generative model: ${config.model}")
    println(s"Embedding model: ${config.embeddingModel}")
    println(s"Ollama API: ${config.baseUrl}")

    val ollamaVersion = client.serverVersion()
    val modelDigest = client.requireModelInstalled()
    val embeddingDigest =
      if config.runAnchorDiscovery then Some(client.requireModelInstalled(config.embeddingModel))
      else None

    println(s"Ollama version: $ollamaVersion")
    println(s"Generative model digest: $modelDigest")
    embeddingDigest.foreach(value => println(s"Embedding model digest: $value"))

    println("Evaluating and repairing the development routing catalog...")
    val catalogRepair = CatalogRepair.repair(
      client = client,
      config = config,
      initialCatalog = inputs.initialCatalog,
      tests = inputs.developmentTests
    )
    val finalCatalog = catalogRepair.catalog

    println(s"Initial development routing accuracy: ${format4(catalogRepair.initial.accuracy)}")
    println(s"Final development routing accuracy: ${format4(catalogRepair.finalEvaluation.accuracy)}")
    println(s"Accepted catalog edits: ${catalogRepair.acceptedSteps.size}")

    println("Compiling EngineerIntent into a ratified TaskIR proposal...")
    val compilation = PromptCompiler.compile(client, config, finalCatalog, intent)

    if !compilation.accepted then
      val report = rejectedReport(
        config,
        inputs,
        ollamaVersion,
        modelDigest,
        embeddingDigest,
        started,
        client.usage,
        catalogRepair,
        compilation
      )
      writeRejectedOutputs(config.outputDir, report, finalCatalog, compilation)
      val reportPath = config.outputDir.resolve("report.json")
      throw new IllegalStateException(s"No TaskIR proposal passed ratification. See $reportPath")

    val task = compilation.task.get

    val anchorDiscovery =
      if config.runAnchorDiscovery then
        println("Running embedding and behavioral anchor discovery...")
        AnchorDiscovery.discover(client, config, finalCatalog, intent, inputs.developmentTests)
      else
        AnchorDiscoveryResult(
          evaluations = Vector.empty,
          selectedPositive = intent.positiveAnchors,
          generatedCandidates = Vector.empty
        )

    val anchorOverride =
      if config.runAnchorDiscovery then Some(anchorDiscovery.selectedPositive)
      else None

    val routingRequest = PromptRenderer.renderRoutingQuery(task, anchorOverride)
    val computedPrompt = PromptRenderer.render(task, anchorOverride)

    println("Constructing comparison prompts...")
    val genericPrompt = GenericPrompt.generate(client, intent, seed = 17011)
    val expertPrompt =
      s"In ${intent.artifactLocators.mkString(", ")}, update ${intent.coordinate} from ${intent.fromVersion} to ${intent.toVersion}. " +
      s"Modify only ${intent.allowedWrites.mkString(", ")}. Do not modify ${intent.forbiddenWrites.mkString(", ")}. " +
      s"Run ${intent.validationCommands.mkString("; ")}. Return ${intent.requiredEvidence.mkString(", ")}."

    val variants = Vector(
      PromptVariant("vague-seed", intent.seed, intent.seed),
      PromptVariant("manuscript-expert", expertPrompt, expertPrompt),
      PromptVariant("generic-llm-rewrite", genericPrompt, genericPrompt),
      PromptVariant("full-converge", routingRequest, computedPrompt)
    )

    val variantEvaluations = variants.map { variant =>
      println(s"Evaluating prompt variant ${variant.id}...")
      val primaryTest = RouteTest(
        id = s"primary-${variant.id}",
        kind = "primary",
        requests = Vector(variant.routingRequest),
        expected = intent.expectedOwner,
        mustNot = Set.empty,
        protectedCase = false
      )
      val route = Router.evaluate(client, config, finalCatalog, Vector(primaryTest)).cases.head
      val plan = PlanProbe.evaluate(client, config, finalCatalog, intent, variant.agentPrompt)
      VariantEvaluation(
        id = variant.id,
        routingRequestHash = sha256(variant.routingRequest),
        agentPromptHash = sha256(variant.agentPrompt),
        agentPromptLength = variant.agentPrompt.length,
        primaryRoute = route,
        plan = plan
      )
    }

    println("Running the 2 by 2 query-expansion versus catalog-repair study...")
    val factorial = FactorialStudy.run(
      client = client,
      config = config,
      initialCatalog = inputs.initialCatalog,
      repairedCatalog = finalCatalog,
      intent = intent,
      expandedRoutingQuery = routingRequest
    )

    println("Evaluating the held-out routing confirmation suite...")
    val confirmation = Router.evaluate(client, config, finalCatalog, inputs.confirmationTests)
    val confirmationPasses = confirmation.cases.count(_.pass)
    val confirmationInterval = Stats.wilson(confirmationPasses, confirmation.cases.size)

    val concrete = ConcreteOracle.run(config, intent)
    val finished = Instant.now()

    val report = completedReport(
      config = config,
      inputs = inputs,
      ollamaVersion = ollamaVersion,
      modelDigest = modelDigest,
      embeddingDigest = embeddingDigest,
      started = started,
      finished = finished,
      usage = client.usage,
      catalogRepair = catalogRepair,
      compilation = compilation,
      variants = variantEvaluations,
      factorial = factorial,
      confirmation = confirmation,
      confirmationInterval = confirmationInterval,
      anchorDiscovery = anchorDiscovery,
      concrete = concrete
    )

    writeCompletedOutputs(
      outputDir = config.outputDir,
      report = report,
      prompt = computedPrompt,
      routingRequest = routingRequest,
      task = task,
      finalCatalog = finalCatalog,
      variants = variantEvaluations,
      anchors = anchorDiscovery
    )

    val full = variantEvaluations.find(_.id == "full-converge").get
    println(s"Full CONVERGE expected-owner rate: ${format4(full.primaryRoute.expectedRate)}")
    println(s"Full CONVERGE routing margin: ${format4(full.primaryRoute.margin)}")
    println(s"Full CONVERGE simulated-plan pass rate: ${format4(full.plan.passRate)}")
    println(s"Held-out confirmation accuracy: ${format4(confirmation.accuracy)}")
    println(s"Output directory: ${config.outputDir}")

  private def completedReport(
      config: Config,
      inputs: ExperimentInputs,
      ollamaVersion: String,
      modelDigest: String,
      embeddingDigest: Option[String],
      started: Instant,
      finished: Instant,
      usage: Usage,
      catalogRepair: CatalogRepairResult,
      compilation: CompilationResult,
      variants: Vector[VariantEvaluation],
      factorial: FactorialResult,
      confirmation: RouteEvaluation,
      confirmationInterval: (Double, Double),
      anchorDiscovery: AnchorDiscoveryResult,
      concrete: ConcreteOracleResult
  ): Value =
    val full = variants.find(_.id == "full-converge").get
    val developmentPass = catalogRepair.finalEvaluation.failedIds.isEmpty
    val planPass = full.plan.passRate >= config.minExpectedRate && full.plan.activeViolations == 0
    val confirmationPass = confirmation.failedIds.isEmpty
    val suiteRelativeFixpoint =
      developmentPass && compilation.accepted && full.primaryRoute.pass && planPass && confirmationPass

    ujson.Obj(
      "status" -> "completed",
      "harnessVersion" -> HarnessVersion,
      "startedAt" -> started.toString,
      "finishedAt" -> finished.toString,
      "manifest" -> manifestJson(config, inputs, ollamaVersion, modelDigest, embeddingDigest),
      "usage" -> usageJson(usage),
      "catalogRepair" -> catalogRepairJson(catalogRepair),
      "promptCompilation" -> compilationJson(compilation),
      "anchorDiscovery" -> anchorDiscoveryJson(anchorDiscovery, config.runAnchorDiscovery),
      "variants" -> Json.values(variants.map(variantJson)),
      "factorial" -> factorialJson(factorial),
      "confirmation" -> ujson.Obj(
        "evaluation" -> routeEvaluationJson(confirmation),
        "wilson95" -> ujson.Arr(confirmationInterval._1, confirmationInterval._2)
      ),
      "concreteOracle" -> concreteJson(concrete),
      "certificate" -> ujson.Obj(
        "suiteRelativeBoundedEmpiricalFixpoint" -> suiteRelativeFixpoint,
        "developmentRoutingPass" -> developmentPass,
        "taskIRRatificationPass" -> compilation.accepted,
        "primaryRoutingPass" -> full.primaryRoute.pass,
        "simulatedPlanPass" -> planPass,
        "heldOutConfirmationPass" -> confirmationPass,
        "claim" -> "This certificate is relative to the recorded model digests, inputs, routing suites, thresholds, and finite perturbation schedule. It is not a universal proof for arbitrary models or future requests."
      )
    )

  private def rejectedReport(
      config: Config,
      inputs: ExperimentInputs,
      ollamaVersion: String,
      modelDigest: String,
      embeddingDigest: Option[String],
      started: Instant,
      usage: Usage,
      catalogRepair: CatalogRepairResult,
      compilation: CompilationResult
  ): Value =
    ujson.Obj(
      "status" -> "rejected",
      "harnessVersion" -> HarnessVersion,
      "startedAt" -> started.toString,
      "manifest" -> manifestJson(config, inputs, ollamaVersion, modelDigest, embeddingDigest),
      "usage" -> usageJson(usage),
      "catalogRepair" -> catalogRepairJson(catalogRepair),
      "promptCompilation" -> compilationJson(compilation),
      "claim" -> "No computed prompt was admitted because no LLM-proposed TaskIR passed the external Scala ratifier."
    )

  private def manifestJson(
      config: Config,
      inputs: ExperimentInputs,
      ollamaVersion: String,
      modelDigest: String,
      embeddingDigest: Option[String]
  ): Value =
    ujson.Obj(
      "generativeModel" -> config.model,
      "generativeModelDigest" -> modelDigest,
      "embeddingModel" -> config.embeddingModel,
      "embeddingModelDigest" -> embeddingDigest.getOrElse("not-used"),
      "ollamaVersion" -> ollamaVersion,
      "ollamaBaseUrl" -> config.baseUrl,
      "scalaVersion" -> scala.util.Properties.versionNumberString,
      "javaVersion" -> System.getProperty("java.version"),
      "osName" -> System.getProperty("os.name"),
      "configDir" -> config.configDir.toString,
      "testsDir" -> config.testsDir.toString,
      "outputDir" -> config.outputDir.toString,
      "routeTrials" -> config.routeTrials,
      "planTrials" -> config.planTrials,
      "compilerAttempts" -> config.compilerAttempts,
      "catalogCandidates" -> config.catalogCandidates,
      "maxCatalogEdits" -> config.maxCatalogEdits,
      "temperature" -> config.temperature,
      "topP" -> config.topP,
      "numCtx" -> config.numCtx,
      "minExpectedRate" -> config.minExpectedRate,
      "minMargin" -> config.minMargin,
      "runAnchorDiscovery" -> config.runAnchorDiscovery,
      "generateCandidatePhrases" -> config.generateCandidatePhrases,
      "anchorGamma" -> config.anchorGamma,
      "minGeometricMargin" -> config.minGeometricMargin,
      "minBehavioralScore" -> config.minBehavioralScore,
      "maxSelectedAnchors" -> config.maxSelectedAnchors,
      "anchorRedundancyThreshold" -> config.anchorRedundancyThreshold,
      "inputHashes" -> Json.stringMap(inputs.sourceHashes.toVector.sortBy(_._1)),
      "routerSystemHash" -> Router.systemHash,
      "compilerSystemHash" -> PromptCompiler.systemHash,
      "planProbeSystemHash" -> PlanProbe.systemHash
    )

  private def usageJson(usage: Usage): Value =
    ujson.Obj(
      "calls" -> usage.calls,
      "totalDurationNs" -> usage.totalDurationNs,
      "loadDurationNs" -> usage.loadDurationNs,
      "promptTokens" -> usage.promptTokens,
      "generatedTokens" -> usage.generatedTokens
    )

  private def compilationJson(result: CompilationResult): Value =
    ujson.Obj(
      "accepted" -> result.accepted,
      "task" -> result.task.map(task => TaskIR.toJson(task)).getOrElse(ujson.Null),
      "attempts" -> Json.values(result.attempts.map { attempt =>
        ujson.Obj(
          "index" -> attempt.index,
          "seed" -> attempt.seed,
          "rawResponseHash" -> attempt.rawResponseHash,
          "task" -> attempt.task.map(task => TaskIR.toJson(task)).getOrElse(ujson.Null),
          "deviations" -> Json.values(attempt.deviations.map(deviationJson))
        )
      })
    )

  private def catalogRepairJson(result: CatalogRepairResult): Value =
    ujson.Obj(
      "initial" -> routeEvaluationJson(result.initial),
      "final" -> routeEvaluationJson(result.finalEvaluation),
      "rawCandidates" -> result.rawCandidates,
      "localFixpoint" -> result.localFixpoint,
      "acceptedSteps" -> Json.values(result.acceptedSteps.map { step =>
        ujson.Obj(
          "acceptedIndex" -> step.acceptedIndex,
          "witnessTest" -> step.witnessTest,
          "expectedAgent" -> step.expectedAgent,
          "wrongAgent" -> step.wrongAgent,
          "atoms" -> Json.arr(step.atoms),
          "beforeFailed" -> Json.arr(step.beforeFailed),
          "afterFailed" -> Json.arr(step.afterFailed),
          "beforeMeanMargin" -> step.beforeMeanMargin,
          "afterMeanMargin" -> step.afterMeanMargin
        )
      })
    )

  private def routeEvaluationJson(evaluation: RouteEvaluation): Value =
    ujson.Obj(
      "accuracy" -> evaluation.accuracy,
      "hardBoundaryErrorRate" -> evaluation.hardBoundaryErrorRate,
      "minimumMargin" -> evaluation.minimumMargin,
      "meanMargin" -> evaluation.meanMargin,
      "routeFlipRate" -> evaluation.routeFlipRate,
      "failedIds" -> Json.arr(evaluation.failedIds),
      "cases" -> Json.values(evaluation.cases.map(routeCaseJson))
    )

  private def routeCaseJson(result: RouteCaseResult): Value =
    ujson.Obj(
      "testId" -> result.testId,
      "expected" -> result.expected,
      "winner" -> result.winner,
      "expectedRate" -> result.expectedRate,
      "strongestRival" -> result.strongestRival,
      "strongestRivalRate" -> result.strongestRivalRate,
      "margin" -> result.margin,
      "forbiddenSelections" -> result.forbiddenSelections,
      "pass" -> result.pass,
      "trials" -> Json.values(result.trials.map { trial =>
        ujson.Obj(
          "scheduleIndex" -> trial.scheduleIndex,
          "request" -> trial.request,
          "selected" -> trial.selected,
          "expected" -> trial.expected,
          "pass" -> trial.pass,
          "seed" -> trial.seed,
          "orderShift" -> trial.orderShift
        )
      })
    )

  private def planJson(plan: PlanEvaluation): Value =
    ujson.Obj(
      "passRate" -> plan.passRate,
      "passed" -> plan.passed,
      "total" -> plan.total,
      "wilson95" -> ujson.Arr(plan.wilsonLow, plan.wilsonHigh),
      "activeViolations" -> plan.activeViolations,
      "trials" -> Json.values(plan.trials.map { trial =>
        ujson.Obj(
          "index" -> trial.index,
          "seed" -> trial.seed,
          "perturbation" -> trial.perturbation,
          "pass" -> trial.pass,
          "plan" -> trial.plan.map(actionPlanJson).getOrElse(ujson.Null),
          "deviations" -> Json.values(trial.deviations.map(deviationJson))
        )
      })
    )

  private def variantJson(variant: VariantEvaluation): Value =
    ujson.Obj(
      "id" -> variant.id,
      "routingRequestHash" -> variant.routingRequestHash,
      "agentPromptHash" -> variant.agentPromptHash,
      "agentPromptLength" -> variant.agentPromptLength,
      "primaryRoute" -> routeCaseJson(variant.primaryRoute),
      "plan" -> planJson(variant.plan)
    )

  private def factorialJson(result: FactorialResult): Value =
    ujson.Obj(
      "queryEffectWithoutCatalogRepair" -> result.queryEffectWithoutCatalogRepair,
      "queryEffectWithCatalogRepair" -> result.queryEffectWithCatalogRepair,
      "catalogEffectWithoutQueryExpansion" -> result.catalogEffectWithoutQueryExpansion,
      "catalogEffectWithQueryExpansion" -> result.catalogEffectWithQueryExpansion,
      "interaction" -> result.interaction,
      "cells" -> Json.values(result.cells.map { cell =>
        ujson.Obj(
          "id" -> cell.id,
          "queryExpansion" -> cell.queryExpansion,
          "catalogRepair" -> cell.catalogRepair,
          "promptHash" -> cell.promptHash,
          "catalogHash" -> cell.catalogHash,
          "route" -> routeCaseJson(cell.route)
        )
      })
    )

  private def anchorDiscoveryJson(result: AnchorDiscoveryResult, enabled: Boolean): Value =
    ujson.Obj(
      "enabled" -> enabled,
      "generatedCandidates" -> Json.arr(result.generatedCandidates),
      "selectedPositive" -> Json.arr(result.selectedPositive),
      "evaluations" -> Json.values(result.evaluations.map(anchorJson))
    )

  private def anchorJson(anchor: AnchorResult): Value =
    ujson.Obj(
      "phrase" -> anchor.phrase.text,
      "source" -> anchor.phrase.source,
      "eligibleForPositiveSelection" -> anchor.phrase.eligibleForPositiveSelection,
      "targetSimilarity" -> anchor.targetSimilarity,
      "strongestRival" -> anchor.strongestRival,
      "rivalSimilarity" -> anchor.rivalSimilarity,
      "geometricMargin" -> anchor.geometricMargin,
      "targetMassGain" -> anchor.targetMassGain,
      "spill" -> anchor.spill,
      "discriminativeScore" -> anchor.discriminativeScore,
      "selected" -> anchor.selected
    )

  private def actionPlanJson(plan: ActionPlan): Value =
    ujson.Obj(
      "selectedOwner" -> plan.selectedOwner,
      "writes" -> Json.arr(plan.writes),
      "commands" -> Json.arr(plan.commands),
      "effects" -> Json.arr(plan.effects),
      "evidence" -> Json.arr(plan.evidence),
      "handoff" -> Json.arr(plan.handoff),
      "claimsSuccessWithoutEvidence" -> plan.claimsSuccessWithoutEvidence
    )

  private def concreteJson(result: ConcreteOracleResult): Value =
    ujson.Obj(
      "attempted" -> result.attempted,
      "repository" -> result.repository.getOrElse(""),
      "changedPaths" -> Json.arr(result.changedPaths),
      "writeSetPass" -> result.writeSetPass,
      "targetCoordinatePass" -> result.targetCoordinatePass,
      "commandRun" -> result.commandRun,
      "commandExit" -> result.commandExit.map(value => ujson.Num(value.toDouble)).getOrElse(ujson.Null),
      "commandOutputHash" -> result.commandOutputHash.getOrElse(""),
      "error" -> result.error.getOrElse("")
    )

  private def deviationJson(deviation: Deviation): Value =
    ujson.Obj("code" -> deviation.code, "detail" -> deviation.detail)

  private def writeCompletedOutputs(
      outputDir: Path,
      report: Value,
      prompt: String,
      routingRequest: String,
      task: TaskIR,
      finalCatalog: Vector[Agent],
      variants: Vector[VariantEvaluation],
      anchors: AnchorDiscoveryResult
  ): Unit =
    Files.createDirectories(outputDir)
    write(outputDir.resolve("computed-prompt.txt"), prompt + "\n")
    write(outputDir.resolve("computed-routing-request.txt"), routingRequest + "\n")
    write(outputDir.resolve("task-ir.json"), ujson.write(TaskIR.toJson(task), indent = 2) + "\n")
    write(outputDir.resolve("final-catalog.json"), ujson.write(agentsJson(finalCatalog), indent = 2) + "\n")
    write(outputDir.resolve("report.json"), ujson.write(report, indent = 2) + "\n")
    write(outputDir.resolve("summary.csv"), summaryCsv(variants))
    write(outputDir.resolve("anchors.csv"), anchorsCsv(anchors))

  private def writeRejectedOutputs(
      outputDir: Path,
      report: Value,
      finalCatalog: Vector[Agent],
      compilation: CompilationResult
  ): Unit =
    Files.createDirectories(outputDir)
    write(outputDir.resolve("report.json"), ujson.write(report, indent = 2) + "\n")
    write(outputDir.resolve("final-catalog.json"), ujson.write(agentsJson(finalCatalog), indent = 2) + "\n")
    compilation.task.foreach(task => write(outputDir.resolve("task-ir.json"), ujson.write(TaskIR.toJson(task), indent = 2) + "\n"))

  private def agentsJson(catalog: Vector[Agent]): Value =
    Json.values(catalog.map { agent =>
      ujson.Obj(
        "id" -> agent.id,
        "description" -> agent.description,
        "ownedArtifacts" -> Json.arr(agent.ownedArtifacts),
        "writablePaths" -> Json.arr(agent.writablePaths),
        "forbiddenEffects" -> Json.arr(agent.forbiddenEffects)
      )
    })

  private def summaryCsv(variants: Vector[VariantEvaluation]): String =
    val header = "variant,expected_rate,margin,forbidden_selections,route_pass,plan_pass_rate,active_plan_violations,prompt_length\n"
    val rows = variants.map { variant =>
      Vector(
        variant.id,
        format4(variant.primaryRoute.expectedRate),
        format4(variant.primaryRoute.margin),
        variant.primaryRoute.forbiddenSelections.toString,
        variant.primaryRoute.pass.toString,
        format4(variant.plan.passRate),
        variant.plan.activeViolations.toString,
        variant.agentPromptLength.toString
      ).map(csvEscape).mkString(",")
    }
    header + rows.mkString("\n") + "\n"

  private def anchorsCsv(result: AnchorDiscoveryResult): String =
    val header = "phrase,source,eligible,selected,target_similarity,strongest_rival,rival_similarity,geometric_margin,target_mass_gain,spill,discriminative_score\n"
    val rows = result.evaluations.map { anchor =>
      Vector(
        anchor.phrase.text,
        anchor.phrase.source,
        anchor.phrase.eligibleForPositiveSelection.toString,
        anchor.selected.toString,
        format4(anchor.targetSimilarity),
        anchor.strongestRival,
        format4(anchor.rivalSimilarity),
        format4(anchor.geometricMargin),
        format4(anchor.targetMassGain),
        format4(anchor.spill),
        format4(anchor.discriminativeScore)
      ).map(csvEscape).mkString(",")
    }
    header + rows.mkString("\n") + (if rows.nonEmpty then "\n" else "")

  private def csvEscape(value: String): String =
    if value.exists(character => character == ',' || character == '"' || character == '\n' || character == '\r') then
      "\"" + value.replace("\"", "\"\"") + "\""
    else value

  private def write(path: Path, text: String): Unit =
    Files.writeString(path, text, StandardCharsets.UTF_8)

  private def printHelp(): Unit =
    println(
      """CONVERGE Offline
        |
        |Usage
        |  sbt run
        |  sbt "run --validate-only"
        |  sbt "run --help"
        |
        |The normal run reads config/ and tests/, calls local Ollama models, and writes results under CONVERGE_OUT.
        |Use --validate-only to validate the JSON inputs and authority relationships without contacting Ollama.
        |""".stripMargin
    )
