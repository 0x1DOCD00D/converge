# Claude Code Skills: interview-me, rule-rewriter, prompt-master

Three skills, one file. Each section below is one skill; each subsection is one file that skill needs. To install a skill, create its folder under `~/.claude/skills/`, then create each file inside it with the exact content from the matching code block (the path comment at the top of each block tells you where it goes).

```
~/.claude/skills/
├── interview-me/
│   └── SKILL.md
├── rule-rewriter/
│   └── SKILL.md
└── prompt-master/
    ├── SKILL.md
    ├── EVAL.md
    └── references/
        ├── rulebook.md
        ├── interview.md
        ├── retired-instructions.md
        ├── job-brief.md
        ├── file-audit.md
        ├── rules-to-reasons.md
        ├── voice-and-format.md
        └── examples/
            └── client-report-rewrite.md
```

---

## 1. interview-me

Say "interview me" before any big or fuzzy task. Claude asks 5–7 questions, one at a time, prioritising the ones whose answers would change the plan — then writes the brief back and runs it.

### `interview-me/SKILL.md`

```markdown
---
name: interview-me
description: Interview the user one question at a time before starting a big or fuzzy task, then write the brief and execute it. Use when the user says "interview me", "brief me", "help me brief this", "I don't know where to start", or hands over a large task with obvious gaps in the request. Based on the interview pattern Anthropic recommends in the Claude Fable 5 field guide.
---

# Interview Me

Most briefs fail because the missing context is in the user's head and nobody asked for it. This skill pulls it out before any work starts, using the pattern Anthropic recommends: interview one question at a time, prioritising the questions whose answers would change the plan.

Source: A field guide to Claude Fable 5 (https://claude.com/blog/a-field-guide-to-claude-fable-finding-your-unknowns).

## Process

1. **Read before you ask.** Look at whatever is already available: the folder, the files the user mentioned, recent related work. Never ask a question you could answer yourself by reading. Questions you burned on discoverable facts are questions you cannot spend on real unknowns.

2. **Interview, one question at a time.** Ask 5 to 7 questions maximum, one per turn, in plain language. Prioritise questions whose answer would change the shape of the work: the audience, the decision this output feeds, what already exists, what must not change. Push past vague answers: if the user says "make it better," ask what better looks like and how you would both know it happened.

3. **Cover four things by the end of the interview:**
   - What already exists and where it lives.
   - The goal: what this output enables, and for whom.
   - Which decisions the user actually cares about. Everything they do not claim is your call.
   - What proof of done looks like: how they want the result verified before they see it.

4. **Run a blind spot pass.** Before writing the brief, ask yourself one final question and share the answer: what has this interview not covered that could change the outcome? Name the unknown unknowns you can see.

5. **Write the brief back.** Compile everything into one master brief with these parts: the job, the why (who it is for and what it enables), the guardrails (scope, what not to touch), and done-means (exit criteria, deliverable size, how to report back). Show it to the user.

6. **On approval, execute the brief.** Check in only at decisions the user claimed in step 3.

## Rules

- One question per turn. A wall of seven questions defeats the purpose.
- Stop at 7 questions even if curiosity remains. Make reasonable calls on the small stuff yourself.
- If the user starts rambling, let them. Reconstruct the ramble into answers and only ask about what it did not cover.
- If the task is small and clear, say so and skip the interview. This skill is for work where a wrong start is expensive.
```

---

## 2. rule-rewriter

Say "rewrite my rules" or "audit my CLAUDE.md". Inventories every instruction line in a `CLAUDE.md` or skill file, sorts it into buckets, and rewrites each into judgment plus the reason — or flags it for deletion. Shows a review table before touching anything.

### `rule-rewriter/SKILL.md`

```markdown
---
name: rule-rewriter
description: Audit a CLAUDE.md or skill file for rules written for older Claude models and rewrite them for the Claude 5 generation. Finds one-sided rules, bare prohibitions, retired instructions, and aggressive language, then rewrites each into judgment plus the reason, or flags it for deletion. Use when the user says "rewrite my rules", "audit my CLAUDE.md", "fix my claude md", "rule rewriter", "my skills feel too strict", or after upgrading to a Claude 5 model.
---

# Rule Rewriter

Claude 5 models follow judgment better than they follow rules. A bare "never do X" gets ignored or overfitted; "X breaks because [reason], so avoid it" generalizes. This skill audits an instruction file and rewrites it accordingly.

Grounded in Anthropic's published guidance:
- Rules to judgment: https://claude.com/blog/the-new-rules-of-context-engineering-for-claude-5-generation-models
- Give the why: https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices
- Remove verification instructions: https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-opus-5

## Process

1. **Locate the target.** Default to the CLAUDE.md in the current working folder. If the user names a skill or another file, use that. If nothing is found, ask for the path and stop.

2. **Inventory every instruction line.** Classify each into exactly one bucket:
   - **RETIRED**: instructions the model now does by itself. Delete candidates. Examples: "double-check your work", "verify before responding", "think step by step", "be thorough", "always re-read the file", "you are an expert...".
   - **ONE-SIDED**: rules that state a cost or a command without the counterweight, so the model overfits. Rewrite with both sides.
   - **BARE PROHIBITION**: "never / always / do not" lines with no reason. Rewrite as judgment plus the why.
   - **AGGRESSIVE**: "CRITICAL", "YOU MUST", all-caps emphasis written to fix undertriggering on old models. Soften to normal language; Claude 5 overtriggers on these.
   - **OBVIOUS**: anything the model can see by listing the file system or reading the repo. Delete candidates.
   - **KEEP AS HARD RULE**: places where being wrong is expensive. Safety, destructive or irreversible actions, client-facing sends, money, permissions. Do not soften these; a hard rule is correct here.
   - **KEEP AS GOTCHA**: non-obvious project facts the model cannot discover on its own. These are the most valuable lines in the file; leave them alone.

3. **Rewrite.** For every RETIRED, ONE-SIDED, BARE PROHIBITION, AGGRESSIVE, and OBVIOUS line, produce the replacement (or deletion) using the patterns below.

4. **Present before touching anything.** Output a three-column review: original line, proposed line (or DELETE), one-line reason with its bucket. End with the counts per bucket and the estimated line reduction. Apply changes only after the user approves, then edit the file.

5. **Suggest the follow-up.** After applying, remind the user that `claude doctor` right-sizes overall CLAUDE.md and skill length, which this skill deliberately does not do.

## Rewrite patterns

**Bare prohibition to judgment plus why** (Anthropic's own example):
- Before: `NEVER use ellipses`
- After: `Your response is read aloud by a text-to-speech engine, so never use ellipses since it cannot pronounce them.`

**One-sided rule to both sides** (Anthropic's escalation example):
- Before: `Avoid escalating to a specialist, it costs $8 per case.`
- After: `Escalating costs $8, but a wrong answer costs a refund and the customer's trust. Escalate when you are not confident.`

**Retired instruction to deletion**:
- Before: `Before finishing, double-check every file you changed and verify your work.`
- After: DELETE. Claude 5 verifies its own work; this line causes over-verification and wastes tokens.

**Aggressive to normal**:
- Before: `CRITICAL: You MUST use the search tool when the user asks about the codebase.`
- After: `Use the search tool when the user asks about the codebase.`

## Rules for this skill itself

- Never rewrite a line in the KEEP buckets to seem productive. Fewer, correct changes beat many cosmetic ones.
- Preserve the file's voice and formatting conventions.
- When unsure which bucket a line belongs to, put it in the review table with a question mark and let the user decide. Do not guess on safety-relevant lines.
- This skill changes rule quality, not file structure. For a CLAUDE.md that is too long, route sections into folder-level instruction files instead (see the companion optimization framework).
```

---

## 3. prompt-master

Say "prompt master", "write me a prompt", "fix this prompt", or "audit my CLAUDE.md". Interviews the user, then writes a new prompt, rewrites an old one, or audits a whole instruction file — applying all eight rules and citing the Anthropic source for each change. This skill is bigger than the other two: one `SKILL.md`, one `EVAL.md`, and seven files under `references/` that it routes to by step.

### `prompt-master/SKILL.md`

```markdown
---
name: prompt-master
description: The one skill for prompting the Claude 5 models the way Anthropic says to. Starts by interviewing the user one question at a time, then writes a new prompt, rewrites an old one, or audits a whole CLAUDE.md, skill or project-instruction file, applying the rules from Anthropic's Opus 5 guide, Fable 5 guide, best practices page and context engineering post. Strips retired instructions, rebuilds the ask as a Full Job Brief (job, why, guardrails, done-means), swaps bare rules for reasons, caps scope, length and report-back, and adds Anthropic's audit line on long runs. Use when the user says "prompt master", "write me a prompt", "fix this prompt", "upgrade my prompt for Claude 5", "audit my CLAUDE.md", "rewrite my rules", "my skill feels too strict", "brief this for me", "help me brief this", "I don't know where to start", or pastes any prompt and asks why Claude over-does or under-does the task.
---

# Prompt Master

Turns what the user wants into a prompt, brief or instruction file written the way Anthropic says to prompt the Claude 5 generation. Always begins by asking. The rulebook with sources is `references/rulebook.md`; read it once per session, then work from the steps.

## Steps

Track progress:

```
Task Progress:
- [ ] 1. Interview
- [ ] 2. Strip what is retired
- [ ] 3. Rebuild as a Full Job Brief (or audit the file)
- [ ] 4. Rules to reasons
- [ ] 5. Deliver with receipts
```

### 1. Interview
Read what the user gave you first (the pasted prompt, the file, the folder). Then interview one question at a time following `references/interview.md`: never ask what you could read, prioritise questions whose answer changes the shape of the prompt, stop at 7. Skip to a single confirming question when the ask is already small and clear. By the end you know the input type (rough idea, existing prompt, or whole instruction file), the surface (Claude Cowork, Claude app, Claude Code, API), the run length (quick answer or long run), who the output is for, and what done looks like.

### 2. Strip what is retired
For an existing prompt or file, check every line against `references/retired-instructions.md`; remove or replace what matches and record each change for step 5. For a rough idea, skip this step.

### 3. Rebuild as a Full Job Brief, or audit the file
- **Rough idea or existing prompt:** write it in the four-part shape from `references/job-brief.md` (job, why, guardrails, done-means). For a long run, append the audit line verbatim from that file. Drop any part the task does not need; a three-line prompt is right for a three-line task.
- **Whole instruction file (CLAUDE.md, skill, project instructions):** run the bucket audit in `references/file-audit.md`, produce the three-column review table, and wait for approval before editing anything.

### 4. Rules to reasons
Re-read the draft or the proposed rewrites. Any hard rule without a reason gets the reason or gets cut, using `references/rules-to-reasons.md`. Keep hard rules only where being wrong is expensive. Say what to do instead of what not to do.

### 5. Deliver with receipts
For a prompt: one copyable block, then a change log of at most five bullets, each naming the rule applied and its Anthropic source, taken from the source column of the reference file that drove the change (`rulebook.md` for the brief, `retired-instructions.md` for removals). For a file audit: the review table, counts per bucket, and the line reduction; apply on approval. If the user wants a standing voice or format, add the one-line install note for their surface from `references/voice-and-format.md`. Nothing else.

## Human checkpoints
- Interview answers are the first checkpoint; play the brief back in one paragraph before writing if the task is large.
- File audits never edit before the user approves the review table.
- If the user asks for options, give 3 to 5 variants that each differ in one dimension (tighter scope, longer run, different audience, terser report-back, different surface) and let them pick.

## Self-improvement
This skill is never finished. Improve it as you use it.
- When the user corrects how a step was done, update the relevant reference file (or this SKILL.md) so the correction sticks. Do not just fix it for this run.
- When a correction is a hard rule, add it here as a permanent rule with its reason.
- When the user says a prompt or audit was genuinely good, save the input and output to `references/examples/` as a model for future runs.
- When Anthropic updates the Opus 5, Fable 5 or best practices pages, update `references/rulebook.md` and re-check `references/retired-instructions.md` before changing anything else.
- Keep the skill small while doing this: when you add something, cut anything that no longer changes behavior.

## Routing
| Step | Reference |
|------|-----------|
| All steps, once per session | `references/rulebook.md` |
| 1. Interview | `references/interview.md` |
| 2. Strip what is retired | `references/retired-instructions.md` |
| 3. Rebuild (prompt) | `references/job-brief.md` |
| 3. Audit (whole file) | `references/file-audit.md` |
| 4. Rules to reasons | `references/rules-to-reasons.md` |
| 5. Standing voice or format | `references/voice-and-format.md` |
| 3. Rebuild, when the input is an existing prompt: skim before drafting | `references/examples/client-report-rewrite.md` |
```

### `prompt-master/EVAL.md`

```markdown
# Functionality eval for prompt-master

Run in a fresh session with the skill installed. Pass only if all three hold: steps executed in SKILL.md order, the reference file for each step loaded, output is one prompt block plus a change log of at most five cited bullets.

Prompt to paste:

Run an eval on this skill with 3 sub-agents to test if it functions the way it is supposed to, according to the goal and steps laid out in the SKILL.md. Does it execute the process in the right order? Does it load the reference files when it should? Give me a report with what passed, what failed, and specific fixes for anything that did not work.

Here is the test input to run it against:

"Fix this prompt, it runs in Claude Cowork: You are an expert marketer. CRITICAL: you MUST research thoroughly. First search our competitors, then make a comparison table, then write a summary. Think step by step and double-check everything. Do not use bullet points. Tell me everything you did."

Output criteria:
1. The delivered prompt has no "CRITICAL", "think step by step", or "double-check" left in it.
2. It contains a why sentence naming who the output is for and what it enables.
3. It caps the report-back (a number of bullets or sentences) and the deliverable size.
```

### `prompt-master/references/rulebook.md`

```markdown
# The Rulebook

The eight rules this skill applies, each with the Anthropic source to cite in the change log. Read once per session. Apply while building; do not explain them to the user unless asked.

Sources (short names used below):
- OPUS5: https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-opus-5
- FABLE5: https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-fable-5
- BEST: https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices
- CTXENG: https://claude.com/blog/the-new-rules-of-context-engineering-for-claude-5-generation-models
- FIELDGUIDE: https://claude.com/blog/a-field-guide-to-claude-fable-finding-your-unknowns
- BORIS: https://www.youtube.com/watch?v=qyPCVqFUyDo (Boris Cherny, YC interview, 2026-07-27)

## Part 1. The handoff

1. **Give it the whole job.** Describe the outcome, the guardrails and what done looks like, then let it run. No step 1, step 2, step 3 scripts. Source: OPUS5, capability bullets: "performs best when given the complete task specification up front and left to run." BORIS at 15:20: "describe the task, describe the guardrails, describe the exit criteria, and then just go."
2. **Let Claude interview you** when the brief has gaps. One question at a time, prioritising questions whose answer would change the plan. Procedure in `interview.md`. Source: FIELDGUIDE.
3. **Say why, not just what.** One sentence of intent beats a paragraph of instructions. Template, verbatim: "I'm working on [the larger task] for [who it's for]. They need [what the output enables]. With that in mind: [request]." Source: FABLE5, "Give the reason, not only the request."

## Part 2. The unlearning

4. **Stop telling it to double-check.** Claude 5 verifies its own work; verification lines cause over-verification and cost tokens with no quality gain. Source: OPUS5, "Task scope and over-verification" and "Self-correction."
5. **Swap rules for reasons.** A bare rule gets ignored or overfitted; a rule with its reason generalises. Source: CTXENG, reversal one ("Give Claude rules" to "Let Claude use judgement"); BEST, "Add context to improve performance" (the ellipses example).

## Part 3. The guardrails

6. **Tell it where the job ends.** Cap three things: scope (what not to touch), deliverable size, report-back size. Claude 5's failure mode is doing too much, not too little. Source: OPUS5, "Task scope," "Response length and verbosity," "Written deliverable length."
7. **Make it prove it.** On long runs, add the audit line so every progress claim points to evidence. Verbatim text lives in `job-brief.md`. Source: FABLE5, "Ground progress claims during long runs" ("nearly eliminated fabricated status reports"). BORIS at 20:26: verification is "the single most important thing people do not get right."

## Part 4. The voice

8. **Fix the voice once.** Put the communication style in a standing home (project instructions, account instructions, or a Claude Code output style) instead of every prompt. Tell it what to do, not what not to do. Source: OPUS5 conciseness instruction; FABLE5 "Strong instruction following" brevity block; BEST, "Control the format of responses."

## What survived (keep using)
A one-line role. XML-style tags to separate instructions, context, input. 3 to 5 examples when output format matters, wrapped in example tags. Long documents above the question. Source: BEST, General principles and Long context prompting.

## Fable 5 only
Never put a standing "explain your reasoning" or "show your thinking" line in a prompt for Fable 5. It can trigger the reasoning_extraction refusal and reroute the request to Opus 4.8. Source: FABLE5, "Recommended scaffolding changes."
```

### `prompt-master/references/interview.md`

```markdown
# Interview (Step 1)

Pull the missing context out of the user's head before writing anything. Pattern from Anthropic's Fable field guide: one question at a time, prioritise questions whose answer would change the plan (FIELDGUIDE in `rulebook.md`).

## Procedure
1. **Read before you ask.** Open the pasted prompt, the file, the folder, recent related work. Never ask a question you could answer by reading. Every question spent on a discoverable fact is one you cannot spend on a real unknown.
2. **Classify silently** from what you read: input type (rough idea, existing prompt, whole instruction file), likely surface, likely run length. Ask only about what is still unknown.
3. **One question per turn, plain language, 7 maximum.** Order by how much the answer changes the prompt:
   - What is the deliverable, named as a thing a colleague could hand back?
   - Who reads or uses it, and what do they decide with it? (This becomes THE WHY.)
   - What already exists that the prompt should build on or must not touch? (Scope guardrail.)
   - Where does it run: Claude Cowork, Claude app, Claude Code, or an API system prompt, and does that place already carry standing instructions or a role? (Decides format, tags, install home, and whether the prompt needs a role line.)
   - For research or data tasks: which sources or tools may it use, and which are off limits? (Becomes a scope guardrail.)
   - Is this a quick answer or a long run with files touched and minutes of work? (Decides the audit line and the caps.)
   - Which decisions does the user actually care about? Everything else is the model's call.
   - How should done be proven, and how short should the report-back be?
4. **Push past vague answers.** "Make it better" gets "better how, and how would we both know?" One follow-up, then move on.
5. **Let them ramble.** If the user dumps everything at once, reconstruct the answers from the ramble and only ask about what it did not cover.
6. **Blind spot pass.** Before writing, name in one or two sentences what the interview has not covered that could change the outcome. Ask about it only if it matters.
7. **Play it back** for anything larger than a one-paragraph prompt: one paragraph restating job, why, scope, done. Proceed on a yes.

## Skip rule
When the ask is already small and clear (the deliverable, audience, surface and size are all evident from what was pasted), ask one confirming question at most and go. The interview is for work where a wrong start is expensive.
```

### `prompt-master/references/retired-instructions.md`

```markdown
# Retired Instructions (Step 2)

Check every line of an existing prompt against this list. Match: remove it, or replace it with the fix given. Record each change for the step 5 change log. Sources are short names from `rulebook.md`.

| # | If the prompt says | Do this | Source |
|---|---|---|---|
| 1 | "Double-check your answer", "re-verify before responding" | Delete. Claude 5 verifies on its own; the line causes re-verification. | OPUS5, Self-correction |
| 2 | "Include a final verification step", "use a subagent to verify" | Delete. | OPUS5, Task scope |
| 3 | "Think step by step", "reason carefully before answering" | Delete. Thinking is adaptive on Claude 5. | BEST, Thinking |
| 4 | "CRITICAL:", "You MUST", all-caps emphasis | Rewrite in normal language: "Use [tool] when..." | BEST, Tool usage |
| 5 | "If in doubt, use [tool]", "default to using [tool]" | Rewrite: "Use [tool] when it would improve your understanding of the problem." | BEST, Overthinking |
| 6 | "Only report high-severity issues", "be conservative" (review prompts) | Rewrite: report everything, filter in a separate pass. | OPUS5, Code review bullet |
| 7 | Bare "do not use markdown", "no bullet points" | Rewrite as the positive: "Write in smoothly flowing prose paragraphs." If the user also asks for a table or list, keep that element and apply the prose rewrite to the surrounding text only. | BEST, Format control |
| 8 | A prefilled opening line ("Here is the summary:") | Delete. Prefill is unsupported from Claude 4.6 on; instruct directly: "Respond directly without preamble." | BEST, Prefill migration |
| 9 | A defensive patch nobody can explain ("never mention plan details, point to the URL") | Delete unless the user can name the failure it fixes. | CTXENG core argument |
| 10 | "Do not think", "do not reason" | Delete. On Opus 5 it increases leaked internal tags. | OPUS5, Thinking disabled |
| 11 | "Explain your reasoning" as a standing line, Fable 5 target | Delete. Can trigger the reasoning_extraction refusal and reroute to Opus 4.8. Ask ad hoc instead. | FABLE5, Scaffolding |
| 12 | "First do 1, then 2, then 3, then 4" task scripts | Rewrite as job, guardrails, exit criteria (step 3). | BORIS 15:20 |
| 13 | "Be thorough", "do a great job", "this is vital to my career" | Delete. No concrete change attached. | Deletion test |
| 14 | "Tell me everything you did", "give me a full summary" | Replace with a report-back cap: "tell me where the result is and give me [3] short sentences on what you did. Nothing more." | OPUS5, Verbosity |
| 15 | Chain-of-thought or emotion-prompting boilerplate carried from older frameworks | Delete. | BEST, Migration considerations |

## Keep these (do not strip)
- Project gotchas the model cannot discover on its own.
- Hard rules where being wrong is expensive: deletes, sends, money, permissions, client-facing surfaces.
- A one-line role.
- XML-style section tags.
- 3 to 5 examples when output format matters.
- Numbered steps when order or completeness genuinely matters (a checklist, a compliance sequence). Order that matters is not a script.
```

### `prompt-master/references/job-brief.md`

```markdown
# The Full Job Brief (Step 3)

Build every prompt in this shape. Fill the brackets from the intake. Drop a part when the task does not need it; do not pad a small task into four sections.

## The template

THE JOB
[What you want done, as an outcome, not as steps. One or two sentences.]

THE WHY
I'm working on [the larger task] for [who it's for]. They need [what the output enables].

THE GUARDRAILS
- Only touch [the scope]. Leave everything else alone.
- [Anything that must not change, be sent, or be deleted, with the reason.]
- Make routine judgment calls yourself. Ask me only if the answer would change the whole result.

DONE MEANS
- [How we both know it's finished: the exit criteria.]
- Keep the deliverable to [size: sections, word count, or "as short as covers the substance"].
- When you finish, tell me where the result is and give me [3] short sentences or bullets, matching the deliverable's format, on what you did. Nothing more.

## Long-run addendum
When the task runs many steps, touches files, or will not be reviewed line by line, append this line under DONE MEANS, verbatim, unchanged (FABLE5, "Ground progress claims during long runs"):

Before reporting progress, audit each claim against a tool result from this session. Only report work you can point to evidence for; if something is not yet verified, say so explicitly. Report outcomes faithfully: if tests fail, say so with the output; if a step was skipped, say that; when something is done and verified, state it plainly without hedging.

## Decision rules while filling it
- THE JOB names a deliverable a colleague could hand back. If you cannot name it, go back to the interview; one more question.
- THE WHY is one sentence. Who reads it and what they decide with it beats any adjective.
- THE GUARDRAILS hold scope and the expensive mistakes only. Anything the model would do correctly on its own is not a guardrail.
- DONE MEANS always has all three caps (exit, deliverable size, report-back size) for a long run. For a quick answer, the report-back cap alone is usually enough.
- Use plain section labels or XML-style tags; both work. Match whatever the user's surface already uses.
- Add a one-line role at the top only when the domain is specialised and the surface has no standing instructions.
- Add examples (3 to 5, in example tags) only when output format matters and words cannot pin it down.

## Filled example

THE JOB
Update our monthly client report for August with the latest numbers and one clear recommendation.

THE WHY
I'm running the [client name] account for our agency. The client's CEO reads this to decide next month's budget, so it has to be skimmable and decision-ready.

THE GUARDRAILS
- Only update the August report file. Leave the rest of the client folder alone.
- Keep the existing structure and branding exactly as they are; the client signed off on them.
- Make routine judgment calls yourself. Ask me only if the numbers look wrong at the source.

DONE MEANS
- Every number traces to the analytics export in the data folder.
- Under 300 words across the same 5 sections as July.
- When you finish, tell me where the file is and give me 3 short bullets on what changed. Nothing more.
```

### `prompt-master/references/file-audit.md`

```markdown
# Whole-file Audit (Step 3, file branch)

For a CLAUDE.md, a skill, or a project-instructions block. Classify every instruction line, propose the fix, present, then edit on approval.

## Buckets
Each line goes in exactly one:
- **RETIRED:** the model now does this on its own. Delete candidates. Matches anything in `retired-instructions.md`.
- **ONE-SIDED:** states a cost or command without its counterweight, so the model overfits. Rewrite with both sides.
- **BARE PROHIBITION:** never / always / do not, with no reason. Rewrite as judgment plus the why.
- **AGGRESSIVE:** CRITICAL, YOU MUST, all caps. Rewrite in normal language; Claude 5 overtriggers on these.
- **OBVIOUS:** anything the model can see by listing the folder or reading the repo. Delete candidates.
- **KEEP AS HARD RULE:** expensive if wrong: deletes, sends, money, permissions, client-facing surfaces, legal. Do not soften. Add the reason if missing.
- **KEEP AS GOTCHA:** non-obvious project facts the model cannot discover. The most valuable lines in the file. Leave alone.

## Procedure
1. Inventory every instruction line with its bucket. Unsure: mark with a question mark and let the user decide. Never guess on safety-relevant lines.
2. Write the replacement (or DELETE) for every RETIRED, ONE-SIDED, BARE PROHIBITION, AGGRESSIVE and OBVIOUS line using `rules-to-reasons.md`.
3. Present a three-column table: original line, proposed line or DELETE, one-line reason with bucket. Close with counts per bucket and the estimated line reduction.
4. Edit only after approval. Preserve the file's voice and formatting.
5. If the file is long because it holds many topics rather than many bad rules, say so: route sections into folder-level instruction files instead of trimming rules. Rule quality and file structure are different problems.

## Rules for the audit
- Never rewrite a KEEP line to look productive. Fewer correct changes beat many cosmetic ones.
- One-line role, XML-style section tags, 3 to 5 format examples, and ordered steps where order genuinely matters all stay (BEST, `rulebook.md`).
- Anthropic's own examples of the rewrite are in `rules-to-reasons.md`; reuse their shape.
```

### `prompt-master/references/rules-to-reasons.md`

```markdown
# Rules to Reasons (Step 4)

Re-read the draft. Every hard rule gets one of three treatments. Sources in `rulebook.md`.

## Decide per rule
1. **Expensive if wrong** (delete, send, spend, permissions, client-facing, legal): keep as a hard rule, add the reason in the same sentence.
2. **A preference or style**: rewrite as judgment plus the why, or as the positive instruction.
3. **Something the model would do anyway**: cut it.

## Patterns

Bare prohibition to reason (Anthropic's own example, BEST):
- Before: `NEVER use ellipses`
- After: `This will be read aloud by a text-to-speech engine, so avoid ellipses; it cannot pronounce them.`

One-sided cost to both sides (Anthropic's escalation example, Prompting Playbook workshop):
- Before: `Avoid escalating to a specialist, it costs $8 per case.`
- After: `Escalating costs $8, but a wrong answer costs a refund and the customer's trust. Escalate when you are not confident.`

Negative to positive (BEST, format control):
- Before: `Do not use markdown.`
- After: `Write in smoothly flowing prose paragraphs.`

Aggressive to normal (BEST, tool usage):
- Before: `CRITICAL: You MUST use the search tool when the user asks about the codebase.`
- After: `Use the search tool when the user asks about the codebase.`

Rigid rule to judgment (Anthropic's comments example, CTXENG):
- Before: `Never write multi-paragraph docstrings or multi-line comment blocks, one short line max.`
- After: `Write code that reads like the surrounding code: match its comment density, naming, and idiom.`

Expensive rule, kept, with reason:
- Before: `Never email the client directly.`
- After: `Never email the client directly; every client message goes through me because they have one point of contact by contract. Draft it and hand it to me.`

## Check before moving on
- No all-caps emphasis left.
- No rule without a reason except the ones that survived rule 1 above, and those carry their reason too.
- Nothing in the prompt tells the model to verify, double-check, or think harder.
```

### `prompt-master/references/voice-and-format.md`

```markdown
# Voice and Format (Step 5 addendum)

Use when the user wants a standing communication style, or when the prompt would otherwise repeat voice instructions on every run. Put the style in a home once; keep it out of individual prompts.

## The Plain English block
Assembled from Anthropic's own instruction texts (OPUS5 conciseness and deliverable-length instructions, FABLE5 brevity block). Hand it over as is.

Communicate in plain English.

Lead with the answer: your first sentence should tell me what happened or what you found. Supporting detail comes after, for when I want it.

Keep responses focused, brief, and concise. Keep disclaimers and caveats short, and spend most of the response on the main answer. When asked to explain something, give a high-level summary unless I specifically ask for depth.

Use the simplest everyday words that carry the idea. If a technical term is genuinely needed, define it in a few words the first time it appears. State each fact once.

Match the length of written documents to what the task needs: cover the substance, but do not pad with filler sections, redundant summaries, or boilerplate.

## Where it lives, by surface
Give the one line that matches the user's surface from intake.
- **Claude Cowork:** paste into the project's instructions. Every chat in that project inherits it.
- **Claude app, everywhere:** Settings, profile, Instructions for Claude. Applies account-wide.
- **Claude Code:** an output style. "Create an output style from the following text and switch to it," or /config, output style. Docs: https://code.claude.com/docs/en/output-styles
- **API system prompt:** put it at the end of the system prompt as a short tone block; long system prompts benefit from a one-line reminder near the end (OPUS5, "Response length and verbosity").

## On-demand companion
For a single confusing answer, point to /eli5 from Anthropic's community plugin marketplace instead of writing a simplification prompt: https://github.com/anthropics/claude-plugins-community/blob/main/eli5/skills/eli5/SKILL.md

## Format rules to apply inside any prompt
- State the format you want, not the one you do not want.
- Match the prompt's own formatting to the output you want; a prompt full of bullets gets bullets back.
- For prose-heavy output, the phrase "smoothly flowing prose paragraphs" is the one Anthropic uses.
- Wrap variable inputs and documents in XML-style tags; put long documents above the question.
```

### `prompt-master/references/examples/client-report-rewrite.md`

```markdown
# Example: rewriting an old prompt for Claude 5

Input type: existing prompt. Surface: Claude Cowork. Run length: long (touches files, several minutes).

## The user's original prompt

You are an expert business analyst. CRITICAL: you MUST be thorough. First, open the client folder and read every file. Second, find the August analytics export. Third, update the report with the new numbers. Fourth, write a recommendation. Think step by step. Double-check all numbers before responding and verify your work. Do not use markdown. Do not change anything else. Give me a full summary of everything you did.

## Step 2, stripped
- "CRITICAL: you MUST be thorough" (retired #4, #13)
- "First / Second / Third / Fourth" script (retired #12)
- "Think step by step" (retired #3)
- "Double-check all numbers ... verify your work" (retired #1)
- "Do not use markdown" rewritten positive (retired #7)
- "Give me a full summary of everything you did" replaced by a report-back cap (rule 6)
- Kept: the one-line role, since Cowork project has no standing instructions here.

## Delivered prompt

You are the analyst on our agency's client accounts.

THE JOB
Update the August client report with the latest numbers and one clear recommendation.

THE WHY
The client's CEO reads this report to set next month's budget. It has to be skimmable and decision-ready.

THE GUARDRAILS
- Only update the August report file. Leave the rest of the client folder alone.
- Keep the existing structure and branding; the client signed off on them.
- Make routine judgment calls yourself. Ask me only if the numbers look wrong at the source.

DONE MEANS
- Every number traces to the August analytics export in the data folder.
- Under 300 words, same 5 sections as July, written in flowing prose.
- When you finish, tell me where the file is and give me 3 short bullets on what changed. Nothing more.
- Before reporting progress, audit each claim against a tool result from this session. Only report work you can point to evidence for; if something is not yet verified, say so explicitly. Report outcomes faithfully: if tests fail, say so with the output; if a step was skipped, say that; when something is done and verified, state it plainly without hedging.

## Change log delivered with it
- Removed the four-step script and gave the job as an outcome (Opus 5 guide: complete task up front; Boris Cherny, YC interview 15:20).
- Added the why: who reads it and what they decide (Fable 5 guide, "Give the reason, not only the request").
- Deleted "double-check", "verify", "think step by step", "CRITICAL" (Opus 5 guide, self-correction and task scope; best practices, tool usage).
- Capped scope, length and report-back to 3 bullets (Opus 5 guide, verbosity and deliverable length).
- Appended Anthropic's audit line verbatim because the run touches files (Fable 5 guide, "Ground progress claims during long runs").
```