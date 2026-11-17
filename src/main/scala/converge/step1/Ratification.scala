package converge.step1

import ujson.Value

/**
 * 1.4: a construct after the ratifier has judged it.
 *
 * verdict:
 *  - accepted:    the phrase is part of the requirement, its reading is fixed by the requirement or by a verified citation,
 *                 and the reviewer found that the meaning only restates that text (see Review.scala);
 *  - provisional: the reading may be right, but it introduces something (a value, a name, or anything else the reviewer
 *                 found the text does not say) that no accepted source states;
 *  - openGap:     the model found a question the phrase leaves open, and no accepted source answers it;
 *  - rejected:    the proposal is not about this requirement (the phrase is not in it, or the slot does not fit its kind).
 */
final case class RatifiedConstruct(
    requirementId: String,
    phrase: String,
    slot: String,
    meaning: String,
    verdict: String,
    reason: String,
    alternatives: Vector[String],
    question: String,
    provenance: Value,
    requirementSpan: Option[(Int, Int)],
    promptSpan: Option[(String, Int, Int)],
    fallback: Boolean,
    review: Option[Value] = None
):
  def kept: Boolean = verdict != "rejected"

  def toJson: Value =
    val o = ujson.Obj(
      "requirementId" -> requirementId, "phrase" -> phrase, "slot" -> slot, "meaning" -> meaning,
      "verdict" -> verdict, "reason" -> reason, "provenance" -> provenance
    )
    if question.nonEmpty then o("openQuestion") = question
    if alternatives.nonEmpty then o("alternatives") = ujson.Arr.from(alternatives)
    requirementSpan.foreach((s, e) => o("requirementSpan") = ujson.Arr(s, e))
    promptSpan.foreach((id, s, e) => o("promptSpan") = ujson.Obj("promptId" -> id, "span" -> ujson.Arr(s, e)))
    if fallback then o("fallback") = true
    review.foreach(r => o("review") = r)
    o

/**
 * The ratifier's judgment of one proposal: the constructs, the deviations a new attempt should fix, and the spans of the
 * requirement text that no kept construct covers.
 */
final case class Ratification(constructs: Vector[RatifiedConstruct], deviations: Vector[String],
    uncovered: Vector[(Int, Int)] = Vector.empty)

object StepRatifier:
  private val Number = """(?<![\w.])\d+(?:\.\d+)?(?![\w])""".r
  private val EnvName = """\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b""".r
  private val Flag = """(?<![\w-])--[a-z][a-z0-9-]*""".r

  /**
   * Words that carry no requirement on their own. A part of a requirement that holds only these (and punctuation) need
   * not be covered by a construct; any other word, including "not", "only" and "never", must be.
   */
  val CoverageStopwords: Vector[String] = Vector(
    "a", "an", "the", "and", "or", "of", "to", "for", "in", "on", "by", "with", "its", "it", "is", "are", "be", "as",
    "that", "this", "each", "which", "from", "into", "at")
  private val Word = """[\p{L}\p{N}][\p{L}\p{N}'._-]*""".r

  /**
   * The parts of `text` that none of the `covered` spans includes and that hold at least one word outside
   * CoverageStopwords, trimmed to their first and last letter or digit.
   */
  def uncoveredSpans(text: String, covered: Seq[(Int, Int)]): Vector[(Int, Int)] =
    val mark = Array.fill(text.length)(false)
    covered.foreach((s, e) => (math.max(s, 0) until math.min(e, text.length)).foreach(mark(_) = true))
    val runs = Vector.newBuilder[(Int, Int)]
    var i = 0
    while i < text.length do
      if mark(i) then i += 1
      else
        val start = i
        while i < text.length && !mark(i) do i += 1
        runs += ((start, i))
    runs.result().flatMap { (s, e) =>
      val run = text.substring(s, e)
      if Word.findAllIn(run).map(_.toLowerCase.reverse.dropWhile("._-'".contains(_)).reverse)
          .exists(w => w.nonEmpty && !CoverageStopwords.contains(w)) then
        Some((s + run.indexWhere(_.isLetterOrDigit), s + run.lastIndexWhere(_.isLetterOrDigit) + 1))
      else None
    }

  /** Values a reading states: numbers, environment-variable-style names and command-line flags. */
  def valueTokens(meaning: String): Vector[String] =
    (Number.findAllIn(meaning) ++ EnvName.findAllIn(meaning) ++ Flag.findAllIn(meaning)).toVector.distinct

  /** The values of `meaning` that none of the accepted texts contains. */
  def unsupportedValues(meaning: String, accepted: Seq[String]): Vector[String] =
    val corpus = accepted.map(Text.squash).mkString(" \n ")
    valueTokens(meaning).filterNot { v =>
      if v.head.isDigit then s"""(?<![\\w.])${java.util.regex.Pattern.quote(v)}(?![\\w])""".r.findFirstIn(corpus).nonEmpty
      else corpus.contains(v)
    }

  /** The user's words this requirement came from, which a "settled" citation with source "origin" may quote. */
  def originText(context: ElaborationContext, s1: Section1): String = context.entry.source match
    case Source.PromptSpan(id, field, _, _, _) =>
      s1.prompt(id).map(p => if field == "answer" then p.answer.getOrElse("") else p.text).getOrElse("")
    case Source.InstructionSpan(file, _, _, _) => s1.files.instructions.getOrElse(file, "")
    case Source.DecisionSource(_, _, answer, _) => answer

  /**
   * The texts a "settled" citation may quote, all written or chosen by the user: the requirement, its origin, the seed
   * prompt and every decision answer. The source the model named is tried first.
   */
  def citationSources(context: ElaborationContext, s1: Section1, c: ProposedConstruct): Vector[(String, ujson.Obj)] =
    val entry = context.entry
    val requirement = Vector(entry.text -> ujson.Obj("type" -> "requirement", "requirementId" -> entry.id))
    val origin = Vector(originText(context, s1) -> ujson.Obj("type" -> "origin", "requirementId" -> entry.id, "source" -> entry.source.toJson))
    val seed = s1.seedPrompt.toVector.map(p => p.text -> ujson.Obj("type" -> "prompt", "promptId" -> p.id))
    val decisions = s1.decisions.map(d => d.answer -> ujson.Obj("type" -> "decision", "decision" -> d.id, "answeredBy" -> d.answeredBy))
    val named = decisions.filter(_._2("decision").str == c.citationDecision)
    val preferred = c.citationSource match
      case "decision"    => named
      case "requirement" => requirement
      case "origin"      => origin
      case _             => Vector.empty
    (preferred ++ named ++ origin ++ requirement ++ seed ++ decisions).distinctBy(_._2)

  /** Where a phrase sits in the user's prompt, when the requirement is a prompt quote containing it once. */
  private def promptSpan(entry: SourceEntry, phrase: String, s1: Section1): Option[(String, Int, Int)] = entry.source match
    case Source.PromptSpan(id, field, _, start, end) =>
      for
        p <- s1.prompt(id)
        text = if field == "answer" then p.answer.getOrElse("") else p.text
        (s, e) <- Text.uniqueSpan(text.substring(start, end), phrase)
      yield (id, start + s, start + e)
    case _ => None

  def ratify(context: ElaborationContext, proposal: Proposal, s1: Section1): Ratification =
    val entry = context.entry
    val deviations = Vector.newBuilder[String]
    proposal.parseError.foreach(e => deviations += s"Your reply could not be used ($e). Return {\"constructs\": [...]} as specified.")
    val origin = originText(context, s1)
    val allowedSlots = Slots.ByKind.getOrElse(entry.kind, Slots.All)
    val seen = scala.collection.mutable.Set.empty[String]

    val judged = proposal.constructs.zipWithIndex.map { (c, i) =>
      val n = i + 1
      val label = s"construct $n (\"${c.phrase.take(60)}\")"
      def result(verdict: String, reason: String, provenance: Value = ujson.Null, span: Option[(Int, Int)] = None) =
        RatifiedConstruct(entry.id, c.phrase, c.slot, c.meaning, verdict, reason,
          if c.openQuestion.nonEmpty then c.readings else Vector.empty, c.openQuestion, provenance, span,
          if span.isDefined then promptSpan(entry, c.phrase, s1) else None, fallback = false)

      val span = if Text.occursOnce(entry.text, c.phrase) then Text.uniqueSpan(entry.text, c.phrase) else None
      if span.isEmpty then
        deviations += s"$label: the phrase must be copied exactly from the requirement text and occur there once."
        result("rejected", "the phrase is not a unique part of the requirement text")
      else if !seen.add(Text.squash(c.phrase).toLowerCase) then
        result("rejected", "the same phrase was proposed twice", span = span)
      else if !allowedSlots.contains(c.slot) then
        deviations += s"$label: slot '${c.slot}' does not fit a ${entry.kind} requirement; use one of ${allowedSlots.mkString(", ")}."
        result("rejected", s"slot '${c.slot}' does not fit kind ${entry.kind}", span = span)
      else
        val accepted = Vector(entry.text, origin)
        def valueCheck(reason: String, provenance: Value, extra: Seq[String] = Nil) =
          unsupportedValues(c.meaning, accepted ++ extra) match
            case Vector() => result("accepted", reason, provenance, span)
            case vs => result("provisional", s"the reading introduces ${vs.mkString(", ")}, which no accepted source states", provenance, span)
        val distinctReadings = c.readings.map(a => Text.squash(a).toLowerCase).distinct.size
        c.claim match
          case "determined" =>
            valueCheck("the requirement's own words fix this reading",
              ujson.Obj("type" -> "requirement", "requirementId" -> entry.id))
          case "settled" =>
            // The model's label is only a hint: the quote is looked up in every text the user wrote, deterministically.
            // A failed citation does not cost a new attempt: the open question the model found stands as a gap.
            val derivedFrom = entry.source.userWords
            val readingValues = c.readings.flatMap(valueTokens).distinct
            citationSources(context, s1, c).find((text, _) => Text.occursOnce(text, c.citationQuote)) match
              case None =>
                result("openGap", "the open question was claimed answered, but the quote is not in anything the user wrote", ujson.Null, span)
              case Some(_) if Text.squash(entry.text).contains(Text.squash(c.citationQuote)) ||
                  Text.squash(derivedFrom).contains(Text.squash(c.citationQuote)) =>
                result("openGap", "the cited passage is the one this requirement was derived from, so it cannot answer a question about it", ujson.Null, span)
              case Some(_) if readingValues.nonEmpty && unsupportedValues(readingValues.mkString(" "), Seq(c.citationQuote)).size == readingValues.size =>
                result("openGap", s"the readings differ by ${readingValues.mkString(", ")}, and the cited passage states none of them", ujson.Null, span)
              case Some((text, prov)) =>
                valueCheck("settled by a verified citation",
                  ujson.Obj.from(prov.obj.toSeq :+ ("quote" -> ujson.Str(c.citationQuote))), Seq(text))
          case _ =>
            if distinctReadings < 2 then
              deviations += s"$label: an open question needs at least two different readings."
            result("openGap", "the phrase leaves a question open, and no accepted source answers it", ujson.Null, span)
    }

    // Coverage: every substantive part of the requirement must belong to some kept construct. When nothing was kept,
    // the whole requirement falls back as written (see fallback), so only a partial cover is reported here. Text that
    // occurs more than once in the requirement cannot be proposed as a phrase (a phrase must be unique), so asking for
    // it again would only waste attempts: it goes straight to the fallback after the last attempt.
    val kept = judged.filter(_.kept)
    val uncovered = if kept.isEmpty then Vector.empty else uncoveredSpans(entry.text, kept.flatMap(_.requirementSpan))
    uncovered.map((s, e) => entry.text.substring(s, e)).filter(Text.occursOnce(entry.text, _)).foreach { t =>
      deviations += s"""the text "$t" is not covered by any construct; propose a construct for it, copied exactly from the requirement."""
    }
    Ratification(judged, deviations.result(), uncovered)

  /**
   * The construct used for text no proposal covers: the whole requirement when no proposal survives, or one uncovered
   * span of it after the last attempt. Either stands as written.
   */
  def fallback(entry: SourceEntry, span: Option[(Int, Int)] = None): RatifiedConstruct =
    val (s, e) = span.getOrElse((0, entry.text.length))
    val text = entry.text.substring(s, e)
    val reason =
      if span.isEmpty then "no proposed construct survived ratification; the requirement stands as written"
      else "no proposed construct covered this part of the requirement; it stands as written, in the user's words, not analysed for open questions and not reviewed"
    RatifiedConstruct(entry.id, text, Slots.default(entry.kind), text, "accepted", reason, Vector.empty, "",
      ujson.Obj("type" -> "requirement", "requirementId" -> entry.id), Some((s, e)), None, fallback = true)
