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

class Step1Spec extends munit.FunSuite:

  private val causewayDir: Path = Paths.get(getClass.getResource("/step1/causeway").toURI)
  private def causeway: Section1Files = Section1Files.fromDirectory(causewayDir)

  private def construct(phrase: String, slot: String, meaning: String, question: String = "",
      readings: Vector[String] = Vector.empty, source: String = "none", decision: String = "", quote: String = "") =
    ProposedConstruct(phrase, slot, meaning, question, readings, source, decision, quote)

  private def context(id: String): (ElaborationContext, Section1) =
    val (snapshot, entries) = Step1.prepare(causeway)
    val s1 = snapshot.section1
    (ElaborationContext(entries.find(_.id == id).get, s1.seedPrompt.get.text, s1.decisions), s1)

  private def ratify(id: String, cs: ProposedConstruct*): Ratification =
    val (ctx, s1) = context(id)
    StepRatifier.ratify(ctx, Proposal(cs.toVector, "", Usage(), None), s1)

  // ---- 1.1 ----

  test("1.1 fingerprints the Causeway contract and counts its requirements by origin") {
    val snapshot = LoadContract.run(causeway)
    assertEquals(snapshot.sha256, "b1b12e0278d18a2187a7b89d200eae91518c996990b943f9de7764df32947f0c")
    assertEquals(snapshot.id, "CS-b1b12e0278d1")
    val counts = snapshot.toJson("counts")
    assertEquals(counts("requirements").num.toInt, 29)
    assertEquals(counts("fromPrompts").num.toInt, 10)
    assertEquals(counts("fromDecisions").num.toInt, 19)
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
    entries.find(_.id == "REQ-011").get.source match
      case Source.DecisionSource(id, _, answer, _) =>
        assertEquals(id, "DEC-001")
        assert(answer.startsWith("Fixed built-in criteria"))
      case other => fail(s"REQ-011 has source $other")
    assertEquals(entries.count(_.binding), 29)
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
    assertEquals(r.deviations, Vector.empty)
  }

  test("1.4 turns a phrase with two readings into an open gap") {
    val r = ratify("REQ-017", construct("a default limit applies", "precondition", "Some default limit applies.",
      "How large is the default limit?", Vector("a fixed number of the most recent commits", "commits from a fixed recent time window")))
    assertEquals(r.constructs.head.verdict, "openGap")
    assertEquals(r.deviations, Vector.empty)
  }

  test("1.4 accepts a settled reading only when its citation is verified") {
    // "genuine bug fixes" in REQ-005 (from the seed prompt) is answered by another passage: decision DEC-003
    val ok = ratify("REQ-005", construct("genuine bug fixes", "postcondition", "A commit counts as a bug fix when the subagent judges it so.",
      "What makes a bug fix genuine?", Vector("the subagent's judgement", "a fixed formula"), source = "decision", decision = "DEC-003",
      quote = "The subagent judges from message, change and linked text"))
    assertEquals(ok.constructs.head.verdict, "accepted")
    assertEquals(ok.constructs.head.provenance("decision").str, "DEC-003")

    val bad = ratify("REQ-017", construct("limit the scan by a number of commits or a start date", "precondition",
      "The user may give a commit count or a start date.", "Is the scan limited by count or by date?", Vector("count", "date"), source = "decision",
      decision = "DEC-004", quote = "the user may pick any limit"))
    assertEquals(bad.constructs.head.verdict, "openGap")
    assertEquals(bad.deviations, Vector.empty) // the question stands as a gap; no new attempt is spent on it
  }

  test("1.4 does not let a requirement's own source answer a question about it, nor a passage without the values in question") {
    // qwen3:4b asked "What is the default limit value?" (100 or 50 commits) and cited DEC-004, the decision REQ-017 came from
    val circular = ratify("REQ-017", construct("without one, a default limit applies", "precondition", "x",
      "What is the default limit value?", Vector("default limit is 100 commits", "default limit is 50 commits"), source = "origin",
      decision = "DEC-004", quote = "A user-specified limit or start date with a default limit; merge commits left out"))
    assertEquals(circular.constructs.head.verdict, "openGap")
    assert(circular.constructs.head.reason.contains("derived from"), circular.constructs.head.reason)

    val noValue = ratify("REQ-017", construct("without one, a default limit applies", "precondition", "x",
      "What is the default limit value?", Vector("100 commits", "50 commits"), source = "decision", decision = "DEC-001",
      quote = "ordered by stars"))
    assertEquals(noValue.constructs.head.verdict, "openGap")
    assert(noValue.constructs.head.reason.contains("states none of them"), noValue.constructs.head.reason)
  }

  test("1.4 finds a citation wherever the user wrote it, whatever source the model named") {
    // qwen3:4b quoted DEC-002's answer for REQ-003 but labelled it "origin" (REQ-003's origin is prompt P-0001)
    val r = ratify("REQ-003", construct("checked out at a pinned (fixed, specific) commit", "effect", "x",
      "What defines the pinned commit?", Vector("given by the user", "current at the time"), source = "origin",
      quote = "otherwise the tool pins the commit current at that moment"))
    assertEquals(r.constructs.head.verdict, "accepted")
    assertEquals(r.constructs.head.provenance("decision").str, "DEC-002")
    assertEquals(r.deviations, Vector.empty)
  }

  test("1.4 rejects a slot that does not fit the requirement's kind, and an open question needs two readings") {
    val r = ratify("REQ-017",
      construct("limit the scan by a number of commits or a start date", "effect", "x"),
      construct("a default limit applies", "precondition", "x", "How large is it?", Vector("only one")))
    assertEquals(r.constructs.map(_.verdict), Vector("rejected", "openGap"))
    assertEquals(r.deviations.size, 2)
  }

  test("1.4 places a phrase of a prompt-backed requirement in the user's prompt") {
    // "pinned" is in the requirement ("...at a pinned (fixed, specific) commit") and in the user's words ("...at a pinned commit.")
    val r = ratify("REQ-003", construct("pinned", "effect", "The clone is checked out at one fixed commit."))
    assertEquals(r.constructs.head.verdict, "accepted")
    assertEquals(r.constructs.head.promptSpan, Some(("P-0001", 476, 482)))
    val (ctx, _) = context("REQ-003")
    assertEquals(r.constructs.head.requirementSpan.map(_._1), Some(ctx.entry.text.indexOf("pinned")))
    // a phrase of the requirement that the user never wrote has no place in the prompt
    val own = ratify("REQ-003", construct("a local clone", "effect", "A copy of the repository exists locally."))
    assertEquals(own.constructs.head.promptSpan, None)
  }

  test("1.3/1.4 retry with the ratifier's feedback, and fall back to the whole requirement when nothing survives") {
    val (ctx, s1) = context("REQ-017")
    val elaborator = ScriptedElaborator((_, attempt) =>
      if attempt == 1 then Vector(construct("a default limit of 50", "precondition", "x"))
      else Vector(construct("a default limit applies", "precondition", "x", "How large is it?", Vector("a", "b"))))
    val e = Step1.elaborate(ctx, s1, elaborator, maxAttempts = 3)
    assertEquals(e.attempts.size, 2)
    assert(elaborator.feedbackSeen(1)._3.head.contains("copied exactly"), elaborator.feedbackSeen.toString)
    assertEquals(e.constructs.map(_.verdict), Vector("openGap"))

    val silent = Step1.elaborate(ctx, s1, ScriptedElaborator((_, _) => Vector.empty), maxAttempts = 3)
    assertEquals(silent.attempts.size, 1)
    assert(silent.constructs.head.fallback)
    assertEquals(silent.constructs.head.phrase, ctx.entry.text)
  }

  // ---- 1.5 to 1.8 ----

  private val wholeRequirement = ScriptedElaborator((ctx, _) =>
    Vector(construct(ctx.entry.text, Slots.default(ctx.entry.kind), ctx.entry.text)))

  test("a full run builds every record, and the same inputs give the same epoch") {
    val a = Step1.run(causeway, wholeRequirement, 3)
    assertEquals(a.records.map(_._1), Step1.RecordNames)
    val spec = a.record("specIR")
    assertEquals(spec("protectedRequirements").arr.size, 29)
    assertEquals(spec("acceptedMeanings").arr.size, 29)
    assertEquals(spec("obligations").arr.size, 29)
    assertEquals(spec("variableCandidates").arr.map(_("id").str).toVector, Vector("DN-01"))
    assertEquals(a.record("evidenceLedger")("entries").arr.size, 29)
    assertEquals(a.record("controllerState")("revision").num.toInt, 0)
    val b = Step1.run(causeway, wholeRequirement, 3)
    assertEquals(a.record("epochManifest")("manifestId"), b.record("epochManifest")("manifestId"))
  }

  test("the ledger records who issued each commitment") {
    val ledger = Step1.run(causeway, wholeRequirement, 3).record("evidenceLedger")("entries").arr
    assertEquals(ledger.head("issuer").str, "user (prompt P-0001)")
    assertEquals(ledger(10)("subject").str, "REQ-011")
    assert(ledger(10)("issuer").str.startsWith("assistant"), ledger(10)("issuer").str)
  }

  // ---- the service ----

  test("the service validates a request at once, runs it in the background and serves the records") {
    val out = Files.createTempDirectory("step1")
    val server = new Step1Server(0, out, 3, ujson.Obj("service" -> "test"), () => wholeRequirement).start()
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
      assertEquals(ujson.read(spec.body)("protectedRequirements").arr.size, 29)
      assertEquals(get(s"/step1/runs/$runId/records/nothing").statusCode, 404)
      assertEquals(get("/step1/runs/no-such-run").statusCode, 404)
    finally server.stop(0)
  }

