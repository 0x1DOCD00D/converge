package converge

import scala.util.Try
import ujson.Value

object Schemas:
  private val stringSchema: Value = ujson.Obj("type" -> "string")

  private def arrayOfStrings(maxItems: Int = 32): Value =
    ujson.Obj(
      "type" -> "array",
      "items" -> stringSchema,
      "maxItems" -> maxItems
    )

  def taskIR(agentIds: Vector[String]): Value =
    val properties = ujson.Obj(
      "normalizedGoal" -> stringSchema,
      "artifactClass" -> stringSchema,
      "artifactLocators" -> arrayOfStrings(16),
      "operation" -> stringSchema,
      "coordinate" -> stringSchema,
      "fromVersion" -> stringSchema,
      "toVersion" -> stringSchema,
      "expectedOwner" -> ujson.Obj("type" -> "string", "enum" -> Json.arr(agentIds)),
      "allowedWrites" -> arrayOfStrings(32),
      "forbiddenWrites" -> arrayOfStrings(64),
      "allowedEffects" -> arrayOfStrings(32),
      "forbiddenEffects" -> arrayOfStrings(64),
      "positiveAnchors" -> arrayOfStrings(64),
      "negativeAnchors" -> arrayOfStrings(64),
      "validationCommands" -> arrayOfStrings(32),
      "requiredEvidence" -> arrayOfStrings(64),
      "handoffOwner" -> ujson.Obj("type" -> "string", "enum" -> Json.arr(agentIds))
    )

    val required = Vector(
      "normalizedGoal",
      "artifactClass",
      "artifactLocators",
      "operation",
      "coordinate",
      "fromVersion",
      "toVersion",
      "expectedOwner",
      "allowedWrites",
      "forbiddenWrites",
      "allowedEffects",
      "forbiddenEffects",
      "positiveAnchors",
      "negativeAnchors",
      "validationCommands",
      "requiredEvidence",
      "handoffOwner"
    )

    ujson.Obj(
      "type" -> "object",
      "properties" -> properties,
      "required" -> Json.arr(required),
      "additionalProperties" -> false
    )

  def candidatePhrases(maxItems: Int): Value =
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "phrases" -> ujson.Obj(
          "type" -> "array",
          "items" -> stringSchema,
          "maxItems" -> math.max(1, maxItems),
          "uniqueItems" -> true
        )
      ),
      "required" -> Json.arr(Vector("phrases")),
      "additionalProperties" -> false
    )

  def freePrompt: Value =
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj("prompt" -> stringSchema),
      "required" -> Json.arr(Vector("prompt")),
      "additionalProperties" -> false
    )

  def route(agentIds: Vector[String]): Value =
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "selectedAgent" -> ujson.Obj(
          "type" -> "string",
          "enum" -> Json.arr(agentIds :+ "ABSTAIN")
        )
      ),
      "required" -> Json.arr(Vector("selectedAgent")),
      "additionalProperties" -> false
    )

  def descriptionEdit(agentIds: Vector[String], atoms: Vector[String]): Value =
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "expectedAgent" -> ujson.Obj("type" -> "string", "enum" -> Json.arr(agentIds)),
        "wrongAgent" -> ujson.Obj("type" -> "string", "enum" -> Json.arr(agentIds :+ "ABSTAIN")),
        "boundaryAtoms" -> ujson.Obj(
          "type" -> "array",
          "items" -> ujson.Obj("type" -> "string", "enum" -> Json.arr(atoms)),
          "minItems" -> 1,
          "maxItems" -> 6,
          "uniqueItems" -> true
        )
      ),
      "required" -> Json.arr(Vector("expectedAgent", "wrongAgent", "boundaryAtoms")),
      "additionalProperties" -> false
    )

  def actionPlan(agentIds: Vector[String]): Value =
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "selectedOwner" -> ujson.Obj("type" -> "string", "enum" -> Json.arr(agentIds :+ "UNSPECIFIED")),
        "writes" -> arrayOfStrings(32),
        "commands" -> arrayOfStrings(32),
        "effects" -> arrayOfStrings(32),
        "evidence" -> arrayOfStrings(64),
        "handoff" -> arrayOfStrings(16),
        "claimsSuccessWithoutEvidence" -> ujson.Obj("type" -> "boolean")
      ),
      "required" -> Json.arr(Vector(
        "selectedOwner",
        "writes",
        "commands",
        "effects",
        "evidence",
        "handoff",
        "claimsSuccessWithoutEvidence"
      )),
      "additionalProperties" -> false
    )

object Ratifier:
  def checkTask(task: TaskIR, intent: EngineerIntent): Vector[Deviation] =
    val deviations = Vector.newBuilder[Deviation]

    def exact(code: String, actual: String, expected: String): Unit =
      if actual.trim != expected.trim then
        deviations += Deviation(code, s"Expected '$expected' but received '$actual'.")

    def exactSet(code: String, actual: Vector[String], expected: Vector[String]): Unit =
      val actualSet = actual.map(normalize).toSet
      val expectedSet = expected.map(normalize).toSet
      if actualSet != expectedSet then
        deviations += Deviation(
          code,
          s"Expected exactly ${expected.mkString("[", ", ", "]")} but received ${actual.mkString("[", ", ", "]")}."
        )

    exact("goal", task.normalizedGoal, intent.normalizedGoal)
    exact("artifact-class", task.artifactClass, intent.artifactClass)
    exactSet("artifact-locators", task.artifactLocators, intent.artifactLocators)
    exact("operation", task.operation, intent.operation)
    exact("coordinate", task.coordinate, intent.coordinate)
    exact("from-version", task.fromVersion, intent.fromVersion)
    exact("to-version", task.toVersion, intent.toVersion)
    exact("owner", task.expectedOwner, intent.expectedOwner)
    exactSet("allowed-writes", task.allowedWrites, intent.allowedWrites)
    exactSet("forbidden-writes", task.forbiddenWrites, intent.forbiddenWrites)
    exactSet("allowed-effects", task.allowedEffects, intent.allowedEffects)
    exactSet("forbidden-effects", task.forbiddenEffects, intent.forbiddenEffects)
    exactSet("positive-anchors", task.positiveAnchors, intent.positiveAnchors)
    exactSet("negative-anchors", task.negativeAnchors, intent.negativeAnchors)
    exactSet("validation", task.validationCommands, intent.validationCommands)
    exactSet("evidence", task.requiredEvidence, intent.requiredEvidence)
    exact("handoff", task.handoffOwner, intent.handoffOwner)

    deviations.result()

  def canonicalize(task: TaskIR, intent: EngineerIntent): TaskIR =
    task.copy(
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

  def checkPlan(plan: ActionPlan, intent: EngineerIntent): Vector[Deviation] =
    val deviations = Vector.newBuilder[Deviation]

    if plan.selectedOwner != intent.expectedOwner then
      deviations += Deviation("plan-owner", s"Expected owner ${intent.expectedOwner}, observed ${plan.selectedOwner}.")

    val unauthorizedWrites = plan.writes.filterNot { actual =>
      intent.allowedWrites.exists(pattern => PathPolicy.matches(pattern, actual))
    }
    if unauthorizedWrites.nonEmpty then
      deviations += Deviation("plan-write-set", s"Unauthorized writes: ${unauthorizedWrites.mkString(", ")}.")

    val missingRequiredWrites = intent.allowedWrites.filterNot { required =>
      plan.writes.exists(actual => PathPolicy.matches(required, actual) || PathPolicy.matches(actual, required))
    }
    if missingRequiredWrites.nonEmpty then
      deviations += Deviation("plan-missing-write", s"Missing required writes: ${missingRequiredWrites.mkString(", ")}.")

    val normalizedEffects = plan.effects.map(normalize)
    val forbiddenEffectHits = intent.forbiddenEffects.filter { forbidden =>
      normalizedEffects.exists { actual =>
        actual == normalize(forbidden) || actual.contains(normalize(forbidden)) || normalize(forbidden).contains(actual)
      }
    }
    if forbiddenEffectHits.nonEmpty then
      deviations += Deviation("plan-forbidden-effect", s"Forbidden effects proposed: ${forbiddenEffectHits.mkString(", ")}.")

    val commandText = plan.commands.map(normalize).mkString(" ; ")
    val missingCommands = intent.validationCommands.filterNot(command => commandText.contains(normalize(command)))
    if missingCommands.nonEmpty then
      deviations += Deviation("plan-validation", s"Missing validation commands: ${missingCommands.mkString(", ")}.")

    val evidenceText = plan.evidence.map(normalize).mkString(" ")
    val missingEvidence = intent.requiredEvidence.filterNot(item => evidenceText.contains(normalize(item)))
    if missingEvidence.nonEmpty then
      deviations += Deviation("plan-evidence", s"Missing evidence: ${missingEvidence.mkString(", ")}.")

    val handoffText = plan.handoff.map(normalize).mkString(" ")
    if !handoffText.contains(normalize(intent.handoffOwner)) then
      deviations += Deviation("plan-handoff", s"Missing handoff to ${intent.handoffOwner}.")

    if plan.claimsSuccessWithoutEvidence then
      deviations += Deviation("self-ratification", "The simulated agent would claim success without complete evidence.")

    deviations.result()

object PromptRenderer:
  def renderRoutingQuery(task: TaskIR, positiveAnchorOverride: Option[Vector[String]] = None): String =
    val anchors = positiveAnchorOverride.getOrElse(task.positiveAnchors)
    val positiveBlock =
      if anchors.isEmpty then "No additional positive routing coordinate was admitted."
      else anchors.mkString(", ")

    s"""Atomic request
       |${task.normalizedGoal}. Change ${task.coordinate} from ${task.fromVersion} to ${task.toVersion} in ${task.artifactLocators.mkString(", ")}.
       |
       |Authoritative artifact and operation
       |Artifact class: ${task.artifactClass}. Operation: ${task.operation}. Technical coordinates: $positiveBlock.
       |
       |Required scope
       |The requested write set is exactly ${task.allowedWrites.mkString(", ")}. Required effects: ${task.allowedEffects.mkString(", ")}.
       |
       |Negative boundary
       |Do not reinterpret the request as work on ${task.negativeAnchors.mkString(", ")}. Forbidden writes: ${task.forbiddenWrites.mkString(", ")}. Forbidden effects: ${task.forbiddenEffects.mkString(", ")}.
       |
       |Verification and handoff
       |Validate with ${task.validationCommands.mkString("; ")}. If work outside the declared write set becomes necessary, hand it to ${task.handoffOwner} rather than enlarging this task.
       |""".stripMargin.trim

  def render(task: TaskIR, positiveAnchorOverride: Option[Vector[String]] = None): String =
    val anchors = positiveAnchorOverride.getOrElse(task.positiveAnchors)
    val positiveBlock =
      if anchors.isEmpty then "No additional positive routing coordinate was admitted."
      else anchors.mkString(", ")

    s"""You are ${task.expectedOwner}, the authoritative owner for this atomic task.
       |
       |Task
       |Perform ${task.operation} for ${task.coordinate} in ${task.artifactLocators.mkString(", ")}. Required value transition: from ${task.fromVersion} to ${task.toVersion}.
       |
       |Positive technical coordinates
       |Use these validated coordinates to interpret the task: $positiveBlock.
       |
       |Scope and ownership
       |Modify only ${task.allowedWrites.mkString(", ")}. Do not modify ${task.forbiddenWrites.mkString(", ")}.
       |
       |Permitted effects
       |${task.allowedEffects.mkString(", ")}.
       |
       |Forbidden effects
       |${task.forbiddenEffects.mkString(", ")}.
       |
       |Negative boundaries
       |Do not reinterpret this task as work on ${task.negativeAnchors.mkString(", ")}.
       |
       |Validation
       |Run ${task.validationCommands.mkString("; ")} after the final permitted edit. Do not claim completion if any required validator fails.
       |
       |Required evidence
       |Return ${task.requiredEvidence.mkString(", ")} and bind the evidence to the final repository state.
       |
       |Handoff
       |If the requested change exposes work outside the declared write set, stop before making that out-of-scope edit and hand it to ${task.handoffOwner}.
       |
       |Completion rule
       |Do not claim success unless the requested change is complete, all required validation succeeds, all required evidence is fresh, and every effect remains within the declared authority.
       |""".stripMargin.trim

object PromptCompiler:
  private val compilerSystem =
    """You are the proposal stage of the CONVERGE prompt compiler. Convert the supplied authoritative EngineerIntent into the requested TaskIR JSON without changing any value. You may reorder arrays, but you may not add, remove, generalize, infer, or broaden task facts, owners, writes, effects, anchors, validation commands, evidence requirements, or handoffs. The external Scala ratifier, not you, decides whether the proposal is accepted."""

  def systemHash: String = sha256(compilerSystem)

  def compile(
      client: OllamaClient,
      config: Config,
      catalog: Vector[Agent],
      intent: EngineerIntent
  ): CompilationResult =
    val attempts = Vector.newBuilder[CompilerAttempt]
    var accepted: Option[TaskIR] = None
    var feedback = Vector.empty[Deviation]
    var index = 0

    while index < config.compilerAttempts && accepted.isEmpty do
      val seed = 20011 + index * 173
      val response = Try {
        client.generateJson(
          system = compilerSystem,
          prompt = compilerPrompt(intent, feedback),
          schema = Schemas.taskIR(catalog.map(_.id)),
          seed = seed,
          temperature = 0.0
        )
      }

      response match
        case scala.util.Success(value) =>
          Try(TaskIR.fromJson(value.content)) match
            case scala.util.Success(task) =>
              val deviations = Ratifier.checkTask(task, intent)
              attempts += CompilerAttempt(index, seed, Some(task), deviations, sha256(value.rawResponse))
              if deviations.isEmpty then accepted = Some(Ratifier.canonicalize(task, intent))
              else feedback = deviations
            case scala.util.Failure(error) =>
              val deviations = Vector(Deviation("invalid-task-ir", errorMessage(error)))
              attempts += CompilerAttempt(index, seed, None, deviations, sha256(value.rawResponse))
              feedback = deviations
        case scala.util.Failure(error) =>
          val deviations = Vector(Deviation("ollama-task-ir-call", errorMessage(error)))
          attempts += CompilerAttempt(index, seed, None, deviations, "")
          feedback = deviations

      index += 1

    CompilationResult(
      accepted = accepted.nonEmpty,
      task = accepted,
      attempts = attempts.result()
    )

  private def compilerPrompt(intent: EngineerIntent, feedback: Vector[Deviation]): String =
    val intentJson = TaskIR.toJson(TaskIR.fromIntent(intent))
    val feedbackText =
      if feedback.isEmpty then "No previous deviations."
      else feedback.map(deviation => s"${deviation.code}: ${deviation.detail}").mkString("\n")

    s"""Authoritative EngineerIntent encoded as the exact TaskIR values you must preserve
       |
       |${ujson.write(intentJson, indent = 2)}
       |
       |Feedback from the external Scala ratifier on the previous proposal
       |
       |$feedbackText
       |
       |Return one TaskIR JSON object. Copy every authoritative value exactly. Do not solve the software task.
       |""".stripMargin

object GenericPrompt:
  def generate(client: OllamaClient, intent: EngineerIntent, seed: Int): String =
    val prompt =
      s"""Rewrite the following authoritative task facts into a clear software-agent prompt. Do not invent facts.
         |Goal: ${intent.normalizedGoal}
         |Artifact: ${intent.artifactLocators.mkString(", ")}
         |Operation: ${intent.operation}
         |Coordinate: ${intent.coordinate}
         |Version change: ${intent.fromVersion} to ${intent.toVersion}
         |Allowed writes: ${intent.allowedWrites.mkString(", ")}
         |Forbidden writes: ${intent.forbiddenWrites.mkString(", ")}
         |Validation: ${intent.validationCommands.mkString("; ")}
         |Evidence: ${intent.requiredEvidence.mkString(", ")}
         |""".stripMargin

    val result = client.generateJson(
      system = "You write one concise software-agent prompt from supplied facts. Return JSON only and do not add permissions or tasks.",
      prompt = prompt,
      schema = Schemas.freePrompt,
      seed = seed,
      temperature = 0.0
    )
    Json.string(result.content, "prompt")
