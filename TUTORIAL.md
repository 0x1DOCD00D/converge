# COalgebraic Navigation via VERified Geometric Embeddings (CONVERGE) Offline step-by-step tutorial

This tutorial explains exactly how the `converge` Scala 3 application computes and empirically validates a precise prompt for a specialized LLM agent using local Ollama models. The input is a complete `EngineerIntent`. The program is not trying to guess the engineer's intent from a tiny phrase such as `build.sbt`. EngineerIntent already states the desired operation, target artifact, ownership, allowed writes, forbidden writes, effects, validation, evidence, anchors, and handoff. CONVERGE uses that rich specification as an invariant while it computes a prompt whose routing and simulated behavior better match the specification.

## How CONVERGE works in this implementation

_COalgebraic Navigation via VERified Geometric Embeddings (CONVERGE)_ is a prompt computation and validation system. It is not a free form prompt rewriter; its input is a complete `EngineerIntent` that already states what the engineer wants done, which artifact is authoritative, which agent owns the operation, which writes and effects are allowed, which writes and effects are forbidden, how the result must be validated, what evidence must be returned, and where out of scope work must be handed off. The job of CONVERGE is to transform that rich specification into a prompt that an actual local LLM routes and interprets more reliably, while refusing any transformation that changes the engineer's original authority, scope, or success criteria.
```ini
  "seed": "build.sbt",
  "normalizedGoal": "Update the named sbt dependency",
  "artifactClass": "BuildDefinition",
  "artifactLocators": ["build.sbt"],
  "operation": "ModifyDependencyCoordinate",
  "coordinate": "org.typelevel %% cats-effect",
  "fromVersion": "3.5.4",
  "toVersion": "3.6.1",
  "expectedOwner": "build-agent",
  "allowedWrites": ["build.sbt"],
  "forbiddenWrites": ["src/**", "Dockerfile", "infra/**", ".github/**", "deployment/**"],
  "allowedEffects": ["repo.write:build.sbt", "build.execute"],
  "forbiddenEffects": ["repo.write:src/**", "repo.write:Dockerfile", "repo.write:infra/**", "cloud.apply", "secret.read"],
  "positiveAnchors": ["build.sbt", "libraryDependencies", "ModuleID", "dependency coordinate", "sbt update"],
  "negativeAnchors": ["pom.xml", "package.json", "Scala source refactoring", "Dockerfile", "Terraform", "deployment"],
  "validationCommands": ["sbt clean update test"],
  "requiredEvidence": ["unified diff", "selected dependency graph", "eviction output", "exit status"],
  "handoffOwner": "source-agent"
```

The Scala 3 application is the actor that controls this entire process. Ollama supplies two kinds of model computation to the Scala application: a generative model that proposes structured language artifacts and makes routing decisions, and an embedding model that maps already existing text phrases into numerical vectors. Neither Ollama model is allowed to decide what is correct, because correctness comes from the engineer supplied intent, the architect supplied authority map, the constitution, and the labeled routing tests. Scala therefore treats model output as either an untrusted proposal or an empirical observation and performs all admission, rejection, scoring, comparison, and final prompt construction outside the LLM.

At the highest level, the implementation performs the following computation.

```text
EngineerIntent
     |
     v
Validate against authority and constitution
     |
     v
Load current agent catalog
     |
     v
Measure baseline routing behavior
     |
     v
Repair catalog boundaries if necessary
     |
     v
Compile EngineerIntent into ratified TaskIR
     |
     v
Collect candidate routing phrases
     |
     v
Embed candidate phrases and agent reference texts
     |
     v
Measure geometric target versus rival proximity
     |
     v
Behaviorally test promising candidate phrases
     |
     v
Select nonregressing routing anchors
     |
     v
Render precise routing request and specialist prompt
     |
     v
Test routing and simulated action behavior
     |
     v
Evaluate held out routing cases
     |
     v
Optionally evaluate the real target repository
     |
     v
Write report, prompt, routing request, and certificate evidence
```

The important feature of this pipeline is that every arrow has an identified source, an identified actor, a concrete implementation mechanism, and a specific purpose. For example, `EngineerIntent -> TaskIR` does not mean that "CONVERGE somehow understands the intent." It means that `Main.scala` passes an already loaded `EngineerIntent` to the prompt compilation code, the local generative model proposes a JSON `TaskIR`, and deterministic Scala ratification compares every proposed field against the authoritative `EngineerIntent`. The proposal survives only if it preserves the original task exactly.

## The three systems that must not be confused

There are three distinct systems involved in a complete experiment. The first is the `converge-offline` Scala application described in this tutorial, the second is the local Ollama server and its downloaded models, and the third is the target application that a specialist LLM may eventually modify. The directory structure of `converge-offline` belongs only to the first system and imposes no structure whatsoever on the target application.

The CONVERGE application contains the controller code, experiment inputs, routing tests, and generated evidence. Ollama runs as a separate local process and exposes HTTP endpoints that the Scala application calls. The target application is another repository whose structure might contain services, modules, Terraform, Docker files, generated agents, or anything else, and CONVERGE accesses that repository only when the optional concrete oracle is enabled.

The relationship is therefore:

```text
converge-offline Scala application
        |
        | HTTP requests
        v
local Ollama server
        |
        | model responses
        v
converge-offline Scala application
        |
        | computed specialist prompt
        v
specialist LLM or agent
        |
        | optional real modifications
        v
separate target application repository
```

This separation matters because otherwise a path such as `build.sbt` can become ambiguous. `build.sbt` in an `EngineerIntent` refers to an artifact of the target engineering task, whereas the root `build.sbt` of `converge-offline` is simply the build definition for the CONVERGE implementation itself. The Scala controller keeps these roles separate by loading task paths from `engineer-intent.json` and using `CONVERGE_REPO` only when it is explicitly asked to inspect a real target repository.

## The role of Domain.scala

`Domain.scala` defines the typed language used by the rest of the application. The source of these types is the CONVERGE design itself: the implementation author has decided that concepts such as `EngineerIntent`, `Agent`, `TaskIR`, `RouteTest`, `RouteEvaluation`, `AnchorResult`, and `ConcreteOracleResult` must exist as explicit Scala values rather than as loosely formatted text. The Scala compiler then becomes an additional enforcement mechanism because later stages have to pass correctly typed objects instead of arbitrary prose.

For example, `EngineerIntent` is represented as a case class containing fields such as:

```scala
final case class EngineerIntent(
    seed: String,
    normalizedGoal: String,
    artifactClass: String,
    artifactLocators: Vector[String],
    operation: String,
    coordinate: String,
    fromVersion: String,
    toVersion: String,
    expectedOwner: String,
    allowedWrites: Vector[String],
    forbiddenWrites: Vector[String],
    allowedEffects: Vector[String],
    forbiddenEffects: Vector[String],
    positiveAnchors: Vector[String],
    negativeAnchors: Vector[String],
    validationCommands: Vector[String],
    requiredEvidence: Vector[String],
    handoffOwner: String
)
```

The source of the values in this object is `config/engineer-intent.json`, which is supplied by the engineer or by an upstream engineering interface whose output the engineer has already approved. `ExperimentInputs.load` in `Domain.scala` reads the file with Java NIO, parses the JSON using ujson, and creates the immutable Scala object. The purpose is to make the engineer's specification an explicit input object that later model calls may elaborate but may not silently redefine.

`Domain.scala` also defines `ExperimentInputs`, whose job is to collect all six experiment inputs into one typed object. The source files are `engineer-intent.json`, `agents.json`, `authority.json`, `constitution.json`, `routing-development.json`, and `routing-confirmation.json`, and `ExperimentInputs.load` is the Scala actor that reads them. The loader also hashes the raw contents so that the final report can identify exactly which input versions produced a particular result.

The validation performed by `ExperimentInputs.validate` is important because it occurs before Ollama is trusted with anything. Scala checks that agent identifiers are unique, that `expectedOwner` and `handoffOwner` actually exist in the catalog, that the authority map names only known agents, that the expected owner declares ownership of the required artifact class, and that every allowed write is covered by the owner's writable path patterns. Scala also checks that required constitutional prohibitions appear in the intent, that the same effect is not simultaneously allowed and forbidden, that evidence exists when the constitution requires it, and that every routing test names a valid expected owner.

Consider the dependency update example. If `EngineerIntent` says that `build-agent` owns `ModifyDependencyCoordinate` on `BuildDefinition`, but `authority.json` says that `source-agent` owns that combination, Scala terminates before making a model call. The source of truth in this comparison is the engineer supplied intent together with the architect supplied authority map, the actor performing the comparison is `ExperimentInputs.validate`, and the mechanism is exact typed comparison. The purpose is to prevent the experiment from optimizing a prompt around a contradictory or unauthorized task specification.

## Where the agent catalog comes from

The initial agent catalog does not come from CONVERGE and is not invented by Ollama during the experiment. Its source is the existing multi-agent application being optimized, where the descriptions may originally have been written by a human architect or generated by an earlier application generation process and then accepted as the current configuration. For the experiment, that current configuration is normalized into `config/agents.json`, and `ExperimentInputs.load` is the Scala actor that parses it into `Vector[Agent]`.

An agent record contains information such as its identifier, router-visible description, owned artifact classes, writable paths, and forbidden effects. The router-visible description is especially important because this is the text that the routing LLM actually compares when selecting an owner. The structured ownership fields are equally important because they provide information that Scala can use independently of whatever persuasive or misleading language happens to appear in the prose description.

For example, BuildAgent might be represented conceptually as:

```json
{
  "id": "build-agent",
  "description": "Handles Scala builds, sbt, dependencies, build configuration, and setup.",
  "ownedArtifacts": [
    "BuildDefinition"
  ],
  "writablePaths": [
    "build.sbt",
    "project/**"
  ],
  "forbiddenEffects": [
    "cloud.apply",
    "secret.read"
  ]
}
```

The description is not ground truth. It is part of the routing mechanism being evaluated and may in fact be the source of a routing error. The authority map is kept separate precisely so that CONVERGE can say, for example, that SourceAgent sounds relevant to a dependency problem but BuildAgent is nevertheless the authoritative owner of the requested `BuildDefinition` operation.

## Where the authority map comes from

The authority map is supplied by the architect responsible for the multi-agent application's ownership boundaries. Its source is `config/authority.json`, its reader is `ExperimentInputs.load`, and its purpose is to provide an answer to the question "who owns this operation?" that is independent of the language used in the agent descriptions. This separation prevents the router from becoming its own judge.

A rule can conceptually state:

```json
{
  "artifactClass": "BuildDefinition",
  "operation": "ModifyDependencyCoordinate",
  "owner": "build-agent"
}
```

If the `EngineerIntent` names the same artifact class and operation, Scala finds this rule and checks that the declared owner equals `EngineerIntent.expectedOwner`. The actor performing this operation is deterministic Scala code rather than an LLM, and the mechanism is exact matching of structured fields. The purpose is to turn agent ownership from an informal linguistic impression into a machine-checkable relation.

This is why CONVERGE can distinguish "SourceAgent knows a lot about Cats Effect" from "SourceAgent owns this task." Knowledge, topical similarity, and ownership are deliberately separate concepts. The router may be influenced by all three, but the final expected label comes from the authority map rather than from whichever description sounds most plausible to the model.

## Where the constitution comes from

The constitution is supplied by the system or security architect and is stored in `config/constitution.json`. It contains requirements that are global to the experiment, such as effects that must always remain forbidden, whether evidence is required before success can be claimed, and whether out of scope writes require an explicit handoff. `ExperimentInputs.load` parses these rules and `ExperimentInputs.validate` checks that the supplied `EngineerIntent` does not already violate them.

This arrangement gives constitutional rules higher authority than model generated proposals. If an LLM proposes a prompt that would improve routing by granting BuildAgent permission to modify `src/main/scala`, that proposal cannot be accepted merely because the routing statistics become better. The source of the prohibition is the constitution and EngineerIntent, the actor applying it is the Scala ratifier, the mechanism is deterministic comparison of write and effect sets, and the purpose is to make optimization subordinate to policy rather than the other way around.

The constitution therefore serves as an invariant over the optimization process. Catalog wording may change, candidate anchors may be added or removed, and prompt formulations may evolve, but the optimization is not permitted to relax constitutional restrictions. This is one of the fundamental differences between CONVERGE and asking an LLM to "improve this prompt until it works."

## Where the routing tests come from

The development routing suite is supplied by the engineer as an executable specification of known ownership boundaries. Its source is `tests/routing-development.json`, and each case contains one expected agent, one or more equivalent request formulations, optional `mustNot` agents, and an indication of whether the case is protected against regression. `ExperimentInputs.load` parses these cases into `RouteTest` objects, and `Router.evaluate` later uses them to measure the actual behavior of the local routing model.

A development test might contain several formulations of the same build task:

```text
Change cats-effect in build.sbt.

Bump the cats-effect ModuleID version in libraryDependencies.

Resolve the cats-effect version in the sbt build definition.
```

The engineer assigns `build-agent` as the expected owner because ownership comes from the architecture rather than from the router. The actor that later selects an agent for each sentence is the local generative Ollama model, while the actor that compares the selected ID with the expected ID is Scala. The purpose of having several formulations is to measure whether routing remains correct when surface wording changes.

The confirmation suite comes from the engineer as well, but it has a different role. Its source is `tests/routing-confirmation.json`, and the optimization code does not use it to select anchors or repair catalog descriptions. It is loaded so that the final system can be evaluated after development has stopped, which makes it possible to distinguish actual generalization from memorization of visible routing cases.

## What Main.scala does at startup

`Main.scala` is the orchestration layer. Its first source of runtime information is the process environment, which `Config.fromEnvironment` converts into a typed `Config` object containing the Ollama URL, model names, trial counts, thresholds, directories, and optional repository location. The purpose of this configuration layer is to let an experiment change the number of trials or enable embedding discovery without editing Scala source.

`Main.scala` then calls `ExperimentInputs.load`, which reads and validates the six experiment input files described above. The actor performing this loading is deterministic Scala code, and no model call occurs until the input set has survived structural and authority checks. The output of this stage is one `ExperimentInputs` object containing the complete immutable starting state of the experiment.

The program next constructs `OllamaClient` using the configured base URL. `OllamaClient` is not itself an LLM; it is Scala code that uses Java `HttpClient` to communicate with the local Ollama server. Its purpose is to make all HTTP details, response parsing, usage accounting, model checks, generation calls, and embedding calls explicit and reusable.

## How the experiment is bound to a specific local model

At startup, `Main.scala` asks `OllamaClient` for the running Ollama server version. The source is the local Ollama process, the actor making the request is Scala, and the mechanism is an HTTP request to the Ollama version endpoint. The returned version is written into the final report so that a result is not silently detached from the runtime that produced it.

`Main.scala` also asks `OllamaClient` to verify that the configured generative model is installed and to obtain its digest. If anchor discovery is enabled, the same process is performed for the configured embedding model. The purpose is to identify the actual local model contents rather than relying only on a friendly model name such as `qwen3:8b`.

The experiment therefore treats the model digest as part of the epoch. If the local model is replaced while keeping the same friendly name, the digest changes and the previous certificate no longer describes the current runtime. This is necessary because CONVERGE makes empirical claims about observed behavior of a particular model configuration, not timeless claims about a model family name.

## Why CONVERGE repairs the routing catalog before computing the final prompt

The current implementation first evaluates and, when necessary, repairs the agent catalog. The source of the initial catalog is `agents.json`, the source of the expected routing labels is the development suite, the actor that orchestrates evaluation is `CatalogRepair`, and the actor making individual routing choices is the local generative model through `Router`. The purpose is to establish a reasonably stable routing environment before measuring which prompt phrases help the current task.

This ordering matters because a catastrophically ambiguous catalog can make prompt optimization misleading. Suppose BuildAgent says it handles "Scala builds and dependencies" while SourceAgent says it handles "Scala, Cats Effect, compiler errors, and dependency-related failures." A request mentioning `cats-effect` may be attracted to SourceAgent even when the authoritative artifact is `build.sbt`, so CONVERGE first measures whether the catalog itself creates a systematic ownership ambiguity.

`Router.evaluate` does not ask the model once and declare victory. Scala creates a fixed schedule of repeated trials that changes random seed, rotates catalog order, and cycles through equivalent request variants. The local generative model produces one structured `selectedAgent` result for each trial, while Scala records all decisions and computes the expected-owner rate, strongest-rival rate, routing margin, forbidden selections, and overall pass status.

If BuildAgent wins eight of ten trials and SourceAgent wins two, Scala computes an expected-owner rate of 0.8 and a strongest-rival rate of 0.2. The routing margin is therefore 0.6, and that number is produced by Scala from recorded route decisions rather than reported by the LLM. The purpose of the margin is to distinguish a decisive boundary from a fragile case in which the correct owner happens to win by one completion.

## How catalog repair works

When a development case fails, `CatalogRepair` uses the failed case as a concrete counterexample. The source of the failure is the recorded `RouteEvaluation`, which identifies both the expected owner and the strongest incorrect rival, and the actor selecting a repair target is Scala. The purpose is to repair one observed ownership boundary rather than allowing a model to rewrite every agent description freely.

The local generative model may be asked to propose a small set of boundary terms, but its choice is constrained to a finite vocabulary defined by the application. A typical vocabulary might contain `build.sbt`, `libraryDependencies`, `ModuleID`, `src/main/scala`, `Dockerfile`, `Terraform resource`, and `ECS task definition`. The actor proposing a subset is Ollama, but Scala constructs a JSON schema that prevents the model from returning arbitrary prose outside the approved search space.

Suppose the failed case should route to BuildAgent but SourceAgent is the strongest rival. Ollama may propose `build.sbt`, `libraryDependencies`, and `ModuleID` as discriminating atoms. Scala then ratifies the proposal by checking that the expected agent is really the failed case owner, that the wrong agent is really the observed rival, that every atom belongs to the finite vocabulary, and that the proposal remains within the edit budget.

After ratification, Scala applies the edit symmetrically. BuildAgent receives a deterministic positive boundary constructed from the selected atoms, while SourceAgent receives a deterministic mirrored exclusion that says those same build-definition coordinates do not belong to SourceAgent. The purpose of this paired modification is to increase the correct attraction and decrease the specific rival attraction rather than merely making one description louder.

The candidate catalog is not accepted immediately. `Router.evaluate` reruns the complete development suite, and Scala checks that previously passing protected cases remain passing. The source of acceptance is therefore global replay evidence, the actor making the admission decision is Scala, and the purpose is to prevent a repair from simply moving the error from one routing boundary to another.

## Why TaskIR exists even though EngineerIntent is already complete

After catalog repair, `Main.scala` invokes `PromptCompiler.compile`. The source of truth for this stage remains `EngineerIntent`; the local generative model is only asked to propose a structured `TaskIR` representation of that already specified task. The purpose of this step is not to discover the engineer's intent but to test whether the model can construct the typed projection consumed by the downstream prompt renderer without changing authority or scope.

Suppose EngineerIntent contains:

```text
artifactClass = BuildDefinition
artifactLocator = build.sbt
operation = ModifyDependencyCoordinate
coordinate = org.typelevel %% cats-effect
fromVersion = 3.5.4
toVersion = 3.6.1
expectedOwner = build-agent
allowedWrites = build.sbt
```

The prompt compiler sends those facts to the local generative model and requires a JSON object matching the `TaskIR` schema. The model may reorder lists or make other harmless representational choices, but it is not allowed to replace `build.sbt` with another path, add `Dockerfile`, change the version, change the owner, remove required evidence, or enlarge the effect set.

The actor that decides whether a proposal is valid is the Scala `Ratifier`, not the model that generated the proposal. `Ratifier.checkTask` compares every important scalar and set-valued field with the corresponding EngineerIntent field and produces explicit deviations when a mismatch is found. If a proposal fails, those deviations may be returned to the model for another bounded attempt, but the invariant itself never changes.

When a proposal finally passes, Scala canonicalizes it. Canonicalization means that the authoritative EngineerIntent values and ordering are restored into a stable `TaskIR` representation so that harmless JSON ordering differences cannot create different prompts. The output is therefore not "whatever the model thought the task was," but a typed object whose admission was conditioned on exact preservation of the engineer supplied contract.

## Where candidate anchor phrases come from

Anchor discovery begins only after CONVERGE already knows the intended task and the current repaired catalog. The source of the initial candidate vocabulary is not an embedding vector and is not some hidden model dimension; it is ordinary human-readable text collected from authoritative or explicitly identified sources. `CandidatePhraseCollector.scala` is the Scala component responsible for creating this candidate set and recording where every phrase came from.

One source is `EngineerIntent.positiveAnchors`. In the current data model these fields are named anchors, but when `CONVERGE_RUN_ANCHORS=true` they should be understood as engineer supplied positive candidate terms that still have to be measured. Examples for the running task are `build.sbt`, `libraryDependencies`, `ModuleID`, `dependency coordinate`, and `sbt update`.

Another source is the structured target-agent profile. If BuildAgent declares ownership of `BuildDefinition`, writable path `build.sbt`, and a router description discussing sbt dependencies, the collector can add those already existing strings to the candidate universe. The source is therefore the checked-in catalog, the actor collecting them is Scala, and the purpose is to avoid depending only on vocabulary that happened to be repeated manually in EngineerIntent.

Development routing cases provide another source of vocabulary. The collector can observe technical phrases that appear in requests already labeled for the expected owner, while confirmation cases are deliberately excluded from this process. The purpose is to allow the candidate vocabulary to reflect realistic development requests without leaking held-out confirmation data into optimization.

The local generative model may optionally propose additional synonyms or technical phrases. In that case the source information supplied to the model consists of EngineerIntent and already known terminology, the actor generating additional strings is Ollama, and Scala records those strings as model hypotheses rather than as facts. They become usable anchors only if subsequent geometric and behavioral tests support them.

A crucial implementation rule is that every candidate remains attached to its textual source and provenance. The application should therefore think of a candidate as something like:

```text
text = libraryDependencies
source = EngineerIntent
eligibleForPositiveSelection = true
```

rather than as an anonymous string. The purpose of this provenance is to make the final prompt explainable: when `libraryDependencies` appears in the computed prompt, the report can say where the phrase came from and which measurements caused it to be selected.

## Where the multiple agent reference phrases come from

The program does not invent several sentences about an agent merely because "one sentence is not enough." `CandidatePhraseCollector.referenceTexts` constructs the reference text set from information that already exists in the agent profile and from development examples whose expected label is that agent. The source is therefore the current catalog and the labeled development suite, the actor is Scala, and the purpose is to represent several known aspects of an agent's owned semantic territory.

For BuildAgent, reference texts might include the router description, owned artifact names, writable path terminology, and development requests known to belong to BuildAgent. SourceAgent receives its own corresponding reference set, and the same procedure is applied to every other agent. Confirmation examples are not included because their role is later evaluation, not construction of the geometry.

This answers an important question about phrases such as `libraryDependencies` or `ModuleID`. They must come from an identifiable textual source such as EngineerIntent, the current agent configuration, a development request, or an explicitly recorded model hypothesis. CONVERGE never claims that these words were read out of an embedding dimension.

## What Ollama embeddings actually do

Once Scala has ordinary text phrases, `OllamaClient.embed` sends them to the local embedding model. The source of the text is the candidate set and the agent reference text sets just described, the caller is the Scala application, and the actor performing vectorization is the downloaded embedding model served by Ollama. The mechanism is an HTTP request to Ollama's embedding endpoint containing an array of strings.

For example, Scala may send:

```text
build.sbt
libraryDependencies
ModuleID
Scala source refactoring
Dockerfile
ECS task definition
```

The embedding model returns one floating-point vector for each string. Scala keeps each vector attached to the original string that produced it, so `libraryDependencies` might be represented internally as a record containing both the text `libraryDependencies` and a vector containing hundreds or thousands of floating-point coordinates.

An individual coordinate has no human-readable interpretation in this implementation. Coordinate 173 is not called "buildness," coordinate 417 is not called "source-code-ness," and no Scala function attempts to discover such labels. Meaning is treated as distributed across the whole vector, so all geometry is performed using entire vectors.

The vector is also never converted back into English. The implemented direction is text to vector to numerical measurement to selection decision to reuse of the original text. If `libraryDependencies` survives the selection process, `PromptRenderer` inserts the original string `libraryDependencies`; it does not decode that word from the embedding vector.

## How Geometry.scala constructs the numerical agent reference points

For each agent, the embedding model returns a vector for every reference text associated with that agent. `Geometry.scala` is the deterministic Scala actor that combines these model returned vectors into numerical quantities used by anchor discovery. The purpose of separating this arithmetic from the LLM is to ensure that statements such as "this phrase is 0.31 closer to BuildAgent than to SourceAgent" are computed numbers rather than model opinions.

The simplest compact representation used by the implementation is an agent centroid. Suppose BuildAgent has vectors B1, B2, through BN, where every vector has the same dimensionality. `Geometry.centroid` computes the coordinate-wise arithmetic mean and then normalizes the resulting vector.

Conceptually:

```text
BuildAgent reference texts
        |
        v
embedding model
        |
        v
B1, B2, B3, ..., BN
        |
        v
Scala coordinate-wise mean
        |
        v
raw BuildAgent centroid
        |
        v
Scala normalization
        |
        v
normalized BuildAgent centroid
```

If the vectors were only three dimensional for illustration, averaging `[0.80, 0.50, 0.33]`, `[0.70, 0.60, 0.39]`, and `[0.75, 0.55, 0.36]` would first produce approximately `[0.75, 0.55, 0.36]`. Scala then divides this average by its Euclidean norm to obtain a normalized centroid. Real embedding vectors are much larger, but the arithmetic is exactly the same.

## How a specific proximity number is obtained

Suppose the candidate phrase is `libraryDependencies`. `OllamaClient.embed` has already returned its vector, while `Geometry.centroid` has already produced one normalized centroid for BuildAgent, SourceAgent, TestAgent, ContainerAgent, and DeployAgent. `Geometry.cosine` is the Scala actor that compares the complete candidate vector against each complete centroid vector.

If the candidate and centroid are normalized, cosine similarity is their dot product. Scala multiplies coordinate zero of the candidate by coordinate zero of the centroid, coordinate one by coordinate one, and continues for the full vector, then sums the products. The result is one numerical similarity score for that candidate-agent pair.

Suppose an actual run produced:

```text
libraryDependencies to BuildAgent = 0.826
libraryDependencies to SourceAgent = 0.451
libraryDependencies to TestAgent = 0.338
libraryDependencies to ContainerAgent = 0.194
libraryDependencies to DeployAgent = 0.172
```

The numbers are not generated by asking the LLM to rate similarity from zero to one. They are computed by Scala directly from the vectors returned by the embedding API. The purpose is to turn vague language such as "libraryDependencies is close to BuildAgent" into a reproducible mathematical measurement.

## Why the target versus rival margin matters more than target similarity alone

A phrase can be close to the correct agent and still be a poor routing anchor. Suppose `dependency` has a BuildAgent similarity of 0.74 but a SourceAgent similarity of 0.70. The phrase is certainly related to BuildAgent, but it barely distinguishes BuildAgent from SourceAgent.

`AnchorDiscovery.scala` therefore computes a geometric margin. The source values are the target-agent similarity and the largest similarity among all rival agents, the actor performing the subtraction is Scala, and the purpose is to measure discrimination rather than generic relevance. In the example above, the margin for `dependency` would be only 0.04.

Contrast that with a hypothetical `build.sbt` measurement of 0.81 for BuildAgent and 0.40 for SourceAgent. Its target-versus-rival margin would be 0.41, which makes it geometrically much more promising. This does not yet make `build.sbt` a validated anchor, because the actual routing LLM still has to be tested behaviorally.

## Why embedding geometry is only a screening mechanism

The embedding model and the generative routing model are different computational objects. A candidate phrase can have an excellent geometric margin in `qwen3-embedding` and nevertheless have little effect, or even a harmful effect, on routing decisions made by `qwen3:8b`. CONVERGE therefore treats geometry as a way to rank promising candidates, not as the final definition of routing usefulness.

This distinction is scientifically important. The source of a geometric score is the embedding model plus deterministic Scala arithmetic, while the source of behavioral routing evidence is repeated decisions by the actual generative routing model. The purpose of the second stage is to test whether the surrogate semantic geometry predicts real routing behavior.

This design also makes disagreement informative. If `ModuleID` is geometrically close to BuildAgent but inserting `ModuleID` into requests causes SourceAgent to win more often, CONVERGE rejects the phrase as a behavioral anchor for this router. The geometry suggested a hypothesis, and the routing experiment falsified it.

## How behavioral anchor testing works

`AnchorDiscovery.discover` creates a positive request set from development requests whose expected owner equals `EngineerIntent.expectedOwner`. It separately creates a non-target request set from development cases belonging to other agents. The source is therefore only the development routing suite, the actor constructing the two sets is Scala, and the purpose is to measure both desired attraction and harmful spill.

Scala first routes the unmodified positive requests and records how often the expected owner wins. It also routes the unmodified non-target requests and records how often the expected owner incorrectly steals those tasks. These measurements form the baseline against which a candidate phrase is evaluated.

Now consider `libraryDependencies`. `AnchorDiscovery.inject` deterministically appends a controlled clause such as:

```text
Technical routing coordinate: libraryDependencies.
```

to each test request. The source of the inserted phrase is the candidate record, the actor performing the insertion is Scala, and the purpose is to create a small intervention that changes one textual factor while leaving the rest of the request constant.

The routing model is then called again under the same trial schedule. Suppose the expected owner's mass on positive requests rises from 0.68 to 0.82, producing a target mass gain of 0.14. Suppose its incorrect mass on non-target requests rises from 0.05 to 0.06, producing spill of 0.01.

Scala computes a discriminative behavioral score such as:

```text
score = targetMassGain - gamma * spill
```

With `gamma = 2.0`, the example becomes:

```text
score = 0.14 - 2.0 * 0.01
score = 0.12
```

The source of `targetMassGain` and `spill` is actual router behavior, the actor calculating the score is Scala, and the purpose is to reward useful attraction while penalizing stealing tasks from neighboring owners. The coefficient `gamma` comes from runtime configuration rather than from the LLM, which makes the tradeoff explicit and reproducible.

## How CONVERGE decides that a phrase is really an anchor

A phrase is not selected merely because it appears in EngineerIntent, because the embedding model likes it, or because one routing completion happens to improve. `AnchorDiscovery` requires it to satisfy configured geometric and behavioral thresholds and to have positive target mass gain. It must also be eligible for positive selection based on provenance, which prevents negative or rival terms from becoming positive anchors merely because they have large numerical scores.

The selection process also removes redundant phrases. If `dependency declaration` and `dependency specification` have nearly identical vectors and exceed the configured redundancy threshold, Scala retains the better-ranked candidate and omits the redundant one. The source of this decision is pairwise candidate cosine similarity, the actor is Scala, and the purpose is to keep the final prompt compact rather than filling it with near-synonyms.

Selection stops when the configured maximum number of anchors has been reached or when no remaining candidate qualifies. The resulting strings remain ordinary human-readable phrases such as `build.sbt`, `libraryDependencies`, and `ModuleID`. Their special status comes from the recorded evidence attached to them, not from a change in their textual representation.

## How PromptRenderer.scala turns the accepted representation into text

After prompt compilation and optional anchor discovery, `Main.scala` passes the canonical `TaskIR` and selected positive anchors to `PromptRenderer.scala`. The source of task facts is the ratified TaskIR, the source of the routing vocabulary is either the validated anchor set or the EngineerIntent anchor list when discovery is disabled, and the actor creating the final text is deterministic Scala code. The purpose of deterministic rendering is to prevent a final unconstrained LLM rewrite from silently undoing all previous authority checks.

The program renders two different artifacts. `PromptRenderer.renderRoutingQuery` creates a routing request that deliberately does not state the expected agent name, because naming `build-agent` would leak the answer to the router. `PromptRenderer.render` creates the specialist prompt and does name the expected owner, because this second artifact is intended for the specialist after routing has conceptually occurred.

A resulting specialist prompt can contain sections corresponding directly to typed fields:

```text
Task
comes from normalizedGoal, artifactLocators,
coordinate, fromVersion, and toVersion.

Routing coordinates
come from the selected positive anchors.

Write boundary
comes from allowedWrites and forbiddenWrites.

Effect boundary
comes from allowedEffects and forbiddenEffects.

Validation
comes from validationCommands.

Evidence
comes from requiredEvidence.

Handoff
comes from handoffOwner.
```

This source mapping is why the prompt can be audited. If the final prompt says `Modify only build.sbt`, the source is not a creative sentence invented by Ollama; it is a deterministic rendering of `EngineerIntent.allowedWrites`. If the final prompt says `libraryDependencies` is a technical routing coordinate, the report can additionally show the geometric and behavioral measurements that caused that phrase to be selected.

## Why the program creates several prompt variants

CONVERGE must demonstrate that its machinery improves something measurable rather than merely producing a longer prompt. `Main.scala` therefore creates several prompt conditions and evaluates all of them using the same routing model and plan probe. The source of these conditions is the same EngineerIntent, but the amount and method of elaboration differs.

The `vague-seed` condition uses only `EngineerIntent.seed`. The `manuscript-expert` condition uses a manually constructed concise instruction containing the main task, scope, validation, and evidence information. The `generic-llm-rewrite` condition asks the same generative model for a conventional one-shot rewrite, while `full-converge` uses the ratified TaskIR, repaired catalog, validated anchors, deterministic renderer, and other CONVERGE machinery.

This comparison is important because an experiment that evaluates only the final CONVERGE prompt cannot tell whether the same improvement could have been obtained by simply writing a longer prompt. The actor conducting the comparison is Scala, the mechanism is matched evaluation of all variants, and the purpose is to isolate the value added by the CONVERGE architecture. Prompt length, routing rate, routing margin, and simulated plan adherence are therefore recorded for each condition.

## How the program separates query repair from catalog repair

CONVERGE can change two distinct surfaces. It can change the current routing query by adding validated discriminating language, and it can change the persistent catalog by repairing the descriptions that compete for requests. `FactorialStudy.run` evaluates these effects separately rather than attributing every improvement to one undifferentiated system.

The study evaluates four combinations:

```text
raw query with initial catalog

expanded query with initial catalog

raw query with repaired catalog

expanded query with repaired catalog
```

The source of the raw query is the EngineerIntent-derived baseline, the source of the expanded query is `PromptRenderer.renderRoutingQuery`, the source of the initial catalog is `agents.json`, and the source of the repaired catalog is the accepted output of `CatalogRepair`. Scala computes the differences between these cells so the final report can state whether improvement came primarily from query expansion, catalog repair, or an interaction between the two.

This is important because a repaired catalog may make even a weak query work well, while a strong expanded query may compensate for a poor catalog. Treating them as separate factors exposes that distinction. It also provides a more credible empirical account of what CONVERGE actually changed.

## How PlanProbe tests likely specialist behavior

Correct routing does not guarantee correct action. A model can select BuildAgent correctly and then propose editing `build.sbt`, `Dockerfile`, and Scala source together. `PlanProbe` therefore asks the local generative model to return a structured action plan describing the owner it would act as, paths it would write, commands it would run, effects it would perform, evidence it would collect, handoffs it would make, and whether it would claim success without evidence.

The source of the plan request is the computed specialist prompt and the current catalog. The actor proposing the action plan is the local generative model, while the actor determining whether that plan is acceptable is the Scala `Ratifier`. The purpose is to separate model prediction from policy judgment.

Suppose the plan contains:

```text
writes:
build.sbt
Dockerfile
```

while EngineerIntent allows only:

```text
build.sbt
```

The plan is rejected even if the model provides a persuasive explanation for changing Docker. The source of the rejection is the exact allowed-write set, the mechanism is path-policy comparison in Scala, and the purpose is to prevent the model from self-authorizing additional work.

PlanProbe is still only a simulation. It provides evidence about what the frozen model says it would attempt under the tested prompt, but it does not prove that a real Write tool, shell process, Git operation, or cloud deployment will obey the same boundary. That stronger claim requires the optional concrete oracle.

## How held-out confirmation is used

After catalog repair, prompt compilation, anchor discovery, and development evaluation have finished, `Main.scala` evaluates `routing-confirmation.json`. The source of these cases is the engineer created held-out suite, the actor making route selections is the same frozen routing model, and the actor calculating accuracy is Scala. The purpose is to estimate whether the resulting routing behavior generalizes beyond the cases used to guide optimization.

The program also computes a Wilson confidence interval for confirmation accuracy. This statistic is produced by Scala from the number of passing confirmation cases and total confirmation cases. The LLM neither computes nor interprets the interval for purposes of admission.

A confirmation failure must be handled carefully. If the developer inspects the failure and changes anchors or catalog descriptions because of it, that case has become development data. A scientifically clean next experiment therefore requires a new untouched confirmation set.

## How the concrete target repository can be checked

When `CONVERGE_REPO` names a target repository, `ConcreteOracle` in `Main.scala` can inspect actual repository state. The source is the real filesystem and Git repository, the actor invoking checks is Scala, and external tools such as Git and sbt provide concrete evidence. The purpose is to move beyond simulated model intentions and verify what actually changed.

`ConcreteOracle` runs:

```text
git -C TARGET_REPOSITORY diff --name-only
```

and compares the resulting changed paths with `EngineerIntent.allowedWrites`. If only `build.sbt` is authorized but the Git diff reports both `build.sbt` and `Dockerfile`, the write-set check fails regardless of what the LLM previously claimed.

The oracle also reads the primary artifact and checks that the target version is present while the old version is absent. When `CONVERGE_RUN_ORACLE=true`, it executes the configured validation command, records the process exit status, and hashes the command output. The source of this evidence is the actual repository and actual process execution, not a model simulation.

## What Main.scala writes at the end

`Main.scala` collects all results into `report.json`. The sources include model metadata, input hashes, catalog-repair history, TaskIR compilation attempts, prompt variant evaluations, factorial results, confirmation results, anchor evaluations, and optional concrete-oracle evidence. The actor serializing this information is Scala, and the purpose is to make the entire experiment auditable after execution.

The program also writes `computed-prompt.txt`. Its source is the canonical TaskIR together with the selected positive anchor set, its actor is `PromptRenderer.render`, and its purpose is to provide the actual specialist prompt that the experiment evaluated. This is the prompt that should be reused if the user wants the behavior represented by the recorded report.

The program separately writes `computed-routing-request.txt`. Its source is the same task representation, but `PromptRenderer.renderRoutingQuery` deliberately omits the expected owner name. The purpose is to provide the exact no-answer-leak request used to test routing.

`summary.csv` provides a compact comparison of prompt variants. The source is the structured report, the actor generating the CSV is Scala, and the purpose is to make routing rate, margin, plan adherence, and prompt length easy to compare without manually traversing the full JSON report. The detailed evidence remains in `report.json`.

## What the final certificate means

The program calls the successful result a suite-relative bounded empirical fixpoint. "Suite-relative" means the claim is relative to the finite development and confirmation suites that were actually executed. "Bounded" means candidate generation, catalog repair, trial counts, anchor count, and other search processes are constrained by explicit finite budgets.

"Empirical" means the routing and plan claims come from observations of a particular local model under a recorded configuration. The result is therefore tied to the model digest, Ollama version, prompt hashes, catalog, authority specification, constitution, trial schedule, and test suites written into the report. Replacing any of those important elements creates a new experimental epoch that should be evaluated again.

"Fixpoint" means that under the implemented finite repair process there is no currently admissible tested transition that improves the protected objective, or that the required development conditions have all been satisfied. It does not automatically mean that English prompts form a mathematical contraction space or that Banach's fixed-point theorem applies to unrestricted LLM generation. The stronger Banach claim belongs only to a separately defined deterministic inner operator for which contraction assumptions can actually be established.

## Complete worked trace for the cats-effect example

Assume the engineer supplies an EngineerIntent saying that `org.typelevel %% cats-effect` must change from `3.5.4` to `3.6.1` in `build.sbt`. The same object says that `build-agent` is the expected owner, only `build.sbt` may be written, source and deployment effects are forbidden, `sbt clean update test` must be executed, evidence must be returned, and source adaptation must be handed to `source-agent`. The source of these facts is the engineer, and Scala loads them without asking Ollama to infer any of them.

The architect supplies an authority rule assigning `ModifyDependencyCoordinate` on `BuildDefinition` to `build-agent`. The current multi-agent application supplies the catalog containing descriptions for BuildAgent, SourceAgent, TestAgent, ContainerAgent, and DeployAgent. Scala validates that these inputs agree before the first adaptive model operation begins.

The development suite may reveal that requests mentioning `cats-effect` sometimes route to SourceAgent because SourceAgent's description mentions Cats Effect and dependency-related failures. `CatalogRepair` identifies SourceAgent as the strongest rival and may ask the local model to choose a few discriminating atoms from a finite vocabulary. Scala accepts a repair only if it survives complete development-suite replay without protected regressions.

`PromptCompiler` then asks the model for a TaskIR representation of the already known task. If the model proposes writing `Dockerfile`, changes the owner, changes the target version, or drops required evidence, `Ratifier.checkTask` rejects that attempt. Once an exact proposal is admitted, Scala canonicalizes it and uses that TaskIR for downstream rendering.

If anchor discovery is enabled, `CandidatePhraseCollector` obtains terms such as `build.sbt`, `libraryDependencies`, `ModuleID`, and `dependency coordinate` from EngineerIntent and the current BuildAgent context. It may also obtain bounded model hypotheses such as `sbt dependency declaration`, but those phrases remain untrusted candidates. Scala records the provenance of every candidate before sending any of them to the embedding model.

`OllamaClient.embed` sends the candidate phrases and agent reference texts to the local embedding model. The embedding model returns numerical vectors, `Geometry.centroid` builds one reference centroid per agent, and `Geometry.cosine` computes how close each candidate is to BuildAgent and to every rival. If `libraryDependencies` has a large BuildAgent-versus-SourceAgent geometric margin, it moves into the behaviorally tested shortlist.

`AnchorDiscovery` then appends `libraryDependencies` to matched development requests and reruns the real routing model. If BuildAgent selection rises substantially on BuildAgent cases while BuildAgent selection barely changes on SourceAgent, TestAgent, ContainerAgent, and DeployAgent cases, Scala computes a positive behavioral score. If the score exceeds the configured threshold and the term is not redundant with already selected anchors, Scala promotes the original string `libraryDependencies` into the selected anchor set.

`PromptRenderer.renderRoutingQuery` uses the canonical task and selected anchors to generate the routing request without saying that BuildAgent is the answer. `PromptRenderer.render` separately generates the specialist prompt and explicitly names BuildAgent because that prompt is intended for the specialist after routing. The phrases inserted into these prompts are the original human-readable strings that survived the experiments, not English text decoded from vectors.

The router evaluates the full CONVERGE request repeatedly, and PlanProbe evaluates the likely specialist action plan repeatedly. Scala compares these results with the vague seed, manually constructed expert prompt, and generic LLM rewrite. The factorial study separately measures the effect of query expansion and catalog repair so that CONVERGE cannot claim credit for an improvement without showing which intervention produced it.

Finally, the held-out confirmation suite is executed. If configured, the concrete oracle then checks the actual target repository, verifies the changed file set, verifies the version replacement, and executes the required validation command. `Main.scala` records the complete chain of evidence in `report.json` and writes the exact computed prompt to `computed-prompt.txt`.

The important consequence is that the final prompt is not simply a longer description of the original task. Every authoritative requirement comes from a named human or configuration source, every LLM-generated addition is treated as a proposal, every numerical proximity is computed from actual embedding vectors by Scala, every routing anchor must demonstrate behavioral utility against the actual router, and every accepted change must survive the applicable regression checks. That combination of provenance, geometry, behavioral intervention, deterministic ratification, and replay is the concrete implementation of CONVERGE in this program.


# Part 1. Understand what application this repository contains

## Step 1. Distinguish the CONVERGE application from the target application

The directory in this tutorial is the CONVERGE prompt-generation and validation application.

```text
converge-offline/
```

It is not the target application that an LLM may later generate or modify.

A target application could be a Scala service, a multi-module repository, a cloud system, a Terraform project, or a generated agentic application with a completely different layout.

For example, a target application could look like this.

```text
target-application/
  services/
    api/
    worker/
  platform/
    terraform/
  containers/
  deployment/
```

CONVERGE does not require that structure. It only uses target-repository paths that are explicitly supplied through EngineerIntent and, when the concrete oracle is enabled, through `CONVERGE_REPO`.

Source: the repository created for this tutorial.

Actor: the human developer distinguishes the two repositories before execution.

Mechanism: the CONVERGE source tree is kept separate from any target repository path.

Output: a clean separation between prompt generator and prompt target.

Purpose: prevent the implementation layout of CONVERGE from being mistaken for a required target-application layout.

Trust status: architectural convention established by the project.

## Step 2. Inspect the actual CONVERGE directory structure

The complete project structure is.

```text
converge-offline/

build.sbt
LICENSE
README.md
TUTORIAL.md
EXPERIMENT.md
.gitignore

project/
  build.properties

config/
  engineer-intent.json
  agents.json
  authority.json
  constitution.json

tests/
  routing-development.json
  routing-confirmation.json

src/main/scala/converge/
  Domain.scala
  OllamaClient.scala
  Geometry.scala
  CandidatePhraseCollector.scala
  AnchorDiscovery.scala
  Router.scala
  PromptRenderer.scala
  Main.scala

out/
```

Source: the checked-in project.

Actor: sbt reads `build.sbt` and `project/build.properties`; the JVM runs the eight Scala source files; `Main.scala` reads the JSON files.

Mechanism: normal sbt project conventions.

Output: one compilable Scala 3 application.

Purpose: make the experiment reproducible from a clean checkout.

Trust status: project infrastructure.

# Part 2. Understand the build before running any model

## Step 3. Read build.sbt

The root `build.sbt` declares.

```scala
ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "org.converge"
ThisBuild / version := "0.4.0"
```

It also declares the ujson dependency.

```scala
libraryDependencies ++= Seq(
  "com.lihaoyi" %% "ujson" % "4.4.3"
)
```

The `%%` operator tells sbt to use the Scala 3 artifact variant of ujson.

Source: `build.sbt`.

Actor: sbt.

Mechanism: sbt evaluates the build definition and resolves Scala and ujson through its dependency resolver.

Output: compiler and runtime classpaths for the project.

Purpose: make compilation reproducible rather than depending on hidden Scala CLI directives.

Trust status: checked-in build configuration.

## Step 4. Read project/build.properties

The file contains.

```text
sbt.version=1.12.14
```

Source: `project/build.properties`.

Actor: the sbt launcher.

Mechanism: the launcher reads the file and selects the requested sbt version for the project.

Output: a consistent build-tool version.

Purpose: avoid different machines silently using incompatible sbt behavior.

Trust status: checked-in build configuration.

## Step 5. Install and verify Java

The project requires JDK 17 or newer.

Run.

```bash
java -version
```

An acceptable result contains a Java major version of 17 or greater.

Source: the locally installed JDK.

Actor: the shell runs `java`.

Mechanism: `java -version` prints the runtime version.

Output: the JDK version visible to sbt.

Purpose: verify that Scala compilation and Java HTTP APIs used by `OllamaClient.scala` are available.

Trust status: local runtime evidence.

## Step 6. Install and verify sbt

Run.

```bash
sbt --version
```

The installed sbt launcher may report a different launcher version. The checked-in project file tells that launcher to resolve sbt 1.12.14 for this repository.

Source: the locally installed sbt launcher and `project/build.properties`.

Actor: the sbt launcher.

Mechanism: launcher bootstrap.

Output: an sbt process configured for this project.

Purpose: compile and execute the eight Scala sources as one application.

Trust status: local build-tool evidence.

## Step 7. Compile before involving Ollama

From the project root run.

```bash
sbt clean compile
```

Source: all `.scala` files under `src/main/scala` plus the dependency graph in `build.sbt`.

Actor: sbt launches the Scala 3.8.4 compiler.

Mechanism: normal incremental Scala compilation after `clean` removes previous class files.

Output: compiled classes under `target/scala-3.8.4/classes`.

Purpose: separate source-code errors from model behavior. If compilation fails, there is no point debugging Ollama yet.

Trust status: compiler evidence.

## Step 8. Package the thin JAR if desired

Run.

```bash
sbt package
```

Source: compiled project classes.

Actor: sbt.

Mechanism: standard package task.

Output: a JAR under `target/scala-3.8.4/`.

Purpose: provide a normal JVM artifact.

Trust status: build artifact.

The JAR is thin. It does not bundle ujson. Routine execution with `sbt run` is therefore simpler.

# Part 3. Understand every authoritative input

## Step 9. Read config/engineer-intent.json

The example EngineerIntent says that the desired task is a cats-effect dependency change in `build.sbt`.

Important fields include.

```json
{
  "normalizedGoal": "Update the named sbt dependency",
  "artifactClass": "BuildDefinition",
  "artifactLocators": ["build.sbt"],
  "operation": "ModifyDependencyCoordinate",
  "coordinate": "org.typelevel %% cats-effect",
  "fromVersion": "3.5.4",
  "toVersion": "3.6.1",
  "expectedOwner": "build-agent"
}
```

Source: the engineer or an upstream interface approved by the engineer.

Actor that reads it: `ExperimentInputs.load` in `Domain.scala`.

Mechanism: Java NIO reads the file and ujson parses it.

Output: an immutable `EngineerIntent` Scala object.

Purpose: define the meaning that prompt optimization must preserve.

Trust status: authoritative task input.

## Step 10. Understand why the seed field is not the whole prompt

The example contains.

```json
"seed": "build.sbt"
```

This does not mean that the user supplied only `build.sbt` and asked CONVERGE to invent everything else.

The whole EngineerIntent is the input. `seed` is merely one terse baseline phrase used by the `vague-seed` comparison condition.

Source: EngineerIntent.

Actor that uses the seed: `Main.scala` when constructing the `vague-seed` baseline.

Mechanism: the string is passed unchanged as both routing request and simulated agent prompt for that baseline.

Output: a deliberately weak comparison condition.

Purpose: quantify how much a rich computed prompt improves over a minimal seed.

Trust status: authoritative input used as a baseline, not as the entire task specification.

## Step 11. Read allowedWrites and forbiddenWrites

The example declares.

```json
"allowedWrites": ["build.sbt"],
"forbiddenWrites": [
  "src/**",
  "Dockerfile",
  "infra/**",
  ".github/**",
  "deployment/**"
]
```

Source: EngineerIntent.

Actor that enforces them before model execution: `ExperimentInputs.validate`.

Actor that uses them after a plan proposal: `Ratifier.checkPlan` in `PromptRenderer.scala`.

Actor that uses them against a real repository: `ConcreteOracle` in `Main.scala`.

Mechanism: `PathPolicy.matches` performs exact and simple recursive-path matching.

Output: deterministic authority checks.

Purpose: stop an LLM from turning a dependency update into source, Docker, infrastructure, CI, or deployment work.

Trust status: authoritative constraints.

## Step 12. Read allowedEffects and forbiddenEffects

The example includes.

```json
"allowedEffects": [
  "repo.write:build.sbt",
  "build.execute"
],
"forbiddenEffects": [
  "repo.write:src/**",
  "repo.write:Dockerfile",
  "repo.write:infra/**",
  "cloud.apply",
  "secret.read"
]
```

Source: EngineerIntent.

Actor: Scala validation and ratification code.

Mechanism: set and normalized-string comparisons.

Output: an effect envelope for the task.

Purpose: separate what the specialist may do from what it merely knows about.

Trust status: authoritative task constraints.

## Step 13. Read positiveAnchors and negativeAnchors

The example positive terms are.

```text
build.sbt
libraryDependencies
ModuleID
dependency coordinate
sbt update
```

The negative terms are.

```text
pom.xml
package.json
Scala source refactoring
Dockerfile
Terraform
deployment
```

Source: EngineerIntent.

Actor that uses them when anchor discovery is disabled: `PromptRenderer`.

Actor that measures them when anchor discovery is enabled: `CandidatePhraseCollector` and `AnchorDiscovery`.

Mechanism: positive terms are marked eligible for positive selection; negative terms are measured diagnostically but are not eligible to become positive prompt anchors.

Output: candidate routing vocabulary.

Purpose: provide task-specific semantic coordinates whose routing usefulness can be tested.

Trust status: authoritative terminology, but not automatically validated routing effectiveness.

## Step 14. Read validationCommands and requiredEvidence

The example contains.

```json
"validationCommands": ["sbt clean update test"],
"requiredEvidence": [
  "unified diff",
  "selected dependency graph",
  "eviction output",
  "exit status"
]
```

Source: EngineerIntent.

Actor that renders them into the prompt: `PromptRenderer`.

Actor that checks simulated adherence: `Ratifier.checkPlan`.

Actor that actually runs the first validator when the concrete oracle is enabled: `ConcreteOracle`.

Mechanism: deterministic string rendering, plan checking, and shell execution.

Output: completion conditions and evidence requirements.

Purpose: prevent the model from treating a text edit as sufficient proof of success.

Trust status: authoritative validation contract.

## Step 15. Read config/agents.json

The agent catalog already exists before the CONVERGE experiment.

For example, the BuildAgent description is.

```text
Handles Scala builds, sbt, dependencies, build configuration, and setup.
```

Source: the current multi-agent application configuration. The original author may have been a human architect or an earlier generation process.

Actor that loads it: `ExperimentInputs.load`.

Mechanism: ujson parses each record into an `Agent` case class.

Output: `Vector[Agent]`.

Purpose: give the routing LLM the actual descriptions it must discriminate among.

Trust status: authoritative as the baseline catalog configuration, but not authoritative as ground truth for who should win.

## Step 16. Understand why agents.json is not the ownership oracle

An agent description can be badly written. For example, SourceAgent says it handles dependency-related failures. That wording could attract a dependency-update request even when BuildAgent owns the build definition.

Therefore the catalog cannot define its own correctness.

Source of descriptions: `agents.json`.

Actor that makes routing decisions: the local generative model.

Source of the correct label: `authority.json` and labeled routing tests.

Purpose: preserve an external standard against which ambiguous descriptions are evaluated.

Trust status: catalog is a test subject, not the ownership oracle.

## Step 17. Read config/authority.json

The relevant rule is.

```json
{
  "artifactClass": "BuildDefinition",
  "operation": "ModifyDependencyCoordinate",
  "owner": "build-agent"
}
```

Source: system architect.

Actor that checks it: `ExperimentInputs.validate`.

Mechanism: exact artifact-class and operation lookup.

Output: the normative owner for EngineerIntent.

Purpose: prevent the routing model or catalog text from deciding ground truth.

Trust status: authoritative ownership policy.

## Step 18. Read config/constitution.json

The example says that `secret.read` must always be forbidden, success requires evidence, and out-of-scope work requires handoff.

Source: system or security architect.

Actor that checks it before model calls: `ExperimentInputs.validate`.

Mechanism: deterministic validation of EngineerIntent against global requirements.

Output: either a valid experiment input or an immediate failure.

Purpose: ensure prompt optimization cannot silently weaken higher-level policy.

Trust status: authoritative governance policy.

# Part 4. Understand the routing suites

## Step 19. Read tests/routing-development.json

The development suite contains positive, near-miss, and hard-negative requests.

A BuildAgent positive case includes.

```text
Change cats-effect in build.sbt.
```

A SourceAgent near miss includes.

```text
The dependency version is already correct. Adapt Scala source to the changed library API.
```

A DeployAgent hard negative includes.

```text
Deploy the already-built image to ECS staging.
```

Source: the engineer or evaluator who labels the cases using the authority model.

Actor that loads them: `ExperimentInputs.load`.

Actor that routes them: the local generative model through `Router.route`.

Actor that evaluates them: `Router.evaluate`.

Purpose: create executable routing specifications rather than relying on intuition about descriptions.

Trust status: authoritative development labels.

## Step 20. Read tests/routing-confirmation.json

This file contains held-out routing cases.

Source: the evaluator before optimization begins.

Actor that loads and hashes it: `ExperimentInputs.load` and report generation.

Actor that does not use it during repair: `CatalogRepair` only receives the development suite.

Actor that does not use it for agent reference geometry: `CandidatePhraseCollector.referenceTexts` only receives development tests.

Actor that evaluates it after optimization: `Main.scala` calls `Router.evaluate` after prompt and catalog work are complete.

Purpose: detect overfitting to visible routing cases.

Trust status: held-out empirical confirmation data.

# Part 5. Validate configuration before model calls

## Step 21. Run --validate-only

Run.

```bash
sbt "run --validate-only"
```

Source: all four files under `config/` and both files under `tests/`.

Actor: `Main.main` calls `ExperimentInputs.load`, which calls `ExperimentInputs.validate`.

Mechanism: JSON parsing and deterministic consistency checks.

Output: a short success message or a precise exception.

Purpose: catch structural problems before invoking a model.

Trust status: deterministic local validation.

## Step 22. Example authority mismatch failure

Suppose EngineerIntent says.

```json
"expectedOwner": "source-agent"
```

while authority.json says the `BuildDefinition / ModifyDependencyCoordinate` operation belongs to `build-agent`.

Actor detecting the mismatch: `ExperimentInputs.validate`.

Mechanism: exact equality check.

Output: an exception explaining the disagreement.

Purpose: stop the experiment from optimizing toward an incorrect ground-truth label.

The correct fix is to repair EngineerIntent or authority.json according to the real architecture. Changing a router description is not a valid way to hide an authority contradiction.

## Step 23. Example write-authority failure

Suppose EngineerIntent adds.

```text
Dockerfile
```

to `allowedWrites` while BuildAgent declares writable paths only `build.sbt` and `project/**`.

Actor detecting the failure: `ExperimentInputs.validate`.

Mechanism: every allowed write must match at least one writable pattern of the expected owner.

Output: an error naming the unauthorized path.

Purpose: reject an inconsistent task before the LLM sees it.

# Part 6. Start Ollama and freeze the model identities

## Step 24. Start the Ollama server

Run.

```bash
ollama serve
```

Source: locally installed Ollama runtime.

Actor: Ollama process.

Mechanism: local HTTP server.

Output: API endpoints under `http://localhost:11434/api` unless overridden.

Purpose: serve the local generative and embedding models without using a remote inference API.

Trust status: local inference runtime.

## Step 25. Download the generative model

Run.

```bash
ollama pull qwen3:8b
```

Source: Ollama model registry.

Actor: Ollama.

Mechanism: model download and local storage.

Output: local `qwen3:8b` model.

Purpose: perform TaskIR proposals, routing, candidate-phrase proposals, catalog-repair proposals, generic prompt rewriting, and plan simulation.

Trust status: model artifact whose exact digest is recorded at runtime.

## Step 26. Download the embedding model

When anchor discovery is enabled, run.

```bash
ollama pull qwen3-embedding
```

Source: Ollama model registry.

Actor: Ollama.

Mechanism: model download.

Output: local embedding model.

Purpose: map candidate and reference texts into a common numerical vector space.

Trust status: model artifact whose exact digest is recorded when used.

## Step 27. Understand the two Ollama roles

The generative model and embedding model perform different jobs.

The generative model produces language decisions and JSON proposals.

The embedding model maps text to numeric vectors.

Scala sits above both and decides what is accepted.

```text
Scala controller
  -> generative Ollama model for proposals and routing
  -> embedding Ollama model for vectors
  -> Scala ratification and statistics
```

Purpose: prevent a vague statement such as "the model computes the prompt" from hiding several different mechanisms.

# Part 7. Understand the local API calls

## Step 28. Check the Ollama server version

At normal startup, `Main.scala` calls `OllamaClient.serverVersion`.

Source: running Ollama server.

Actor: `OllamaClient` sends HTTP GET to `/api/version`.

Mechanism: Java `HttpClient`.

Output: Ollama version string.

Purpose: bind the experiment to a concrete runtime version.

Trust status: runtime metadata.

## Step 29. Check model digests

`OllamaClient.requireModelInstalled` calls `/api/tags` and finds the configured model name.

Source: local Ollama model registry.

Actor: `OllamaClient`.

Mechanism: HTTP GET and JSON parsing.

Output: model digest string.

Purpose: distinguish two runs that use models with the same friendly name but different local contents.

Trust status: runtime metadata.

## Step 30. Understand /api/generate

`OllamaClient.generateJson` sends.

```text
model
system prompt
user prompt
stream = false
think = false
JSON schema in format
sampling options
```

Source: Scala caller such as Router, PromptCompiler, CandidatePhraseCollector, CatalogRepair, GenericPrompt, or PlanProbe.

Actor performing generation: local generative model.

Mechanism: POST `/api/generate`.

Output: a JSON string inside Ollama's `response` field, which Scala parses again as structured JSON.

Purpose: make model outputs machine-checkable rather than relying on prose parsing.

Trust status: untrusted model proposal or observation until Scala validates it.

## Step 31. Understand /api/embed

`OllamaClient.embed` sends an array of strings to POST `/api/embed`.

Source: candidate phrases and agent reference texts.

Actor performing vectorization: local embedding model.

Mechanism: Ollama embedding endpoint.

Output: one floating-point vector per input string.

Purpose: obtain measurable positions in a surrogate semantic geometry.

Trust status: model-derived numerical representation.

# Part 8. Evaluate and repair the initial routing catalog

## Step 32. Route one request

For a request such as.

```text
Change cats-effect in build.sbt.
```

`Router.route` renders every agent description and the request into one router prompt.

Source: current catalog plus one development request.

Actor building the router prompt: Scala `Router.route`.

Actor choosing the agent: local generative model.

Mechanism: structured `/api/generate` call whose schema permits the catalog agent IDs plus `ABSTAIN`.

Output: one `selectedAgent` string.

Purpose: observe actual routing behavior.

Trust status: empirical model observation, not ground truth.

## Step 33. Rotate the catalog order

LLMs can be position-sensitive. Therefore `Router.fixedSchedule` assigns an `orderShift` to each trial.

Source: configured number of routing trials and catalog size.

Actor: Scala.

Mechanism: cyclic vector rotation.

Output: a different catalog order for repeated trials.

Purpose: prevent a prompt from appearing stable only because BuildAgent always appeared first.

Trust status: deterministic perturbation.

## Step 34. Cycle request paraphrases

A routing test contains several equivalent request variants.

`Router.fixedSchedule` cycles through them with `requestVariant`.

Source: the labeled routing test.

Actor: Scala.

Mechanism: modular index selection.

Output: repeated trials over several formulations.

Purpose: measure robustness to request wording.

Trust status: deterministic perturbation over engineer-labeled examples.

## Step 35. Compute expectedRate

Suppose a case is routed 10 times and BuildAgent wins 8 trials.

Scala computes.

```text
expectedRate = 8 / 10 = 0.80
```

Source: recorded route selections.

Actor: `Router.aggregateCase`.

Mechanism: count divided by trial count.

Output: expected-owner frequency.

Purpose: quantify stability instead of reporting a single lucky route.

## Step 36. Compute strongestRivalRate and margin

Suppose SourceAgent wins 2 of those 10 trials.

```text
strongestRivalRate = 0.20
margin = 0.80 - 0.20 = 0.60
```

Source: the same recorded route selections.

Actor: Scala.

Mechanism: maximum non-target count and subtraction.

Output: routing margin.

Purpose: distinguish a decisive win from a fragile near tie.

## Step 37. Enforce mustNot strictly

If a protected test lists SourceAgent under `mustNot`, even one trial selecting SourceAgent makes `forbiddenSelections` positive and the case fails.

Source: the test specification and route trials.

Actor: `Router.aggregateCase`.

Mechanism: count selections whose agent appears in `mustNot`.

Output: `forbiddenSelections` and case pass status.

Purpose: make hard routing boundaries explicit.

## Step 38. Identify a failed boundary

Suppose BuildAgent is expected but SourceAgent is the strongest rival.

Source: `RouteEvaluation.failedIds` and the matching `RouteCaseResult`.

Actor: `CatalogRepair.repair`.

Mechanism: choose the first failed development case as a witness and read its strongest rival.

Output: one pairwise repair problem such as BuildAgent versus SourceAgent.

Purpose: repair the specific ambiguous boundary instead of rewriting the whole catalog.

## Step 39. Generate a bounded catalog-repair proposal

`CatalogRepair.proposeEdit` asks the local generative model to choose terms only from a finite vocabulary such as.

```text
build.sbt
libraryDependencies
ModuleID
src/main/scala/**
Dockerfile
ECS task definition
Terraform resource
```

Source: failed test, current catalog, observed rival, and checked-in finite vocabulary in `Router.scala`.

Actor proposing atoms: local generative model.

Actor constraining the output: JSON schema created by Scala.

Mechanism: POST `/api/generate` at temperature zero.

Output: `DescriptionEdit` containing expected agent, wrong agent, and a small atom list.

Purpose: use the LLM for bounded search rather than unrestricted policy writing.

Trust status: untrusted proposal.

## Step 40. Ratify the catalog-repair proposal

Scala checks that the expected agent matches the witness label, the wrong agent matches the observed rival, every atom is in the finite vocabulary, and the proposal contains no more than six atoms.

Source: model proposal plus empirical witness.

Actor: `CatalogRepair.ratifyEdit`.

Mechanism: deterministic Boolean checks.

Output: admissible edit or rejection.

Purpose: prevent the same LLM from inventing arbitrary repair text.

Trust status: deterministic admissibility decision.

## Step 41. Apply the repair symmetrically

If the selected atoms are.

```text
build.sbt
libraryDependencies
ModuleID
```

Scala appends a positive ownership boundary to BuildAgent and a mirrored exclusion to SourceAgent.

Source: ratified finite atom set.

Actor: `CatalogRepair.applyEdit`.

Mechanism: deterministic templates.

Output: candidate catalog.

Purpose: strengthen the correct side and weaken the exact rival interpretation together.

Trust status: candidate configuration, not yet accepted.

## Step 42. Replay the complete development suite

The candidate catalog is evaluated on every development routing case.

Source: candidate catalog and full development suite.

Actor making route selections: generative model.

Actor comparing results: Scala.

Mechanism: complete rerun through `Router.evaluate`.

Output: candidate RouteEvaluation.

Purpose: detect regressions outside the witness case.

## Step 43. Reject protected regressions

`noProtectedRegression` requires every previously passing protected case to remain passing.

Source: before and after evaluations.

Actor: Scala.

Mechanism: compare per-test pass values by test ID.

Output: admissible or inadmissible repair.

Purpose: prevent a local improvement from merely moving the routing error elsewhere.

## Step 44. Stop catalog repair under a finite termination rule

The repair loop stops when one of these conditions occurs.

```text
all development cases pass
no candidate improves the current catalog
maximum accepted catalog edits is reached
```

Source: current evaluation and configured edit budget.

Actor: `CatalogRepair.repair`.

Mechanism: finite while loop.

Output: `CatalogRepairResult` and final catalog.

Purpose: define a bounded empirical repair process. The report field localFixpoint is true only when all development cases pass or no tested candidate improves the catalog. If the edit budget is exhausted while failures remain, the loop terminates but localFixpoint remains false.

Trust status: suite-relative empirical result.

# Part 9. Compile EngineerIntent into TaskIR without letting the model redefine it

## Step 45. Ask the model for a TaskIR proposal

`PromptCompiler.compile` sends the authoritative EngineerIntent values to the local generative model and asks for one structured TaskIR object.

Source: EngineerIntent.

Actor proposing TaskIR: local generative model.

Actor generating the JSON schema: Scala `Schemas.taskIR`.

Mechanism: structured `/api/generate` call at temperature zero.

Output: one TaskIR proposal.

Purpose: test whether the model can produce the typed projection required by the downstream renderer.

Trust status: untrusted proposal.

## Step 46. Compare every TaskIR field with EngineerIntent

Suppose the model proposes.

```text
allowedWrites = build.sbt, Dockerfile
```

EngineerIntent says only.

```text
allowedWrites = build.sbt
```

Actor detecting the mismatch: `Ratifier.checkTask`.

Mechanism: exact scalar and normalized set comparisons.

Output: typed deviation such as `allowed-writes`.

Purpose: make EngineerIntent invariant under prompt compilation.

Trust status: deterministic external ratification.

## Step 47. Feed deviations back for another bounded attempt

If a proposal fails, `PromptCompiler` includes the Scala ratifier's deviation messages in the next prompt.

Source: deterministic deviation list.

Actor constructing feedback prompt: Scala.

Actor proposing the next TaskIR: local generative model.

Mechanism: repeat until accepted or `CONVERGE_COMPILER_ATTEMPTS` is exhausted.

Output: accepted canonical TaskIR or rejection.

Purpose: permit correction without allowing self-ratification.

## Step 48. Canonicalize an accepted TaskIR

Even if the model returns arrays in a different order, the accepted object is replaced with EngineerIntent's canonical field ordering.

Source: accepted proposal and EngineerIntent.

Actor: `Ratifier.canonicalize`.

Mechanism: Scala `copy` with authoritative fields.

Output: canonical TaskIR.

Purpose: make final prompt rendering deterministic after acceptance.

Trust status: authoritative projection.

# Part 10. Build candidate routing vocabulary

## Step 49. Collect EngineerIntent positive phrases

`CandidatePhraseCollector` marks EngineerIntent positive terms as eligible for positive selection.

Source: `EngineerIntent.positiveAnchors`.

Actor: Scala.

Mechanism: wrap each string in `CandidatePhrase` with source `engineer-intent-positive`.

Output: positive candidate records.

Purpose: preserve direct provenance from the task specification.

Trust status: authoritative terminology, routing value not yet measured.

## Step 50. Collect EngineerIntent negative phrases

Negative terms are wrapped with source `engineer-intent-negative` and are not eligible for positive selection.

Source: `EngineerIntent.negativeAnchors`.

Actor: Scala.

Mechanism: deterministic collection.

Output: diagnostic negative candidate records.

Purpose: compare target phrases with known rival semantic regions.

## Step 51. Collect target-agent profile terms

For the expected owner, the collector adds its `ownedArtifacts` and `writablePaths` as eligible candidates.

For BuildAgent these include.

```text
BuildDefinition
SbtPluginDefinition
build.sbt
project/**
```

Source: `agents.json` record for the expected owner.

Actor: `CandidatePhraseCollector.collect`.

Mechanism: deterministic extraction from structured fields.

Output: target-profile candidates.

Purpose: use information already present in the application architecture without asking an LLM to invent it.

## Step 52. Collect rival-agent profile terms

The same structured fields from rival agents are added as non-positive diagnostic candidates.

For SourceAgent this includes.

```text
ScalaSource
src/main/scala/**
```

Source: rival records in `agents.json`.

Actor: Scala.

Mechanism: deterministic extraction.

Output: rival-profile candidates.

Purpose: make it possible to observe that some phrases lie much closer to a competitor than to the expected owner.

## Step 53. Add diagnostic control phrases

The code adds.

```text
version
package
restaurant
```

Source: fixed control list in `CandidatePhraseCollector.scala`.

Actor: Scala.

Mechanism: deterministic constants.

Output: control candidates.

Purpose: provide generic and clearly unrelated comparison terms in the anchor report.

## Step 54. Optionally ask the generative model for more candidate phrases

When `CONVERGE_GENERATE_CANDIDATES=true`, Scala sends EngineerIntent and a summary of the catalog to the local generative model.

The model may propose phrases such as.

```text
sbt dependency declaration
dependency version selection
library dependency entry
```

Source information supplied to the call: EngineerIntent and catalog descriptions.

Caller: `CandidatePhraseCollector.proposeAdditional`.

Actor proposing phrases: local generative model.

Mechanism: JSON schema restricting output to a bounded array of strings, with temperature zero.

Output: short phrase hypotheses.

Purpose: enlarge search recall beyond literal terminology already stored in the configuration.

Trust status: untrusted hypotheses.

## Step 55. Ratify generated phrase shape

Generated phrases must be nonempty, at most 120 characters, at most eight words, and contain no newline.

Source: model-generated strings.

Actor: Scala `validGeneratedPhrase`.

Mechanism: deterministic length and token-count checks.

Output: syntactically admissible hypotheses.

Purpose: prevent long prompt fragments or accidental multi-sentence policy text from entering anchor search.

Trust status: still only candidate vocabulary.

## Step 56. Deduplicate candidate phrases

All candidates are grouped by normalized text.

If the same phrase comes from EngineerIntent and a model hypothesis, the higher-priority provenance is retained.

Source: all candidate records.

Actor: Scala.

Mechanism: lowercase and whitespace normalization followed by grouping.

Output: one record per unique phrase.

Purpose: avoid redundant embedding and routing calls while preserving the strongest provenance.

# Part 11. Construct each agent's reference vector set

## Step 57. Build reference texts from real sources

For every agent, `CandidatePhraseCollector.referenceTexts` combines.

```text
agent description
owned artifact classes
writable paths
labeled development requests whose expected owner is that agent
```

For BuildAgent, this may include.

```text
Handles Scala builds, sbt, dependencies, build configuration, and setup.
BuildDefinition
SbtPluginDefinition
build.sbt
project/**
Change cats-effect in build.sbt.
Resolve an sbt eviction by changing libraryDependencies.
```

Source: `agents.json` and `routing-development.json`.

Actor: Scala.

Mechanism: filter development tests by expected owner and concatenate the structured text sources.

Output: `Map[AgentId, Vector[String]]`.

Purpose: represent an agent by several known semantic coordinates instead of one vague sentence.

Trust status: configuration and engineer-labeled development data.

## Step 58. Understand why held-out requests are excluded

`routing-confirmation.json` is not passed to `referenceTexts`.

Source of exclusion: application control flow in `Main.scala` and `CandidatePhraseCollector.scala`.

Actor: Scala.

Mechanism: only `inputs.developmentTests` is passed to anchor discovery.

Output: reference geometry constructed without confirmation leakage.

Purpose: preserve held-out evaluation integrity.

# Part 12. Convert text into vectors

## Step 59. Batch all reference and candidate texts

`AnchorDiscovery.discover` collects the union of all reference strings and candidate phrases.

Source: candidate collector and agent reference map.

Actor: Scala.

Mechanism: concatenation followed by `distinct`.

Output: one ordered text vector.

Purpose: embed each unique string once and reuse the result.

## Step 60. Call the embedding model

Scala calls.

```text
POST /api/embed
```

with the text array.

Source: the ordered unique text list.

Caller: `OllamaClient.embed`.

Actor producing numbers: the local embedding model.

Mechanism: Ollama embedding API.

Output: one numeric vector for every string.

Purpose: place candidate and reference texts in one comparable vector space.

Trust status: model-derived numerical representation.

## Step 61. Keep the phrase and vector associated

Scala creates a map from original string to returned vector.

Conceptually.

```text
"libraryDependencies"
  -> [0.0214, -0.0381, 0.0062, ...]
```

Source: input phrase list and embedding response in the same order.

Actor: Scala.

Mechanism: `zip(...).toMap`.

Output: text-to-vector lookup.

Purpose: later select the original English phrase without any vector-to-text decoding.

Trust status: phrase provenance plus model-derived vector.

## Step 62. Understand why vector coordinate 173 is not a word

An embedding coordinate does not have a reliable human label such as "buildness" or "deploymentness".

The program never interprets individual dimensions.

Actor: none. No coordinate-decoding operation exists in the application.

Mechanism actually used: whole-vector cosine similarity.

Purpose: avoid a false description of embeddings as if they were dictionaries from dimensions to concepts.

## Step 63. Understand the direction of conversion

The implemented direction is.

```text
text phrase
  -> embedding vector
  -> Scala similarity measurements
  -> Scala selection decision
  -> original text phrase inserted into prompt
```

There is no operation.

```text
vector -> English phrase
```

This matters because the candidate vocabulary must come from EngineerIntent, agent metadata, or explicit model-generated phrase hypotheses before the embedding step.

# Part 13. Compute agent centroids and phrase proximity

## Step 64. Compute one centroid per agent

Suppose BuildAgent has reference vectors B1 through BN.

Scala computes their coordinate-wise mean and then normalizes it.

Source: BuildAgent reference vectors from the embedding model.

Actor: `Geometry.centroid`.

Mechanism: vector averaging followed by L2 normalization.

Output: one BuildAgent centroid vector.

Purpose: provide a compact reference point for candidate-to-agent comparisons.

Trust status: deterministic calculation over model embeddings.

## Step 65. Work a tiny centroid example

Imagine three toy three-dimensional vectors.

```text
B1 = [0.80, 0.50, 0.33]
B2 = [0.70, 0.60, 0.39]
B3 = [0.75, 0.55, 0.36]
```

The coordinate-wise mean is approximately.

```text
[0.75, 0.55, 0.36]
```

`Geometry.normalize` then divides by the vector length.

Source: hypothetical vectors only for explanation.

Actor: the same arithmetic implemented by Scala.

Purpose: show exactly what "construct an agent centroid" means.

## Step 66. Compute cosine similarity to the target

For candidate `libraryDependencies`, Scala retrieves its vector and the BuildAgent centroid.

Source: embedding lookup and centroid map.

Actor: `Geometry.cosine`.

Mechanism: dot product divided by the product of vector norms.

Output: one floating-point target similarity.

Purpose: quantify geometric association with the expected owner.

## Step 67. Compute similarity to every rival

The same candidate vector is compared with SourceAgent, TestAgent, ContainerAgent, and DeployAgent centroids.

Source: one candidate vector and all agent centroids.

Actor: Scala.

Mechanism: repeated cosine calculation.

Output: one score per agent.

Purpose: distinguish relevance from discrimination.

## Step 68. Compute geometric margin

Suppose a real run produced values like.

```text
BuildAgent similarity = 0.826
SourceAgent similarity = 0.451
```

Then Scala computes.

```text
geometricMargin = 0.826 - 0.451 = 0.375
```

The numeric values above are only an arithmetic example unless they appear in an actual `anchors.csv` from a run.

Source: measured similarities.

Actor: `AnchorDiscovery`.

Mechanism: target similarity minus largest rival similarity.

Output: discriminative geometric margin.

Purpose: avoid selecting a phrase merely because it is relevant to both target and rival.

## Step 69. Understand why a generic term can be weak

Imagine `dependency` has.

```text
BuildAgent = 0.74
SourceAgent = 0.70
```

Its margin is only 0.04.

A phrase can therefore be very relevant to BuildAgent yet provide little routing discrimination.

Purpose: explain why CONVERGE searches for boundary-defining vocabulary rather than a bag of topic words.

# Part 14. Measure actual behavioral pull with the routing model

## Step 70. Build the positive request set

`AnchorDiscovery` takes every development request whose expected owner equals EngineerIntent.expectedOwner.

Source: `routing-development.json`.

Actor: Scala.

Mechanism: filter by expected owner and flatten request variants.

Output: positive request vector.

Purpose: measure a candidate across several known formulations rather than one sentence.

## Step 71. Build the non-target request set

The same code collects every development request whose expected owner is not the target owner.

Source: development suite.

Actor: Scala.

Mechanism: complementary filter.

Output: negative request vector.

Purpose: measure spill into neighboring ownership regions.

## Step 72. Measure baseline target routing mass

Before adding any candidate, Scala routes the positive request set using the fixed schedule.

Source: positive requests and current repaired catalog.

Actor making decisions: generative model.

Actor counting target selections: Scala `routeMass`.

Mechanism: repeated calls to `Router.route`.

Output: baseline positive target mass.

Purpose: establish the intervention baseline.

## Step 73. Measure baseline incorrect target mass on non-target requests

Scala similarly routes the negative request set and counts how often the target owner is incorrectly selected.

Source: non-target development requests.

Actor: router model plus Scala counter.

Output: baseline negative target mass.

Purpose: know how much wrong-owner attraction already exists before inserting an anchor.

## Step 74. Insert one candidate phrase

For each candidate, Scala transforms a request such as.

```text
Change cats-effect in build.sbt.
```

into.

```text
Change cats-effect in build.sbt.
Technical routing coordinate: libraryDependencies.
```

Source: original request and candidate phrase.

Actor: `AnchorDiscovery.inject`.

Mechanism: deterministic string append.

Output: intervention request.

Purpose: isolate the routing effect of one candidate phrase.

## Step 75. Compute targetMassGain

Suppose baseline positive target mass is 0.68 and candidate positive mass is 0.82.

```text
targetMassGain = 0.82 - 0.68 = 0.14
```

Source: before and after routing observations.

Actor: Scala.

Purpose: estimate the candidate's positive behavioral pull.

## Step 76. Compute spill

Suppose baseline incorrect BuildAgent mass on non-target requests is 0.05 and with the candidate it becomes 0.08.

```text
spill = max(0, 0.08 - 0.05) = 0.03
```

Source: before and after non-target routing observations.

Actor: Scala.

Purpose: penalize a phrase that steals work from neighboring owners.

## Step 77. Compute the discriminative behavioral score

The implemented equation is.

```text
discriminativeScore = targetMassGain - gamma * spill
```

With the defaults.

```text
gamma = 2.0
```

Using the illustrative values above.

```text
0.14 - 2.0 * 0.03 = 0.08
```

Source: empirical routing masses and configured `CONVERGE_ANCHOR_GAMMA`.

Actor: Scala.

Output: one model-specific behavioral score.

Purpose: reward target attraction while penalizing boundary damage.

# Part 15. Select validated positive anchors

## Step 78. Check provenance eligibility

A candidate must have `eligibleForPositiveSelection=true` before it can be inserted as a positive anchor.

Eligible sources include EngineerIntent positive terms, target-agent structured metadata, and bounded model hypotheses.

EngineerIntent negative terms, rival profile terms, and diagnostic controls are not eligible.

Source: CandidatePhrase provenance.

Actor: Scala.

Mechanism: Boolean field check.

Purpose: prevent a strong rival term such as `Dockerfile` from becoming a positive BuildAgent anchor merely because it has a large magnitude somewhere in the geometry.

## Step 79. Apply geometric and behavioral thresholds

A candidate must meet.

```text
geometricMargin >= CONVERGE_MIN_GEOMETRIC_MARGIN

discriminativeScore >= CONVERGE_MIN_BEHAVIORAL_SCORE

targetMassGain > 0
```

Source: measured AnchorResult plus runtime configuration.

Actor: `AnchorDiscovery.discover`.

Mechanism: deterministic filtering.

Output: ranked eligible candidates.

Purpose: require both geometric discrimination and actual beneficial routing behavior.

## Step 80. Remove redundant phrases

Suppose two candidates are.

```text
dependency declaration
dependency specification
```

If their vector cosine similarity is at least `CONVERGE_ANCHOR_REDUNDANCY_THRESHOLD`, the later lower-ranked candidate is skipped.

Source: candidate vectors and ranking.

Actor: `AnchorDiscovery.selectNonRedundant`.

Mechanism: pairwise cosine similarity against already selected candidates.

Output: compact anchor set.

Purpose: prevent the final prompt from becoming a repetitive thesaurus.

## Step 81. Enforce the anchor count limit

Selection stops after `CONVERGE_MAX_SELECTED_ANCHORS` phrases.

Source: runtime configuration.

Actor: Scala.

Mechanism: finite fold over ranked candidates.

Output: bounded positive anchor vector.

Purpose: keep prompt expansion controlled.

## Step 82. Understand what selected means

Suppose `libraryDependencies` is selected.

That does not mean the vector was translated into the word `libraryDependencies`.

The string existed first. Scala retained it beside its vector, measured it, and then reused the original string.

This is the exact bridge from numerical geometry back to prompt text.

# Part 16. Render the routing request and specialist prompt

## Step 83. Render a no-answer-leak routing request

`PromptRenderer.renderRoutingQuery` does not name the expected owner.

It includes the task, artifact class, operation, validated technical coordinates, write set, effects, negative boundaries, validation, and handoff.

Source: canonical TaskIR plus optional selected anchor override.

Actor: Scala `PromptRenderer`.

Mechanism: deterministic string template.

Output: `computed-routing-request.txt`.

Purpose: test whether the router can infer the authoritative owner without being told the answer.

Trust status: deterministic compilation from accepted input and measured anchors.

## Step 84. Render the specialist prompt

`PromptRenderer.render` does name the expected owner because routing has already occurred conceptually and this prompt is intended for the specialist.

Source: canonical TaskIR and selected anchors.

Actor: Scala.

Mechanism: deterministic template.

Output: `computed-prompt.txt`.

Purpose: produce the actual prompt to give the selected specialist agent.

## Step 85. Trace one sentence to its source

The sentence.

```text
Modify only build.sbt.
```

comes from `EngineerIntent.allowedWrites` through canonical TaskIR.

Actor that inserts it: Scala `PromptRenderer`.

No LLM authors or approves that permission.

## Step 86. Trace an anchor sentence to its source

Suppose the final prompt contains.

```text
Use these validated coordinates to interpret the task: build.sbt, libraryDependencies, ModuleID.
```

Each phrase has a candidate provenance recorded in `anchors.csv`.

Its vector comes from the embedding model.

Its geometric score is computed by Scala.

Its behavioral effect comes from repeated generative-router trials.

Its final insertion is performed by Scala.

This provenance chain is what makes the expansion auditable.

# Part 17. Compare CONVERGE with simpler prompt conditions

## Step 87. Evaluate vague-seed

The `vague-seed` condition uses exactly `EngineerIntent.seed`.

Source: EngineerIntent.

Actor: `Main.scala` constructs the variant.

Purpose: baseline against a weak prompt.

## Step 88. Evaluate manuscript-expert

`Main.scala` deterministically creates a manually precise prompt using the same task facts but without the full CONVERGE anchor machinery.

Source: EngineerIntent.

Actor: Scala.

Purpose: compare CONVERGE with a reasonable human-style precise prompt rather than only a straw-man seed.

## Step 89. Evaluate generic-llm-rewrite

`GenericPrompt.generate` asks the local model to rewrite the task facts into a clear prompt without CONVERGE's typed repair and empirical anchor selection.

Source: EngineerIntent facts.

Actor proposing wording: local generative model.

Actor constraining output shape: Scala JSON schema.

Purpose: compare against ordinary one-shot LLM prompt improvement.

## Step 90. Evaluate full-converge

This condition uses the no-leak routing request and full specialist prompt produced from ratified TaskIR and, when enabled, selected empirical anchors.

Source: complete CONVERGE pipeline.

Actor: Scala renderer after LLM proposals and empirical measurements.

Purpose: test the full method.

# Part 18. Simulate specialist behavior without executing tools

## Step 91. Ask for a structured action plan

For each prompt variant, `PlanProbe.evaluate` asks the generative model to simulate what a tool-bearing specialist would attempt.

The JSON must include.

```text
selectedOwner
writes
commands
effects
evidence
handoff
claimsSuccessWithoutEvidence
```

Source: prompt variant.

Actor proposing plan: local generative model.

Mechanism: structured `/api/generate`.

Output: ActionPlan.

Purpose: cheaply detect likely authority or validation failures before real tool execution.

Trust status: simulation only.

## Step 92. Perturb the specialist prompt

PlanProbe cycles through perturbations including identity, an irrelevant environment suffix, moving the forbidden-effect section forward, and replacing one phrase with a synonym.

Source: fixed perturbation list in `Router.scala`.

Actor: Scala.

Mechanism: deterministic string transforms.

Output: several prompt variants for plan simulation.

Purpose: check that the plan is not correct only under one exact surface form.

## Step 93. Ratify the action plan externally

`Ratifier.checkPlan` checks owner, writes, forbidden effects, validation commands, evidence, handoff, and self-ratification.

Source: model-proposed plan plus EngineerIntent.

Actor: Scala.

Mechanism: deterministic checks.

Output: deviation list and pass/fail.

Purpose: prevent the model from approving its own proposed behavior.

## Step 94. Understand the simulation limit

A simulated plan that says only `build.sbt` will be written is not proof that a later tool execution will really obey that boundary.

The simulation is an empirical predictor of behavior.

Concrete repository evidence requires the optional oracle after actual specialist execution.

# Part 19. Separate query expansion from catalog repair

## Step 95. Run the 2 by 2 experiment

`FactorialStudy.run` evaluates.

```text
q0-c0  raw query, initial catalog
q1-c0  expanded query, initial catalog
q0-c1  raw query, repaired catalog
q1-c1  expanded query, repaired catalog
```

Source: EngineerIntent, final routing request, initial catalog, repaired catalog.

Actor making route decisions: generative model.

Actor computing effects: Scala.

Purpose: determine whether gains come from changing the current request, persistent catalog repair, or their interaction.

## Step 96. Compute query effect without catalog repair

Scala computes.

```text
rate(q1-c0) - rate(q0-c0)
```

Purpose: isolate the contribution of query expansion against the original catalog.

## Step 97. Compute catalog effect without query expansion

Scala computes.

```text
rate(q0-c1) - rate(q0-c0)
```

Purpose: isolate the contribution of persistent catalog repair on the raw query.

## Step 98. Compute interaction

The implementation computes.

```text
queryEffectWithCatalogRepair - queryEffectWithoutCatalogRepair
```

Purpose: show whether the two mechanisms reinforce or interfere with one another.

# Part 20. Evaluate held-out confirmation

## Step 99. Route the confirmation suite only after optimization

`Main.scala` evaluates `routing-confirmation.json` after catalog repair, TaskIR compilation, anchor discovery, prompt rendering, variant evaluation, and the factorial study.

Source: held-out suite.

Actor making decisions: same local generative model.

Actor computing metrics: Scala `Router.evaluate`.

Output: held-out routing accuracy and per-case evidence.

Purpose: detect overfitting.

## Step 100. Compute a Wilson interval

Scala counts how many held-out cases pass and calls `Stats.wilson`.

Source: held-out pass count and total case count.

Actor: Scala.

Mechanism: Wilson score interval for a binomial proportion.

Output: a 95 percent interval stored in `report.json`.

Purpose: report uncertainty rather than only a point estimate.

# Part 21. Optionally validate a real target repository

## Step 101. Supply the separate repository path

After a specialist actually modifies a disposable target worktree, set.

```bash
export CONVERGE_REPO=/absolute/path/to/target-application
export CONVERGE_RUN_ORACLE=true
```

Source: person launching the experiment.

Actor: `Config.fromEnvironment` reads the path.

Mechanism: environment variable.

Output: explicit target repository location.

Purpose: avoid any assumption that the target lives inside the CONVERGE project.

## Step 102. Inspect changed paths

`ConcreteOracle` runs.

```text
git diff --name-only
```

Source: actual target worktree.

Actor producing file state: whichever specialist or tool system already modified the worktree.

Actor checking it: Git invoked by Scala.

Output: changed-path vector.

Purpose: compare real writes with EngineerIntent.allowedWrites.

Trust status: concrete repository evidence.

## Step 103. Check write-set conformance

Every changed path must match an allowed-write pattern, and every required allowed write must appear in the diff.

Source: Git diff plus EngineerIntent.

Actor: `ConcreteOracle`.

Mechanism: `PathPolicy.matches`.

Output: `writeSetPass`.

Purpose: detect an agent that touched an unauthorized file even if its final prose claims otherwise.

## Step 104. Check the requested version transition

The oracle opens the first artifact locator from EngineerIntent.

For this example it looks for `3.6.1` and requires `3.5.4` to be absent from that file.

Source: actual target file.

Actor: Scala.

Mechanism: Java NIO text read and substring checks.

Output: `targetCoordinatePass`.

Purpose: verify that the declared target transition is visible in the real artifact.

## Step 105. Run the real validator

The oracle executes the first EngineerIntent validation command.

For this example.

```bash
sbt clean update test
```

Source: EngineerIntent.

Actor executing it: operating-system shell launched by Scala.

Actor recording evidence: `ConcreteOracle`.

Output: command exit code and SHA-256 hash of combined output.

Purpose: replace model self-report with executable validation evidence.

# Part 22. Understand every output file

## Step 106. Inspect computed-prompt.txt

Source: canonical TaskIR and selected anchor set.

Actor: `PromptRenderer.render` and `Main.writeCompletedOutputs`.

Mechanism: deterministic string rendering and Java NIO write.

Purpose: provide the final specialist prompt as a directly reusable artifact.

## Step 107. Inspect computed-routing-request.txt

Source: canonical TaskIR and selected anchors.

Actor: `PromptRenderer.renderRoutingQuery`.

Purpose: expose the exact no-answer-leak request used to test routing.

## Step 108. Inspect task-ir.json

Source: the accepted canonical TaskIR.

Actor: `Main.writeCompletedOutputs`.

Purpose: show the exact typed task from which the prompt was rendered.

## Step 109. Inspect final-catalog.json

Source: baseline catalog plus any repair proposals that survived external ratification and complete development replay.

Actor: `CatalogRepair` computes it and `Main` serializes it.

Purpose: make persistent routing changes auditable.

## Step 110. Inspect anchors.csv

Each row contains.

```text
phrase
source
eligible
selected
target_similarity
strongest_rival
rival_similarity
geometric_margin
target_mass_gain
spill
discriminative_score
```

Source: `AnchorDiscoveryResult`.

Actor: `Main.anchorsCsv`.

Purpose: make every anchor decision inspectable without reading the full JSON report.

## Step 111. Inspect summary.csv

Each row compares one prompt condition with fields for expected-owner rate, margin, forbidden selections, routing pass, simulated-plan pass rate, active plan violations, and prompt length.

Source: `VariantEvaluation` objects.

Actor: `Main.summaryCsv`.

Purpose: provide a compact experimental comparison.

## Step 112. Inspect report.json

This is the complete experiment record.

It contains.

```text
manifest
usage
catalogRepair
promptCompilation
anchorDiscovery
variants
factorial
confirmation
concreteOracle
certificate
```

Source: every stage of the experiment.

Actor: `Main.completedReport`.

Purpose: preserve enough detail to audit the run later.

# Part 23. Run the application in increasingly strong modes

## Step 113. Run configuration validation only

```bash
sbt "run --validate-only"
```

Use this immediately after editing any JSON input.

## Step 114. Run a minimal generative experiment

```bash
export OLLAMA_MODEL=qwen3:8b
export CONVERGE_RUN_ANCHORS=false
export CONVERGE_ROUTE_TRIALS=5
export CONVERGE_PLAN_TRIALS=5
export CONVERGE_OUT=out/run-minimal
sbt run
```

This tests routing, catalog repair, TaskIR compilation, prompt variants, plan simulation, factorial separation, and held-out routing without embedding measurements.

## Step 115. Run the full anchor experiment

```bash
export OLLAMA_MODEL=qwen3:8b
export CONVERGE_EMBEDDING_MODEL=qwen3-embedding
export CONVERGE_RUN_ANCHORS=true
export CONVERGE_GENERATE_CANDIDATES=true
export CONVERGE_ROUTE_TRIALS=20
export CONVERGE_PLAN_TRIALS=20
export CONVERGE_OUT=out/run-anchors
sbt run
```

This additionally performs embedding geometry, generated candidate hypotheses, behavioral anchor interventions, spill measurement, redundancy pruning, and measured anchor insertion.

## Step 116. Run a stronger study

```bash
export CONVERGE_ROUTE_TRIALS=50
export CONVERGE_PLAN_TRIALS=50
export CONVERGE_COMPILER_ATTEMPTS=6
export CONVERGE_CATALOG_CANDIDATES=4
export CONVERGE_MAX_CATALOG_EDITS=4
export CONVERGE_MIN_EXPECTED_RATE=0.80
export CONVERGE_MIN_MARGIN=0.20
export CONVERGE_MIN_GEOMETRIC_MARGIN=0.05
export CONVERGE_MIN_BEHAVIORAL_SCORE=0.02
export CONVERGE_MAX_SELECTED_ANCHORS=6
export CONVERGE_ANCHOR_REDUNDANCY_THRESHOLD=0.92
export CONVERGE_OUT=out/run-001
sbt run
```

The stronger run costs more local inference time but provides a less noisy empirical estimate.

# Part 24. Interpret failures correctly

## Step 117. A TaskIR rejection is not a routing failure

If no TaskIR proposal exactly matches EngineerIntent, the program writes a rejection report and stops before fabricating a final prompt.

Meaning: the local model failed the typed projection stage.

Correct debugging location: `report.json` under `promptCompilation.attempts`.

## Step 118. A routing failure is not an authority failure

If the model routes a build-definition task to SourceAgent while authority.json assigns BuildAgent, the authority remains unchanged.

Meaning: the router or catalog boundary is defective.

Correct debugging location: development routing case and catalog repair.

## Step 119. Good geometry with bad behavioral pull means reject the phrase

Suppose an embedding margin is positive but adding the phrase decreases actual BuildAgent routing.

Meaning: the surrogate embedding geometry did not predict this router's behavior for that candidate.

Correct action: do not select the phrase.

This disagreement is useful experimental evidence rather than a reason to override the router measurements.

## Step 120. Good routing with a bad plan means the specialist prompt is still defective

If BuildAgent is selected reliably but simulated plans include Dockerfile writes, routing is not the problem.

Correct debugging location: prompt boundaries and plan ratification.

## Step 121. Good simulation with a bad concrete oracle exposes simulator error

If plan simulation predicts only `build.sbt` but the actual worktree contains Dockerfile changes, the simulation was not faithful enough.

Meaning: real execution evidence overrides simulated intent.

# Part 25. Understand the empirical fixpoint claim

## Step 122. What the program calls a suite-relative bounded empirical fixpoint

The final certificate becomes true only when the final development catalog has no failed cases, TaskIR is ratified, the full CONVERGE primary route passes, the simulated full prompt meets the configured plan threshold with no active violations, and the held-out confirmation suite has no failed cases.

Source: recorded finite experiment.

Actor computing the Boolean certificate: `Main.completedReport`.

Mechanism: deterministic conjunction of recorded pass conditions.

Output: `suiteRelativeBoundedEmpiricalFixpoint` in `report.json`.

Purpose: state exactly what passed without pretending to prove arbitrary future behavior.

## Step 123. What the certificate does not prove

It does not prove that every future English request will route correctly.

It does not prove that another LLM has the same geometry or behavior.

It does not prove that an unrestricted tool-bearing agent will behave exactly like the simulated action plan.

It does not survive a changed authority map, changed constitution, changed model digest, changed catalog, or changed confirmation suite without recertification.

# Part 26. A complete worked data lineage for one final phrase

## Step 124. Trace libraryDependencies from origin to final prompt

Assume `libraryDependencies` appears in the final selected anchors.

Its source may be EngineerIntent positive terminology.

`CandidatePhraseCollector` records that provenance.

`OllamaClient.embed` asks the embedding model for its vector.

`Geometry.cosine` compares that vector with every agent centroid.

`AnchorDiscovery` computes target and rival similarity and geometric margin.

The generative router is then called repeatedly on positive and non-target development requests with and without the phrase.

Scala computes targetMassGain, spill, and discriminativeScore.

If the phrase passes thresholds and is not redundant, Scala marks it selected.

`PromptRenderer` inserts the original string `libraryDependencies` into the final prompt.

At no point is an embedding vector converted into an English word.

# Part 27. A complete worked data lineage for one authority boundary

## Step 125. Trace build.sbt write authority

EngineerIntent says `allowedWrites = [build.sbt]`.

`agents.json` says BuildAgent has writable path `build.sbt`.

`authority.json` says `BuildDefinition / ModifyDependencyCoordinate` belongs to BuildAgent.

`ExperimentInputs.validate` checks all three sources for consistency.

`PromptCompiler` may propose a TaskIR, but `Ratifier.checkTask` rejects any different allowed-write set.

`PromptRenderer` writes `Modify only build.sbt` into the final specialist prompt.

`PlanProbe` asks what the model would write.

`Ratifier.checkPlan` rejects any simulated path outside the allowed set.

If a real target worktree is checked, `ConcreteOracle` compares the actual Git diff against the same allowed set.

This chain shows how one EngineerIntent field remains invariant from configuration through simulated and concrete validation.

# Part 28. Recommended scientific workflow

## Step 126. Freeze inputs before optimization

Commit or otherwise hash the four config files and two routing suites before running the study.

The program records SHA-256 hashes in `report.json`.

Purpose: make later reruns comparable.

## Step 127. Use development cases for repair only

Do not inspect confirmation failures while tuning the same confirmation set.

If a confirmation case is used to repair the system, move it into development and create a new untouched confirmation set.

## Step 128. Record model digests, not only model names

`qwen3:8b` is a friendly name. The digest binds the run to a particular locally installed artifact.

## Step 129. Report both geometry and behavior

For anchor claims, report at least.

```text
targetSimilarity
strongestRival
rivalSimilarity
geometricMargin
targetMassGain
spill
discriminativeScore
selected
```

A geometric plot without behavioral routing evidence is not enough to establish a useful anchor.

## Step 130. Use concrete execution evidence for tool claims

Do not claim that Read, Write, shell, Git, or deployment behavior is guaranteed merely because PlanProbe simulated a compliant action plan.

Use a disposable worktree and concrete oracle evidence for repository-change claims.

# Part 29. Final mental model

The full application can be summarized as this pipeline.

```text
EngineerIntent supplied by engineer
        |
        v
Scala loads and validates authority and constitution
        |
        v
Existing agent catalog and development tests
        |
        v
Actual Ollama routing measurements
        |
        v
Bounded pairwise catalog repair with full development replay
        |
        v
LLM proposes TaskIR
        |
        v
Scala ratifies TaskIR against EngineerIntent
        |
        v
Candidate phrases collected from authoritative and bounded sources
        |
        v
Embedding model converts phrases to vectors
        |
        v
Scala computes target and rival proximity
        |
        v
Actual routing model measures pull and spill
        |
        v
Scala selects nonredundant validated anchors
        |
        v
Scala deterministically renders routing request and specialist prompt
        |
        v
Four prompt conditions are compared
        |
        v
Simulated action plans are externally ratified
        |
        v
2 by 2 query versus catalog effects are measured
        |
        v
Held-out routing suite is evaluated
        |
        v
Optional real target repository is checked
        |
        v
Auditable output files and suite-relative certificate
```

The core principle is simple. The LLM proposes language and exhibits behavior. The embedding model supplies vectors. Scala supplies the authority checks, vector mathematics, statistics, regression logic, prompt rendering, provenance, and final admission decisions.
