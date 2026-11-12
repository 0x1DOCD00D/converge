package converge.step1

import java.nio.file.{Path, Paths}
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter

/**
 * CONVERGE Step 1 entry point.
 *
 *   run   --section1 <GRAIL-DX out/ folder> [--out <folder>]   one run, records written to the folder
 *   serve [--port <port>]                                     the HTTP service (see Step1Server)
 *
 * Environment: OLLAMA_URL, OLLAMA_MODEL (default qwen3:4b), CONVERGE_STEP1_TEMPERATURE (0.0), CONVERGE_STEP1_SEED (17),
 * CONVERGE_STEP1_NUM_CTX (4096), CONVERGE_STEP1_ATTEMPTS (3), CONVERGE_STEP1_OUT (out/step1), CONVERGE_STEP1_PORT (8090).
 */
object Step1Main:
  private val usage =
    """usage: converge.step1.Step1Main run --section1 <GRAIL-DX out folder> [--out <folder>]
      |       converge.step1.Step1Main serve [--port <port>]""".stripMargin

  def main(args: Array[String]): Unit =
    val env = sys.env
    val flags = args.drop(1).grouped(2).collect { case Array(k, v) if k.startsWith("--") => k.drop(2) -> v }.toMap
    val attempts = env.get("CONVERGE_STEP1_ATTEMPTS").flatMap(_.toIntOption).filter(_ > 0).getOrElse(3)
    val outRoot = Paths.get(env.getOrElse("CONVERGE_STEP1_OUT", "out/step1")).toAbsolutePath.normalize
    val settings = ElaboratorSettings.fromEnvironment(env)
    val code =
      try
        args.headOption match
          case Some("run") if flags.contains("section1") =>
            run(Paths.get(flags("section1")), flags.get("out").map(Paths.get(_)), outRoot, settings, attempts)
          case Some("elaborate") if flags.contains("section1") && flags.contains("requirement") =>
            // 1.3 and 1.4 for one requirement, printed: for checking the model before a full run.
            val (snapshot, entries) = Step1.prepare(Section1Files.fromDirectory(Paths.get(flags("section1"))))
            val s1 = snapshot.section1
            val elaborator = new OllamaElaborator(settings)
            flags("requirement").split(",").map(_.trim).filter(_.nonEmpty).foreach { id =>
              val entry = entries.find(_.id == id).getOrElse(throw Step1Error(s"no requirement $id"))
              println(s"===== $id: ${entry.text}")
              val e = Step1.elaborate(ElaborationContext(entry, s1.seedPrompt.map(_.text).getOrElse(""), s1.decisions), s1, elaborator, attempts)
              e.attempts.zipWithIndex.foreach { case ((p, _, r), i) =>
                println(s"--- attempt ${i + 1}: ${p.usage.promptTokens} prompt tokens, ${p.usage.generatedTokens} generated, " +
                  s"${p.usage.totalDurationNs / 1000000000L} s")
                println(ujson.write(ujson.Arr.from(p.constructs.map(_.toJson)), indent = 2))
                r.deviations.foreach(d => println(s"deviation: $d"))
              }
              println("--- kept")
              e.constructs.foreach(c => println(s"  ${c.verdict}: \"${c.phrase}\" ${if c.question.nonEmpty then s"? ${c.question} ${c.alternatives.mkString("[", " | ", "]")}" else ""} (${c.reason})"))
            }
            0
          case Some("serve") =>
            val port = flags.get("port").orElse(env.get("CONVERGE_STEP1_PORT")).flatMap(_.toIntOption).getOrElse(8090)
            val info = ujson.Obj("service" -> "converge-step1", "model" -> settings.model, "ollama" -> settings.baseUrl,
              "maxAttempts" -> attempts, "outRoot" -> outRoot.toString)
            new Step1Server(port, outRoot, attempts, info, () => new OllamaElaborator(settings)).start()
            println(s"converge step 1 service on port $port (model ${settings.model}, records under $outRoot)")
            Thread.currentThread().join()
            0
          case _ =>
            System.err.println(usage)
            2
      catch
        case e: Step1Error =>
          e.problems.foreach(p => System.err.println(s"error: $p"))
          1
    sys.exit(code)

  private def run(section1: Path, out: Option[Path], outRoot: Path, settings: ElaboratorSettings, attempts: Int): Int =
    val files = Section1Files.fromDirectory(section1)
    val (snapshot, _) = Step1.prepare(files)
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now)
    val dir = out.getOrElse(outRoot.resolve(s"$stamp-${snapshot.sha256.take(8)}"))
    println(s"contract ${snapshot.id} (${snapshot.section1.requirements.size} requirements); elaborator ${settings.model}")
    val outcome = Step1.run(files, new OllamaElaborator(settings), attempts,
      (done, total, current) => if current.nonEmpty then println(s"  [${done + 1}/$total] $current"))
    Step1.write(dir, outcome)
    println(ujson.write(outcome.summary, indent = 2))
    println(s"records written to $dir")
    0
