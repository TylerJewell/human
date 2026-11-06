# loan-origination

The Loan Origination assistant of the Akka local console with its human oversight
rebuilt on the library: the seven scenario controls S1 to S7, the template review, and the domain
reviews that were plain workflow pauses there. The service runs on port **9166** with the
development routes on, registers itself in `~/.akka/human/services/loan-origination.json` for the
companion, and serves the oversight contract at `/oversight/*` ([`spec/CONTRACT.md`](../../spec/CONTRACT.md)).
It needs no model API keys: its agents are deterministic stand-ins.

People decide every event through `/oversight`: claim, decide, escalate, send back and so on. The
reviewers are the ones in [`governance/overseers.yaml`](src/main/resources/governance/overseers.yaml);
the roles a call carries must be ones the registry gives that person.

## Run

```
mvn compile exec:java          # or akka_local_run_service on this directory
curl localhost:9166/oversight/info
```

`mvn -q -pl samples/loan-origination -am verify` (from the repository root) runs the TestKit
integration tests in-process. The local runtime knows the service as `human-loan-origination`, since
the local console's own loan-origination service (9089) uses the plain name; events and the registration
file name it `loan-origination`.

## How the library is wired

- `LoanSetup` builds the `HumanPlugins`: the bundle and the overseer registry of `governance/`, a
  resumer for each waiting workflow (underwriting, notice, contest, deadline extension), the apply
  handlers of the monitors, flags, templates and model changes, the halt scope of
  `eligibility-assessment`, the local proof ledger and the development clock. Its dependency provider
  hands the plug-ins and the `HumanClient` to every component.
- `application.conf` lists the library components the service loads and sets `human.environment`
  (`pre-production`, as the demonstration raised every event), `human.dev-routes`, `human.register`,
  the monitors' interval and the halt's backlog limit.

## Controls

[`governance/controls.yaml`](src/main/resources/governance/controls.yaml) keeps the names of
the local console's bundle under ids of its own, so a runtime shared with the local console's
loan-origination service has no two controls with one id. Each id is formed as Verify forms an authored
row's: `custom-` and the first 12 hex characters of `sha256(name + "|" + 2026-10-29T19:37:07Z)`, the
time the rows were written. Each row's `human_config` is Verify v2.8 and names its pattern.

| Row | Name | Pattern | Flavor | Who decides | Deadline, then | Raised by |
|---|---|---|---|---|---|---|
| `custom-042d5cb61624` | S1 Deadline extensions beyond policy need the system owner | action-approval | system-authorization | loan-system-owner; head-of-credit when escalated; 20 s minimum; judge recommendation | PT30M, then DENY | The support assistant asks for more than 14 days (`DeadlineExtensionWorkflow`) |
| `custom-91174bd91366` | S2 Referral rate drift is reviewed by the model risk owner | triage | monitoring | model-risk-owner, then head-of-credit | P1D, escalate | `Monitors`: under 20% of the last 24 h's assessments referred (once there are 20) |
| `custom-b5bb62199a0a` | S3 Rushed underwriting reviews go to the risk officer | oversight-quality | monitoring | risk-officer, then head-of-credit | P2D, escalate | `Monitors`, from the library's figures: over 10% of the underwriting review's decisions in 7 days made under its 8 s minimum |
| `custom-347ea9d5a177` | S4 Bursts of protected-characteristics blocks go to compliance | triage | monitoring | compliance-lead, then head-of-credit | PT1H, escalate | `Monitors`: more than 5 notice drafts blocked in the last hour |
| `custom-83bbe9c7cde5` | S5 Model changes to adverse-action notices need two approvals | change-authorization | system-authorization | two of ai-system-owner and compliance-lead, neither proposer nor builder; 60 s minimum | P2D, then DENY | `POST /notice-writer/model-changes` |
| `custom-d04d2229e3f9` | S6 Automated eligibility assessment can be halted | halt-restart | halt | one oversight or safety officer halts; two others approve a restart while the safe-state checks pass | PT4H, then DENY (restart) | `POST /oversight/halts`; a HALT on S2 proposes one |
| `custom-4c293fe7fd2e` | S7 Flagged support replies are triaged | flag-feedback | feedback | ai-system-owner, then compliance-lead | P5D, abandoned | `POST /support/sessions/{id}/flag` or `POST /oversight/flags` |
| `custom-40579578cb71` | Adverse-action letter template review | change-authorization | system-authorization | underwriting-counsel, not the proposer or the notice writer | P3D, then the proposer is told | `POST /notice-writer/templates` |
| `custom-00d5b5cdf6bc` | Held adverse-action notices are released by a reviewer | decision-review | domain-decision | adverse-action-reviewer | PT4H, then DENY (standard letter) | `NoticeWorkflow`: the guardrail blocked the draft |
| `custom-b406265b0443` | Referred applications are decided by an underwriter | decision-review | domain-decision | underwriter, then senior-underwriter; 8 s minimum, recorded rather than refused; assessor recommendation | P2D, then P3D at senior review | `UnderwritingWorkflow`: the rule did not approve alone, or the assessment is halted |
| `custom-27163ccc75f2` | Declined applicants can contest the decision | contest-appeal | domain-decision | senior-underwriter, then head-of-credit; never the underwriter who declined or their managers | P14D, then P7D at head of credit | `POST /applications/{id}/contest` (`ContestWorkflow`) |

The template review is change authorization, not decision review: a template is a change to every
notice for its decline reason, authorised before it is used, not a decision about one person.

### Settings that differ from the local console's rows

- **Flavors.** The seven scenario rows keep theirs. `custom-40579578cb71` had no flavor (mechanism `hitl`); it
  takes `human-system-authorization`, the only flavor change authorization runs under.
- **S1.** A timeout applies the default (DENY) at the first deadline; the head of credit decides only
  when someone escalates. A standing approval for the pattern settles later requests for the same
  number of days (the library matches the exact match key), not "up to as many days".
- **S3.** `rushed-review-share > 10` (reviews under 8 s) is `below-minimum-share > 0.1`, the library's
  figure, over the underwriting review's minimum review time of 8 s (`minimum_review: WARN`, so rushed
  decisions are recorded, breached, rather than refused). The library has no minimum count of
  reviews. An EDIT records the minimum review time asked for on the monitor; the control's minimum
  comes from the bundle and does not change at runtime.
- **S5.** Independence `[proposer, first-reviewer]` is `[proposer, builder]`: first-reviewer names the
  decider under appeal, and two-person review already needs two different people. Any two holders of
  either role may approve; the library has no "one of each role".
- **S6.** Allowed actions `[APPROVE, DENY]` are `[HALT, APPROVE, DENY]`: as Verify's halt-restart
  defines it, one officer halts at once and the restart is the two-person decision. Independence
  `[first-reviewer]` is `[proposer]`: whoever asks for a restart may not approve it. The 20 s minimum
  review time is left out; a control with one still halts at once, and the minimum applies to the restart
  votes.
- **S2, S3, S4, S7.** `sticky_modes: []` is left out (the shared default, approve once, has the same
  effect). S2's minimum of 20 assessments is service configuration
  (`loan-origination.monitors.referral-minimum`). S4's first draft allowed halting notice generation;
  as in the local console, it is not offered.

## Overseers

[`governance/overseers.yaml`](src/main/resources/governance/overseers.yaml): the people of the local console's
prototype fixtures, with roles added so every control with two-person review or an independence rule
has at least two qualified people. K. Osei stays unqualified (training expired).

| Person | Prototype roles | Added |
|---|---|---|
| tyler.jewell@akka.io | ai-system-owner, loan-system-owner, head-of-credit | |
| a.devos@example.com | model-risk-owner | risk-officer |
| r.chen@example.com | compliance-lead | oversight-officer, adverse-action-reviewer, underwriting-counsel |
| s.patel@example.com | oversight-officer, safety-officer | |
| j.lind@example.com | head-of-credit | loan-system-owner, senior-underwriter, underwriter |
| m.okafor@example.com | underwriting-counsel, adverse-action-reviewer | senior-underwriter, underwriter |
| l.moreau@example.com | safety-officer | underwriter |
| k.osei@example.com (unqualified) | risk-officer | underwriter |

## Domain routes

| Scenario | Route | Body | What happens |
|---|---|---|---|
| Intake | `POST /applications` | `{ applicant, nationalId, amount, termMonths, monthlyIncome, monthlyDebts }` | Documents requested; deadline in 14 days. A `nationalId` starting `DEF` has a default in the simulated credit file |
| Underwriting | `POST /applications/{id}/documents` | `{ type: PAYSLIP \| BANK_STATEMENT, reference }` | With both in, `UnderwritingWorkflow` assesses; a low-risk approval up to 25,000 goes ahead alone, anything else raises `…09` for an underwriter. APPROVE approves, DENY declines and starts the notice |
| | `GET /applications`, `GET /applications/{id}`, `…/underwriting`, `GET /underwriting/queue` | | Every application with its status; the application with its history; the workflow; applications waiting for an underwriter |
| Held notice | (a decline whose rationale names a protected characteristic) | | The guardrail withholds the sentence and `NoticeWorkflow` raises `…08`: APPROVE issues the draft as shown, EDIT the patched `/output`, DENY or a timeout the standard letter. `GET /applications/{id}/notice` |
| Contest | `POST /applications/{id}/contest` | `{ statement }` | Within 30 days of the notice. `ContestWorkflow` raises `…10`: REVERSE approves the application, APPROVE upholds and issues a contest notice. `GET …/contest` |
| S1 | `POST /support/turns` | `{ sessionId?, applicationId, message }` | The assistant answers; "N more days" moves the deadline at once up to 14, beyond that starts `DeadlineExtensionWorkflow` (answer's `extensionId`): APPROVE moves it N days, EDIT the patched `/arguments/days`. Asking about the extension later reports the outcome. `GET /deadline-extensions/{id}` |
| S7 | `POST /support/sessions/{id}/flag` | `{ by, note }` | Flags the session's latest reply; APPROVE adds it to the eval set (`GET /support/eval-set`), DENY dismisses |
| S5 | `POST /notice-writer/model-changes` | `{ to, proposedBy, builtBy, evaluation? }` | Two approvals switch the notice writer's model (`GET /notice-writer`); a send-back goes to the proposer, who resubmits through `POST /oversight/events/{id}/resubmit` |
| Template | `POST /notice-writer/templates` | `{ reason, text, proposedBy? }` | Counsel's approval puts the template in use for the reason; later notices for it open with it |
| S6 | `POST /oversight/halts` | `{ reviewer, control: "custom-d04d2229e3f9", lift: false, reason }` | Halted: underwriting asks no assessor and refers every application (`AUTOMATED_ASSESSMENT_HALTED`) |
| | `POST /oversight/halts` with `lift: true` | | A restart; approval is refused (409) until no application is between documents and routing and at most `loan-origination.halt.max-referral-backlog` (5) wait for an underwriter (`GET /oversight/halts` shows the checks) |
| S2, S3, S4 | `POST /monitors/run` | `{}` | Runs the monitors now (they also run every minute) and answers each reading; one event per episode, later readings add occurrences while it is pending. A HALT on S2 raises an S6 halt for an officer to exercise. `GET /monitors/{key}` shows what was decided (S3's key is `custom-b5bb62199a0a:<watched control>`) |

## Underwriting desk

The service's own screens, at http://localhost:9166/, for the people who make the loan's domain
decisions and never open the Akka console. It is a Vite and React app in [`ui/`](ui), drawn with
`@akka-human/kit` in a plain bank-operations look (navy bar, white panels, the system's fonts, dense
tables). Every review is decided next to the application, through the kit's decision panel:

- **Underwriting queue** (`#/underwriting`): `GET /underwriting/queue`, most urgent first. The open
  application shows its figures, the eligibility assessment and the assessor's recommendation beside the
  decision panel of its `…09` review, and the history beneath.
- **Held notices** (`#/notices`): `…08` events. The held draft, with the withheld sentences removed,
  beside the panel: release it as shown, amend it, or send the standard letter.
- **Contests** (`#/contests`): `…10` events. The original decision and the appeal, with the person who
  made the decision under appeal (and the line above them) named as barred.
- **Applications** (`#/applications`): `GET /applications` with a status filter; each opens to its file.

Halts, model changes, monitors and flagged replies are governance: the desk links to the Akka console
(http://localhost:9167/) for them. The officer picker chooses who the desk acts as, from
`GET /oversight/registry`; writes go to `/oversight/*` on the same origin with that person stamped as a
rehearsal (`identityProvider: loan-underwriting-desk`).

The built files are kept in `src/main/resources/static-resources/`, so the Maven build needs no Node.
After changing the desk, rebuild them and restart the service:

```
pnpm install                                               # once, at the repository root
pnpm --filter @akka-human/loan-origination-ui build        # writes static-resources/
pnpm --filter @akka-human/loan-origination-ui test         # the desk's own logic
pnpm --filter @akka-human/loan-origination-ui dev          # http://localhost:5184, calls forwarded to :9166
```

## Stand-ins

The model agents are deterministic classes in `domain/`: `EligibilityAssessor` (APPROVE, REFER or
DECLINE from debt-to-income and the credit file, never the applicant's identity, plus the
automatic-approval rule), `NoticeWriter` (explains the reasons, repeats the underwriter's rationale;
its guardrail withholds sentences naming health, age, marital status, religion or ethnicity),
`ApplicantSupport` (the assistant and its `extendDocumentDeadline` tool, and the deadline extension
judge). The documents deadline is recorded and moved, but no timer expires an application whose
documents do not arrive.
