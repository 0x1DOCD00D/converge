package converge

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.util.Try
import ujson.Value

final class OllamaClient(config: Config):
  private val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(15))
    .build()

  private var cumulativeUsage: Usage = Usage()

  def usage: Usage = cumulativeUsage

  def serverVersion(): String =
    val response = sendGet("version", Duration.ofSeconds(30))
    Json.string(ujson.read(response), "version")

  def modelDigest(modelName: String): Option[String] =
    val root = ujson.read(sendGet("tags", Duration.ofSeconds(30)))
    val models = root.obj.get("models") match
      case Some(array: ujson.Arr) => array.value.toVector
      case _ => Vector.empty

    models.find { item =>
      val objectValue = item.obj
      val name = objectValue.get("name").orElse(objectValue.get("model")) match
        case Some(ujson.Str(value)) => Some(value)
        case _ => None
      name.exists(candidate => sameModel(candidate, modelName))
    }.flatMap { item =>
      item.obj.get("digest") match
        case Some(ujson.Str(value)) => Some(value)
        case _ => None
    }

  def requireModelInstalled(modelName: String): String =
    modelDigest(modelName).getOrElse {
      throw new IllegalStateException(
        s"Ollama model '$modelName' is not installed. Run: ollama pull $modelName"
      )
    }

  def requireModelInstalled(): String = requireModelInstalled(config.model)

  def embed(texts: Vector[String]): Vector[Vector[Double]] =
    if texts.isEmpty then Vector.empty
    else
      val requestBody = ujson.Obj(
        "model" -> config.embeddingModel,
        "input" -> Json.arr(texts),
        "truncate" -> false
      )

      val responseBody = sendPost("embed", requestBody, Duration.ofMinutes(10))
      val root = ujson.read(responseBody)
      val vectors = root.obj.get("embeddings") match
        case Some(array: ujson.Arr) =>
          array.value.toVector.map { item => item.arr.toVector.map(_.num) }
        case _ => Vector.empty

      if vectors.size != texts.size then
        throw new IllegalStateException(
          s"Ollama returned ${vectors.size} embeddings for ${texts.size} input texts"
        )

      val callUsage = Usage(
        calls = 1L,
        totalDurationNs = Json.longOrZero(root, "total_duration"),
        loadDurationNs = Json.longOrZero(root, "load_duration"),
        promptTokens = Json.longOrZero(root, "prompt_eval_count"),
        generatedTokens = 0L
      )
      cumulativeUsage = cumulativeUsage + callUsage
      vectors

  def generateJson(
      system: String,
      prompt: String,
      schema: Value,
      seed: Int,
      temperature: Double = config.temperature
  ): LlmJson =
    val requestBody = ujson.Obj(
      "model" -> config.model,
      "system" -> system,
      "prompt" -> prompt,
      "stream" -> false,
      "think" -> false,
      "keep_alive" -> "15m",
      "format" -> schema,
      "options" -> ujson.Obj(
        "temperature" -> temperature,
        "top_p" -> config.topP,
        "seed" -> seed,
        "num_ctx" -> config.numCtx
      )
    )

    val responseBody = sendPost("generate", requestBody, Duration.ofMinutes(20))
    val root = ujson.read(responseBody)
    val raw = Json.string(root, "response")
    val parsed = parseJsonPayload(raw)

    val callUsage = Usage(
      calls = 1L,
      totalDurationNs = Json.longOrZero(root, "total_duration"),
      loadDurationNs = Json.longOrZero(root, "load_duration"),
      promptTokens = Json.longOrZero(root, "prompt_eval_count"),
      generatedTokens = Json.longOrZero(root, "eval_count")
    )
    cumulativeUsage = cumulativeUsage + callUsage

    LlmJson(content = parsed, usage = callUsage, rawResponse = raw)

  private def sendGet(endpoint: String, timeout: Duration): String =
    val request = HttpRequest.newBuilder()
      .uri(URI.create(s"${config.baseUrl}/$endpoint"))
      .timeout(timeout)
      .GET()
      .build()

    val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    requireSuccess(response.statusCode(), response.body(), s"GET /api/$endpoint")
    response.body()

  private def sendPost(endpoint: String, body: Value, timeout: Duration): String =
    val request = HttpRequest.newBuilder()
      .uri(URI.create(s"${config.baseUrl}/$endpoint"))
      .timeout(timeout)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body), StandardCharsets.UTF_8))
      .build()

    val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    requireSuccess(response.statusCode(), response.body(), s"POST /api/$endpoint")
    response.body()

  private def parseJsonPayload(raw: String): Value =
    Try(ujson.read(raw)).getOrElse {
      val stripped = raw.trim
        .stripPrefix("```json")
        .stripPrefix("```")
        .stripSuffix("```")
        .trim
      ujson.read(stripped)
    }

  private def sameModel(installed: String, requested: String): Boolean =
    installed == requested ||
    installed.stripSuffix(":latest") == requested.stripSuffix(":latest")

  private def requireSuccess(status: Int, body: String, operation: String): Unit =
    if status / 100 != 2 then
      throw new IllegalStateException(
        s"$operation failed with HTTP $status: ${body.take(1600)}"
      )
