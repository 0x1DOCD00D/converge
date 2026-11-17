package converge.step1

import ujson.Value

/**
 * Section 1's evidence of how a requirement came to be (its `derivation`), as Step 1 verifies, uses and shows it.
 *
 * Steps, in order: "stated" (a quote of the user), "ambiguous" (the questions that were open, with their readings),
 * "decided" (the decision that answered them: question, answer, who answered), "created" (the text the answer became),
 * and "merged" (requirements merged into this one, each whole with its own origin and derivation, and the text before
 * and after the merge).
 */
object Derivation:
  private def str(v: Value, k: String): String = v.objOpt.flatMap(_.get(k)).flatMap(_.strOpt).getOrElse("")
  private def arr(v: Value, k: String): Vector[Value] = v.objOpt.flatMap(_.get(k)).flatMap(_.arrOpt).map(_.toVector).getOrElse(Vector.empty)

  def steps(d: Value): Vector[Value] = d.arrOpt.map(_.toVector).getOrElse(Vector.empty)

  /** The derivation of a merged requirement held in a "merged" step (empty if it has none). */
  private def of(f: Value): Value = f.objOpt.flatMap(_.get("derivation")).getOrElse(ujson.Arr())

  /** The text the requirement had before the first merge into it, if anything was merged. */
  def textBeforeMerge(d: Value): Option[String] = steps(d).find(s => str(s, "step") == "merged").map(str(_, "previousText"))

  /** True if decision `id` lists a requirement of this kind and text. */
  def lists(s1: Section1, id: String, kind: String, text: String): Boolean =
    s1.decision(id).exists(_.requirements.exists((k, t) => k == kind && Text.squash(t) == Text.squash(text)))

  /**
   * What does not hold in a requirement's derivation: every decision it names must be in decisions.json with the same
   * answer; every quote of the user must be found where it says; the merged text must be the requirement's text; and
   * every merged requirement must have a verified origin (and, if it came from a decision, be listed by it).
   */
  def problems(s1: Section1, owner: String, text: String, origin: Origin, derivation: Value): Vector[String] =
    steps(derivation).zipWithIndex.flatMap { (s, i) =>
      val at = s"$owner: derivation step ${i + 1} (${str(s, "step")})"
      str(s, "step") match
        case "stated" =>
          s.objOpt.flatMap(_.get("source")) match
            case None => Vector(s"$at: no source")
            case Some(src) =>
              val o = scala.util.Try(Section1.origin(owner, src)).toEither.left.map(_.getMessage)
              o match
                case Left(err)                => Vector(s"$at: $err")
                case Right(same) if same == origin => Vector.empty // the origin is verified on its own
                case Right(other)             => MapSources.locate(s1, at, other).left.toSeq.toVector
        case "decided" =>
          val id = str(s, "decision")
          s1.decision(id) match
            case None => Vector(s"$at: decision $id is not in ${Section1Files.DecisionsFile}")
            case Some(d) if Text.squash(d.answer) != Text.squash(str(s, "answer")) =>
              Vector(s"$at: the answer it records for $id is not the answer in ${Section1Files.DecisionsFile}")
            case Some(_) => Vector.empty
        case "merged" =>
          val textProblem = Option.when(Text.squash(str(s, "text")) != Text.squash(text))(s"$at: the merged text is not the requirement's text").toVector
          textProblem ++ arr(s, "from").flatMap { f =>
            val fid = str(f, "id")
            f.objOpt.flatMap(_.get("origin")) match
              case None => Vector(s"$at: merged requirement $fid has no origin")
              case Some(o) =>
                scala.util.Try(Section1.origin(fid, o)).toEither match
                  case Left(err) => Vector(s"$at: ${err.getMessage}")
                  case Right(fo) =>
                    val located = MapSources.locate(s1, s"$at: merged requirement $fid", fo).left.toSeq.toVector
                    val listed = fo match
                      case Origin.Decision(id) if !lists(s1, id, str(f, "kind"), textBeforeMerge(of(f)).getOrElse(str(f, "text"))) =>
                        Vector(s"$at: decision $id does not list merged requirement $fid")
                      case _ => Vector.empty
                    located ++ listed ++ problems(s1, fid, str(f, "text"), fo, of(f))
          }
        case _ => Vector.empty
    }

  /**
   * The texts the derivation holds that the user wrote or chose: quotes, answers, the texts requirements were created
   * with, merged requirements' texts and the text before a merge. Readings are left out: only the chosen one counts,
   * and it is in the answer.
   */
  def evidenceTexts(derivation: Value): Vector[String] =
    steps(derivation).flatMap { s =>
      str(s, "step") match
        case "stated"  => s.objOpt.flatMap(_.get("source")).map(str(_, "quote")).toVector
        case "decided" => Vector(str(s, "answer"))
        case "created" => Vector(str(s, "text"))
        case "merged" =>
          str(s, "previousText") +: arr(s, "from").flatMap(f =>
            str(f, "text") +: evidenceTexts(of(f)))
        case _ => Vector.empty
    }.filter(_.trim.nonEmpty)

  /** Every "answeredBy" in the derivation, merged requirements included. */
  def answeredBy(derivation: Value): Vector[String] =
    steps(derivation).flatMap { s =>
      str(s, "step") match
        case "decided" => Vector(str(s, "answeredBy"))
        case "merged"  => arr(s, "from").flatMap(f => answeredBy(of(f)))
        case _         => Vector.empty
    }.filter(_.nonEmpty).distinct

  /** The derivation as plain lines for a model to read. */
  def render(derivation: Value, indent: String = ""): String =
    steps(derivation).map { s =>
      str(s, "step") match
        case "stated" =>
          val src = s.objOpt.flatMap(_.get("source")).getOrElse(ujson.Obj())
          val where = if str(src, "type") == "agentInstructions" then s"agent instructions ${str(src, "file")}" else s"prompt ${str(src, "promptId")}"
          s"""$indent- The user wrote ($where): "${str(src, "quote")}""""
        case "ambiguous" =>
          arr(s, "items").distinctBy(it => Text.squash(str(it, "question")).toLowerCase.replaceAll("[^a-z0-9 ]", "")).map { it =>
            val readings = arr(it, "readings").flatMap(_.strOpt).mkString(" | ")
            s"""$indent- A question that was open, answered by the decision below: "${str(it, "question")}" (the readings offered: $readings)"""
          }.mkString("\n")
        case "decided"  => s"""$indent- Decision ${str(s, "decision")}: asked "${str(s, "question")}"; the answer chosen: "${str(s, "answer")}""""
        case "created"  => s"""$indent- The answer became the requirement: "${str(s, "text")}""""
        case "merged" =>
          val from = arr(s, "from").map { f =>
            s"""$indent  ${str(f, "id")} (${str(f, "kind")}): "${str(f, "text")}"""" +
              (if steps(of(f)).isEmpty then "" else "\n" + render(of(f), indent + "    "))
          }.mkString("\n")
          s"""$indent- Its text before the merge: "${str(s, "previousText")}"
             |$indent- Merged into it, because ${str(s, "reason")}:
             |$from""".stripMargin
        case other => s"$indent- ($other)"
    }.mkString("\n")
