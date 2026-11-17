package converge.step1

import converge.Usage
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}

/** A stand-in for the model: returns scripted constructs, and remembers the feedback it was given. */
final class ScriptedElaborator(script: (ElaborationContext, Int) => Vector[ProposedConstruct]) extends Elaborator:
  val feedbackSeen = scala.collection.mutable.ListBuffer.empty[(String, Int, Vector[String])]
  def describe: ujson.Obj = ujson.Obj("type" -> "scripted")
  def propose(context: ElaborationContext, feedback: Vector[String], attempt: Int): Proposal =
    feedbackSeen += ((context.entry.id, attempt, feedback))
    Proposal(script(context, attempt), "", Usage(), None)

/** A stand-in for the reviewer: answers each attempt with `reply`, and remembers what it was shown. */
final class ScriptedReviewer(reply: (Vector[ReviewItem], Int) => ReviewAnswer) extends Reviewer:
  val seen = scala.collection.mutable.ListBuffer.empty[(String, String, Vector[ReviewItem], Int)]
  def describe: ujson.Obj = ujson.Obj("type" -> "scripted")
  def review(entry: SourceEntry, origin: String, items: Vector[ReviewItem], attempt: Int): ReviewAnswer =
    seen += ((entry.id, origin, items, attempt))
    reply(items, attempt)

object ScriptedReviewer:
  /** Judges each item on its own, copying its number and phrase back correctly. */
  def judging(judge: ReviewItem => Option[(String, String)]): ScriptedReviewer =
    ScriptedReviewer((items, _) => ReviewAnswer(
      items.flatMap(i => judge(i).map((v, added) => ReviewJudgement(i.index, i.phrase, v, added))), "", Usage(), None))

class Step1Spec extends munit.FunSuite:

  private val causewayDir: Path = Paths.get(getClass.getResource("/step1/causeway").toURI)
  private def causeway: Section1Files = Section1Files.fromDirectory(causewayDir)
  private def approving = ScriptedReviewer.judging(_ => Some(("restates", "")))

  private def construct(phrase: String, slot: String, meaning: String, question: String = "", readings: Vector[String] = Vector.empty) =
    ProposedConstruct(phrase, slot, meaning, question, readings)

  private def context(id: String): (ElaborationContext, Section1) =
    val (snapshot, entries) = Step1.prepare(causeway)
    (Step1.contextFor(entries.find(_.id == id).get, entries), snapshot.section1)

  private def ratify(id: String, cs: ProposedConstruct*): Ratification =
    val (ctx, s1) = context(id)
    StepRatifier.ratify(ctx, Proposal(cs.toVector, "", Usage(), None), s1)

  /** The deviations other than coverage: the single-construct tests below leave the rest of a requirement uncovered on purpose. */
  private def ruleDeviations(r: Ratification): Vector[String] = r.deviations.filterNot(_.contains("is not covered by any construct"))

  // ---- 1.1 ----

  test("1.1 fingerprints the Causeway contract and counts its requirements by origin") {
    val snapshot = LoadContract.run(causeway)
    assertEquals(snapshot.sha256, "3d9c22003c6698df1b67992da312efd60a64eb904c128dbda5f63bc8853a3487")
    assertEquals(snapshot.id, "CS-3d9c22003c66")
    val counts = snapshot.toJson("counts")
    assertEquals(counts("requirements").num.toInt, 22) // 29 before Section 1 merged seven refinements into five
    assertEquals(counts("fromPrompts").num.toInt, 10)
    assertEquals(counts("fromDecisions").num.toInt, 12)
    assertEquals(counts("designNotes").num.toInt, 1)
  }

  test("1.1 refuses a contract that is not reconciled") {
    val draft = causeway.copy(contract = causeway.contract.replace("\"status\": \"reconciled\"", "\"status\": \"draft\""))
    val e = intercept[Step1Error](LoadContract.run(draft))
    assert(e.problems.head.contains("not 'reconciled'"), e.problems)
  }

  // ---- 1.2 ----

  test("1.2 locates every prompt quote by its character span in the seed prompt") {
    val (_, entries) = Step1.prepare(causeway)
    def span(id: String) = entries.find(_.id == id).get.source match
      case Source.PromptSpan(p, _, _, s, e) => (p, s, e)
      case other                            => fail(s"$id has source $other")
    assertEquals(span("REQ-001"), ("P-0001", 205, 249))
    assertEquals(span("REQ-003"), ("P-0001", 444, 490))
    assertEquals(span("REQ-007"), ("P-0001", 709, 786))
    assertEquals(span("REQ-008"), span("REQ-007")) // one sentence, two requirements
    assertEquals(span("REQ-010"), ("P-0001", 813, 863))
  }

  test("1.2 ties decision-backed requirements to the decision and the user's answer; the design note is not binding") {
    val (_, entries) = Step1.prepare(causeway)
    entries.find(_.id == "REQ-012").get.source match
      case Source.DecisionSource(id, _, answer, _) =>
        assertEquals(id, "DEC-001")
        assert(answer.startsWith("Fixed built-in criteria"))
      case other => fail(s"REQ-012 has source $other")
    assertEquals(entries.count(_.binding), 22)
    val note = entries.filterNot(_.binding)
    assertEquals(note.map(_.id), Vector("DN-01"))
    assertEquals(note.head.source.asInstanceOf[Source.DecisionSource].decisionId, "DEC-003")
  }

  test("1.2 refuses a requirement whose quote is not in its prompt") {
    val edited = causeway.copy(contract = causeway.contract.replace(
      "\"quote\": \"Pick one repo and clone it at a pinned commit.\"", "\"quote\": \"Pick one repo and clone it at the latest commit.\""))
    val e = intercept[Step1Error](Step1.prepare(edited))
    assertEquals(e.problems.size, 1)
    assert(e.problems.head.startsWith("REQ-003: quote does not occur exactly once in prompt P-0001"), e.problems)
  }

  // ---- 1.2: the evidence ----

  test("1.2 verifies the evidence: each decision a derivation names, merged requirements included, must match decisions.json") {
    // REQ-003 absorbed REQ-013 and REQ-014, which came from DEC-002
    val edited = causeway.copy(decisions = causeway.decisions.replace("may give a commit; otherwise", "may give a commit; else"))
    val e = intercept[Step1Error](Step1.prepare(edited))
    assert(e.problems.exists(p => p.startsWith("REQ-013: derivation step 2 (decided)") && p.contains("DEC-002")), e.problems)
  }

  test("1.2 a reference must name a requirement of the contract") {
    val c = causeway.contract
    val ref = "\"requirement\": \"REQ-026\""
    assertEquals(c.split(java.util.regex.Pattern.quote(ref), -1).length - 1, 1) // only REQ-027's reference names REQ-026
    val e = intercept[Step1Error](Step1.prepare(causeway.copy(contract = c.replace(ref, "\"requirement\": \"REQ-099\""))))
    assert(e.problems.exists(_.contains("REQ-027: refers to REQ-099, which is not a requirement")), e.problems)
  }

  test("1.3 the elaborator sees the requirement, its evidence and what it refers to, never the seed prompt or the other decisions") {
    val (ctx, _) = context("REQ-003")
    val prompt = ElaborationPrompt.user(ctx, Vector.empty)
    assert(prompt.contains("kind: persistentEffect, validInput"), prompt)
    assert(prompt.contains("Its text before the merge"), prompt)
    assert(prompt.contains("REQ-013 (validInput)") && prompt.contains("REQ-014 (persistentEffect)"), prompt)
    assert(prompt.contains("otherwise the tool pins the commit current at that moment"), prompt) // DEC-002's answer
    assert(prompt.contains("\"recorded with the run\" is defined by REQ-008"), prompt)
    assert(!prompt.contains("<seed_prompt>") && !prompt.contains("Decision DEC-009"), prompt) // other decisions are not shown
    // REQ-028 ("The tool clones only repositories on github.com ...") builds on REQ-003, so it is shown as such
    assert(prompt.contains("REQ-028 (\"clones\" there means this requirement)"), prompt)
    assert(prompt.contains("A question that was open, answered by the decision below"), prompt)
    // a decision that resolved several items asking the same question shows that question once
    val shown12 = ElaborationPrompt.evidence(context("REQ-012")._1)
    assertEquals(shown12.split("What makes a discovered repository", -1).length - 1, 2) // once as asked, once in the decision
    // REQ-012 ("Discovered repositories are shown ...") refers back to REQ-002, so REQ-002 is shown it
    val (ctx2, _) = context("REQ-002")
    assert(ElaborationPrompt.user(ctx2, Vector.empty).contains("REQ-012 (\"Discovered repositories\" there means this requirement)"))
    // ids of the contract's records are names, not values
    assertEquals(StepRatifier.valueTokens("The clone of REQ-003, pinned per DEC-002, keeps 50 commits."), Vector("50"))
    // values the evidence states count as stated
    assert(ctx.supportingTexts.exists(_.contains("otherwise the tool pins the commit current at that moment")))
  }

  test("1.4 a merged requirement may use the slots of every kind merged into it") {
    assertEquals(Slots.allowed(Vector("validInput", "errorHandling")), Vector("precondition", "errorPolicy"))
    val (ctx, _) = context("REQ-003")
    assertEquals(ctx.entry.kinds, Vector("persistentEffect", "validInput"))
  }

  // ---- 1.4 ----

  test("1.4 rejects a phrase that is not part of the requirement, such as a finding's behavior") {
    val r = ratify("REQ-017", construct("at most 5 commits per batch", "precondition", "Batches hold at most 5 commits."))
    assertEquals(r.constructs.head.verdict, "rejected")
    assert(r.deviations.head.contains("copied exactly from the requirement text"), r.deviations)
  }

  test("1.4 keeps a reading provisional when it introduces a value no source states") {
    val r = ratify("REQ-017", construct("a default limit applies", "precondition", "Without a limit, a default limit of 100 commits applies."))
    assertEquals(r.constructs.head.verdict, "provisional")
    assert(r.constructs.head.reason.contains("100"), r.constructs.head.reason)
    assertEquals(ruleDeviations(r), Vector.empty)
  }

  test("1.4 turns a phrase with two readings into an open gap") {
    val r = ratify("REQ-017", construct("a default limit applies", "precondition", "Some default limit applies.",
      "How large is the default limit?", Vector("a fixed number of the most recent commits", "commits from a fixed recent time window")))
    assertEquals(r.constructs.head.verdict, "openGap")
    assertEquals(ruleDeviations(r), Vector.empty)
  }

  test("1.4 rejects a slot that does not fit the requirement's kind, and an open question needs two readings") {
    val r = ratify("REQ-017",
      construct("limit the scan by a number of commits or a start date", "effect", "x"),
      construct("a default limit applies", "precondition", "x", "How large is it?", Vector("only one")))
    assertEquals(r.constructs.map(_.verdict), Vector("rejected", "openGap"))
    assertEquals(ruleDeviations(r).size, 2)
  }

  test("1.4 places a phrase of a prompt-backed requirement in the user's prompt") {
    // "from chat" is in the requirement ("...of the workflow from chat with a slash command") and in the user's words
    // ("I'll drive it from chat with slash commands.", P-0001 [205, 249])
    val r = ratify("REQ-001", construct("from chat", "precondition", "The steps are run from the chat."))
    assertEquals(r.constructs.head.verdict, "accepted")
    assertEquals(r.constructs.head.promptSpan, Some(("P-0001", 219, 228)))
    val (ctx, _) = context("REQ-001")
    assertEquals(r.constructs.head.requirementSpan.map(_._1), Some(ctx.entry.text.indexOf("from chat")))
    // a phrase of the requirement that the user never wrote has no place in the prompt
    val own = ratify("REQ-001", construct("each step of the workflow", "precondition", "Every step can be run."))
    assertEquals(own.constructs.head.promptSpan, None)
  }

  test("1.3/1.4 retry with the ratifier's feedback, and fall back to the whole requirement when nothing survives") {
    val (ctx, s1) = context("REQ-017")
    val elaborator = ScriptedElaborator((_, attempt) =>
      if attempt == 1 then Vector(construct("a default limit of 50", "precondition", "x"))
      else Vector(construct("The user can limit the scan by a number of commits or a start date", "precondition", "x"),
        construct("without one, a default limit applies", "precondition", "x", "How large is it?", Vector("a", "b"))))
    val e = Step1.elaborate(ctx, s1, elaborator, approving, maxAttempts = 3)
    assertEquals(e.attempts.size, 2)
    assert(elaborator.feedbackSeen(1)._3.head.contains("copied exactly"), elaborator.feedbackSeen.toString)
    assertEquals(e.constructs.map(_.verdict), Vector("accepted", "openGap"))

    val silent = Step1.elaborate(ctx, s1, ScriptedElaborator((_, _) => Vector.empty), approving, maxAttempts = 3)
    assertEquals(silent.attempts.size, 1)
    assert(silent.constructs.head.fallback)
    assertEquals(silent.constructs.head.phrase, ctx.entry.text)
  }

  // ---- 1.4: coverage ----

  test("1.4 reports the part of a requirement that no kept construct covers") {
    // qwen3:4b split REQ-022's "or a repository or commit does not exist" into phrases the requirement does not contain;
    // with those rejected, the condition and the requirement's whole reaction were covered by nothing
    val r = ratify("REQ-022",
      construct("When GitHub is unreachable", "errorPolicy", "x"),
      construct("or rate-limited", "errorPolicy", "x"),
      construct("or a repository does not exist", "errorPolicy", "x"))
    assertEquals(r.constructs.map(_.verdict), Vector("accepted", "accepted", "rejected"))
    val (ctx, _) = context("REQ-022")
    assertEquals(r.uncovered.map((s, e) => ctx.entry.text.substring(s, e)),
      Vector("or a repository or commit does not exist, the user is told what failed"))
    assert(r.deviations.exists(_.contains("\"or a repository or commit does not exist, the user is told what failed\" is not covered")), r.deviations)
  }

  test("1.4 needs no construct for words that carry no requirement, but does for every other word") {
    val full = ratify("REQ-017",
      construct("The user can limit the scan by a number of commits or a start date", "precondition", "x"),
      construct("without one, a default limit applies", "precondition", "x"))
    assertEquals(full.uncovered, Vector.empty)
    assertEquals(full.deviations, Vector.empty)
    // "the" between the phrases and the final "." need no construct
    val stop = ratify("REQ-027", construct("Agents cannot read", "frame", "x"), construct(".env file", "frame", "x"))
    assertEquals(stop.uncovered, Vector.empty)
    // "cannot" does
    val negation = ratify("REQ-027", construct("Agents", "frame", "x"), construct("read the .env file.", "frame", "x"))
    val (ctx, _) = context("REQ-027")
    assertEquals(negation.uncovered.map((s, e) => ctx.entry.text.substring(s, e)), Vector("cannot"))
  }

  test("1.3/1.4 text still uncovered after the last attempt stands as written, next to the kept constructs") {
    val (ctx, s1) = context("REQ-022")
    val elaborator = ScriptedElaborator((_, _) =>
      Vector(construct("When GitHub is unreachable", "errorPolicy", "x"), construct("or rate-limited", "errorPolicy", "x")))
    val e = Step1.elaborate(ctx, s1, elaborator, approving, maxAttempts = 2)
    assertEquals(e.attempts.size, 2)
    assert(elaborator.feedbackSeen(1)._3.exists(_.contains("is not covered")), elaborator.feedbackSeen.toString)
    assertEquals(e.constructs.map(_.fallback), Vector(false, false, true))
    val partial = e.constructs.last
    assertEquals(partial.phrase, "or a repository or commit does not exist, the user is told what failed")
    assertEquals(partial.verdict, "accepted")
    assertEquals(partial.requirementSpan.map((s, t) => ctx.entry.text.substring(s, t)), Some(partial.phrase))
    assert(partial.reason.contains("no proposed construct covered this part"), partial.reason)
    assertEquals(e.uncovered.size, 1)
  }

  // ---- 1.4: the reviewer ----

  test("1.4 the reviewer moves a meaning that adds to its requirement to provisional") {
    // qwen3:4b read REQ-027 "Agents cannot read the .env file." as "The application is not permitted to read the .env file."
    val (ctx, s1) = context("REQ-027")
    val reviewer = ScriptedReviewer.judging(i => if i.meaning.contains("application") then Some(("adds", "The application")) else Some(("restates", "")))
    val e = Step1.elaborate(ctx, s1, ScriptedElaborator((_, _) =>
      Vector(construct("Agents cannot read the .env file.", "frame", "The application is not permitted to read the .env file."))), reviewer, 3)
    val c = e.constructs.head
    assertEquals(c.verdict, "provisional")
    assert(c.reason.contains("adds \"The application\""), c.reason)
    assertEquals(c.review.map(_("verdict").str), Some("adds"))
    assertEquals(reviewer.seen.head._3.map(_.meaning), Vector("The application is not permitted to read the .env file."))

    val restated = Step1.elaborate(ctx, s1, ScriptedElaborator((_, _) =>
      Vector(construct("Agents cannot read the .env file.", "frame", "Agents are not able to read the .env file."))), reviewer, 3)
    assertEquals(restated.constructs.head.verdict, "accepted")
  }

  test("1.4 the reviewer sees only accepted meanings, and a meaning it does not judge is not accepted") {
    val (ctx, s1) = context("REQ-017")
    val silent = ScriptedReviewer.judging(_ => None)
    val e = Step1.elaborate(ctx, s1, ScriptedElaborator((_, _) => Vector(
      construct("The user can limit the scan by a number of commits or a start date", "precondition", "x"),
      construct("without one, a default limit applies", "precondition", "A default of 100 commits applies."))), silent, 3)
    // the second is provisional already (100 is stated nowhere); the reviewer is not asked about it
    assertEquals(silent.seen.head._3.map(_.phrase), Vector("The user can limit the scan by a number of commits or a start date"))
    assertEquals(e.constructs.map(_.verdict), Vector("provisional", "provisional"))
    assert(e.constructs.head.reason.contains("could not be reviewed"), e.constructs.head.reason)
    // a decision-backed requirement is shown with the question that was open and the answer chosen
    val (ctx12, _) = context("REQ-012")
    val shown = ElaborationPrompt.evidence(ctx12)
    assert(shown.contains("A question that was open") && shown.contains("Decision DEC-001:") && shown.contains("Fixed built-in criteria"), shown)
  }

  test("1.4 the reviewer's reply is read strictly") {
    val parsed = ReviewPrompt.parse(ujson.read(
      """{"reviews": [{"construct": 1, "phrase": "a", "verdict": "restates", "added": ""},
        |{"construct": 2, "phrase": "b", "verdict": "maybe", "added": ""},
        |{"construct": 3, "phrase": "c", "verdict": "ADDS", "added": " local database "}]}""".stripMargin))
    assertEquals(parsed.map(_.map(j => (j.construct, j.verdict, j.added))),
      Right(Vector((1, "restates", ""), (3, "adds", "local database"))))
    assert(ReviewPrompt.parse(ujson.read("""{"verdicts": []}""")).isLeft)
  }

  test("1.4 a judgement counts only if it is the one entry for its number and copies its phrase; otherwise the reviewer is asked again") {
    val (ctx, s1) = context("REQ-017")
    val two = ScriptedElaborator((_, _) => Vector(
      construct("The user can limit the scan by a number of commits or a start date", "precondition", "x"),
      construct("without one, a default limit applies", "precondition", "y")))
    // attempt 1 numbers from 0 (shifted) and repeats a number; attempt 2 answers properly
    val shifted = ScriptedReviewer((items, attempt) =>
      if attempt == 1 then ReviewAnswer(Vector(
        ReviewJudgement(0, items(0).phrase, "adds", "z"), ReviewJudgement(1, items(1).phrase, "restates", ""),
        ReviewJudgement(2, items(1).phrase, "restates", ""), ReviewJudgement(2, items(1).phrase, "adds", "w")), "", Usage(), None)
      else ReviewAnswer(Vector(ReviewJudgement(1, items(0).phrase, "restates", ""), ReviewJudgement(2, items(1).phrase, "adds", "w")),
        "", Usage(), None))
    val e = Step1.elaborate(ctx, s1, two, shifted, 3)
    assertEquals(shifted.seen.map(_._4).toVector, Vector(1, 2))
    assertEquals(e.review.size, 2)
    assertEquals(e.constructs.map(_.verdict), Vector("accepted", "provisional"))

    // the same bad reply twice: nothing is accepted
    val alwaysShifted = ScriptedReviewer((items, _) => ReviewAnswer(
      items.map(i => ReviewJudgement(i.index - 1, i.phrase, "restates", "")), "", Usage(), None))
    assertEquals(Step1.elaborate(ctx, s1, two, alwaysShifted, 3).constructs.map(_.verdict), Vector("provisional", "provisional"))

    // an unreadable first reply is asked again; a good second reply decides
    val flaky = ScriptedReviewer((items, attempt) =>
      if attempt == 1 then ReviewAnswer(Vector.empty, "not json", Usage(), Some("reply is not valid JSON"))
      else ReviewAnswer(items.map(i => ReviewJudgement(i.index, i.phrase, "restates", "")), "", Usage(), None))
    assertEquals(Step1.elaborate(ctx, s1, two, flaky, 3).constructs.map(_.verdict), Vector("accepted", "accepted"))
  }

  test("1.4 uncovered text that occurs more than once costs no attempt, and a word's trailing punctuation does not hide a stopword") {
    // REQ-002 says "not" twice ("not forks, not archived"), so a construct for one "not" alone could never be unique
    val r = ratify("REQ-002",
      construct("The workflow discovers candidate public GitHub repositories and narrows them to a filtered list of repositories judged worth mining; discovery returns only public repositories that are", "postcondition", "x"),
      construct("forks, not archived, and have at least one closed issue or merged pull request.", "postcondition", "x"))
    val (ctx, _) = context("REQ-002")
    assertEquals(r.uncovered.map((s, e) => ctx.entry.text.substring(s, e)), Vector("not"))
    assertEquals(r.deviations, Vector.empty)
    assertEquals(StepRatifier.uncoveredSpans("Keep it.", Seq((0, 4))), Vector.empty)
    assertEquals(StepRatifier.uncoveredSpans("Keep data.", Seq((0, 4))), Vector((5, 9)))
  }

  // ---- 1.5 to 1.8 ----

  private val wholeRequirement = ScriptedElaborator((ctx, _) =>
    Vector(construct(ctx.entry.text, Slots.default(ctx.entry.kind), ctx.entry.text)))

  test("a full run builds every record, and the same inputs give the same epoch") {
    val a = Step1.run(causeway, wholeRequirement, approving, 3)
    assertEquals(a.records.map(_._1), Step1.RecordNames)
    val spec = a.record("specIR")
    assertEquals(spec("protectedRequirements").arr.size, 22)
    assertEquals(spec("acceptedMeanings").arr.size, 22)
    assertEquals(spec("obligations").arr.size, 22)
    assertEquals(spec("variableCandidates").arr.map(_("id").str).toVector, Vector("DN-01"))
    assertEquals(a.record("evidenceLedger")("entries").arr.size, 22)
    assertEquals(a.record("controllerState")("revision").num.toInt, 0)
    val b = Step1.run(causeway, wholeRequirement, approving, 3)
    assertEquals(a.record("epochManifest")("manifestId"), b.record("epochManifest")("manifestId"))
  }

  test("a full run records the review and the coverage in the summary and the reviewer in the epoch") {
    val a = Step1.run(causeway, wholeRequirement, approving, 3)
    val review = a.summary("review")
    assertEquals(review("calls").num.toInt, 22)
    assertEquals(review("restates").num.toInt, 22)
    assertEquals(a.summary("requirementsWithUncoveredText").num.toInt, 0)
    assertEquals(a.record("epochManifest")("reviewer")("type").str, "scripted")
    assert(a.record("epochManifest")("ratifier")("coverage")("stopwords").arr.nonEmpty)
    assertEquals(a.record("ratifiedAssignments")("requirements")(0)("uncovered").arr.size, 0)
    assertEquals(a.record("specIR")("acceptedMeanings")(0)("review")("verdict").str, "restates")
    assertEquals(a.record("constructTable")("constructs")(0)("review")("verdict").str, "restates")
  }

  test("the ledger records who issued each commitment") {
    val ledger = Step1.run(causeway, wholeRequirement, approving, 3).record("evidenceLedger")("entries").arr
    assertEquals(ledger.head("issuer").str, "user (prompt P-0001)")
    assertEquals(ledger(10)("subject").str, "REQ-012")
    assert(ledger(10)("issuer").str.startsWith("assistant"), ledger(10)("issuer").str)
    // REQ-003 was stated by the user and absorbed requirements from DEC-002, which the assistant answered
    assertEquals(ledger(2)("subject").str, "REQ-003")
    assertEquals(ledger(2)("issuer").str, "user (prompt P-0001)")
    assert(ledger(2)("decisionsAnsweredBy")(0).str.startsWith("assistant"), ledger(2).toString)
  }

  // ---- the service ----

  test("the service validates a request at once, runs it in the background and serves the records") {
    val out = Files.createTempDirectory("step1")
    val server = new Step1Server(0, out, 3, ujson.Obj("service" -> "test"), () => wholeRequirement,
      () => approving).start()
    val base = s"http://localhost:${server.getAddress.getPort}"
    val http = HttpClient.newHttpClient()
    def get(path: String) = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString())
    def post(body: ujson.Value) = http.send(HttpRequest.newBuilder(URI.create(base + "/step1/runs"))
      .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body))).build(), HttpResponse.BodyHandlers.ofString())
    try
      assertEquals(get("/health").statusCode, 200)
      val files = causeway
      val body = ujson.Obj("files" -> ujson.Obj(
        "reconciledContract.json" -> files.contract, "promptRecord.json" -> files.promptRecord, "decisions.json" -> files.decisions))

      val refused = post(ujson.Obj("files" -> ujson.Obj("reconciledContract.json" -> "{}")))
      assertEquals(refused.statusCode, 422)

      val accepted = post(body)
      assertEquals(accepted.statusCode, 202, accepted.body)
      val runId = ujson.read(accepted.body)("runId").str
      val deadline = System.currentTimeMillis + 30000
      var status = ""
      while status != "done" && status != "failed" && System.currentTimeMillis < deadline do
        Thread.sleep(100)
        status = ujson.read(get(s"/step1/runs/$runId").body)("status").str
      assertEquals(status, "done")
      val spec = get(s"/step1/runs/$runId/records/specIR")
      assertEquals(spec.statusCode, 200)
      assertEquals(ujson.read(spec.body)("protectedRequirements").arr.size, 22)
      assertEquals(get(s"/step1/runs/$runId/records/nothing").statusCode, 404)
      assertEquals(get("/step1/runs/no-such-run").statusCode, 404)
    finally server.stop(0)
  }

