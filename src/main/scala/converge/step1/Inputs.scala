package converge.step1

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.util.Using
import ujson.Value

/** A Step 1 refusal: the input or a record is not what Step 1 can accept. Each problem is one line. */
final class Step1Error(val problems: List[String]) extends RuntimeException(problems.mkString("; "))

object Step1Error:
  def apply(problems: String*): Step1Error = new Step1Error(problems.toList)

/** Where a requirement of the reconciled contract comes from (its `origin` in Section 1). */
enum Origin:
  case Prompt(promptId: String, quote: String)
  case Instructions(file: String, quote: String)
  case Decision(decisionId: String)

final case class Requirement(id: String, kind: String, text: String, origin: Origin, supersedes: Vector[String])

/** A design note of the contract: a choice that is not binding on independently built applications. */
final case class DesignNote(id: String, text: String, origin: Origin)

final case class RecordedPrompt(id: String, kind: String, text: String, answer: Option[String])

final case class UserDecision(
    id: String,
    resolves: Vector[String],
    question: String,
    answer: String,
    answeredBy: String,
    requirements: Vector[(String, String)],
    designNote: Option[String]
)

/** The Section 1 files exactly as Section 1 wrote them. Hashes are taken over these texts. */
final case class Section1Files(contract: String, promptRecord: String, decisions: String, instructions: Map[String, String])

object Section1Files:
  val ContractFile = "reconciledContract.json"
  val PromptRecordFile = "promptRecord.json"
  val DecisionsFile = "decisions.json"
  val InstructionsPrefix = "agentInstructions/"

  /** Reads Section 1's `out/` folder. */
  def fromDirectory(dir: Path): Section1Files =
    def read(name: String): String =
      val p = dir.resolve(name)
      if !Files.isRegularFile(p) then throw Step1Error(s"missing Section 1 file: $p")
      Files.readString(p, UTF_8)
    val instructionsDir = dir.resolve("agentInstructions")
    val instructions =
      if !Files.isDirectory(instructionsDir) then Map.empty[String, String]
      else
        Using.resource(Files.walk(instructionsDir))(_.iterator.asScala.filter(Files.isRegularFile(_)).toList)
          .map(p => instructionsDir.relativize(p).toString.replace('\\', '/') -> Files.readString(p, UTF_8))
          .toMap
    Section1Files(read(ContractFile), read(PromptRecordFile), read(DecisionsFile), instructions)

  /** The API body: {"files": {"reconciledContract.json": "<text>", "promptRecord.json": ..., "decisions.json": ..., "agentInstructions/<path>": ...}}. */
  def fromApiBody(body: Value): Section1Files =
    val files = body.objOpt.flatMap(_.get("files")).flatMap(_.objOpt)
      .getOrElse(throw Step1Error("the request body needs a \"files\" object"))
    def text(name: String): String =
      files.get(name).flatMap(_.strOpt).getOrElse(throw Step1Error(s"files.\"$name\" is missing or is not a string"))
    val instructions = files.collect { case (k, ujson.Str(v)) if k.startsWith(InstructionsPrefix) => k.stripPrefix(InstructionsPrefix) -> v }.toMap
    Section1Files(text(ContractFile), text(PromptRecordFile), text(DecisionsFile), instructions)

/** The parsed content of the Section 1 files. */
final case class Section1(
    files: Section1Files,
    contractJson: Value,
    status: String,
    requirements: Vector[Requirement],
    designNotes: Vector[DesignNote],
    resolvedAmbiguities: Vector[Value],
    findingsCount: Int,
    prompts: Vector[RecordedPrompt],
    decisions: Vector[UserDecision]
):
  def prompt(id: String): Option[RecordedPrompt] = prompts.find(_.id == id)
  def decision(id: String): Option[UserDecision] = decisions.find(_.id == id)

  /** The seed prompt: the first instruction the user typed. */
  def seedPrompt: Option[RecordedPrompt] = prompts.find(_.kind == "instruction")

object Section1:
  private def str(v: Value, k: String): String = v.objOpt.flatMap(_.get(k)).flatMap(_.strOpt).getOrElse("")
  private def arr(v: Value, k: String): Vector[Value] = v.objOpt.flatMap(_.get(k)).flatMap(_.arrOpt).map(_.toVector).getOrElse(Vector.empty)

  private def json(name: String, text: String): Value =
    try ujson.read(text)
    catch case e: Exception => throw Step1Error(s"$name is not valid JSON: ${e.getMessage}")

  private def origin(owner: String, v: Value): Origin =
    str(v, "type") match
      case "prompt"            => Origin.Prompt(str(v, "promptId"), str(v, "quote"))
      case "agentInstructions" => Origin.Instructions(str(v, "file"), str(v, "quote"))
      case "decision"          => Origin.Decision(str(v, "decision"))
      case other               => throw Step1Error(s"$owner: unknown origin type '$other'")

  def parse(files: Section1Files): Section1 =
    val contract = json(Section1Files.ContractFile, files.contract)
    val record = json(Section1Files.PromptRecordFile, files.promptRecord)
    val decisionsJson = json(Section1Files.DecisionsFile, files.decisions)

    val requirements = arr(contract, "requirements").map { r =>
      val id = str(r, "id")
      val o = r.objOpt.flatMap(_.get("origin")).getOrElse(throw Step1Error(s"$id: no origin"))
      Requirement(id, str(r, "kind"), str(r, "text"), origin(id, o), arr(r, "supersedes").flatMap(_.strOpt))
    }
    val designNotes = arr(contract, "designNotes").zipWithIndex.map { (n, i) =>
      val text = str(n, "text")
      if str(n, "note").nonEmpty then
        val o = n.objOpt.flatMap(_.get("origin")).getOrElse(throw Step1Error(s"${str(n, "note")}: no origin"))
        DesignNote(str(n, "note"), text, origin(str(n, "note"), o))
      else DesignNote(f"DN-${i + 1}%02d", text, Origin.Decision(str(n, "decision")))
    }
    val prompts = arr(record, "prompts").map(p =>
      RecordedPrompt(str(p, "id"), str(p, "kind"), str(p, "text"), p.objOpt.flatMap(_.get("answer")).flatMap(_.strOpt)))
    val decisions = arr(decisionsJson, "decisions").map { d =>
      UserDecision(
        id = str(d, "id"),
        resolves = arr(d, "resolves").flatMap(_.strOpt),
        question = str(d, "question"),
        answer = str(d, "answer"),
        answeredBy = str(d, "answeredBy"),
        requirements = arr(d, "requirements").map(r => str(r, "kind") -> str(r, "text")),
        designNote = d.objOpt.flatMap(_.get("designNote")).flatMap(_.strOpt)
      )
    }
    Section1(files, contract, str(contract, "status"), requirements, designNotes, arr(contract, "resolvedAmbiguities"),
      arr(contract, "findings").size, prompts, decisions)

/** Text helpers shared by every substep. */
object Text:
  /** Collapses whitespace runs, as Section 1 does when it verifies quotes. */
  def squash(s: String): String = s.replaceAll("\\s+", " ").trim

  def occurrences(text: String, q: String): Int =
    if q.isEmpty then 0
    else Iterator.iterate(text.indexOf(q))(i => text.indexOf(q, i + 1)).takeWhile(_ >= 0).size

  /** True if the squashed quote occurs exactly once in the squashed text (Section 1's quote rule). */
  def occursOnce(text: String, quote: String): Boolean =
    squash(quote).nonEmpty && occurrences(squash(text), squash(quote)) == 1

  /** The character span [start, end) of the one whitespace-tolerant occurrence of `quote` in `text`. */
  def uniqueSpan(text: String, quote: String): Option[(Int, Int)] =
    val words = squash(quote).split(" ").filter(_.nonEmpty).map(java.util.regex.Pattern.quote)
    if words.isEmpty then None
    else
      words.mkString("\\s+").r.findAllMatchIn(text).toList match
        case List(m) => Some((m.start, m.end))
        case _       => None

  def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  def sha256(s: String): String = sha256(s.getBytes(UTF_8))

  def counts(values: Iterable[String]): ujson.Obj =
    ujson.Obj.from(values.groupBy(identity).toList.sortBy(_._1).map((k, v) => k -> ujson.Num(v.size)))
