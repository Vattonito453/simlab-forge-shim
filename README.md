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
`result` (per game: winner/draw/turns/duration). stderr: human progress.

## Roadmap

Stage 0 (this): stock Forge AI, typed log export.
Stage 1+: a `PlayerController` wrapper taking deck-plan JSON — mulligan
policy, plan-aware casting, combat and interaction humanization. See Sim
Lab's `tasks/07-humanlike-agent.md`.
