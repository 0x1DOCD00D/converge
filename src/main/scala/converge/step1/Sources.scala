package converge.step1

import ujson.Value

/** 1.1: the contract Step 1 works from, identified by its hash. */
final case class ContractSnapshot(id: String, sha256: String, promptRecordSha256: String, decisionsSha256: String, section1: Section1):
  def toJson: Value =
    val s1 = section1
    ujson.Obj(
      "snapshotId" -> id,
      "source" -> Section1Files.ContractFile,
      "sha256" -> sha256,
      "status" -> s1.status,
      "counts" -> ujson.Obj(
        "requirements" -> s1.requirements.size,
        "byKind" -> Text.counts(s1.requirements.map(_.kind)),
        "fromPrompts" -> s1.requirements.count(r => !r.origin.isInstanceOf[Origin.Decision]),
        "fromDecisions" -> s1.requirements.count(_.origin.isInstanceOf[Origin.Decision]),
        "resolvedAmbiguities" -> s1.resolvedAmbiguities.size,
        "designNotes" -> s1.designNotes.size,
        "findings" -> s1.findingsCount
      ),
      "derivedFrom" -> ujson.Obj(
        Section1Files.PromptRecordFile -> ujson.Obj("sha256" -> promptRecordSha256, "prompts" -> s1.prompts.size),
        Section1Files.DecisionsFile -> ujson.Obj("sha256" -> decisionsSha256, "decisions" -> s1.decisions.size)
      ),
      "contract" -> s1.contractJson
    )

object LoadContract:
  /** Parses the Section 1 files, refuses a contract that is not reconciled, and fingerprints it. */
  def run(files: Section1Files): ContractSnapshot =
    val s1 = Section1.parse(files)
    if s1.status != "reconciled" then
      throw Step1Error(s"the contract's status is '${s1.status}', not 'reconciled'; finish Section 1 (build-contract) first")
    if s1.requirements.isEmpty then throw Step1Error("the contract has no requirements")
    val sha = Text.sha256(files.contract)
    ContractSnapshot(s"CS-${sha.take(12)}", sha, Text.sha256(files.promptRecord), Text.sha256(files.decisions), s1)

/** Where a requirement's words come from, located exactly. */
enum Source:
  /** A span [start, end) of the prompt's `field` ("text", or "answer" for a questionAnswer). */
  case PromptSpan(promptId: String, field: String, quote: String, start: Int, end: Int)
  case InstructionSpan(file: String, quote: String, start: Int, end: Int)
  case DecisionSource(decisionId: String, question: String, answer: String, answeredBy: String)

  def toJson: Value = this match
    case PromptSpan(id, field, quote, s, e) =>
      ujson.Obj("type" -> "prompt", "promptId" -> id, "field" -> field, "span" -> ujson.Arr(s, e), "quote" -> quote)
    case InstructionSpan(file, quote, s, e) =>
      ujson.Obj("type" -> "agentInstructions", "file" -> file, "span" -> ujson.Arr(s, e), "quote" -> quote)
    case DecisionSource(id, q, a, by) =>
      ujson.Obj("type" -> "decision", "decision" -> id, "question" -> q, "answer" -> a, "answeredBy" -> by)

  /** The user's own words this source points at: the quote, or the answer the user chose. */
  def userWords: String = this match
    case PromptSpan(_, _, quote, _, _)     => quote
    case InstructionSpan(_, quote, _, _)   => quote
    case DecisionSource(_, _, answer, _)   => answer

/** One row of the source map: a requirement (or design note) and its verified source. */
final case class SourceEntry(id: String, kind: String, text: String, binding: Boolean, source: Source, supersedes: Vector[String]):
  def toJson: Value =
    val o = ujson.Obj("id" -> id, "kind" -> kind, "text" -> text, "binding" -> binding, "source" -> source.toJson)
    if supersedes.nonEmpty then o("supersedes") = ujson.Arr.from(supersedes)
    o

object MapSources:
  private def locate(s1: Section1, owner: String, origin: Origin): Either[String, Source] = origin match
    case Origin.Prompt(id, quote) =>
      s1.prompt(id) match
        case None => Left(s"$owner: prompt $id is not in the prompt record")
        case Some(p) if p.kind != "instruction" && p.kind != "questionAnswer" =>
          Left(s"$owner: prompt $id has kind ${p.kind}, not instruction or questionAnswer")
        case Some(p) =>
          val (field, text) = if p.kind == "questionAnswer" then ("answer", p.answer.getOrElse("")) else ("text", p.text)
          (Text.occursOnce(text, quote), Text.uniqueSpan(text, quote)) match
            case (true, Some((s, e))) => Right(Source.PromptSpan(id, field, quote, s, e))
            case _                    => Left(s"$owner: quote does not occur exactly once in prompt $id: '${quote.take(70)}'")
    case Origin.Instructions(file, quote) =>
      s1.files.instructions.get(file) match
        case None => Left(s"$owner: instruction file $file was not supplied")
        case Some(text) =>
          (Text.occursOnce(text, quote), Text.uniqueSpan(text, quote)) match
            case (true, Some((s, e))) => Right(Source.InstructionSpan(file, quote, s, e))
            case _                    => Left(s"$owner: quote does not occur exactly once in $file")
    case Origin.Decision(id) =>
      s1.decision(id) match
        case None    => Left(s"$owner: decision $id is not in ${Section1Files.DecisionsFile}")
        case Some(d) => Right(Source.DecisionSource(id, d.question, d.answer, d.answeredBy))

  /** Locates the source of every requirement and design note, or refuses with every problem found. */
  def run(snapshot: ContractSnapshot): Vector[SourceEntry] =
    val s1 = snapshot.section1
    val problems = Vector.newBuilder[String]
    val requirementRows = s1.requirements.flatMap { r =>
      val located = locate(s1, r.id, r.origin).flatMap { src =>
        src match
          // A decision-backed requirement must be one the decision actually lists.
          case Source.DecisionSource(id, _, _, _) =>
            val listed = s1.decision(id).exists(_.requirements.exists((k, t) => k == r.kind && Text.squash(t) == Text.squash(r.text)))
            if listed then Right(src) else Left(s"${r.id}: decision $id does not list this requirement")
          case _ => Right(src)
      }
      located match
        case Left(p)    => problems += p; None
        case Right(src) => Some(SourceEntry(r.id, r.kind, r.text, binding = true, src, r.supersedes))
    }
    val noteRows = s1.designNotes.flatMap { n =>
      locate(s1, n.id, n.origin) match
        case Left(p)    => problems += p; None
        case Right(src) => Some(SourceEntry(n.id, "designNote", n.text, binding = false, src, Vector.empty))
    }
    val found = problems.result()
    if found.nonEmpty then throw new Step1Error(found.toList)
    requirementRows ++ noteRows
