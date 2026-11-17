package converge.step1

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer
import ujson.Value

/** Reports progress through the requirements of 1.3 and 1.4. */
trait Progress:
  def update(done: Int, total: Int, current: String): Unit

object Progress:
  val none: Progress = (_, _, _) => ()

/** Every record of one Step 1 run, by name, plus a summary. */
final case class Step1Outcome(records: Vector[(String, Value)], summary: Value):
  def record(name: String): Value = records.find(_._1 == name).map(_._2).getOrElse(throw Step1Error(s"no record $name"))

object Step1:
  val RecordNames: Vector[String] = Vector(
    "contractSnapshot", "sourceMap", "taskIRProposal", "ratifiedAssignments", "constructTable",
    "specIR", "evidencePolicy", "evidenceLedger", "epochManifest", "controllerState"
  )

  /**
   * What the elaborator and the reviewer see for one requirement: it, with its evidence, the requirements it refers to,
   * and the requirements that refer to it (a refinement may point back at what it refines).
   */
  def contextFor(entry: SourceEntry, entries: Vector[SourceEntry]): ElaborationContext =
    ElaborationContext(entry,
      entry.references.flatMap((phrase, id) => entries.find(_.id == id).map(phrase -> _)),
      entries.filter(_.binding).flatMap(o => o.references.collect { case (phrase, id) if id == entry.id && o.id != entry.id => phrase -> o }))

  /** 1.1 and 1.2: deterministic, and quick. The service runs them before it accepts a request. */
  def prepare(files: Section1Files): (ContractSnapshot, Vector[SourceEntry]) =
    val snapshot = LoadContract.run(files)
    (snapshot, MapSources.run(snapshot))

  /**
   * 1.3 and 1.4 for one requirement: propose, ratify, and retry with the ratifier's feedback while attempts remain; then
   * let the reviewer judge the accepted meanings, and stand any text no construct covers as written.
   */
  def elaborate(context: ElaborationContext, s1: Section1, elaborator: Elaborator, reviewer: Reviewer, maxAttempts: Int): Elaborated =
    val attempts = ListBuffer.empty[(Proposal, Vector[String], Ratification)]
    var feedback = Vector.empty[String]
    var attempt = 1
    var finished = false
    while !finished do
      val proposal = elaborator.propose(context, feedback, attempt)
      val ratification = StepRatifier.ratify(context, proposal, s1)
      attempts += ((proposal, feedback, ratification))
      if ratification.deviations.isEmpty || attempt >= maxAttempts then finished = true
      else
        feedback = ratification.deviations
        attempt += 1
    val last = attempts.last._3
    val nothingKept = !last.constructs.exists(_.kept)
    val uncovered = if nothingKept then Vector.empty else last.uncovered
    val fallbacks =
      if nothingKept then Vector(StepRatifier.fallback(context.entry))
      else uncovered.map(span => StepRatifier.fallback(context.entry, Some(span)))
    val (reviewed, answer) = Review(context.entry, ElaborationPrompt.evidence(context), last.constructs, reviewer)
    Elaborated(context.entry, attempts.toVector, reviewed.filter(_.kept) ++ fallbacks, reviewed ++ fallbacks, answer, uncovered)

  def run(files: Section1Files, elaborator: Elaborator, reviewer: Reviewer, maxAttempts: Int,
      progress: Progress = Progress.none): Step1Outcome =
    val started = System.nanoTime()
    val (snapshot, entries) = prepare(files)
    val s1 = snapshot.section1
    val binding = entries.filter(_.binding)

    val elaborated = binding.zipWithIndex.map { (entry, i) =>
      progress.update(i, binding.size, entry.id)
      elaborate(contextFor(entry, entries), s1, elaborator, reviewer, maxAttempts)
    }
    progress.update(binding.size, binding.size, "")

    val proposalRecord = ujson.Obj("requirements" -> ujson.Arr.from(elaborated.map { e =>
      ujson.Obj("requirementId" -> e.entry.id, "kind" -> e.entry.kind, "text" -> e.entry.text,
        "attempts" -> ujson.Arr.from(e.attempts.zipWithIndex.map { case ((p, fb, r), i) =>
          val o = ujson.Obj(
            "attempt" -> (i + 1),
            "feedback" -> ujson.Arr.from(fb),
            "proposed" -> ujson.Arr.from(p.constructs.map(_.toJson)),
            "deviations" -> ujson.Arr.from(r.deviations),
            "usage" -> ujson.Obj("promptTokens" -> p.usage.promptTokens.toDouble, "generatedTokens" -> p.usage.generatedTokens.toDouble,
              "durationMs" -> (p.usage.totalDurationNs / 1000000L).toDouble)
          )
          p.parseError.foreach(err => o("parseError") = err)
          o
        }),
        "review" -> ujson.Arr.from(e.review.map(_.toJson)))
    }))

    val ratifiedRecord = ujson.Obj("requirements" -> ujson.Arr.from(elaborated.map { e =>
      ujson.Obj("requirementId" -> e.entry.id, "kind" -> e.entry.kind, "attempts" -> e.attempts.size,
        "constructs" -> ujson.Arr.from(e.ratified.map(_.toJson)), "verdicts" -> Text.counts(e.ratified.map(_.verdict)),
        "uncovered" -> ujson.Arr.from(e.uncovered.map((s, t) => ujson.Str(e.entry.text.substring(s, t)))))
    }))

    val rows = ConstructTable.build(elaborated)
    val specIR = SpecIR.build(snapshot, entries, rows)
    val ledger = Evidence.ledger(entries)
    val manifest = Epoch.manifest(snapshot, elaborator.describe, reviewer.describe, maxAttempts)
    val state = ControllerState.initial(specIR, ledger, manifest)

    val all = elaborated.flatMap(_.ratified)
    val proposals = elaborated.flatMap(_.attempts.map(_._1))
    val reviews = elaborated.flatMap(_.review)
    val reviewed = all.flatMap(_.review).map(_("verdict").str)
    val usage = proposals.map(_.usage) ++ reviews.map(_.usage)
    val summary = ujson.Obj(
      "contractSnapshot" -> snapshot.id,
      "manifestId" -> manifest("manifestId"),
      "requirements" -> binding.size,
      "designNotes" -> entries.count(!_.binding),
      "constructs" -> rows.size,
      "verdicts" -> Text.counts(all.map(_.verdict)),
      "openGaps" -> specIR("openGaps").arr.size,
      "fallbacks" -> rows.count(_.construct.fallback),
      "requirementsWithUncoveredText" -> elaborated.count(_.uncovered.nonEmpty),
      "review" -> ujson.Obj("calls" -> reviews.size, "requirementsReviewedTwice" -> elaborated.count(_.review.size > 1),
        "restates" -> reviewed.count(_ == "restates"),
        "adds" -> reviewed.count(_ == "adds"), "unjudged" -> reviewed.count(_ == "unjudged")),
      "attempts" -> elaborated.map(_.attempts.size).sum,
      "requirementsNeedingRetry" -> elaborated.count(_.attempts.size > 1),
      "modelCalls" -> (proposals.size + reviews.size),
      "promptTokens" -> usage.map(_.promptTokens).sum.toDouble,
      "generatedTokens" -> usage.map(_.generatedTokens).sum.toDouble,
      "modelSeconds" -> math.round(usage.map(_.totalDurationNs).sum / 1e9).toDouble,
      "wallSeconds" -> math.round((System.nanoTime() - started) / 1e9).toDouble
    )

    Step1Outcome(Vector(
      "contractSnapshot" -> snapshot.toJson,
      "sourceMap" -> ujson.Obj("entries" -> ujson.Arr.from(entries.map(_.toJson))),
      "taskIRProposal" -> proposalRecord,
      "ratifiedAssignments" -> ratifiedRecord,
      "constructTable" -> ujson.Obj("constructs" -> ujson.Arr.from(rows.map(_.toJson))),
      "specIR" -> specIR,
      "evidencePolicy" -> Evidence.Policy,
      "evidenceLedger" -> ledger,
      "epochManifest" -> manifest,
      "controllerState" -> state
    ), summary)

  /** Writes each record as <name>.json, and the summary as summary.json. */
  def write(dir: Path, outcome: Step1Outcome): Unit =
    Files.createDirectories(dir)
    (outcome.records :+ ("summary" -> outcome.summary)).foreach { (name, value) =>
      Files.writeString(dir.resolve(s"$name.json"), ujson.write(value, indent = 2) + "\n", UTF_8)
    }
