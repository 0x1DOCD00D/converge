# CONVERGE Offline Experimental Protocol

This document defines the reproducible experiment implemented by the Scala 3 application in this repository. It is intentionally narrower than a universal correctness claim. The experiment asks whether a frozen CONVERGE controller can transform a supplied EngineerIntent into a prompt and routing configuration that cause a frozen local Ollama model to respect the declared owner, write boundary, effects, validation obligations, evidence obligations, and handoff policy more reliably than the comparison prompts.

The Scala application in this repository is the CONVERGE prompt generation and validation application. It is not the target application that a specialist LLM may later generate or modify. A target repository is used only when the optional concrete oracle is enabled through CONVERGE_REPO.

## 1. Experimental inputs and their provenance

The experiment uses six versioned JSON inputs. Main.scala calls ExperimentInputs.load. Domain.scala performs the file parsing and static consistency checks.

| File | Source of the information | Actor that reads it | Mechanism | Purpose |
| --- | --- | --- | --- | --- |
| config/engineer-intent.json | Engineer or an upstream engineering front end whose result the engineer accepts | ExperimentInputs in Domain.scala | ujson parsing | Defines the task that prompt computation must preserve |
| config/agents.json | Existing multi-agent application configuration normalized for this experiment | ExperimentInputs in Domain.scala | ujson parsing | Supplies the router-visible competing agents and their declared artifact/write profiles |
| config/authority.json | System architect | ExperimentInputs in Domain.scala | ujson parsing and exact lookup | Supplies independent ground truth for artifact and operation ownership |
| config/constitution.json | System or security architect | ExperimentInputs in Domain.scala | ujson parsing and deterministic predicates | Supplies global prohibitions and evidence/handoff requirements |
| tests/routing-development.json | Engineer or experiment designer | ExperimentInputs in Domain.scala | ujson parsing | Supplies labeled routing cases that catalog repair and anchor discovery may use |
| tests/routing-confirmation.json | Engineer or experiment designer, frozen before optimization | ExperimentInputs in Domain.scala | ujson parsing | Supplies held-out routing cases used only after optimization |

The input loader records the SHA-256 hash of every one of these files in report.json. This binds the final result to the exact experiment inputs.

## 2. Static validation before Ollama is called

Run:

```bash
sbt "run --validate-only"
```

Main.scala loads the six files and Domain.scala rejects the configuration before any Ollama call if any required file is missing or if any of the following conditions fails.

1. Agent identifiers must be unique.
2. EngineerIntent.expectedOwner must exist in agents.json.
3. EngineerIntent.handoffOwner must exist in agents.json.
4. EngineerIntent must contain at least one artifact locator, writable artifact, and validation command.
5. authority.json must contain an exact artifactClass plus operation rule for EngineerIntent.
6. Every owner named by authority.json must exist in agents.json.
7. Every agent must have a nonempty router description, owned-artifact set, and writable-path set.
8. The owner from authority.json must equal EngineerIntent.expectedOwner.
9. The expected owner must declare ownership of EngineerIntent.artifactClass.
10. Every allowed write must fall inside the expected owner's declared writable paths.
11. EngineerIntent must include every effect forbidden by the expected owner's catalog profile.
12. Allowed and forbidden write paths must not overlap.
13. EngineerIntent must contain every globally forbidden effect required by constitution.json.
14. No effect may appear in both the allowed and forbidden sets.
15. If the constitution requires evidence, EngineerIntent.requiredEvidence must be nonempty.
16. If the constitution requires handoff, EngineerIntent.handoffOwner must be nonempty.
17. Routing test identifiers must be unique across development and confirmation suites.
18. Every routing test must contain at least one request.
19. Every expected and mustNot agent identifier must exist in agents.json.
20. A routing test may not list its expected owner in mustNot.

This phase tests the experiment definition, not the LLM.

## 3. Frozen Ollama environment

The normal run requires one generative model and, when anchor discovery is enabled, one embedding model.

Default values are:

```text
Generative model: qwen3:8b
Embedding model: qwen3-embedding
Ollama API: http://localhost:11434/api
```

Main.scala asks OllamaClient.scala for the Ollama server version and model digests. These values are written into report.json. The purpose is to prevent a result obtained under one model snapshot from silently being attributed to another model snapshot.

## 4. Development routing baseline

Router.scala calls the generative Ollama model with the complete current catalog and one request. The model is constrained by a JSON schema to return one selectedAgent from the catalog or ABSTAIN.

Router.fixedSchedule generates a deterministic perturbation schedule. Each schedule entry contains:

```text
seed
catalog order shift
request variant index
```

For each development RouteTest, Router.evaluate performs CONVERGE_ROUTE_TRIALS calls and computes:

```text
expectedRate
strongestRivalRate
margin
forbiddenSelections
pass
```

The route margin is:

```text
expectedRate - strongestRivalRate
```

A development case passes only if all of the following are true:

```text
aggregate winner equals expected owner
expectedRate is at least CONVERGE_MIN_EXPECTED_RATE
margin is at least CONVERGE_MIN_MARGIN
forbiddenSelections equals zero
```

The last condition is intentionally strict for the mustNot set.

## 5. Catalog repair

CatalogRepair in Router.scala operates only on development tests. It never uses the held-out confirmation suite.

When a development case fails, Scala identifies the strongest observed rival. Scala then asks the generative model to choose at most six boundary atoms from a finite schema-constrained vocabulary. The model does not write arbitrary catalog prose and does not approve its own proposal.

Scala ratifies a proposal by checking:

```text
expectedAgent equals the labeled owner of the failed test
wrongAgent equals the measured rival
all selected atoms belong to the finite repair vocabulary
at least one and at most six atoms are present
agent identifiers are valid
```

Scala then creates a paired candidate repair. The expected owner's description receives a positive ownership clause using those atoms. The wrong owner's description receives a mirrored exclusion clause using the same atoms.

Router.evaluate replays the complete development suite. Scala accepts the candidate only when no previously passing protected test becomes failing and the new evaluation is strictly better by either reducing the failure count or, with equal failure count, increasing mean margin by more than 0.01.

The accepted repair sequence and its before and after metrics are stored in report.json. The resulting catalog is stored in final-catalog.json.

## 6. TaskIR proposal and external ratification

PromptCompiler in PromptRenderer.scala asks the generative model to copy the authoritative EngineerIntent into a schema-constrained TaskIR object. This model call is deliberately redundant: its purpose is to test the proposal plus ratification architecture rather than trust a model-generated intermediate representation.

Ratifier.checkTask in PromptRenderer.scala compares the proposed TaskIR against EngineerIntent. It requires exact equality of scalar values and set equality of array-valued fields. The model may reorder arrays but may not broaden, omit, or substitute task facts.

If a proposal fails, PromptCompiler feeds the deviations back to the model and retries up to CONVERGE_COMPILER_ATTEMPTS times. Only a deviation-free proposal is canonicalized and admitted.

If no proposal passes, Main.scala writes a rejected report and terminates. It does not emit a successful computed prompt.

## 7. Candidate phrase collection

Anchor discovery is enabled with:

```bash
export CONVERGE_RUN_ANCHORS=true
```

CandidatePhraseCollector.scala obtains phrase candidates from four explicit sources.

First, EngineerIntent supplies positive and negative terminology. These strings are read directly from positiveAnchors and negativeAnchors.

Second, the expected owner's Agent record supplies ownedArtifacts and writablePaths. These terms are eligible for positive selection because the catalog itself associates them with the expected owner.

Third, rival Agent records supply ownedArtifacts and writablePaths. These terms are measured as diagnostic boundary vocabulary but are not eligible for positive selection.

Fourth, when CONVERGE_GENERATE_CANDIDATES=true, CandidatePhraseCollector asks the generative model to propose additional short technical phrases. The model receives EngineerIntent and the current catalog and is constrained by a JSON schema. Scala rejects generated strings longer than 120 characters, longer than eight words, or containing line breaks. These strings are untrusted hypotheses until later measurement.

CandidatePhraseCollector also inserts the diagnostic controls version, package, and restaurant. They are never eligible for positive selection. Their purpose is to make obviously weak or irrelevant geometric behavior visible in anchors.csv.

Every CandidatePhrase retains a source label. The vector never replaces the original text.

## 8. Agent reference text construction

CandidatePhraseCollector.referenceTexts constructs each agent's reference text set from information that already exists before embedding measurement.

For an agent A, Scala concatenates:

```text
A.description
A.ownedArtifacts
A.writablePaths
every development request whose expected owner is A
```

The source of each item is therefore either agents.json or an engineer-labeled development routing case. The embedding model does not invent these reference texts.

Held-out confirmation requests are never included. This prevents confirmation leakage into the geometry used for anchor selection.

## 9. Embedding measurement

OllamaClient.embed calls:

```text
POST /api/embed
```

with the model named by CONVERGE_EMBEDDING_MODEL. The request contains every distinct agent reference text and candidate phrase required by the study.

The embedding model returns one floating-point vector per input string. Scala checks that the number of returned vectors equals the number of submitted texts.

The implementation never converts a vector back into words. It keeps the original text string associated with the vector. The vector is only a measuring instrument used by Geometry.scala.

## 10. Agent centroids

For each agent, AnchorDiscovery.scala looks up the embeddings of that agent's reference texts and calls Geometry.centroid.

Geometry.centroid performs these deterministic operations:

```text
verify equal vector dimensions
sum every coordinate across all reference vectors
divide each coordinate by the number of vectors
L2-normalize the resulting mean vector
```

The output is one normalized reference centroid for that agent.

The centroid is a surrogate semantic prototype produced from the frozen embedding model. It must not be described as the exact hidden-state geometry of the generative router unless the same internal representation is actually exposed and measured.

## 11. Candidate geometric margin

For every candidate phrase, AnchorDiscovery.scala computes cosine similarity between the candidate vector and every agent centroid.

The intended target comes from EngineerIntent.expectedOwner. Scala identifies the strongest non-target rival and computes:

```text
geometricMargin = targetSimilarity - strongestRivalSimilarity
```

The embedding model supplies vectors. Geometry.scala supplies the mathematical calculation. Neither model is asked to verbally rate similarity.

The geometric margin is a screening statistic. It is not sufficient for anchor admission.

## 12. Behavioral anchor intervention

AnchorDiscovery.scala also measures the actual generative router.

Positive requests are all development requests whose expected owner equals EngineerIntent.expectedOwner. Negative requests are all development requests assigned to other owners.

Scala first measures baseline target mass on both sets. Then, for each candidate phrase p, Scala creates intervention requests by appending:

```text
Technical routing coordinate: p.
```

The same Router.fixedSchedule mechanism is used to route the baseline and intervention sets.

Scala computes:

```text
targetMassGain = target mass with p - baseline target mass
spill = max(0, negative-set target mass with p - baseline negative-set target mass)
discriminativeScore = targetMassGain - CONVERGE_ANCHOR_GAMMA times spill
```

A candidate is eligible for selection only when:

```text
its source permits positive selection
geometricMargin is at least CONVERGE_MIN_GEOMETRIC_MARGIN
discriminativeScore is at least CONVERGE_MIN_BEHAVIORAL_SCORE
targetMassGain is strictly positive
```

This implements the distinction between geometric association and behavioral routing effect.

## 13. Redundancy pruning and final positive anchor selection

AnchorDiscovery.scala sorts eligible candidates by behavioral score, then geometric margin, then text. It greedily admits candidates until CONVERGE_MAX_SELECTED_ANCHORS is reached.

Before admitting a candidate, Scala compares its embedding with already selected candidate embeddings. A candidate is skipped when its cosine similarity to an already selected phrase is at least CONVERGE_ANCHOR_REDUNDANCY_THRESHOLD.

This prevents the final prompt from filling its anchor budget with near-duplicate phrases.

The complete measured table is written to anchors.csv. The selected phrases are also stored in report.json.

When CONVERGE_RUN_ANCHORS=false, the application skips embedding and behavioral anchor discovery and uses EngineerIntent.positiveAnchors directly. In that mode those phrases are supplied terms, not empirically selected anchors.

## 14. Deterministic prompt rendering

PromptRenderer.scala renders two separate texts from the ratified TaskIR and the selected positive anchor set.

The routing request deliberately does not name EngineerIntent.expectedOwner. It describes the requested artifact, operation, write scope, effects, boundaries, validation, and handoff without giving the router the answer.

The specialist prompt names the expected owner because routing is already complete at that stage. It contains the task, selected positive technical coordinates, allowed and forbidden write scopes, effect boundaries, validation commands, evidence requirements, handoff owner, and completion rule.

No embedding vector is decoded into prompt text. PromptRenderer inserts the original selected phrase strings retained in CandidatePhrase records.

## 15. Four prompt conditions

Main.scala evaluates four conditions under the same current router catalog.

```text
vague-seed
manuscript-expert
generic-llm-rewrite
full-converge
```

The vague-seed condition uses EngineerIntent.seed.

The manuscript-expert condition is a deterministic compact prompt assembled directly from the authoritative task fields.

The generic-llm-rewrite condition asks the same local generative model for a one-shot prompt rewrite without the CONVERGE anchor and regression machinery.

The full-converge condition uses the ratified TaskIR, repaired catalog, selected anchors, deterministic no-answer-leak routing request, and deterministic specialist prompt.

For each condition, Main.scala evaluates primary routing and simulated plan adherence.

## 16. Simulated plan adherence

PlanProbe in Router.scala asks the generative model to simulate the actions that a tool-bearing specialist would attempt. It does not execute those tools.

The schema requires:

```text
selectedOwner
writes
commands
effects
evidence
handoff
claimsSuccessWithoutEvidence
```

Ratifier.checkPlan checks the returned plan against EngineerIntent. It rejects wrong ownership, unauthorized or missing writes, forbidden effects, missing validation, missing evidence, missing handoff, and success claims without evidence.

PlanProbe repeats the simulation under a finite perturbation set and reports pass rate plus a Wilson 95 percent interval. A simulated plan is only a model-relative prediction. It is not evidence that real filesystem or shell effects occurred.

## 17. The 2 by 2 query versus catalog experiment

FactorialStudy in Router.scala evaluates:

```text
q0-c0 raw query with initial catalog
q1-c0 expanded query with initial catalog
q0-c1 raw query with repaired catalog
q1-c1 expanded query with repaired catalog
```

From target selection rates it computes:

```text
queryEffectWithoutCatalogRepair
queryEffectWithCatalogRepair
catalogEffectWithoutQueryExpansion
catalogEffectWithQueryExpansion
interaction
```

This prevents the combined system from hiding whether a gain came from query expansion, catalog repair, or their interaction.

## 18. Held-out confirmation

Only after catalog repair, TaskIR ratification, anchor selection, and prompt rendering are complete does Main.scala evaluate tests/routing-confirmation.json.

Router.evaluate produces the same routing metrics used for development. Stats.wilson computes a Wilson 95 percent interval for the number of held-out cases that satisfy the complete per-case pass rule.

A confirmation case that is inspected and used for a subsequent repair is no longer held out. A new confirmation suite is required for a new unbiased confirmation claim.

## 19. Optional concrete repository oracle

Set:

```bash
export CONVERGE_REPO=/absolute/path/to/a/disposable/target/worktree
export CONVERGE_RUN_ORACLE=true
```

The target repository is separate from the CONVERGE repository and may have any layout. EngineerIntent supplies the exact paths relevant to the current task.

ConcreteOracle in Main.scala performs three checks against the already modified target repository.

First, it executes git diff --name-only and verifies that every changed path is allowed by EngineerIntent and that every required allowed write appears in the diff.

Second, it reads the first artifact locator and checks that toVersion is present and fromVersion is absent.

Third, it executes the first validation command from EngineerIntent by using the operating system shell. It stores the exit code and a SHA-256 hash of command output rather than pretending that a simulated LLM plan constitutes concrete execution evidence.

The current application does not itself run a coding agent to perform the edit. The oracle evaluates the state of the supplied worktree after some external specialist or tool-bearing agent has acted on it.

## 20. Output artifacts

A successful run writes the following files under CONVERGE_OUT, whose default is out/run-latest.

| File | Producer | Meaning |
| --- | --- | --- |
| computed-prompt.txt | PromptRenderer.scala via Main.scala | Final specialist prompt |
| computed-routing-request.txt | PromptRenderer.scala via Main.scala | Final no-answer-leak routing request |
| task-ir.json | Main.scala | Canonical ratified TaskIR |
| final-catalog.json | Main.scala | Initial catalog plus accepted catalog repairs |
| report.json | Main.scala | Complete machine-readable experiment record |
| summary.csv | Main.scala | Compact comparison of the four prompt variants |
| anchors.csv | Main.scala | Candidate anchor geometry, behavioral measurements, provenance, and selection flag |

If TaskIR compilation fails, the application writes report.json and final-catalog.json and terminates without claiming a successful prompt.

## 21. Suite-relative certificate rule

Main.scala sets suiteRelativeBoundedEmpiricalFixpoint to true only when all of the following are true.

```text
the final development routing suite has no failed cases
an LLM-proposed TaskIR passed external Scala ratification
the full CONVERGE primary route passes the configured rate and margin criteria
the full CONVERGE simulated-plan pass rate is at least CONVERGE_MIN_EXPECTED_RATE
there are zero active owner, write, forbidden-effect, or self-ratification violations in the plan probes
the held-out confirmation suite has no failed cases
```

The concrete oracle is reported separately because it is optional and because the application does not itself execute the specialist coding agent.

The certificate is explicitly relative to the recorded finite model, model digest, configuration, suites, thresholds, and perturbation schedule. It is not a proof for arbitrary future models or arbitrary future English requests.

## 22. Recommended execution sequence

Start by checking only local files.

```bash
sbt clean compile
sbt "run --validate-only"
```

Start Ollama and verify the models.

```bash
ollama serve
ollama pull qwen3:8b
ollama pull qwen3-embedding
ollama list
```

Run a small behavioral smoke test without embedding discovery.

```bash
export OLLAMA_MODEL=qwen3:8b
export CONVERGE_ROUTE_TRIALS=5
export CONVERGE_PLAN_TRIALS=5
export CONVERGE_RUN_ANCHORS=false
export CONVERGE_OUT=out/smoke
sbt run
```

Run the full anchor pipeline.

```bash
export CONVERGE_RUN_ANCHORS=true
export CONVERGE_EMBEDDING_MODEL=qwen3-embedding
export CONVERGE_GENERATE_CANDIDATES=true
export CONVERGE_ROUTE_TRIALS=20
export CONVERGE_PLAN_TRIALS=20
export CONVERGE_OUT=out/anchors-001
sbt run
```

For a stronger study, increase the repeated-trial counts and raise the expected-owner threshold before inspecting results.

```bash
export CONVERGE_ROUTE_TRIALS=50
export CONVERGE_PLAN_TRIALS=50
export CONVERGE_MIN_EXPECTED_RATE=0.80
export CONVERGE_MIN_MARGIN=0.20
export CONVERGE_OUT=out/study-001
sbt run
```

Do not tune thresholds after seeing the held-out confirmation suite and still describe that suite as held out.
