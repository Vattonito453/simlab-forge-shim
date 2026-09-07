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
