# simlab-forge-shim

A thin, GPL-3.0 Java adapter that drives [Forge](https://github.com/Card-Forge/forge)
matches programmatically for [Sim Lab] and emits typed game logs as JSON-lines.
Forge itself is used as an **unmodified** upstream release jar.

## Why this exists as a separate repo

This code links Forge, so it is a GPL derivative and is licensed GPL-3.0
(see LICENSE). Sim Lab's own engine and app talk to this shim strictly over a
subprocess boundary and remain independent works.

**Boundary rule:** the shim stays a thin adapter. Strategy knowledge — deck
plans, personality parameters, combo lines, heuristic weights — arrives as
data from the caller (JSON), and is never encoded in Java here. If a change
adds strategy logic to this repo, it belongs on the other side of the
boundary instead.

## Build

Requires Java 17+ and a local Forge desktop jar (2.0.13 tested).

```bash
FORGE_JAR=~/forge/forge-gui-desktop-2.0.13-jar-with-dependencies.jar ./build.sh
```

The build runs the card-name lint first when it can (see "Card-name lint"
under the 0.17.0 notes) and stamps the jar with the commit it was built
from.

## Run

Working directory must be the Forge install dir (Forge resolves `res/`
relative to cwd). Headless Linux needs a virtual display (xvfb) exactly like
Forge's own `sim` mode.

```bash
cd ~/forge
java -cp /path/to/simlab-forge-shim.jar:$FORGE_JAR simlab.shim.SimShim \
  --decks /abs/path/a.dck /abs/path/b.dck /abs/path/c.dck /abs/path/d.dck \
  --games 2 --timeout 120
```

stdout: one JSON record per line — `meta` (run header), `entry` (typed
GameLog entries, chronological, with card name/id when Forge attaches one),
`zone` (every card movement, both directions), `agent` (decision telemetry),
`result` (per game: winner/draw/turns/duration). stderr: human progress.

`zone` records are the reason the caller can stop guessing at board state:
Forge's text log only reports cards LEAVING the battlefield, while the event
bus reports both directions, including `None -> Battlefield`, which is a token
being created. Since 0.3.0 each record also carries `types` (core card types,
comma separated), `pt` (net power/toughness as of the move, creatures only)
and `token`. Those are read off Forge's own card at the moment it moves, so
tokens — which are not real cards and can never be looked up by name — type
correctly and carry their real stats.

## Stages

Stage 0: stock Forge AI, typed log export.
Stages 1-3: plan-driven mulligans, attack splitting, block valuation,
threat-gated countermagic.
Stage 4: grudge memory, kingmaker re-aim, politics-gated counters,
optional-trigger miss (never mandatory triggers).
Stage 5: gated combo pursuit — tutor steering, line-piece cast priority,
and a greed-gated hold on the final piece. Pursuit activates only behind
the line-of-sight gate (every piece on own battlefield / in own hand, or
one short with a tutor in hand or a search already resolving), acts only
on an empty stack, and never touches combat decisions. Lines, tutors, and
greed arrive in the plan JSON; this file stays mechanism.
Task 21 Half 1 (0.15.0): instant-speed answers are held on the agent's own
turn and cast off-turn by the stock AI's own timing; P(hold) and the cutoff
round are plan data.
Engine A/B (0.16.0, opt-in): combat as a bounded assignment search
(`SeeCombat`) and stack/priority gates; both off unless the plan asks.
Tutoring hotfix (0.17.0, opt-in per flag): tutors cast only for pieces
they can legally find, commanders count as tutors only where their search
works, no raw casts of tutors that need choices, graveyard searches ranked
on graveyard value; plan version 2 (`threatLines`); `--seed-forge`.

### Tutoring hotfix, 0.17.0 (flags)

Sim Lab repair plan WS5 T1 (`tasks/25-repair-plan.md` in Sim Lab). Four
mechanisms, each behind its own plan flag in a per-deck `"fix"` object. A
flag that is absent is false, and **with every flag false (or no `"fix"`
at all) the jar decides exactly as 0.16.0 did.** That is the design; what
was measured is narrower. A paired check of the first review build
(fad2d2d) against 0.16.0 on 4 seeds, same `--seed-forge`, matched every
event, agent event and `tutor_cast` until the pair diverged (turns 9 to
19). Two seeds diverged where two runs of the same jar also diverge; the
other two diverged first in the order lands were tapped (turn 9 or 11).
Byte identity is not claimed, and the check has not been repeated on
later builds. The control and the arm of
an experiment run this same jar and differ only in plan JSON. The mechanisms
ask Forge's own rules objects; which cards are tutors and what a search
should take stay plan data, and a card-name lint (below) keeps it that way.

Plan version 2. A deck plan may carry `"planVersion": 2` and:

- `threatLines`: every catalogued line in the deck. Version 2 narrows
  `lines` to the lines this pilot can win with; `threatLines` keeps the rest
  visible. Everything that reads ANOTHER seat's lines reads `threatLines`
  (the counterspell veto's line-completion alarm in `threatOfSpell`, the
  opponent-line proximity bump in `threatOf`), and so do the seat's own line
  guards: trigger protection (an engine line's looping "you may" trigger is
  never dropped by a `triggerMiss` roll), the instant hold's `ownLine` guard
  and protection discipline. Only the seat's own pursuit (line of sight,
  `combo_cast`, `tutor_cast`) reads the narrowed `lines`. The table's threat
  index is unchanged: a version-2 plan keeps its line pieces in `threat` as
  data, and the shim adds no rule of its own for them. Absent: `threatLines`
  is `lines`, so a version-1 plan reads exactly as before.
- `search.graveyardTargets`: `{"Card Name": 1-9}` values for searches whose
  destination is the graveyard. Absent: empty.
- Card names must be the names Forge uses (`Card.getName()`): a transform or
  modal double-faced card by its front face, a split card as `A // B`.

The flags:

| Flag | What it does |
|---|---|
| `fix.tutorReach` | `tutor_cast` requires the missing piece to be in the library and to pass the tutor's own search restriction. The restriction is the search ability's `ChangeType` (empty = any card, as `ChangeZoneEffect` treats it), run through `AbilityUtils.filterListByType`, the call the search makes when it resolves, with the tutor as host and the seat as activating player (`Card.isValid` directly if that filter cannot evaluate outside a resolution). The same test tightens the line-of-sight gate: "one piece short with a tutor in hand" becomes "with a tutor whose search can find that piece", so an unreachable line no longer hides a reachable one. And when a search resolves, a one-short line counts only if its missing piece is among the cards the search offers, so the steer takes the piece the tutor was cast for instead of sighting a line whose piece is gone (seen on the first smoke run: cast seeking a library card, resolved sighting a graveyard card, and the plan pick took over). |
| `fix.commanderTutorZone` | A commander counts as a tutor for the gate only in the zone where its search works: the command zone or hand when casting it searches (a search spell, or an enters-the-battlefield search), the battlefield for an activated search or a trigger that is live there (Magda's). Other tutors count as before (hand or command zone). |
| `fix.noForcedChoices` | `tutor_cast` skips a tutor it cannot hand to Forge as a raw SpellAbility: an X cost (every shim-forced X tutor resolved at X=0), a modal (Charm) spell, any ability in the spell's chain that uses targets, and a card whose search is an activated ability (transmute included) or a later trigger, since casting it does not search. Forge's own AI can still cast or activate all of these with its own choices. |
| `fix.graveyardDest` | When a search of the seat's own library puts the card into the graveyard, the search is ranked on `search.graveyardTargets` (`mode=graveyard`); a card absent from it scores 0, so Forge's own pick stands unless a listed card beats it. Combo pursuit yields in that mode: a piece the line needs in hand or on the battlefield is lost, not found, by a search into the graveyard. Exile destinations are unchanged. |

Under either tutor flag, `tutor_cast` casts the tutor's own search spell
rather than the first castable spell on the card, so the cast is the search
that was checked.

Records added or changed. Both tutor records keep the 0.16.0 head,
`<tutor> seeking <piece>`, and add single-token `key=value` fields as a
TAIL: `" seeking "` splits the two card names, and a parser strips the
trailing `key=value` tokens off the piece (Sim Lab's `engine/qa/tutors.py`
does exactly this, so a 0.16.0 line and a 0.17.0 line parse the same way).

- `tutor_cast`: `<tutor> seeking <piece> reach=<true|false> where=<zone>
  why=<why> route=<route>`, for example `Diabolic Tutor seeking Whip of
  Erebos reach=true where=Library why=ok route=spell`. It was `<tutor>
  seeking <piece>`; the tail is the new part. `where` is the piece's zone
  among the seat's own (Library, Hand, Battlefield, Graveyard, Exile,
  Command, or `absent`); `why` is `ok`, `not-in-library`, `no-search` (no
  library search found on the card), `restriction`, `unevaluable` or
  `error`; `route` is where the tutor's search lives: `spell`, `etb`,
  `activated`, `transmute`, `triggered` or `none`. `unevaluable` marks a
  restriction that compares against a value set only while the spell is
  paid for (an X cost, a sacrificed or discarded card: Forge's `cmcLEX`
  with an undefined X, or a variable whose SVar says so); before the cast
  that value reads 0, so the miss is not a legality verdict. `reach` stays
  false for it, since the shim cannot show the tutor finds the piece.
  Written with the flags off too, so a control arm measures reach the same
  way.
- `tutor_skip` (new): `<tutor> seeking <piece or -> reason=<reason>
  [kind=<kind>] reach=.. where=.. why=.. route=..`, one record per plan
  tutor per turn for every one left in hand when the turn ends. The tutor
  branch is reconsidered at every empty-stack priority, so a turn offers
  several reasons, and the record keeps the reason from the LAST priority
  at which the tutor could legally be cast (some spell on it passed Forge's
  `canPlay`: timing and zone, not mana). A priority at which it could not
  be cast records `not-castable`, and only when the turn has no reason for
  that card yet. So the end-step pass of a sorcery-speed tutor never
  overwrites the main phase's `no-mana` or `weight`, and a turn in which
  the tutor was never castable (a sorcery on an opponent's turn) reads
  `not-castable`. Reasons: `no-lines`, `gate-closed`, `unreachable`,
  `line-owned` (every piece is owned; nothing to fetch), `stock-first` (a
  land drop or counterspell goes first), `piece-first` (a line piece is
  cast instead), `combo-hold`, `stuck` (two failed casts this turn),
  `forced-choice` with `kind=x-cost|modal|targets|activated|transmute|
  triggered`, `not-castable` (timing or zone), `no-mana`, `weight` (the
  stock pick outweighs it), `other-tutor` (another tutor was cast). The
  flag-driven reasons (`unreachable`, `forced-choice`) appear only when
  their flag is on; the rest appear either way.
- `search_seen`: `mode=graveyard` when `fix.graveyardDest` ranked it.
- `meta`: `shim` is `0.17.0`; new `shimCommit` (the commit build.sh
  compiled, `-dirty` when `src/` differed, `unknown` without git),
  `plansSha256` (SHA-256 of the plans file bytes, null without one),
  `planVersions`, `planThreatLines` (whether the plan carried
  `threatLines`) and `fixFlags` (the four flags), each per seat and
  positional with `players`, null for a stock seat; `seedForge` and
  `seedForgeStride`.
- `result`: `killFailed: true`, present only when the shim ended a game
  (turn cap or wall clock) and Forge still did not report it over
  afterwards. Its thread may then still be playing and, under
  `--seed-forge`, drawing from the next game's generator.

**`--seed-forge <long>`** seeds Forge's own RNG per game:
`MyRandom.setRandom(new Random(seed + g * 104729))` before game `g` is
built. Library shuffles, the first-player pick and the stock AI's rolls
draw from it, so the same seed gives the same opening hands (Sim Lab E2),
and two arms given the same seed list play paired openings. Games are
expected to diverge after the first decision that differs. Without the
flag Forge is unseeded, as before. Plan seats' own controller RNG was
already seeded (`seedBases`).

**Card-name lint.** `tools/lint_card_names.py` (Python 3, stdlib) fails
when a Java string literal is exactly a Forge card name. It reads Forge's
`res/cardsfolder` (the directory or the shipped `cardsfolder.zip`) at run
time and keeps nothing: no card list is committed or cached. Comments are
not checked. It matches whole literals exactly, by design: a name in
another case, or one assembled from pieces (two literals joined with `+`),
passes, so it is a tripwire for the ordinary mistake, not a proof that no
card name reaches the Java.

```bash
python3 tools/lint_card_names.py --cardsfolder ~/forge/res/cardsfolder
# or FORGE_CARDSFOLDER=... / FORGE_JAR=... to find it; exit 0 clean,
# 1 card-name literals found, 2 no cardsfolder (nothing checked)
```

`build.sh` runs it before compiling when Python 3 and the cardsfolder next
to `FORGE_JAR` (or `FORGE_CARDSFOLDER`) are present, and fails the build on
a hit; where either is missing it warns and builds (the worker image's JDK
builder has no Python). `REQUIRE_CARD_LINT=1` makes a skip fail.

**Defects fixed in passing, 0.17.0, affect every earlier version.** Both
were found by the E2 runs (`--max-turns 1` kills every game mid-turn, so
the kill path runs twenty times in a row). Forge's `Game.setGameOver`,
which the shim calls to end a game at the turn cap or the wall clock,
threw a `NullPointerException` from `PlayerOutcome.toString` when the kill
landed at the wrong moment; uncaught, it escaped `main` before the game's
records were drained, losing that game and every game after it. The call
is now guarded (the game is already marked over before Forge builds those
strings, so it still ends). And any exception escaping the driver used to
leave the JVM running, because Forge starts non-daemon threads: the
process sat until the caller's outer ceiling killed it, hours later and
with no exit code. The driver now exits 1.

### Combat solver and priority gates, 0.16.0 (opt-in, engine A/B)

Built for Sim Lab's `studies/engine_ab`, from an outside architecture note
that proposed re-engineering Forge's AI in five phases. Phases 1 and 4 of
that note (decoupling `SpellAbilityAi.canPlay`, bitboards, a Zobrist cache,
an embedded ONNX value net) modify Forge internals and are outside this
repo's boundary; the profile in Sim Lab's `engine/SIM_PERFORMANCE.md` also
puts the shim's combat code at 0.0% of game CPU, so they would not buy
speed here either. Phases 2 and 3 are decision policy, which is what this
shim is for. Both are OFF unless the plan asks (`combatSolver`,
`priorityGates`, P(use) rolled once per game), so a plan that omits them
plays exactly as 0.15.0 did.

**Phase 2, `SeeCombat`: combat as a bounded assignment problem.** Block
allocation is a branch-and-bound search over legal blocker-to-attacker
assignments; attack declaration is a branch-and-bound over the attack set
with each candidate keeping the defender Forge chose (or the highest-threat
opponent it can attack); damage across several blockers is a knapsack that
kills the most value with no assignment order (the Foundations rule). All
three score a terminal board utility V(S) = sum omega(my survivors) minus
sum omega(their survivors), with life priced per point by `lifeValue`
scaled by the race clock: the defender doubles it when being raced (control
role), the attacker doubles it when ahead (beatdown role), danger doubles it
again, and a lethal outcome carries a flat penalty. omega is mana value plus
body plus the table's threat index and the plan's weights; tokens count 0.6.
Forge stays the rules engine: legality is `CombatUtil.canBlock` /
`canAttack` / menace minimums, lethal thresholds are
`ComputerUtilCombat.getEnoughDamageToKill` and `getDamageToKill`, and every
chosen assignment is put to `CombatUtil.validateBlocks` /
`validateAttackers`; if Forge rejects it, Forge's own declaration is
restored exactly. Forge's assignment is also always a candidate, scored on
the same scale, so the solver never applies something it rates worse.
Bounded: 12 bodies a side, 4,000 search nodes, per-cluster memoisation.
Records: `see_attack` (set size, removed, added, value, gains, exposure,
lambda, mu, nodes), `see_block` (blocks vs stock, both values, mu, nodes;
`kept stock` or `REVERTED <reason>`), `see_damage`, `see_error`.

Two defects found and fixed on the first smoke runs, recorded because both
are the kind that hide: the search bound included the lethal penalty and so
pruned every branch after the first leaf (best -1115 against a stock -131);
and the combat-aware legality test reports a body Forge already assigned as
unable to block, so with Forge's blocks in place the search could not even
reproduce them. Forge's movable blocks are now lifted before the search and
put back if they win.

**Phase 3, priority gates.** Three rules from the note, layered on the
0.15.0 hold. The End-Step Rule: off-turn, an instant-speed answer waits for
the opponent's end step; the Red-Zone Rule: on their declare-blockers step
it may fire at an attacker aimed at me that my blocks do not already handle
(unblocked, or blocked only by bodies that die to it); a response, danger
or lethal on board still fire at once. The Threat Matrix: a targeted answer
whose target scores under `removalThreatFloor` is held. S_threat is power
plus the table's threat index for the card plus `threatTempoWeight` times
(target mana value minus spell mana value), plus small bumps for a
commander, a planeswalker and the table leader's things; mass effects and
player targets are never floored. The Response-Gated Protection Rule:
protection, regeneration, phasing, damage prevention, fog, and pumps or
bounce aimed at my own permanents are cast only in response to a hostile
spell or ability pointed at my permanent, to save a body of mine in a
combat it is in, or (fog) when the unblocked attack would put me at or
under `dangerLife`. Records: `instant_hold ... gate=untilEndStep|lowThreat`,
`instant_window ... why=endStep|redZone`, `protect_hold`, `protect_window`.
The counterspell veto (Stage 3/4) already plays the note's stack-graph role:
it fires on the threat of the spell being countered and raises its bar when
others hold open mana, which is the bait case.

**Defect fixed in passing, 0.16.0, affects every earlier agent version.**
Forge's PhaseHandler re-asks `declareAttackers` while the declaration fails
its attack requirements. `kingmakerReaim` (0.4.0 onward) could move an
attacker onto a player it was not permitted to attack; Forge rejected the
set, the stock AI declared again, the re-aim fired again, and the loop ran
until the wall clock killed the game. Measured 2026-09-09: 3,970 asks in
one turn in one A/B game; 23 turns with 50 or more re-aims in the 0.15.0
cohort arm's first round alone. Part of every agent arm's clock censoring
(34.1% and 30.5% against stock's 9.7%) was this, not deliberation. Now
every adjusted declaration is validated (`CombatUtil.validateAttackers`)
and reverted to Forge's own when it fails (`attack_reverted`), and a
second ask in the same turn returns Forge's declaration untouched
(`attack_reask`). Agent censoring rates measured before this fix are not
comparable with rates measured after it.

### Instant-speed discipline, 0.15.0 (behavior change)

Sim Lab task 21 Half 1. Measured on the 66-precon cohort (256 stock and 332
agent games): only 19-22% of instants are cast on an opponent's turn and
2.4-2.7% of all spells off-turn, because stock Forge casts an instant-speed
answer in its own main phase as soon as a target clears its threshold, like
a sorcery. An answer that was never dumped is still in hand when a window
arrives, so this change is only the hold; the stock AI still decides when to
fire off-turn (Half 2, recognising the moment, is not here).

`instantDiscipline` runs after `finisherDiscipline` and before the counter
veto. The pick it acts on is an instant-speed spell (an instant, or flash for
this caster) whose ApiType answers a permanent (Destroy, DestroyAll,
DealDamage, DamageAll, Debuff, Sacrifice, ChangeZone, ChangeZoneAll; Counter
is excluded, the Stage 3/4 veto governs it) and that targets an opponent's
permanent (a player target is face burn and is left alone; an untargeted mass
effect counts). On the agent's own turn the agent keeps it and casts the
heaviest plan-weighted other spell in hand that Forge's own AI (`canPlaySa`)
would play now, excluding other answers and finishers; else it passes the
window. Every hold is one `instant_hold <card> phase=<phase> round=<n>
instead=<card>|pass` record per card per phase (Forge re-asks several times
per phase).

Every answer the stock pick does get to cast is one `instant_window <card>
phase=<phase> turnOf=<player>|ownTurn why=<reason>` record, so own-turn
spending is auditable from agent records alone. The reasons are the windows
the hold exists to preserve and the guards that let the stock pick stand:

- `offTurn`: an opponent's turn.
- `inResponse`: an opponent's spell or ability is on top of the stack. The
  agent's own triggers resolving in its own upkeep are not a window (the
  first validation run cast removal into its own upkeep trigger).
- `savesAttacker`: own declare-blockers step and the target is a blocker
  whose power covers one of the agent's attackers. Killing a blocker for
  any other reason on the agent's own turn is held (the first run sent 5 of
  12 answers at the caster's own declare-blockers step).
- `ownLine`: the card is in the deck's own lines or tutors.
- `pastCutoff`: the table round is past `personality.holdInstantUntilRound`
  (default 10; 0 = no cutoff).
- `danger` / `lethalOnBoard`: own life at or below `dangerLife`, or some
  opponent's creature power covers it.
- `handSize`: the hand is over its maximum in main 2 or the end step, so the
  card would be discarded anyway. Earlier phases hold and cast something
  else instead.
- `roll`: `personality.holdInstants` (default 1.0; 0 disables) is P(hold),
  rolled once per card per turn so a dial below 1 does not leak the card out
  on the next priority.

Local validation, the four bundled decks Atraxa / Drana / Nekusar / Kambal
on one Mac (`studies/precon_predict/divergence.py` on the raw logs): with
the shipped gate, 4 games, instants cast on an opponent's turn 42% (8/19)
against 20% (2/10) for 0.14.0 on the same pod, all spells off-turn 4.4%
against 3.9%; the run before it, which differed only in the hand-size guard
still firing in the draw step, measured 59% (19/32) and 8.3%. Every own-turn
answer cast in the final run had a reason (`lethalOnBoard` 3, `inResponse` 2,
`pastCutoff` 2). Games took 23-33 s each, none crashed, no held answer was
discarded. Directional only at this size; the task's acceptance numbers are
owed from a VM run.

### Attack targeting and finisher discipline, 0.14.0 (behavior change)

Two plays a playtester called out on `sim_20260902_145933`, both the plan
agent's own doing, both mechanism-only with the dials in the plan JSON:

**Open target over a fed blocker.** `kingmakerReaim` moved a 2/2 Mutavault
and a 1/1 onto the seat it scored as leader, whose only creature was an
untapped 4/4; the Mutavault died for two damage while the seat two threat
points back sat behind Iron Maiden and Spiteful Visions with no creature at
all. `preferOpenTarget` now runs after the kingmaker pass: an attacker aimed
at a player who has an untapped creature that Forge says can block it is
re-aimed at the highest-threat other opponent with no such blocker, provided
that opponent's threat is at least `personality.openThreatShare` (default
0.6) of the current target's. A lethal swing is never re-aimed. Logged as
`open_reaim moved=N onto=<player>`.

**Finisher discipline.** The plan has said since task 20 which spells are
finishers and how many creatures they want (`search.context`:
`{"hint":"finisher","minCreatures":3}`), but the shim read that only when
steering a library search; stock Forge cast Triumph of the Hordes as a pump
spell onto one or two creatures (four casts in the run, best case five
poison). `finisherDiscipline` now gates the stock pick: a one-shot
finisher-hinted spell with fewer own creatures than the minimum is held, the
best other castable spell in hand is cast instead, else the window is
passed. A board whose power already covers some opponent's life always
casts. Logged as `finisher_hold <card> creatures=N need=M instead=<card>|pass`.

Sim Lab's `deck_plan.py` adds the matching plan data in the same change:
punisher permanents (damage to each or that player on a trigger, damage on
opponents' draws, life loss to each opponent) join the deck's threat list,
so a Nekusar table reads Iron Maiden and Spiteful Visions as the threats
they are.

### Correctness fixes, 0.4.0

Each game runs in its own `Match`. Forge's `Match.startGame` feeds the
previous game's outcome into `GameAction.startGame`, which picks the first
turn from the earliest-seated NON-winner — so in a 4-player pod, whenever
seat 1 did not win the last game, seat 1 goes first. Measured over 168 games
of pre-fix logs: seat 1 took the first turn in 79% of games and seats 3 and 4
essentially never did. `match.clearGamesPlayed()` is NOT a fix for this and
looks like one: it clears the outcomes map but leaves the `lastOutcome` field
that `startGame` actually reads.

A crashed game now reports `error` and `errorClass` instead of being published
as an ordinary draw, and any winner left behind by a crash is discarded rather
than emitted. Per-seat RNG seeds mix in the game index (`seedBase + playerId +
104729 * gameIndex`, both parts recorded in `meta`), so a seat's dice are no
longer identical in every game of a run. `AgentLog` is per-game rather than a
process-global, so a game thread outliving its own game cannot log into the
next one. Reading Forge's `GameLog` is guarded, because its backing list is
unsynchronized and a torn read used to kill the whole run.

Two Stage 4/5 defects fixed after measurement (Sim Lab audit A18, A19):
the line-of-sight gate is now told when a library search this seat
controls is resolving, because a tutor moves to the stack before its own
search resolves and the gate used to read itself closed at exactly the
moment it mattered — 164 searches observed, 0 steers. And the optional
trigger-miss roll now exempts cards the plan names as line pieces: an
iterating "you may" trigger re-asks every iteration, so a 3% per-check
miss halted infinite loops after a median ~23 iterations, every game.

### Plan-target steering, 0.5.0 (behavior change)

The target ranking now ACTS on the agent's own search choices, where 0.4.1
and 0.4.2 only logged it. Combo line-of-sight keeps absolute priority; the
plan pick applies only when it is strictly better than stock's answer on the
same scale, and only in `mode=targets` (keep weights stay measurement-only,
because they were measured picking worse than stock). Ties, no opinion, and
searches stock declined all defer to stock — including on the combo path,
whose older "steer over nothing" behavior is gone, because for a
`ChangeNum > 1` search Forge loops the single-card method and reads a decline
as "stop taking cards", so overriding it appends a card. The multi-card
override swaps its weakest pick rather than growing, though note Forge never
reaches it for an AI seat (`allowMultiSelect` is false there), so an AI
multi-fetch is the loop above. Nothing outside search choices is touched.

One gate is worth stating on its own, because it also fixes a hole that
predates this change: **every option must come from the searching seat's own
library.** Being the decider is not the same as owning the library — Forge
tracks them separately, so Bribery-style effects put an opponent's creature
on my battlefield and an Intuition aimed at me makes me choose out of the
caster's library into the caster's hand. Ranking those by my deck plan is
meaningless, and it would fire easily, since a plan values none of the
opponent's cards. Combo steering had the same hole since 0.3.0 and is now
behind the same gate.

`search_seen` gains `sid`, `dest`, `comboPick` and `src`; `tutor_steer`
becomes `sid=.. mode=combo|plan value=.. stockValue=.. steer=.. over=..`.
Pair the two events on `sid` — a turn can resolve several searches, so
(game, turn, player) is not unique.

`comboPick` says whether the sighted line's missing piece was actually among
the options, which is usually is not: a Finale of Devastation shows creatures
while the piece is an artifact. Without it an analyzer cannot tell "combo
kept priority" from "combo had nothing to take", and will report false
failures — it did, on this change's first probe.

### Target-aware measurement, 0.4.2

The plan JSON may carry a `search` section (Sim Lab task 20 Stage 1):
`targets` (per-card fetch values on their own scale) and `context` gates
(`ramp` decays to the floor from table round `beforeRound` on; `finisher`
stays at the floor until the searcher controls `minCreatures` creatures).
`search_seen`'s planPick ranking now uses those values when present and
falls back to keep weights otherwise, reported as `mode=targets|weights`.
Values below 2 count as no opinion (`agree=na`). Still measurement only:
steering behavior is unchanged since 0.4.0. Old plans parse as before.

### Log schema addition, 0.4.1

`search_seen` now also records what stock AI picked and what a ranking of
the same legal options by plan weight would have picked: `ranked=` (distinct
option names carrying a plan weight), `agree=true|false|na` (`na` = no option
is ranked, so a weight ranking could not have differed), `pickedW=`/`planW=`
(weights of the stock pick and the top-ranked option), and — last, because
card names contain spaces — `picked=` and `planPick=`. Multi-card searches
join stock picks with `|`. Measurement only, for Sim Lab task 20 Stage 0:
the pick itself is unchanged; steering behavior is byte-identical to 0.4.0.

See Sim Lab's `tasks/07-humanlike-agent.md` for design and calibration.
