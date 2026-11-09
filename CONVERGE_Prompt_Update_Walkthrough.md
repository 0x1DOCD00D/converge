CONVERGE Prompt Update Walkthrough

From execution evidence to revised meanings, candidate wording, and accepted agent instructions.

Project: CONVERGE  
Document date: November 9, 2026  
Document type: Technical walkthrough and implementation guide

CONVERGE generates an updated prompt by revising the structured interpretation of a task, identifying the instructions affected by that revision, proposing replacement wording, and checking the consequences. The elaborator proposes meanings and text, the evidence controller classifies feedback, the revision engine selects supported changes, and the renderer produces the instructions delivered to an agent. The verifier subsequently checks the resulting artifacts and behavior against the accepted obligations. A candidate prompt becomes part of an accepted configuration only after the applicable release checks pass.

The central problem is that a failed execution does not identify its own cause. A discrepancy can originate in generated code, an incorrect interpretation, a missing policy, an environment assumption, or a mistaken expected result. Each cause requires a different repair, and changing the task whenever the implementation struggles would conceal defects. CONVERGE therefore keeps the source of every substantive instruction and the evidence supporting each change visible throughout revision.

This document develops the procedures described in [CONVERGE_Manuscript.pdf][S1], dated October 20, 2026, especially Sections 2.4, 3.6, 4.5-4.6, 7.4-7.6, and 8.3. The full revision architecture is a proposed design; the manuscript's inspected Scala prototype implements a narrower prompt-compilation and routing experiment. The prompt templates, record identifiers, numerical examples, and expanded pseudocode below are explanatory constructions unless explicitly attributed to that prototype. They are not measurements from an experiment performed for this document.

Every substantive operation is explained through four questions: who supplied its inputs, which component performs it, what mechanism performs the transformation, and what purpose the transformation serves. Component names describe responsibilities and do not require a separate LLM process for each role. For example, a deterministic Scala function can implement rendering or field comparison, while an LLM can propose wording through a generation API. A deployment must identify which mechanisms are actually implemented before making claims about their behavior.

The following table gives the complete dataflow before the detailed examples.

| Stage | Input source | Responsible component | Concrete mechanism | Purpose |
| --- | --- | --- | --- | --- |
| Record the task | User request and authorized configuration | Intake component | Versioned task fields and source spans | Preserve requirements and their origins |
| Propose meanings | Request, schema, contracts, and accepted evidence | Elaborator | Source-mapped typed records, optionally proposed by an LLM | Make interpretations and gaps explicit |
| Produce observations | Candidate code, inputs, and environment | Execution harness | Isolated execution, traces, diagnostics, and artifact identifiers | Establish what the candidate actually did |
| Resolve evidence | User clarifications, harness records, and domain facts | Evidence resolver | Typed records plus a declared authority and supersession policy | Separate facts, obligations, and conflicts |
| Diagnose the discrepancy | Evidence and dependency models | Diagnostic component | Competing hypotheses and discriminating tests | Locate the layer requiring repair |
| Select a revision | Defeasible assumptions and accepted constraints | Revision engine | Finite search or a solver for the declared rule fragment | Choose a supported semantic change |
| Invalidate consequences | Changed justifications and dependency edges | Dependency manager | Reachability traversal with a visited set | Prevent reuse of stale artifacts |
| Propose wording | Selected patch and preserved obligations | Rewriting component | Bounded text generation or typed-edit generation | Express the justified revision |
| Render the prompt | Admitted records and the assigned work package | Prompt renderer | Deterministic templates and accepted text fragments | Produce actual agent instructions |
| Validate and activate | Coordinated candidate and independent obligations | Verifier and activation controller | Regression replay, confirmation, freshness checks, and version-checked activation | Bind acceptance to the tested candidate |

The first step separates the prompt from the records that determine its meaning.

Suppose the user supplies the following request. The intake component records the three sentences and their source locations without silently adding a policy for every unspecified case. The elaborator then examines the request together with a schema obtained from an authorized input reader. Its purpose is to propose executable interpretations while retaining material choices that the supplied information does not resolve.

```text
For each customer, retain the latest update.
Write the result to a new file.
Do not modify the input files.
```

The word "latest" does not identify a clock. A record may contain both the time an event occurred and the time the application received it. These times can disagree because networks delay messages, devices reconnect, or batches arrive out of order. Consequently, a program can execute successfully and still implement a different meaning from the one the user intended.

The controller keeps the following representations separate. EngineerIntent records the engineer-supplied task contract, TaskIR normalizes one request, and SPECIR records accepted meanings together with obligations and unresolved gaps. The executable workflow supplies operations that realize the accepted interpretation. The rendered prompt is a view compiled from the relevant records, rather than the only place where the task's meaning exists. [S1], Section 2.4.

| Representation | Purpose | Running example |
| --- | --- | --- |
| Original request | Preserve what the user said | "Retain the latest update" |
| Protected intent | Preserve requirements ordinary repairs cannot change | Input files remain unchanged |
| TaskIR | Normalize one task into explicit fields | Group records by customerId |
| SPECIR | Record meanings, assumptions, obligations, and gaps | Recency clock remains unresolved |
| Executable workflow | Describe operations implementing the task | Read, validate, group, select, publish |
| Rendered prompt | Instruct a particular agent | Implement the accepted selection rule |
| Evidence ledger | Record observations, clarifications, and provenance | User accepted event-time ordering |

An illustrative initial record follows. The schema reader supplies the available field names, while the elaborator proposes grouping and maximum selection from the sentence structure. The checker preserves the relationship between "for each customer" and "latest" so that selection remains inside each customer group. The unresolved entries prevent a provisional model choice from being mistaken for an accepted requirement.

```text
Task:
  Select one update per customer.

Grouping:
  key = customerId

Selection:
  operation = maximum
  orderingField = UNRESOLVED
  alternatives = [occurredAt, receivedAt]

Output:
  create a new output file
  preserve selected record contents

Protected requirements:
  input files remain unchanged
  existing files must not be overwritten

Unresolved:
  which timestamp determines recency?
  what happens when event timestamps tie?
  what happens when a required timestamp is malformed?
```

If the elaborator proposes `receivedAt`, the evidence controller labels that choice as provisional unless an accepted source already establishes it. The model's generation probability can help prioritize alternatives but does not authorize a new requirement. An unresolved interpretation may be exercised through an admitted isolated probe to reveal its behavior. Such a probe does not authorize publishing its result as the completed user task.

The second step creates an input on which the competing meanings disagree.

The schema reader reports the fields `customerId`, `recordId`, `occurredAt`, `receivedAt`, and `payload`. The test generator uses those fields and the two candidate clock selectors to construct a discriminating fixture. The execution harness evaluates each admitted probe against the same fixture and records its outputs. The purpose is to make the consequences of the ambiguity concrete before the controller commits to a meaning.

All times in this first fixture refer to the same date and UTC offset.

| Record | Customer | Event time: occurredAt | Arrival time: receivedAt |
| --- | --- | --- | --- |
| R101 | C17 | 10:00 | 12:00 |
| R102 | C17 | 11:00 | 11:10 |
| R201 | C18 | 09:00 | 09:02 |

The two selectors produce different answers for customer C17. The arrival selector compares 12:00 with 11:10 and returns R101. The event selector compares 10:00 with 11:00 and returns R102. Both selectors return R201 for C18 because that customer has only one record.

```text
Latest arrival for C17:
  maximum(receivedAt)
  = maximum(12:00, 11:10)
  = R101

Latest event for C17:
  maximum(occurredAt)
  = maximum(10:00, 11:00)
  = R102
```

Before an authoritative source resolves the clock, this fixture exposes a specification question rather than establishing a defect. The evidence controller presents the competing results to the user only when existing accepted domain information cannot decide the matter. Suppose the user then states that "latest" means the update whose event occurred most recently and that arrival order must not determine recency. The controller records that clarification as an accepted semantic commitment, making R102 the expected result for this fixture.

The third step records feedback as evidence with explicit semantic roles.

The user supplies the intended interpretation, while the execution harness supplies observations about specific runs. The evidence resolver stores both through a typed ledger interface that records source, subject, version, scope, and status. It applies the declared authority policy to distinguish accepted commitments from hypotheses and to identify unresolved conflicts. This prevents an observed output from becoming a requirement merely because it appeared in a feedback message. [S1], Sections 3.1-3.2.

An illustrative clarification record is:

```text
Evidence ID:
  E17

Kind:
  Accepted semantic clarification

Source:
  User

Affected construct:
  C_latest

Accepted meaning:
  Recency is determined by occurredAt.

Discriminating input:
  R101 and R102 for customer C17.

Expected selection:
  R102

Superseded interpretation:
  receivedAt determines recency.

Requirements unaffected:
  Group by customerId.
  Preserve input files.
  Create a new output file.
```

The execution harness produces a different kind of record after running implementation V4 against fixture F7. Its observation can identify a selected record and a traced comparator field. That observation concerns a particular artifact and input, so the record retains their identifiers. It establishes behavior without independently establishing whether the behavior is desired.

```text
Evidence ID:
  E18

Kind:
  Execution observation

Implementation version:
  V4

Input fixture:
  F7

Observed selection:
  R101

Trace:
  Comparator reads receivedAt.
```

The ledger can contain E17 and E18 consistently: one states the desired meaning and the other reports an execution that differs from it. The resolver must retain this distinction when constructing context for an LLM. A compiler diagnostic, a model hypothesis, and a user clarification similarly require separate roles even when all are serialized as text. If equally authoritative records conflict and the policy supplies no justified precedence, the controller records a conflict instead of selecting whichever statement arrived last.

The fourth step diagnoses the layer that requires repair.

The diagnostic component reads accepted meanings, observed traces, code models, and dependency records. It proposes competing explanations through model inspection, explicit rules, or an LLM constrained to produce labeled hypotheses. The test generator then constructs scenarios on which those explanations predict different observations. The harness validates the scenarios and executes admitted probes so that the diagnosis rests on evidence rather than on the fluency of a proposed explanation.

Selecting R101 when event-time semantics requires R102 can have several causes. The following mapping determines which artifact the revision controller should target. The repair request must identify its supporting evidence and affected layer. When the evidence cannot distinguish the causes, the controller returns a measurement obligation rather than prematurely rewriting the task.

| Hypothesis | Explanation | Appropriate repair |
| --- | --- | --- |
| Wrong clock interpretation | A provisional meaning selects the latest arrival | Revise the semantic assignment |
| Wrong comparison direction | The code selects the earliest event | Repair the implementation |
| Incorrect timestamp parsing | Dates or offsets are interpreted incorrectly | Repair parsing or its binding |
| Incorrect expected result | A test oracle reflects a mistaken assumption | Supersede the unsupported oracle label |
| Missing tie policy | Equal event instants have no accepted resolution | Resolve the semantic gap |

For a concrete diagnostic example, suppose three hypotheses survive. H1 chooses the greatest arrival time, H2 chooses the greatest event time, and H3 chooses the smallest event time. The first fixture distinguishes H2 from the other two but does not distinguish H1 from H3. Observing R101 alone therefore does not identify a unique cause.

| Hypothesis | Prediction on the first fixture |
| --- | --- |
| H1: greatest receivedAt | R101 |
| H2: greatest occurredAt | R102 |
| H3: smallest occurredAt | R101 |

The test generator adds a second fixture in which both clocks increase in the same order. The harness uses the same customer and valid timestamp domain for both records. This fixture separates an earliest-event implementation from the two maximum selectors. Together, the fixtures distinguish the three modeled hypotheses, although they do not establish that the hypothesis collection is exhaustive.

| Record | Event time | Arrival time |
| --- | --- | --- |
| R301 | 10:00 | 10:01 |
| R302 | 11:00 | 11:01 |

| Hypothesis | Prediction on first fixture | Prediction on second fixture |
| --- | --- | --- |
| H1 | R101 | R302 |
| H2 | R102 | R302 |
| H3 | R101 | R301 |

The manuscript describes a disagreement score that the scheduler can use to prioritize tests. The diagnostic component supplies a finite set of hypotheses, and each model supplies its predicted observable result for a scenario. The scheduler counts pairs with different predictions and divides by the number of unordered pairs. This score measures disagreement within the current hypothesis collection, rather than the probability that a scenario reveals the true explanation. [S1], Section 3.3.

```text
m = number of surviving hypotheses
x = proposed scenario

totalPairs = m * (m - 1) / 2

disagreement(x) =
  disagreeing hypothesis pairs / totalPairs

For m < 2:
  define disagreement(x) = 0
```

With three hypotheses, `totalPairs = 3 * 2 / 2 = 3`. In the first fixture, H1 disagrees with H2, H2 disagrees with H3, and H1 agrees with H3. Therefore, the score is `2 / 3`. The scheduler can combine that score with execution cost and uncovered obligations while retaining exploration beyond the currently modeled hypotheses.

The fifth step selects a supported semantic revision.

Suppose E17 establishes event-time semantics and E18 shows that the current implementation follows the provisional arrival-time meaning. The revision engine reads these records together with the preserved assumption base and protected requirements. It searches for an admissible replacement interpretation using the declared finite rule language and selection policy. The purpose is to change the defeated meaning while preserving independently justified requirements.

An illustrative typed patch follows. The elaborator may propose its fields, but the external checker must verify their provenance and compatibility with the accepted task. The unresolved policies remain explicit because the clock clarification does not determine them. The patch provides the structured input from which subsequent wording and artifact changes can be generated.

```json
{
  "affectedConstruct": "C_latest",
  "previousMeaning": {
    "operation": "maximum",
    "field": "receivedAt",
    "status": "provisional"
  },
  "replacementMeaning": {
    "operation": "maximum",
    "field": "occurredAt",
    "comparison": "chronological instant"
  },
  "evidence": ["E17", "E18"],
  "preservedRequirements": [
    "group by customerId",
    "preserve input files",
    "create a new output file"
  ],
  "remainingQuestions": [
    "equal event-time policy",
    "malformed timestamp policy"
  ]
}
```

The proposed finite revision operator can rank feasible alternatives using explicit removal, addition, and representation costs. The policy owner supplies the weights and the allowed assumption pool, while the revision engine performs the search. Accepted requirements enter as constraints rather than preferences that the optimizer can trade away for a cheaper change. A candidate that retains arrival-time semantics after E17 is infeasible regardless of its low editing cost. [S1], Section 3.4.

```text
D_new = (D0 minus R) union A

cost(R, A, X) =
    sum(w_remove(d) for d in R)
  + lambda * sum(w_add(a) for a in A)
  + eta * size(X)
```

| Variable | Meaning |
| --- | --- |
| D0 | Preserved default-assumption base for the reasoning epoch |
| R | Defaults proposed for removal |
| A | Replacement assumptions selected from a declared finite pool |
| D_new | Resulting default set |
| X | Consistent extension or interpretation under the declared default semantics |
| w_remove(d) | Policy cost of removing default d |
| w_add(a) | Policy cost of adding assumption a |
| lambda | Weight applied to addition costs |
| eta | Weight applied to representation complexity |
| size(X) | Size of a declared finite canonical representation of X |

For illustration, let removing the arrival default cost 2, adding an event-time default cost 1, `lambda = 1`, `eta = 0.1`, and the resulting representation size be 3. This candidate has cost `2 + 1 * 1 + 0.1 * 3 = 3.3`. The calculation evaluates one candidate; it does not prove that the candidate is optimal. The engine must compare all relevant feasible alternatives, or report that its search is incomplete.

There is a further distinction between defeating a conclusion and deleting its rule. If an accepted event-time fact makes the arrival default inapplicable under the declared default semantics, the old rule can remain dormant without licensing the wrong interpretation. Physically removing that rule and adding another default may therefore be unnecessary and more expensive. The manuscript explicitly identifies this possibility, so the example cost of 3.3 must not be presented as the inevitable revision cost.

Canonical revision also requires a stable optimization problem. The controller fixes the epoch base, admissible candidate construction, weights, evidence policy, and tie-break rule when claiming a canonical result. A local repair procedure that optimizes against the current state can be useful, but its answer may depend on the order in which evidence arrived. The implementation must distinguish those policies instead of claiming order independence for an arbitrary sequence of prompt edits.

The sixth step invalidates the consequences of the defeated interpretation.

The dependency manager reads the changed justification nodes and the graph that links assumptions to meanings, code, expectations, and certificates. It performs a reachability traversal, using a visited set to handle cycles, and marks reachable current justifications stale. Negative dependencies also matter when a rule's applicability depends on the absence of a conflicting conclusion. The purpose is to prevent an old result from surviving merely because its originating assumption was changed elsewhere. [S1], Section 3.5.

| Artifact | Dependency | Required treatment |
| --- | --- | --- |
| Arrival-time selector | Defeated clock interpretation | Mark stale |
| Comparator using receivedAt | Arrival-time selector | Regenerate or repair |
| Expected output R101 | Arrival-time interpretation | Supersede as a normative expectation |
| Earlier acceptance certificate | Old implementation and oracle | Mark stale |
| Customer grouping | Original grouping requirement | Preserve if independently justified |
| Input protection | Original prohibition | Preserve |
| New-file requirement | Original output requirement | Preserve |

Invalidating a justification does not always delete the underlying artifact permanently. An independent justification may allow the dependency manager to re-establish part of the state after recomputation. Conversely, a previously passing test whose expected answer depended on arrival-time semantics cannot remain a mandatory correctness condition after that meaning is defeated. The controller preserves still-valid obligations rather than treating every historical expected answer as immutable.

The seventh step asks a rewriting component to express the selected change.

The revision engine supplies a selected semantic patch, and the evidence resolver supplies its accepted supporting records. The rewriting component receives those inputs together with the current clause, protected requirements, permitted edit scope, and unresolved questions. Through an LLM generation call or a typed-edit procedure, it proposes replacement wording and source mappings. Its purpose is to express the justified change without silently reconstructing the entire task from incomplete context.

The following is an illustrative instruction package for that component. It is an operational expansion of the architecture rather than a verbatim prompt from the manuscript. An implementation can request a structured response containing patch fields, proposed wording, evidence identifiers, and unresolved obligations. The checker must still validate those fields after parsing the response.

```text
Produce a candidate revision for construct C_latest.

Current clause:
  For each customer, retain the latest update.

Current provisional interpretation:
  Select the record with the greatest receivedAt value.

Accepted clarification E17:
  Latest means the event that occurred most recently.
  Arrival order must not determine recency.

Required replacement meaning:
  Within each customerId group, select records having the
  greatest occurredAt instant.

Preserve:
  Group by customerId.
  Preserve input files.
  Write results to a new output file.
  Preserve the contents of selected records.

Permitted changes:
  Replace the clock-selection interpretation.
  Add implementation obligations needed for chronological
  timestamp comparison.
  Identify consequences of the replaced interpretation.

Unresolved policies:
  Equal event times.
  Malformed required timestamps.

Return:
  A structured semantic patch.
  Replacement wording.
  Evidence supporting each added instruction.
  Dependencies requiring regeneration.
  Remaining unresolved questions.

Do not invent answers to unresolved policies.
Do not broaden the permitted effects.
```

The controller can examine several wording candidates against the same accepted patch. Some candidates may contradict the clock clarification, omit grouping, or change the effect boundary. A candidate that hard-codes R102 can reproduce the motivating example without implementing the general rule. The checking mechanism therefore compares the proposed meaning and resulting behavior with the accepted obligations, rather than selecting text solely because it mentions familiar words.

| Candidate | Assessment |
| --- | --- |
| For each customerId, select the record with the greatest occurredAt instant. | Expresses the accepted clock and grouping; ties still require resolution |
| Select the most recently received update for each customer. | Contradicts E17 |
| Select the record with the greatest occurredAt value. | Leaves grouping unstated in isolation; inspect its full context |
| Keep R102 whenever R101 and R102 occur together. | Memorizes the fixture without stating the general rule |
| Remove older records from the input file. | Violates input preservation |

An LLM is not required to author every sentence. A deterministic renderer can insert accepted values into a template such as the following. The application supplies the template, the admitted task record supplies the values, and string interpolation performs the substitution. This makes the origin of each inserted instruction inspectable.

```text
Template:
  For each {groupingKey}, select the record maximizing
  {orderingField} interpreted as {comparisonMeaning}.

Accepted fields:
  groupingKey = customerId
  orderingField = occurredAt
  comparisonMeaning = a chronological instant

Rendered clause:
  For each customerId, select the record maximizing
  occurredAt interpreted as a chronological instant.
```

The proposed architecture can combine LLM-authored clauses with deterministic rendering. The inspected Scala prototype relies heavily on deterministic templates after its task fields have passed external checks. In both cases, the final words come from retained strings or template substitutions. The embedding component scores textual candidates but does not supply an inverse operation that turns a target vector into the intended sentence.

The eighth step checks semantic obligations and geometric bounds.

The admission checker receives the candidate patch and wording from the rewriting component, along with the protected intent and accepted semantic records from the controller. It checks the supported formal properties, authority rules, type constraints, and explicit prohibitions using the declared checking mechanisms. Unsupported properties remain unresolved obligations rather than becoming successful checks by default. The purpose is to reject candidates whose attractive wording hides a changed task.

For example, "Do not overwrite the input file" and "Overwrite the input file" concern the same resource and action but authorize opposite behavior. An embedding encoder can assign them nearby vectors. The authority and effect checks must therefore examine the prohibition directly. Geometric proximity alone cannot establish that the replacement is admissible.

The geometric checker supplies an additional bound on candidate movement. The controller records a fixed encoder configuration, canonical clause context, reference vector, and permitted radii. The embedding service maps each candidate string to a vector, and deterministic numerical code normalizes the vector and computes distances. The checker rejects or separately handles zero or nonfinite vectors because they do not provide valid normalized-distance evidence. The manuscript notes that the inspected prototype does not enforce every one of these stronger validity checks. [S1], Sections 4.1, 4.5, and 7.3.

```text
z0:
  Unit-normalized embedding of the epoch's reference clause.

zt:
  Unit-normalized embedding of the current clause.

znew:
  Unit-normalized embedding of a candidate clause.

R:
  Maximum permitted distance from the reference.

r:
  Maximum permitted distance from the current clause.

Required bounds:
  distance(znew, z0) <= R
  distance(znew, zt) <= r
```

For unit vectors, Euclidean distance and cosine similarity are related by the following derivation. The squared norm of a difference expands into two squared norms and a dot product. Each squared norm equals 1 after normalization, and the dot product equals cosine similarity. Taking the nonnegative square root gives the distance used in this example.

```text
distance(a, b)^2
  = norm(a - b)^2
  = norm(a)^2 + norm(b)^2 - 2 * dot(a, b)
  = 1 + 1 - 2 * cosineSimilarity(a, b)
  = 2 * (1 - cosineSimilarity(a, b))

distance(a, b)
  = sqrt(2 * (1 - cosineSimilarity(a, b)))
```

The variables `a` and `b` denote the two unit vectors being compared. Euclidean distance satisfies the triangle inequality required by the drift argument. The quantity `1 - cosineSimilarity(a, b)` induces the same ranking on unit vectors but is not generally a metric. The implementation must use the quantity required by the stated theorem rather than substituting a convenient similarity score.

Suppose the policy uses the illustrative bounds `R = 0.30` and `r = 0.25`. A candidate with reference similarity 0.96 has reference distance `sqrt(0.08)`, approximately 0.283. If its current-clause similarity is 0.98, its local distance is `sqrt(0.04) = 0.200`. It passes both bounds, although semantic admission remains a separate requirement.

| Candidate | Similarity to reference | Distance from reference | Similarity to current | Distance from current | Geometric result |
| --- | --- | --- | --- | --- | --- |
| A | 0.96 | 0.283 | 0.98 | 0.200 | Pass both bounds |
| B | 0.93 | 0.374 | 0.99 | 0.141 | Fail reference bound |

Candidate B shows why a small local edit does not imply bounded cumulative drift. A long sequence of locally small moves can leave the original neighborhood. The fixed reference bound limits that displacement at every accepted step. Actual radii require a declared calibration procedure, and the numerical values in this table are illustrative rather than recommended settings.

The ninth step renders the candidate prompt for a particular worker.

The prompt renderer reads admitted task fields, protected requirements, the assigned role, the current repair package, and relevant evidence. It assembles the worker's instructions through templates and accepted text fragments. This operation produces a concrete prompt string whose instructions can be traced back to their sources. A role-specific prompt can include only the relevant portion of the complete state, provided that this selection preserves the obligations needed for the assigned work.

The following notation uses `U_t` for the rendered prompt at revision `t`. The manuscript uses other symbols for workflows, so this document keeps the prompt and executable program distinct. `Render` denotes the application procedure that assembles text, not a claim that one universal prompt template solves every task. Its inputs describe the information that the procedure must account for.

```text
U_t = Render(
  accepted task meaning,
  protected requirements,
  assigned role,
  authorized resources and effects,
  current repair obligations,
  relevant evidence,
  required completion evidence
)
```

After accepting event-time semantics, the renderer could produce the following candidate prompt. The accepted clarification supplies the clock rule, the schema supplies timestamp structure, and the original request supplies grouping and input protection. The unresolved tie and malformed-input policies remain visible. Consequently, this prompt can guide development and probes without pretending that the release specification is complete.

```text
Implement the customer-update selection task.

For each distinct customerId, select the record whose
occurredAt timestamp represents the latest chronological
instant.

Interpret timestamps using their dates and explicit offsets.
Normalize them to comparable instants before comparison.
receivedAt must not determine recency.

Preserve all input files and the contents of selected records.
Write results to a new output file without replacing an
existing file.

The equal-event-time policy and malformed-timestamp policy
remain unresolved. Report these gaps instead of silently
introducing a policy.

For the supplied discriminating fixture, customer C17 must
select R102 because its event occurred at 11:00, later than
R101's event at 10:00.

Provide the implementation, validation results, and the list
of unresolved obligations.
```

The fixture supplements the general rule rather than replacing it. The test generator should also construct different identifiers and times so that a worker cannot satisfy the task by special-casing R101 and R102. The verifier evaluates the predicates established by the accepted semantics, including one selection per customer and unchanged inputs. A prompt that states the correct rule does not establish that generated code follows it.

The tenth step uses a new failure to repair implementation without changing meaning.

Suppose the worker generates a comparator that orders timestamp strings. The execution harness runs it against the first fixture and observes the expected result because the relevant timestamps share a compatible representation and offset. The test generator then supplies a fixture with different explicit offsets, justified by the accepted chronological interpretation and schema. The harness records a failure when raw-string or displayed-hour comparison chooses the wrong instant.

| Record | occurredAt | Normalized instant |
| --- | --- | --- |
| R401 | 2026-11-01T10:00:00+02:00 | 2026-11-01T08:00:00Z |
| R402 | 2026-11-01T09:30:00Z | 2026-11-01T09:30:00Z |

R402 is later because 09:30 UTC follows 08:00 UTC. Subtracting the two-hour offset explains the first normalization in this particular example. A real parser must also handle dates, supported ranges, and error behavior according to its contract. The diagnostic component classifies an incorrect selection here as an implementation defect once the clock meaning and supported timestamp domain are established.

The revision controller preserves the accepted meaning and creates a scoped repair obligation. The renderer includes the observed defect, expected result, permitted repair, and required regressions in the next worker prompt. This changes the worker's immediate instructions without changing the user's definition of recency. The distinction prevents a test failure from causing unnecessary specification drift.

```text
Repair the timestamp comparator.

The accepted event-time selection rule remains unchanged.

Observed defect:
  The implementation selected R401 for the offset fixture.

Required result:
  R402, because 09:30 UTC is later than 08:00 UTC.

Required repair:
  Parse supported timestamps into chronological instants.
  Compare the resulting instants.
  Do not compare raw timestamp strings or displayed hours.

Preserve grouping, input protection, and output-creation
requirements.

Rerun the offset fixture and all still-valid applicable
regression tests.
```

An updated prompt can therefore carry a new counterexample while retaining the same task semantics. Other updates can change a resource binding or request additional evidence without redefining the goal. The controller records which category occurred so that the verifier applies the correct obligations. Treating every prompt edit as a change of requirements would erase these useful distinctions.

The eleventh step resolves missing policies and incorporates their consequences.

The test generator next constructs two records whose timestamp strings differ but whose event instants are equal. The harness confirms the equality after normalization. Because the task requires one update per customer, the semantic controller checks whether an accepted tie rule already exists. If none exists, it reports the unresolved choice rather than allowing the worker to use input order as an unrecorded convention.

| Record | Event time | Normalized instant |
| --- | --- | --- |
| R501 | 2026-11-01T08:00:00Z | 2026-11-01T08:00:00Z |
| R502 | 2026-11-01T10:00:00+02:00 | 2026-11-01T08:00:00Z |

Assume the user accepts the following additional policies for this example. Equal event instants are resolved by choosing the lexicographically greatest record identifier. Record identifiers must be unique throughout the accepted input, and malformed required timestamps or duplicate identifiers cause rejection before output publication. The evidence controller records these as newly accepted policies; none follows from the word "latest" alone.

The elaborator now derives a two-component key, and the renderer expresses it in the worker prompt. The code generator implements lexicographic comparison by comparing the first component before consulting the second. Identifier uniqueness ensures a unique maximum in every nonempty customer group under this key. If the domain did not provide uniqueness, another accepted rule or a different input contract would be necessary.

```text
key(record) = (
  normalized occurredAt instant,
  recordId
)

Comparison:
  First compare the event instants.
  If they differ, choose the later instant.
  If they are equal, choose the lexicographically greater
  recordId.
```

The renderer can now emit a more complete candidate prompt. Every added policy is supported by accepted evidence, and each implementation obligation is connected to the corresponding meaning. The output resource binding must implement the required no-overwrite and publication behavior; a sentence in the prompt cannot create a missing storage guarantee. Where the resource lacks the required primitive, the binding controller must resolve that obligation before release.

```text
Implement customer-update selection for the accepted schema.

Input fields:
  customerId
  recordId
  occurredAt
  receivedAt
  payload

Validate the entire input before publishing a result:
  Required timestamps must parse using their explicit dates
  and offsets.
  Record identifiers must satisfy the accepted uniqueness rule.

If validation fails:
  Report the invalid records.
  Do not publish a completed output.
  Leave input files unchanged.

For valid input:
  Group records by customerId.
  Normalize occurredAt values to chronological instants.
  Select one record per group using this comparison key:
    first, greatest occurredAt instant;
    second, lexicographically greatest recordId.

receivedAt does not participate in the selection key.

Preserve the contents of selected records.
Create a new output without overwriting an existing file.
Leave all input files unchanged.

Validate:
  Event order differs from arrival order.
  Different timezone offsets.
  Equal event instants.
  Multiple customers.
  Malformed required timestamps.
  Duplicate identifiers.
  Existing output destination.
  Input preservation.

Report:
  The implementation version tested.
  The actual validation outcomes.
  Remaining unsupported assumptions or failed obligations.

Claim completion only when the required acceptance checks
have passed for the reported implementation.
```

The following mapping allows a reviewer to trace the new instructions. The renderer can retain these references as structured metadata even when it omits the identifiers from the user-facing wording. The verifier uses the same mapping to identify which evidence or dependency changes make an obligation stale. This connection between wording, authority, and validation gives the updated prompt a reviewable basis.

| Instruction | Source |
| --- | --- |
| Group by customerId | Original task and accepted schema |
| Select by occurredAt | Accepted event-time clarification |
| Compare chronological instants | Accepted meaning and timestamp domain |
| Use recordId for ties | Accepted tie policy |
| Reject duplicate identifiers | Accepted domain and error policy |
| Preserve input files | Original protected requirement |
| Create a new output | Original protected requirement |
| Test offset handling | Witnessed implementation failure |
| Report actual validation outcomes | Completion and evidence policy |

The twelfth step validates and activates a coordinated version.

The controller assembles a candidate containing compatible versions of the specification, meanings, prompt, code, tests, bindings, and manifest. It computes an identifier from a declared canonical serialization and associates each check with the exact artifacts examined. The verifier reads the candidate and the applicable obligations, then runs or checks the required evidence-producing procedures. The activation controller checks freshness immediately before making the candidate active. [S1], Sections 8.1-8.5.

```text
Illustrative candidate bundle:
  specification version S8
  semantic assignment version M8
  rendered prompt version U8
  implementation revision C12
  test-oracle version T6
  resource-binding version B3
  evidence manifest V8
```

The verifier returns PASS, FAIL, or UNKNOWN for each declared obligation. PASS means that the chosen check established its stated property under its assumptions, while FAIL requires a justified discrepancy. UNKNOWN covers incomplete search, missing contracts, unsupported formal fragments, timeouts, and unresolved authority. The release controller requires PASS for every mandatory obligation and does not turn an absent counterexample into a proof.

| Check result | Interpretation | Release consequence for a mandatory obligation |
| --- | --- | --- |
| PASS | Declared property established under stated assumptions | May proceed if other gates pass |
| FAIL | Justified violation or counterexample | Repair or reject |
| UNKNOWN | Required result remains unresolved | Keep release blocked |

The verifier also distinguishes development evidence from reserved confirmation. Development cases can guide repeated repairs, while confirmation assesses the frozen candidate under a separately declared policy. If the controller uses a confirmation failure to change the candidate, that evidence becomes part of development history and cannot be reused as untouched confirmation for the next version. Independence concerns the provenance and use of evidence, not simply whether two prompts were sent to different LLMs.

The activation controller must prevent validation of one version from being applied to another. A version check can reject activation if the source or relevant policy changed after verification. Where the resource supports it, a compare-and-swap or equivalent atomic activation mechanism can bind the checked predecessor to the installed candidate. External effects require their own contracts because an email, payment, or remote mutation is not automatically reversible through a local bundle replacement.

If accepted evidence invalidates the current candidate, the controller marks the affected configuration stale and restricts further dependent execution. Retaining its prompt and traces in the ledger preserves history but does not authorize continued use of a defeated interpretation. The release certificate states the checked properties, assumptions, and artifact versions. The proposed control loop is shown below, with diagnostic uncertainty and rejected candidates represented explicitly.

```mermaid
flowchart TD
    A["Current candidate and task records"] --> B["Collect execution evidence"]
    B --> C{"Cause established?"}
    C -->|"No"|D["Run discriminating tests"]
    D --> B
    C -->|"Yes"|E["Revise affected records or artifacts"]
    E --> F["Generate and render candidate prompt"]
    F --> G{"Admission checks pass?"}
    G -->|"No"|E
    G -->|"Yes"|H["Replay regressions and confirm"]
    H --> I{"Acceptance established?"}
    I -->|"No"|B
    I -->|"Yes"|J["Activate versioned bundle"]
```

A second example explains prompt updates for repository maintenance and agent routing.

Assume the project owner supplies an authority configuration assigning dependency declarations in `build.sbt` to BuildAgent and application API adaptation to SourceAgent. The user requests a specified dependency-version change followed by `sbt test`. The intake component records the exact coordinate and versions from the request, and the repository reader supplies the current declaration. The router's task is to select the owner of this operation under that configuration; the artifact name alone does not establish ownership in every project.

The controller distinguishes four texts with different recipients and purposes. A routing request describes the operation to the router, while a specialist prompt supplies the selected worker's concrete obligations. Persistent catalog descriptions explain ownership boundaries across many requests. A scoped repair instruction addresses a particular discrepancy without necessarily changing any durable ownership policy.

| Text | Recipient | Purpose |
| --- | --- | --- |
| Routing request | Router | Select the authorized owner |
| Specialist prompt | Selected worker | Define the assigned work and evidence |
| Agent description | Router's persistent catalog | Describe ownership boundaries |
| Repair instruction | Rewriter or worker | Address a witnessed discrepancy |

The phrase "Fix the dependency problem" may attract either agent because dependency failures can concern declarations or source usage. The renderer can elaborate the request using the accepted operation and artifact fields. For example, it can state that the task changes an existing dependency version declaration in `build.sbt` and that source API adaptation is separate. This expansion makes already accepted distinctions explicit rather than creating a new ownership policy.

The specialist renderer can then create this prompt. The user-supplied task contract supplies the old and new versions, the authority configuration supplies the owner, and the write policy supplies the path restriction. The required command and evidence policy determine the validation instructions. The worker must still execute the permitted operations and produce actual evidence before the verifier can accept completion.

```text
You are BuildAgent.

Update only the specified dependency version in build.sbt,
from the supplied old version to the supplied new version.

Preserve unrelated dependency declarations and settings.
Do not modify application source files.

Run sbt test against the resulting candidate.
Record the command, exit status, and relevant diagnostics.

If failures require application API changes, report the
required handoff to SourceAgent.

Claim completion only when the required evidence is available.
```

The inspected Scala prototype supplies a narrower, concrete prompt-generation procedure.

The manuscript's source audit identifies `PromptCompiler.compile`, `Ratifier.checkTask`, canonicalization, and the renderer in `PromptRenderer.scala`. The engineer-supplied `EngineerIntent` already contains rich normalized task fields. The compiler asks the model to reproduce the corresponding TaskIR values, and the external ratifier compares its output with the authoritative input. After acceptance, canonicalization restores the authoritative values and ordering, and Scala interpolation produces the routing request and specialist prompt. [S1], Sections 7.2-7.4.

| Stage | Input source | Actor and mechanism | Purpose |
| --- | --- | --- | --- |
| Load intent | Engineer configuration | Input loader and structural checks | Establish the declared task fields |
| Propose TaskIR | Authoritative intent | PromptCompiler.compile and model generation | Produce a structured proposal |
| Check fields | Proposal and original intent | Ratifier.checkTask | Reject added, removed, or altered values |
| Retry if allowed | Ratifier deviations | Bounded compiler proposal loop | Correct a rejected proposal |
| Canonicalize | Admitted proposal and original intent | Deterministic canonicalization | Restore authoritative values and order |
| Render | Canonical TaskIR and selected phrases | Scala string interpolation | Create routing and specialist text |

The audited model adapter is `OllamaClient.generateJson`. It sends a generation request with system and user prompts, a JSON schema, seed, temperature, top-p, and context limit; the snapshot also requests nonstreaming output. The adapter parses the response as JSON, after which Scala code performs call-specific checks. A schema-shaped response establishes a formatting boundary, while the ratifier supplies the relevant external field comparisons. These details describe the inspected snapshot, not a guarantee about every later implementation or current model release.

Suppose the authoritative write set contains only `build.sbt`, but the model adds `src/main/scala/**`. The ratifier's normalized-set comparison detects the extra scope and rejects the proposal. The compiler can include the deviation in another bounded attempt, asking for exactly the accepted fields. If the attempt budget is exhausted, the controller reports rejection rather than rendering the last invalid proposal.

```text
Authoritative allowed writes:
  build.sbt

Rejected model proposal:
  build.sbt
  src/main/scala/**

Repair feedback:
  The proposed allowed-write set differs from the authoritative
  allowed-write set. Remove the added source-file scope and
  return exactly the accepted task fields.
```

The manuscript calls this checked copying because the normalized task facts are already present in the input. The result demonstrates proposal admission and field preservation, but it does not establish general ambiguity resolution from arbitrary English. A deterministic conversion from EngineerIntent can produce the same admitted values without a model call. The renderer's output likewise does not establish that a later tool-bearing agent will obey it.

The optional anchor mechanism selects useful wording from retained candidate strings.

The candidate collector obtains phrases from engineer-provided terms, agent metadata, rival metadata, diagnostic controls, and optionally model-generated suggestions. Each candidate retains its original string, source, and selection eligibility. `AnchorDiscovery.discover` uses the embedding adapter and agent reference centroids to compare candidate phrases with the target and rival regions. A behavioral probe also inserts a candidate into requests and measures the router's returned owner identifiers. [S1], Section 7.6.

Examples of retained strings include `build.sbt`, `libraryDependencies`, and "dependency version declaration." These phrases can clarify a relevant operation when the surrounding context supports them. A candidate's provenance and intended role remain necessary because a related phrase can broaden an ownership claim incorrectly. The geometric and behavioral filters supply measured evidence about a candidate, not independent authority to change the task.

The behavioral probe estimates useful attraction and unwanted attraction. The positive request collection contains cases assigned to the target owner, while the negative collection contains cases assigned elsewhere. The probe measures changes in target selection frequency when the candidate phrase is inserted. The scoring function subtracts a penalty for attracting the negative cases.

```text
g = increase in target-agent selection frequency
    on positive cases

s = nonnegative increase in target-agent selection frequency
    on negative cases

gamma = penalty weight for unwanted attraction

behavioralScore = g - gamma * s
```

For the illustrative values `g = 0.40`, `s = 0.10`, and `gamma = 2`, the score is `0.40 - 2 * 0.10 = 0.20`. The candidate helps the target on positive cases but also pulls some requests away from other owners. The penalty expresses that tradeoff in the declared evaluation. These numbers are a worked calculation, not measured performance of a particular phrase.

The prototype combines source eligibility, geometric margin, behavioral score, and positive gain when selecting anchors. It retains the strings alongside their vectors and ultimately inserts `phrase.text` into the renderer. Anchor discovery is optional and disabled by default in the audited configuration. The manuscript also notes that passing its numerical filters does not separately establish full semantic equivalence or preservation of every authority constraint.

Catalog repair changes persistent descriptions and must be evaluated separately from one task prompt.

The routing harness supplies a failing development case with an expected owner derived from the supplied labels and an observed rival selected by the model. The repair component proposes restricted description additions using the declared vocabulary. The controller appends a positive clause to the intended owner's description and a corresponding qualification or exclusion to the rival's description. It then reruns the development suite to check the consequences for previously passing cases. [S1], Sections 4.6 and 7.5.

Under the example ownership policy, a useful paired change could be:

```text
BuildAgent addition:
  Handles dependency version declarations in build.sbt.

SourceAgent qualification:
  Handles source API adaptation.
  Dependency declaration changes belong to BuildAgent under
  the current ownership policy.
```

A broad addition such as "handles dependency problems" might fix a declaration route while stealing an API-adaptation request. The development harness must therefore replay cases for both agents and protect already passing boundaries. In the audited prototype, an edit is admitted only when protected passing cases do not regress and the failure count decreases, or an equal failure count accompanies a sufficient mean-margin improvement. The manuscript identifies a separate limitation: these empirical checks do not by themselves prove that a proposed ownership clause follows from an authoritative ownership matrix.

Routing measurements also require careful interpretation. If five scheduled calls select the expected owner four times and a rival once, the expected-owner frequency is `4 / 5 = 0.8`. The empirical margin over that rival is `(4 - 1) / 5 = 0.6`. These are frequencies under a finite schedule, not posterior probabilities of semantic correctness, and a forbidden-owner selection can still cause rejection under the declared policy.

The inspected prototype asks a plan probe to describe proposed writes, commands, effects, evidence, and handoffs without executing tools. The ratifier checks that structured plan under a finite perturbation schedule. The manuscript reports no integrated specialist loop in the inspected source that applies the selected prompt through a tool-bearing coding agent to perform the requested repository change. Consequently, routing and plan-probe success cannot be presented as evidence that the edit was actually executed correctly. [S1], Sections 7.2 and 7.7.

The following pseudocode makes the proposed prompt-update operation explicit.

The input state comes from the controller's current versioned task records, artifacts, evidence, and remaining budget. Feedback comes from an identified user, harness, compiler, resource monitor, or other permitted source. The procedure delegates each transformation to the responsible component and preserves explicit unresolved outcomes. It is an explanatory composition of the manuscript's algorithms rather than an assertion that this exact function exists in the inspected Scala program.

```text
GENERATE_UPDATED_PROMPT(state, feedback):

  1. Evidence controller:
       append feedback with source, subject, version,
       scope, status, and supersession information.

  2. Evidence resolver:
       resolve the ledger into active observations,
       accepted requirements, and unresolved conflicts.

  3. If a material authority conflict remains:
       return BlockedConflict.

  4. Diagnostic component:
       classify the affected layer using traces, meanings,
       assumptions, contracts, and dependency records.

  5. If diagnosis is unresolved:
       return NeedsMeasurement with discriminating scenarios.

  6. If implementation is affected:
       preserve accepted meanings;
       create a scoped implementation-repair obligation.

  7. If interpretation or a semantic gap is affected:
       elaborator proposes supported revision candidates;
       revision engine solves or searches the declared space;
       engine selects using the declared policy.

  8. If bindings or environment assumptions are affected:
       binding controller revises only justified dependencies.

  9. If an oracle is invalidated:
       evidence controller supersedes affected labels;
       controller begins a new applicable epoch and
       reconstructs reasoning from justified evidence.

 10. If protected intent must change:
       return an amendment obligation for the authorized
       ratifier before proceeding with dependent changes.

 11. Dependency manager:
       invalidate affected meanings, code, expectations,
       prompts, and certificates.

 12. Rewriting component:
       propose source-mapped wording or typed edits for
       the selected revision and current work package.

 13. Admission checkers:
       check protected requirements, authority, types,
       contracts, semantic obligations, geometric bounds,
       and unresolved material gaps.

 14. If a required check is FAIL or UNKNOWN:
       return the corresponding diagnostic or unresolved
       outcome; do not activate the candidate.

 15. Prompt renderer:
       render the candidate worker prompt from admitted
       records and the current work package.

 16. Worker and executor:
       generate or repair the affected implementation;
       admit its bindings and effects before execution.

 17. Verification harness:
       replay applicable, still-valid regression obligations.

 18. If a validated discrepancy appears:
       return the discrepancy to the diagnostic process.

 19. Controller:
       freeze the coordinated candidate and its manifest.

 20. Independent verifier:
       perform reserved confirmation under the declared policy.

 21. Activation controller:
       check evidence binding and commit-time freshness;
       activate only if all mandatory release obligations pass.

 22. Return the accepted prompt and scoped certificate,
       or the precise failed or unresolved outcome.
```

The candidate text exists before release acceptance. A controller may retain provisional text for inspection and safe development even when material obligations remain unresolved. Steps that perform external effects must respect the current authority and binding checks, and a provisional draft does not supply permission to bypass them. The full orchestration also applies finite call, execution, and search budgets so that repeated rejected proposals cannot run indefinitely.

Convergence claims describe specific progress conditions and endpoints.

The policy owner fixes the candidate universe, evaluation rule, relevant labels, and manifest for a theorem's scope. The controller admits edits only when they satisfy the declared progress and preservation conditions. The verifier then evaluates the actual sequence against those premises. Repeated rewriting alone establishes neither that progress continues nor that a correct prompt exists inside the chosen bounds. [S1], Sections 4.6, 5.5-5.11, and 8.2.

For a finite illustration, suppose the harness uses 12 fixed tests and the initial candidate passes 8. Assume every admitted update preserves every previously passing test and makes at least one additional test pass. After k admitted updates, at least `8 + k` tests pass, while no more than 12 can pass. Therefore, at most `12 - 8 = 4` such improving updates can be admitted.

This argument bounds accepted improvements, not rejected proposals or total search time. The model may fail to produce an improving candidate, and a candidate satisfying all 12 cases can still fail outside them. The controller therefore enforces a finite proposal budget independently of the progress theorem. If the budget expires, it reports the unfinished obligations rather than declaring an optimum or impossibility result.

Different prompt strings can also implement equivalent accepted behavior. A deterministic rendering policy can select one textual form, but behavioral acceptance does not require a universally unique English sentence. Conversely, an unchanged prompt can remain wrong if the search repeatedly fails to find a repair. The endpoint must be defined in terms of the declared semantic, execution, and verification obligations.

The controller and verifier use explicit outcome labels to express these distinctions. An implementation should document its exact status vocabulary and the evidence required for each label. The following labels collect the relevant outcomes discussed in the manuscript and this operational walkthrough. Their meaning must remain tied to the supported checks rather than to optimistic release wording.

| Outcome | What it establishes |
| --- | --- |
| AcceptedFormal | Declared properties were proved under stated assumptions |
| AcceptedEmpirical | Candidate satisfied the specified testing policy |
| NeedsClarification | A material meaning lacks authoritative resolution |
| NeedsMeasurement | Evidence does not distinguish remaining explanations |
| BlockedConflict | Material active authority or commitment conflict remains |
| SearchIncomplete | Search did not establish the required result |
| BudgetExhausted | Allotted attempts or resources were consumed |
| NoRealizationWithinBounds | Complete search eliminated the declared bounded candidate universe |

The source map below identifies the manuscript sections supporting the walkthrough. The complete prompt templates and example record names are pedagogical constructions in this document. Claims about Scala methods and prototype limits derive from the manuscript's source audit rather than a new execution or source inspection performed for this rewrite. The source document also provides the broader formal definitions, assumptions, and proofs for the conditional guarantees.

| Topic | Source location in CONVERGE_Manuscript.pdf |
| --- | --- |
| Protected intent and versioned controller state | Sections 2.3-2.4 |
| Latest-record semantics and normalization | Section 2.5 |
| Evidence, defaults, and diagnosis | Sections 3.1-3.3 |
| Finite revision operator and dormant defaults | Section 3.4 |
| Dependency invalidation and REVISE | Sections 3.5-3.6; Algorithm 2 |
| Geometric representation and drift bounds | Sections 4.1 and 4.5 |
| Catalog repair and finite progress | Section 4.6 |
| Conditional convergence | Sections 5.5-5.11 |
| Inspected execution sequence and model adapter | Sections 7.2-7.3 |
| TaskIR checking and prompt rendering | Section 7.4 |
| Routing measurements and catalog implementation | Section 7.5 |
| Anchor collection and selection | Section 7.6 |
| Plan probes and execution limitations | Section 7.7 |
| Full controller, acceptance, and activation | Sections 8.1-8.5; Algorithm 3 |

[S1]: https://chatgpt.com/library?file_id=libfile_aa05d55ced88819184992375be4f7ee9&account_id=personal "CONVERGE_Manuscript.pdf, October 20, 2026"
