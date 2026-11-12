package converge.step1

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.concurrent.{ConcurrentHashMap, Executors}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import ujson.Value

/**
 * The Step 1 HTTP service. GRAIL-DX (or anyone) posts the Section 1 files and gets the Step 1 records back.
 *
 *   GET  /health                               service status
 *   POST /step1/runs                           body {"files": {...}}; validates 1.1-1.2 at once, then runs in the background
 *   GET  /step1/runs                           every run this service knows
 *   GET  /step1/runs/{runId}                   status and progress; the summary once done
 *   GET  /step1/runs/{runId}/records/{name}    one record, e.g. specIR
 *
 * Runs are executed one at a time (the model is the bottleneck) and stored under `outRoot/<runId>/`.
 */
final class Step1Server(port: Int, outRoot: Path, maxAttempts: Int, info: ujson.Obj, makeElaborator: () => Elaborator):

  private final class RunState(val runId: String, val snapshotId: String, val total: Int):
    @volatile var status: String = "queued"
    @volatile var done: Int = 0
    @volatile var current: String = ""
    @volatile var error: Option[String] = None
    @volatile var summary: Option[Value] = None
    val createdAt: String = Instant.now.toString

    def toJson: Value =
      val o = ujson.Obj("runId" -> runId, "status" -> status, "contractSnapshot" -> snapshotId, "createdAt" -> createdAt,
        "progress" -> ujson.Obj("done" -> done, "total" -> total, "current" -> current))
      error.foreach(e => o("error") = e)
      summary.foreach(s => o("summary") = s)
      if status == "done" then o("records") = ujson.Arr.from(Step1.RecordNames)
      o

  private val runs = new ConcurrentHashMap[String, RunState]()
  private val worker = Executors.newSingleThreadExecutor(r => { val t = new Thread(r, "step1-worker"); t.setDaemon(true); t })
  private lazy val elaborator: Elaborator = makeElaborator()
  private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

  def start(): HttpServer =
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/", exchange => handle(exchange))
    server.setExecutor(Executors.newFixedThreadPool(4))
    server.start()
    server

  private def handle(ex: HttpExchange): Unit =
    try
      val parts = ex.getRequestURI.getPath.split("/").filter(_.nonEmpty).toList
      (ex.getRequestMethod, parts) match
        case ("GET", List("health"))                              => reply(ex, 200, ujson.Obj.from(info.value.toSeq :+ ("status" -> ujson.Str("ok"))))
        case ("POST", List("step1", "runs"))                      => submit(ex)
        case ("GET", List("step1", "runs"))                       => reply(ex, 200, ujson.Obj("runs" -> ujson.Arr.from(runs.values.asScala.toVector.sortBy(_.createdAt).map(_.toJson))))
        case ("GET", List("step1", "runs", id))                   => status(ex, id)
        case ("GET", List("step1", "runs", id, "records", name))  => record(ex, id, name)
        case (_, List("health") | List("step1", "runs") | List("step1", "runs", _) | List("step1", "runs", _, "records", _)) =>
          reply(ex, 405, ujson.Obj("error" -> "method not allowed"))
        case _ => reply(ex, 404, ujson.Obj("error" -> "not found"))
    catch
      case e: Exception => Try(reply(ex, 500, ujson.Obj("error" -> Option(e.getMessage).getOrElse(e.toString))))
    finally ex.close()

  private def submit(ex: HttpExchange): Unit =
    val body = new String(ex.getRequestBody.readAllBytes(), UTF_8)
    val parsed = Try(ujson.read(body)).toEither.left.map(e => List(s"the request body is not JSON: ${e.getMessage}"))
    val prepared = parsed.flatMap { json =>
      try
        val files = Section1Files.fromApiBody(json)
        val (snapshot, entries) = Step1.prepare(files)
        Right((files, snapshot, entries))
      catch case e: Step1Error => Left(e.problems)
    }
    prepared match
      case Left(problems) => reply(ex, 422, ujson.Obj("error" -> "the Section 1 files were refused", "problems" -> ujson.Arr.from(problems)))
      case Right((files, snapshot, entries)) =>
        val base = s"${stamp.format(Instant.now)}-${snapshot.sha256.take(8)}"
        val runId = Iterator.from(1).map(n => if n == 1 then base else s"$base-$n").find(id => !runs.containsKey(id) && !Files.exists(outRoot.resolve(id))).get
        val state = RunState(runId, snapshot.id, entries.count(_.binding))
        runs.put(runId, state)
        val task: Runnable = () =>
          state.status = "running"
          try
            val outcome = Step1.run(files, elaborator, maxAttempts, (d, _, c) => { state.done = d; state.current = c })
            Step1.write(outRoot.resolve(runId), outcome)
            state.summary = Some(outcome.summary)
            state.status = "done"
          catch
            case e: Step1Error => state.error = Some(e.problems.mkString("; ")); state.status = "failed"
            case e: Throwable  => state.error = Some(Option(e.getMessage).getOrElse(e.toString)); state.status = "failed"
        worker.submit(task)
        reply(ex, 202, ujson.Obj("runId" -> runId, "status" -> state.status, "contractSnapshot" -> snapshot.id,
          "requirements" -> state.total, "statusUrl" -> s"/step1/runs/$runId"))

  private def status(ex: HttpExchange, id: String): Unit =
    Option(runs.get(id)) match
      case Some(state) => reply(ex, 200, state.toJson)
      case None =>
        // A run from an earlier start of the service: answer from its files.
        val summary = outRoot.resolve(id).resolve("summary.json")
        if safeId(id) && Files.isRegularFile(summary) then
          reply(ex, 200, ujson.Obj("runId" -> id, "status" -> "done", "summary" -> ujson.read(Files.readString(summary, UTF_8)),
            "records" -> ujson.Arr.from(Step1.RecordNames)))
        else reply(ex, 404, ujson.Obj("error" -> s"no run $id"))

  private def record(ex: HttpExchange, id: String, name: String): Unit =
    val file = outRoot.resolve(id).resolve(s"$name.json")
    if !safeId(id) || !(Step1.RecordNames :+ "summary").contains(name) then reply(ex, 404, ujson.Obj("error" -> s"no record $name"))
    else if !Files.isRegularFile(file) then reply(ex, 404, ujson.Obj("error" -> s"run $id has no record $name (yet)"))
    else replyText(ex, 200, Files.readString(file, UTF_8))

  private def safeId(id: String): Boolean = id.matches("[A-Za-z0-9._-]+") && !id.contains("..")

  private def reply(ex: HttpExchange, code: Int, body: Value): Unit = replyText(ex, code, ujson.write(body, indent = 2) + "\n")

  private def replyText(ex: HttpExchange, code: Int, text: String): Unit =
    val bytes = text.getBytes(UTF_8)
    ex.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
    ex.sendResponseHeaders(code, bytes.length.toLong)
    ex.getResponseBody.write(bytes)
