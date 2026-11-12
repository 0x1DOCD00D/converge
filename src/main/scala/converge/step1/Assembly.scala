package converge.step1

import ujson.Value

/** The outcome of 1.3 and 1.4 for one requirement: every attempt, and the constructs that were kept. */
final case class Elaborated(entry: SourceEntry, attempts: Vector[(Proposal, Vector[String], Ratification)], constructs: Vector[RatifiedConstruct])

/** 1.4 also yields the construct table: one row per kept construct, with stable ids. */
final case class ConstructRow(constructId: String, construct: RatifiedConstruct):
  def toJson: Value =
    val c = construct
    val o = ujson.Obj("constructId" -> constructId, "requirementId" -> c.requirementId, "phrase" -> c.phrase,
      "slot" -> c.slot, "verdict" -> c.verdict)
    c.requirementSpan.foreach((s, e) => o("requirementSpan") = ujson.Arr(s, e))
    c.promptSpan.foreach((id, s, e) => o("promptSpan") = ujson.Obj("promptId" -> id, "span" -> ujson.Arr(s, e)))
    if c.fallback then o("fallback") = true
    o

object ConstructTable:
  def build(elaborated: Vector[Elaborated]): Vector[ConstructRow] =
    elaborated.flatMap { e =>
      e.constructs.zipWithIndex.map((c, i) => ConstructRow(f"C_${e.entry.id.replace("-", "")}_${i + 1}%02d", c))
    }

/** 1.5 */
object SpecIR:
  def build(snapshot: ContractSnapshot, entries: Vector[SourceEntry], rows: Vector[ConstructRow]): Value =
    val binding = entries.filter(_.binding)
    def rowJson(r: ConstructRow) =
      val c = r.construct
      val o = ujson.Obj("constructId" -> r.constructId, "requirementId" -> c.requirementId, "phrase" -> c.phrase,
        "slot" -> c.slot, "meaning" -> c.meaning, "provenance" -> c.provenance, "reason" -> c.reason)
      c.promptSpan.foreach((id, s, e) => o("promptSpan") = ujson.Obj("promptId" -> id, "span" -> ujson.Arr(s, e)))
      o
    val gaps = rows.filter(_.construct.verdict == "openGap").zipWithIndex.map { (r, i) =>
      ujson.Obj("gapId" -> f"G-${i + 1}%03d", "constructId" -> r.constructId, "requirementId" -> r.construct.requirementId,
        "phrase" -> r.construct.phrase, "question" -> r.construct.question, "readings" -> ujson.Arr.from(r.construct.alternatives),
        "status" -> "UNRESOLVED", "reason" -> r.construct.reason)
    }
    ujson.Obj(
      "version" -> 1,
      "contractSnapshot" -> snapshot.id,
      "acceptedMeanings" -> ujson.Arr.from(rows.filter(_.construct.verdict == "accepted").map(rowJson)),
      "provisionalMeanings" -> ujson.Arr.from(rows.filter(_.construct.verdict == "provisional").map(rowJson)),
      "obligations" -> ujson.Arr.from(binding.map { e =>
        ujson.Obj("obligation" -> s"requirementKept(${e.id})", "requirementId" -> e.id, "kind" -> e.kind,
          "constructs" -> ujson.Arr.from(rows.filter(_.construct.requirementId == e.id).map(_.constructId)),
          "checker" -> ujson.Null)
      }),
      "protectedRequirements" -> ujson.Arr.from(binding.map(e => ujson.Obj("requirementId" -> e.id, "kind" -> e.kind, "text" -> e.text))),
      "variableCandidates" -> ujson.Arr.from(entries.filterNot(_.binding).map(e =>
        ujson.Obj("id" -> e.id, "text" -> e.text, "source" -> e.source.toJson, "binding" -> false))),
      "openGaps" -> ujson.Arr.from(gaps)
    )

/** 1.6 */
object Evidence:
  val Policy: Value = ujson.Obj(
    "version" -> 1,
    "rules" -> ujson.Arr(
      "The prompt record and the agent instructions the user supplied are the only authority on intent; user decisions recorded by Section 1 carry the authority of whoever answered them, recorded as issuer.",
      "A later correction wins: a commitment that lists earlier prompts under supersedes replaces their statements, which stay in the ledger as history.",
      "Agents propose; deterministic code verifies. A model's reading never becomes a commitment by itself.",
      "A behavior is not wanted because an application does it: observations of runs are recorded as observations and never as commitments."
    ),
    "recordKinds" -> ujson.Arr("commitment", "observation"),
    "supersession" -> "only a commitment whose source is a later user prompt may supersede an earlier one"
  )

  def ledger(entries: Vector[SourceEntry]): Value =
    val rows = entries.filter(_.binding).zipWithIndex.map { (e, i) =>
      val issuer = e.source match
        case Source.PromptSpan(id, _, _, _, _)     => s"user (prompt $id)"
        case Source.InstructionSpan(file, _, _, _) => s"user (agent instructions $file)"
        case Source.DecisionSource(_, _, _, by)    => by
      val o = ujson.Obj("evidenceId" -> f"E${i + 1}%03d", "kind" -> "commitment", "subject" -> e.id,
        "source" -> e.source.toJson, "issuer" -> issuer, "version" -> 1, "scope" -> "task", "status" -> "active")
      if e.supersedes.nonEmpty then o("supersedes") = ujson.Arr.from(e.supersedes)
      o
    }
    ujson.Obj("entries" -> ujson.Arr.from(rows), "observations" -> ujson.Arr())

/** 1.7 */
object Epoch:
  val RequirementKinds: Vector[String] = Vector("validInput", "expectedOutput", "persistentEffect", "errorHandling", "resourcePermission")

  def manifest(snapshot: ContractSnapshot, elaborator: ujson.Obj, maxAttempts: Int): Value =
    val fields = ujson.Obj(
      "contractSnapshot" -> snapshot.id,
      "contractSha256" -> snapshot.sha256,
      "promptRecordSha256" -> snapshot.promptRecordSha256,
      "decisionsSha256" -> snapshot.decisionsSha256,
      "evidencePolicy" -> ujson.Obj("version" -> 1, "sha256" -> Text.sha256(ujson.write(Evidence.Policy))),
      "grammar" -> ujson.Obj("requirementKinds" -> ujson.Arr.from(RequirementKinds), "slots" -> ujson.Arr.from(Slots.All)),
      "elaborator" -> elaborator,
      "ratifier" -> ujson.Obj("maxAttempts" -> maxAttempts),
      "contracts" -> ujson.Arr()
    )
    val id = Text.sha256(ujson.write(fields))
    ujson.Obj.from(fields.obj.toSeq :+ ("manifestId" -> ujson.Str(s"EP-${id.take(12)}")) :+ ("manifestSha256" -> ujson.Str(id)))

/** 1.8 */
object ControllerState:
  def initial(specIR: Value, ledger: Value, manifest: Value): Value =
    ujson.Obj(
      "revision" -> 0,
      "protectedIntent" -> specIR("protectedRequirements"),
      "specificationText" -> ujson.Null,
      "acceptedMeanings" -> specIR("acceptedMeanings"),
      "provisionalMeanings" -> specIR("provisionalMeanings"),
      "openGaps" -> specIR("openGaps"),
      "factualEvidence" -> ujson.Arr(),
      "defeasibleDefaults" -> ujson.Arr(),
      "dependencyGraph" -> ujson.Arr.from(specIR("obligations").arr.map { o =>
        ujson.Obj("node" -> o("obligation"), "dependsOn" -> o("constructs"))
      }),
      "explanatoryHypotheses" -> ujson.Arr(),
      "workflow" -> ujson.Null,
      "resourceBindings" -> ujson.Arr(),
      "testObligations" -> specIR("obligations"),
      "geometryPolicy" -> ujson.Null,
      "evidenceLedger" -> ujson.Obj("commitments" -> ledger("entries").arr.size, "observations" -> 0),
      "versionManifest" -> manifest("manifestId"),
      "remainingBudget" -> ujson.Null
    )
