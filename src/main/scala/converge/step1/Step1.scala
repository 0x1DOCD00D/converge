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

  /** 1.1 and 1.2: deterministic, and quick. The service runs them before it accepts a request. */
  def prepare(files: Section1Files): (ContractSnapshot, Vector[SourceEntry]) =
    val snapshot = LoadContract.run(files)
    (snapshot, MapSources.run(snapshot))

  /** 1.3 and 1.4 for one requirement: propose, ratify, and retry with the ratifier's feedback while attempts remain. */
  def elaborate(context: ElaborationContext, s1: Section1, elaborator: Elaborator, maxAttempts: Int): Elaborated =
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
    val kept = attempts.last._3.constructs.filter(_.kept)
    Elaborated(context.entry, attempts.toVector, if kept.isEmpty then Vector(StepRatifier.fallback(context.entry)) else kept)

  def run(files: Section1Files, elaborator: Elaborator, maxAttempts: Int, progress: Progress = Progress.none): Step1Outcome =
    val started = System.nanoTime()
    val (snapshot, entries) = prepare(files)
    val s1 = snapshot.section1
    val seed = s1.seedPrompt.map(_.text).getOrElse("")
    val binding = entries.filter(_.binding)

    val elaborated = binding.zipWithIndex.map { (entry, i) =>
      progress.update(i, binding.size, entry.id)
      elaborate(ElaborationContext(entry, seed, s1.decisions), s1, elaborator, maxAttempts)
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
        }))
    }))

    val ratifiedRecord = ujson.Obj("requirements" -> ujson.Arr.from(elaborated.map { e =>
      val finalAttempt = e.attempts.last._3.constructs
      val shown = if e.constructs.exists(_.fallback) then finalAttempt :+ e.constructs.head else finalAttempt
      ujson.Obj("requirementId" -> e.entry.id, "kind" -> e.entry.kind, "attempts" -> e.attempts.size,
        "constructs" -> ujson.Arr.from(shown.map(_.toJson)), "verdicts" -> Text.counts(shown.map(_.verdict)))
    }))

    val rows = ConstructTable.build(elaborated)
    val specIR = SpecIR.build(snapshot, entries, rows)
    val ledger = Evidence.ledger(entries)
    val manifest = Epoch.manifest(snapshot, elaborator.describe, maxAttempts)
    val state = ControllerState.initial(specIR, ledger, manifest)

    val all = elaborated.flatMap(e => e.attempts.last._3.constructs ++ e.constructs.filter(_.fallback))
    val proposals = elaborated.flatMap(_.attempts.map(_._1))
    val summary = ujson.Obj(
      "contractSnapshot" -> snapshot.id,
      "manifestId" -> manifest("manifestId"),
      "requirements" -> binding.size,
      "designNotes" -> entries.count(!_.binding),
      "constructs" -> rows.size,
      "verdicts" -> Text.counts(all.map(_.verdict)),
      "openGaps" -> specIR("openGaps").arr.size,
      "fallbacks" -> rows.count(_.construct.fallback),
      "attempts" -> elaborated.map(_.attempts.size).sum,
      "requirementsNeedingRetry" -> elaborated.count(_.attempts.size > 1),
      "modelCalls" -> proposals.size,
      "promptTokens" -> proposals.map(_.usage.promptTokens).sum.toDouble,
      "generatedTokens" -> proposals.map(_.usage.generatedTokens).sum.toDouble,
      "modelSeconds" -> math.round(proposals.map(_.usage.totalDurationNs).sum / 1e9).toDouble,
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
