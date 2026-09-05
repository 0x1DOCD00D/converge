package converge

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import ujson.Value

final case class Config(
    baseUrl: String,
    model: String,
    embeddingModel: String,
    routeTrials: Int,
    planTrials: Int,
    compilerAttempts: Int,
    catalogCandidates: Int,
    maxCatalogEdits: Int,
    temperature: Double,
    topP: Double,
    numCtx: Int,
    minExpectedRate: Double,
    minMargin: Double,
    runAnchorDiscovery: Boolean,
    generateCandidatePhrases: Boolean,
    candidateSuggestions: Int,
    anchorGamma: Double,
    minGeometricMargin: Double,
    minBehavioralScore: Double,
    maxSelectedAnchors: Int,
    anchorRedundancyThreshold: Double,
    configDir: Path,
    testsDir: Path,
    outputDir: Path,
    runConcreteOracle: Boolean,
    repository: Option[Path]
)

object Config:
  def fromEnvironment(): Config =
    val env = sys.env

    def int(name: String, default: Int): Int =
      env.get(name).flatMap(_.toIntOption).getOrElse(default)

    def double(name: String, default: Double): Double =
      env.get(name).flatMap(_.toDoubleOption).getOrElse(default)

    def bool(name: String, default: Boolean): Boolean =
      env.get(name).map(_.trim.toLowerCase).collect {
        case "1" | "true" | "yes" | "on"  => true
        case "0" | "false" | "no" | "off" => false
      }.getOrElse(default)

    val config = Config(
      baseUrl = env.getOrElse("OLLAMA_URL", "http://localhost:11434/api").stripSuffix("/"),
      model = env.getOrElse("OLLAMA_MODEL", "qwen3:8b"),
      embeddingModel = env.getOrElse("CONVERGE_EMBEDDING_MODEL", "qwen3-embedding"),
      routeTrials = math.max(3, int("CONVERGE_ROUTE_TRIALS", 5)),
      planTrials = math.max(3, int("CONVERGE_PLAN_TRIALS", 5)),
      compilerAttempts = math.max(1, int("CONVERGE_COMPILER_ATTEMPTS", 4)),
      catalogCandidates = math.max(1, int("CONVERGE_CATALOG_CANDIDATES", 2)),
      maxCatalogEdits = math.max(0, int("CONVERGE_MAX_CATALOG_EDITS", 2)),
      temperature = math.max(0.0, double("CONVERGE_TEMPERATURE", 0.2)),
      topP = clamp01(double("CONVERGE_TOP_P", 0.9)),
      numCtx = math.max(4096, int("CONVERGE_NUM_CTX", 8192)),
      minExpectedRate = clamp01(double("CONVERGE_MIN_EXPECTED_RATE", 0.70)),
      minMargin = math.max(-1.0, math.min(1.0, double("CONVERGE_MIN_MARGIN", 0.20))),
      runAnchorDiscovery = bool("CONVERGE_RUN_ANCHORS", false),
      generateCandidatePhrases = bool("CONVERGE_GENERATE_CANDIDATES", true),
      candidateSuggestions = math.max(0, int("CONVERGE_CANDIDATE_SUGGESTIONS", 12)),
      anchorGamma = math.max(0.0, double("CONVERGE_ANCHOR_GAMMA", 2.0)),
      minGeometricMargin = double("CONVERGE_MIN_GEOMETRIC_MARGIN", 0.0),
      minBehavioralScore = double("CONVERGE_MIN_BEHAVIORAL_SCORE", 0.0),
      maxSelectedAnchors = math.max(1, int("CONVERGE_MAX_SELECTED_ANCHORS", 6)),
      anchorRedundancyThreshold = clamp01(double("CONVERGE_ANCHOR_REDUNDANCY_THRESHOLD", 0.92)),
      configDir = Paths.get(env.getOrElse("CONVERGE_CONFIG_DIR", "config")).toAbsolutePath.normalize(),
      testsDir = Paths.get(env.getOrElse("CONVERGE_TESTS_DIR", "tests")).toAbsolutePath.normalize(),
      outputDir = Paths.get(env.getOrElse("CONVERGE_OUT", "out/run-latest")).toAbsolutePath.normalize(),
      runConcreteOracle = bool("CONVERGE_RUN_ORACLE", false),
      repository = env.get("CONVERGE_REPO").filter(_.trim.nonEmpty).map(value => Paths.get(value).toAbsolutePath.normalize())
    )

    if config.runConcreteOracle && config.repository.isEmpty then
      throw new IllegalArgumentException("CONVERGE_RUN_ORACLE=true requires CONVERGE_REPO to name the target repository")

    config

  private def clamp01(value: Double): Double = math.max(0.0, math.min(1.0, value))

final case class Usage(
    calls: Long = 0L,
    totalDurationNs: Long = 0L,
    loadDurationNs: Long = 0L,
    promptTokens: Long = 0L,
    generatedTokens: Long = 0L
):
  def +(other: Usage): Usage =
    Usage(
      calls = calls + other.calls,
      totalDurationNs = totalDurationNs + other.totalDurationNs,
      loadDurationNs = loadDurationNs + other.loadDurationNs,
      promptTokens = promptTokens + other.promptTokens,
      generatedTokens = generatedTokens + other.generatedTokens
    )

final case class LlmJson(content: Value, usage: Usage, rawResponse: String)

object Json:
  def string(value: Value, key: String): String =
    value.obj.get(key) match
      case Some(ujson.Str(v)) => v
      case Some(other) => throw new IllegalArgumentException(s"Field '$key' must be a string, found ${ujson.write(other)}")
      case None => throw new IllegalArgumentException(s"Missing required string field '$key' in ${ujson.write(value)}")

  def stringOr(value: Value, key: String, default: String): String =
    value.obj.get(key) match
      case Some(ujson.Str(v)) => v
      case Some(_) => default
      case None => default

  def strings(value: Value, key: String): Vector[String] =
    value.obj.get(key) match
      case Some(array: ujson.Arr) => array.value.toVector.map(_.str)
      case Some(other) => throw new IllegalArgumentException(s"Field '$key' must be an array of strings, found ${ujson.write(other)}")
      case None => Vector.empty

  def boolOr(value: Value, key: String, default: Boolean): Boolean =
    value.obj.get(key) match
      case Some(ujson.Bool(v)) => v
      case Some(_) => default
      case None => default

  def longOrZero(value: Value, key: String): Long =
    value.obj.get(key) match
      case Some(ujson.Num(v)) => v.toLong
      case _ => 0L

  def arr(items: Iterable[String]): ujson.Arr =
    val out = ujson.Arr()
    items.foreach(item => out.value.append(ujson.Str(item)))
    out

  def values(items: Iterable[Value]): ujson.Arr =
    val out = ujson.Arr()
    items.foreach(item => out.value.append(item))
    out

  def stringMap(fields: Iterable[(String, String)]): ujson.Obj =
    val out = ujson.Obj()
    fields.foreach { case (key, value) => out(key) = ujson.Str(value) }
    out

final case class Agent(
    id: String,
    description: String,
    ownedArtifacts: Vector[String],
    writablePaths: Vector[String],
    forbiddenEffects: Vector[String]
)

final case class EngineerIntent(
    seed: String,
    normalizedGoal: String,
    artifactClass: String,
    artifactLocators: Vector[String],
    operation: String,
    coordinate: String,
    fromVersion: String,
    toVersion: String,
    expectedOwner: String,
    allowedWrites: Vector[String],
    forbiddenWrites: Vector[String],
    allowedEffects: Vector[String],
    forbiddenEffects: Vector[String],
    positiveAnchors: Vector[String],
    negativeAnchors: Vector[String],
    validationCommands: Vector[String],
    requiredEvidence: Vector[String],
    handoffOwner: String
)

final case class TaskIR(
    normalizedGoal: String,
    artifactClass: String,
    artifactLocators: Vector[String],
    operation: String,
    coordinate: String,
    fromVersion: String,
    toVersion: String,
    expectedOwner: String,
    allowedWrites: Vector[String],
    forbiddenWrites: Vector[String],
    allowedEffects: Vector[String],
    forbiddenEffects: Vector[String],
    positiveAnchors: Vector[String],
    negativeAnchors: Vector[String],
    validationCommands: Vector[String],
    requiredEvidence: Vector[String],
    handoffOwner: String
)

object TaskIR:
  def fromJson(value: Value): TaskIR =
    TaskIR(
      normalizedGoal = Json.string(value, "normalizedGoal"),
      artifactClass = Json.string(value, "artifactClass"),
      artifactLocators = Json.strings(value, "artifactLocators"),
      operation = Json.string(value, "operation"),
      coordinate = Json.string(value, "coordinate"),
      fromVersion = Json.string(value, "fromVersion"),
      toVersion = Json.string(value, "toVersion"),
      expectedOwner = Json.string(value, "expectedOwner"),
      allowedWrites = Json.strings(value, "allowedWrites"),
      forbiddenWrites = Json.strings(value, "forbiddenWrites"),
      allowedEffects = Json.strings(value, "allowedEffects"),
      forbiddenEffects = Json.strings(value, "forbiddenEffects"),
      positiveAnchors = Json.strings(value, "positiveAnchors"),
      negativeAnchors = Json.strings(value, "negativeAnchors"),
      validationCommands = Json.strings(value, "validationCommands"),
      requiredEvidence = Json.strings(value, "requiredEvidence"),
      handoffOwner = Json.string(value, "handoffOwner")
    )

  def fromIntent(intent: EngineerIntent): TaskIR =
    TaskIR(
      normalizedGoal = intent.normalizedGoal,
      artifactClass = intent.artifactClass,
      artifactLocators = intent.artifactLocators,
      operation = intent.operation,
      coordinate = intent.coordinate,
      fromVersion = intent.fromVersion,
      toVersion = intent.toVersion,
      expectedOwner = intent.expectedOwner,
      allowedWrites = intent.allowedWrites,
      forbiddenWrites = intent.forbiddenWrites,
      allowedEffects = intent.allowedEffects,
      forbiddenEffects = intent.forbiddenEffects,
      positiveAnchors = intent.positiveAnchors,
      negativeAnchors = intent.negativeAnchors,
      validationCommands = intent.validationCommands,
      requiredEvidence = intent.requiredEvidence,
      handoffOwner = intent.handoffOwner
    )

  def toJson(task: TaskIR): Value =
    ujson.Obj(
      "normalizedGoal" -> task.normalizedGoal,
      "artifactClass" -> task.artifactClass,
      "artifactLocators" -> Json.arr(task.artifactLocators),
      "operation" -> task.operation,
      "coordinate" -> task.coordinate,
      "fromVersion" -> task.fromVersion,
      "toVersion" -> task.toVersion,
      "expectedOwner" -> task.expectedOwner,
      "allowedWrites" -> Json.arr(task.allowedWrites),
      "forbiddenWrites" -> Json.arr(task.forbiddenWrites),
      "allowedEffects" -> Json.arr(task.allowedEffects),
      "forbiddenEffects" -> Json.arr(task.forbiddenEffects),
      "positiveAnchors" -> Json.arr(task.positiveAnchors),
      "negativeAnchors" -> Json.arr(task.negativeAnchors),
      "validationCommands" -> Json.arr(task.validationCommands),
      "requiredEvidence" -> Json.arr(task.requiredEvidence),
      "handoffOwner" -> task.handoffOwner
    )

final case class Deviation(code: String, detail: String)

final case class CompilerAttempt(
    index: Int,
    seed: Int,
    task: Option[TaskIR],
    deviations: Vector[Deviation],
    rawResponseHash: String
)

final case class CompilationResult(
    accepted: Boolean,
    task: Option[TaskIR],
    attempts: Vector[CompilerAttempt]
)

final case class RouteTest(
    id: String,
    kind: String,
    requests: Vector[String],
    expected: String,
    mustNot: Set[String],
    protectedCase: Boolean
)

final case class Schedule(seed: Int, orderShift: Int, requestVariant: Int)

final case class RouteTrial(
    testId: String,
    scheduleIndex: Int,
    request: String,
    selected: String,
    expected: String,
    pass: Boolean,
    seed: Int,
    orderShift: Int
)

final case class RouteCaseResult(
    testId: String,
    expected: String,
    winner: String,
    expectedRate: Double,
    strongestRival: String,
    strongestRivalRate: Double,
    margin: Double,
    forbiddenSelections: Int,
    pass: Boolean,
    trials: Vector[RouteTrial]
)

final case class RouteEvaluation(
    cases: Vector[RouteCaseResult],
    accuracy: Double,
    hardBoundaryErrorRate: Double,
    minimumMargin: Double,
    meanMargin: Double,
    routeFlipRate: Double,
    failedIds: Vector[String]
)

final case class DescriptionEdit(
    expectedAgent: String,
    wrongAgent: String,
    boundaryAtoms: Vector[String]
)

final case class CatalogRepairStep(
    acceptedIndex: Int,
    witnessTest: String,
    expectedAgent: String,
    wrongAgent: String,
    atoms: Vector[String],
    beforeFailed: Vector[String],
    afterFailed: Vector[String],
    beforeMeanMargin: Double,
    afterMeanMargin: Double
)

final case class CatalogRepairResult(
    catalog: Vector[Agent],
    initial: RouteEvaluation,
    finalEvaluation: RouteEvaluation,
    acceptedSteps: Vector[CatalogRepairStep],
    rawCandidates: Int,
    localFixpoint: Boolean
)

final case class ActionPlan(
    selectedOwner: String,
    writes: Vector[String],
    commands: Vector[String],
    effects: Vector[String],
    evidence: Vector[String],
    handoff: Vector[String],
    claimsSuccessWithoutEvidence: Boolean
)

object ActionPlan:
  def fromJson(value: Value): ActionPlan =
    ActionPlan(
      selectedOwner = Json.string(value, "selectedOwner"),
      writes = Json.strings(value, "writes"),
      commands = Json.strings(value, "commands"),
      effects = Json.strings(value, "effects"),
      evidence = Json.strings(value, "evidence"),
      handoff = Json.strings(value, "handoff"),
      claimsSuccessWithoutEvidence = Json.boolOr(value, "claimsSuccessWithoutEvidence", true)
    )

final case class PlanTrial(
    index: Int,
    seed: Int,
    perturbation: String,
    plan: Option[ActionPlan],
    deviations: Vector[Deviation],
    pass: Boolean
)

final case class PlanEvaluation(
    passRate: Double,
    passed: Int,
    total: Int,
    wilsonLow: Double,
    wilsonHigh: Double,
    activeViolations: Int,
    trials: Vector[PlanTrial]
)

final case class PromptVariant(id: String, routingRequest: String, agentPrompt: String)

final case class VariantEvaluation(
    id: String,
    routingRequestHash: String,
    agentPromptHash: String,
    agentPromptLength: Int,
    primaryRoute: RouteCaseResult,
    plan: PlanEvaluation
)

final case class FactorialCell(
    id: String,
    queryExpansion: Boolean,
    catalogRepair: Boolean,
    promptHash: String,
    catalogHash: String,
    route: RouteCaseResult
)

final case class FactorialResult(
    cells: Vector[FactorialCell],
    queryEffectWithoutCatalogRepair: Double,
    queryEffectWithCatalogRepair: Double,
    catalogEffectWithoutQueryExpansion: Double,
    catalogEffectWithQueryExpansion: Double,
    interaction: Double
)

final case class CandidatePhrase(
    text: String,
    source: String,
    eligibleForPositiveSelection: Boolean
)

final case class AnchorResult(
    phrase: CandidatePhrase,
    targetSimilarity: Double,
    strongestRival: String,
    rivalSimilarity: Double,
    geometricMargin: Double,
    targetMassGain: Double,
    spill: Double,
    discriminativeScore: Double,
    selected: Boolean
)

final case class AnchorDiscoveryResult(
    evaluations: Vector[AnchorResult],
    selectedPositive: Vector[String],
    generatedCandidates: Vector[String]
)

final case class ConcreteOracleResult(
    attempted: Boolean,
    repository: Option[String],
    changedPaths: Vector[String],
    writeSetPass: Boolean,
    targetCoordinatePass: Boolean,
    commandRun: Boolean,
    commandExit: Option[Int],
    commandOutputHash: Option[String],
    error: Option[String]
)

final case class AuthorityRule(artifactClass: String, operation: String, owner: String)

final case class Constitution(
    requiredGlobalForbiddenEffects: Vector[String],
    requireEvidenceBeforeSuccess: Boolean,
    requireHandoffOnOutOfScopeWrite: Boolean
)

final case class ExperimentInputs(
    intent: EngineerIntent,
    initialCatalog: Vector[Agent],
    authority: Vector[AuthorityRule],
    constitution: Constitution,
    developmentTests: Vector[RouteTest],
    confirmationTests: Vector[RouteTest],
    sourceHashes: Map[String, String]
)

object ExperimentInputs:
  def load(configDir: Path, testsDir: Path): ExperimentInputs =
    val files = Vector(
      "engineer-intent.json" -> configDir.resolve("engineer-intent.json"),
      "agents.json" -> configDir.resolve("agents.json"),
      "authority.json" -> configDir.resolve("authority.json"),
      "constitution.json" -> configDir.resolve("constitution.json"),
      "routing-development.json" -> testsDir.resolve("routing-development.json"),
      "routing-confirmation.json" -> testsDir.resolve("routing-confirmation.json")
    )

    files.foreach { case (_, path) =>
      if !Files.isRegularFile(path) then
        throw new IllegalStateException(s"Required experiment input is missing: $path")
    }

    val raw = files.map { case (name, path) =>
      name -> Files.readString(path, StandardCharsets.UTF_8)
    }.toMap

    val inputs = ExperimentInputs(
      intent = parseIntent(ujson.read(raw("engineer-intent.json"))),
      initialCatalog = parseAgents(ujson.read(raw("agents.json"))),
      authority = parseAuthority(ujson.read(raw("authority.json"))),
      constitution = parseConstitution(ujson.read(raw("constitution.json"))),
      developmentTests = parseRouteTests(ujson.read(raw("routing-development.json"))),
      confirmationTests = parseRouteTests(ujson.read(raw("routing-confirmation.json"))),
      sourceHashes = raw.map { case (name, text) => name -> sha256(text) }
    )

    validate(inputs)
    inputs

  private def parseIntent(value: Value): EngineerIntent =
    EngineerIntent(
      seed = Json.string(value, "seed"),
      normalizedGoal = Json.string(value, "normalizedGoal"),
      artifactClass = Json.string(value, "artifactClass"),
      artifactLocators = Json.strings(value, "artifactLocators"),
      operation = Json.string(value, "operation"),
      coordinate = Json.string(value, "coordinate"),
      fromVersion = Json.string(value, "fromVersion"),
      toVersion = Json.string(value, "toVersion"),
      expectedOwner = Json.string(value, "expectedOwner"),
      allowedWrites = Json.strings(value, "allowedWrites"),
      forbiddenWrites = Json.strings(value, "forbiddenWrites"),
      allowedEffects = Json.strings(value, "allowedEffects"),
      forbiddenEffects = Json.strings(value, "forbiddenEffects"),
      positiveAnchors = Json.strings(value, "positiveAnchors"),
      negativeAnchors = Json.strings(value, "negativeAnchors"),
      validationCommands = Json.strings(value, "validationCommands"),
      requiredEvidence = Json.strings(value, "requiredEvidence"),
      handoffOwner = Json.string(value, "handoffOwner")
    )

  private def parseAgents(value: Value): Vector[Agent] =
    value.arr.toVector.map { item =>
      Agent(
        id = Json.string(item, "id"),
        description = Json.string(item, "description"),
        ownedArtifacts = Json.strings(item, "ownedArtifacts"),
        writablePaths = Json.strings(item, "writablePaths"),
        forbiddenEffects = Json.strings(item, "forbiddenEffects")
      )
    }

  private def parseAuthority(value: Value): Vector[AuthorityRule] =
    value.arr.toVector.map { item =>
      AuthorityRule(
        artifactClass = Json.string(item, "artifactClass"),
        operation = Json.string(item, "operation"),
        owner = Json.string(item, "owner")
      )
    }

  private def parseConstitution(value: Value): Constitution =
    Constitution(
      requiredGlobalForbiddenEffects = Json.strings(value, "requiredGlobalForbiddenEffects"),
      requireEvidenceBeforeSuccess = Json.boolOr(value, "requireEvidenceBeforeSuccess", true),
      requireHandoffOnOutOfScopeWrite = Json.boolOr(value, "requireHandoffOnOutOfScopeWrite", true)
    )

  private def parseRouteTests(value: Value): Vector[RouteTest] =
    value.arr.toVector.map { item =>
      RouteTest(
        id = Json.string(item, "id"),
        kind = Json.string(item, "kind"),
        requests = Json.strings(item, "requests"),
        expected = Json.string(item, "expected"),
        mustNot = Json.strings(item, "mustNot").toSet,
        protectedCase = Json.boolOr(item, "protectedCase", true)
      )
    }

  def validate(inputs: ExperimentInputs): Unit =
    val intent = inputs.intent
    val catalog = inputs.initialCatalog
    val ids = catalog.map(_.id)
    val idSet = ids.toSet

    val duplicateIds = ids.groupBy(id => id).collect { case (id, values) if values.size > 1 => id }.toVector.sorted
    if duplicateIds.nonEmpty then
      throw new IllegalStateException(s"Duplicate agent IDs in agents.json: ${duplicateIds.mkString(", ")}")

    if !idSet.contains(intent.expectedOwner) then
      throw new IllegalStateException(s"EngineerIntent expectedOwner '${intent.expectedOwner}' does not exist in agents.json")

    if !idSet.contains(intent.handoffOwner) then
      throw new IllegalStateException(s"EngineerIntent handoffOwner '${intent.handoffOwner}' does not exist in agents.json")

    val invalidAuthorityOwners = inputs.authority.map(_.owner).filterNot(idSet.contains).distinct.sorted
    if invalidAuthorityOwners.nonEmpty then
      throw new IllegalStateException(
        s"authority.json references unknown owners: ${invalidAuthorityOwners.mkString(", ")}"
      )

    catalog.foreach { agent =>
      if agent.description.trim.isEmpty then
        throw new IllegalStateException(s"Agent ${agent.id} has an empty router description")
      if agent.ownedArtifacts.isEmpty then
        throw new IllegalStateException(s"Agent ${agent.id} declares no owned artifact classes")
      if agent.writablePaths.isEmpty then
        throw new IllegalStateException(s"Agent ${agent.id} declares no writable paths")
    }

    if intent.artifactLocators.isEmpty then
      throw new IllegalStateException("EngineerIntent.artifactLocators must contain at least one authoritative artifact")

    if intent.allowedWrites.isEmpty then
      throw new IllegalStateException("EngineerIntent.allowedWrites must contain at least one writable artifact")

    if intent.validationCommands.isEmpty then
      throw new IllegalStateException("EngineerIntent.validationCommands must contain at least one validator")

    val authorityOwner = inputs.authority.find { rule =>
      rule.artifactClass == intent.artifactClass && rule.operation == intent.operation
    }.map(_.owner).getOrElse {
      throw new IllegalStateException(s"authority.json contains no rule for ${intent.artifactClass} / ${intent.operation}")
    }

    if authorityOwner != intent.expectedOwner then
      throw new IllegalStateException(
        s"Authority mismatch: EngineerIntent expects ${intent.expectedOwner}, but authority.json assigns $authorityOwner"
      )

    val owner = catalog.find(_.id == intent.expectedOwner).get
    if !owner.ownedArtifacts.contains(intent.artifactClass) then
      throw new IllegalStateException(
        s"Agent ${owner.id} does not declare ownership of artifact class ${intent.artifactClass}"
      )

    val unauthorizedWrites = intent.allowedWrites.filterNot { path =>
      owner.writablePaths.exists(pattern => PathPolicy.matches(pattern, path))
    }
    if unauthorizedWrites.nonEmpty then
      throw new IllegalStateException(
        s"EngineerIntent allows writes not covered by ${owner.id}: ${unauthorizedWrites.mkString(", ")}"
      )

    val intentForbiddenNormalized = intent.forbiddenEffects.map(normalize).toSet
    val missingOwnerForbiddenEffects = owner.forbiddenEffects.filterNot { effect =>
      intentForbiddenNormalized.contains(normalize(effect))
    }
    if missingOwnerForbiddenEffects.nonEmpty then
      throw new IllegalStateException(
        s"EngineerIntent omits effects forbidden by ${owner.id}: ${missingOwnerForbiddenEffects.mkString(", ")}"
      )

    val allowedForbiddenOverlap = intent.allowedWrites.filter { allowed =>
      intent.forbiddenWrites.exists(forbidden => PathPolicy.matches(forbidden, allowed) || PathPolicy.matches(allowed, forbidden))
    }
    if allowedForbiddenOverlap.nonEmpty then
      throw new IllegalStateException(
        s"EngineerIntent contains overlapping allowed and forbidden write paths: ${allowedForbiddenOverlap.mkString(", ")}"
      )

    val missingGlobalForbidden = inputs.constitution.requiredGlobalForbiddenEffects.filterNot { required =>
      intent.forbiddenEffects.map(normalize).contains(normalize(required))
    }
    if missingGlobalForbidden.nonEmpty then
      throw new IllegalStateException(
        s"EngineerIntent omits constitutionally forbidden effects: ${missingGlobalForbidden.mkString(", ")}"
      )

    val forbiddenEffectsNormalized = intent.forbiddenEffects.map(normalize).toSet
    val contradictoryEffects = intent.allowedEffects.filter(effect => forbiddenEffectsNormalized.contains(normalize(effect)))
    if contradictoryEffects.nonEmpty then
      throw new IllegalStateException(
        s"EngineerIntent both allows and forbids these effects: ${contradictoryEffects.mkString(", ")}"
      )

    if inputs.constitution.requireEvidenceBeforeSuccess && intent.requiredEvidence.isEmpty then
      throw new IllegalStateException("constitution.json requires evidence before success, but EngineerIntent.requiredEvidence is empty")

    if inputs.constitution.requireHandoffOnOutOfScopeWrite && intent.handoffOwner.trim.isEmpty then
      throw new IllegalStateException("constitution.json requires a handoff owner, but EngineerIntent.handoffOwner is empty")

    val allTests = inputs.developmentTests ++ inputs.confirmationTests
    if allTests.isEmpty then
      throw new IllegalStateException("At least one routing test is required")

    val duplicateTestIds = allTests.map(_.id).groupBy(id => id).collect { case (id, values) if values.size > 1 => id }.toVector.sorted
    if duplicateTestIds.nonEmpty then
      throw new IllegalStateException(s"Duplicate routing test IDs: ${duplicateTestIds.mkString(", ")}")

    allTests.foreach { test =>
      if test.requests.isEmpty then
        throw new IllegalStateException(s"Routing test ${test.id} contains no request variants")
      if !idSet.contains(test.expected) then
        throw new IllegalStateException(s"Routing test ${test.id} expects unknown agent ${test.expected}")
      val unknownMustNot = test.mustNot.filterNot(idSet.contains)
      if unknownMustNot.nonEmpty then
        throw new IllegalStateException(s"Routing test ${test.id} references unknown mustNot agents: ${unknownMustNot.mkString(", ")}")
      if test.mustNot.contains(test.expected) then
        throw new IllegalStateException(s"Routing test ${test.id} lists its expected owner in mustNot")
    }

object PathPolicy:
  def matches(pattern: String, path: String): Boolean =
    val p = normalizePath(pattern)
    val value = normalizePath(path)
    if p == value then true
    else if p.endsWith("/**") then
      val prefix = p.stripSuffix("/**")
      value == prefix || value.startsWith(prefix + "/")
    else if p.startsWith("**/") then
      val suffix = p.stripPrefix("**/")
      value == suffix || value.endsWith("/" + suffix)
    else false

  private def normalizePath(value: String): String =
    value.trim.replace('\\', '/').stripPrefix("./")

def normalize(value: String): String =
  value.trim.toLowerCase.replaceAll("\\s+", " ")

def errorMessage(error: Throwable): String =
  Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

def sha256(value: String): String =
  val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
  digest.map(byte => f"${byte & 0xff}%02x").mkString

def format4(value: Double): String = f"$value%.4f"
