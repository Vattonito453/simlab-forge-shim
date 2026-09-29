/*
 * simlab-forge-shim — GPL-3.0 (see LICENSE).
 */
package simlab.shim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.ComputerUtilCost;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

/**
 * E1 prototype (Sim Lab repair plan WS9 Phase A): a generic interpreter over
 * the plan's "steps" data. Mechanism only: which cards, abilities, targets,
 * loops and stops a line uses are the caller's JSON (README, "Combo executor
 * prototype"). Every action is a SpellAbility of a data-named card, returned
 * only after canPlay and ComputerUtilCost.canPayCost, its target set through
 * sa.canTarget; Forge pays, resolves and adjudicates everything. Any failure
 * hands the turn back to the pilot with an exec_abort record.
 */
final class StepRunner {
    /** choose(): pass priority so my own stack item resolves. */
    static final List<SpellAbility> PASS = Collections.emptyList();

    private final PlanPlayerController pc;
    private final AgentLog log;
    private final List<Object> lines;
    private final Set<String> tried = new HashSet<>();
    private Map<String, Object> line;          // the armed line, or null
    private int turn = -1, step, act, iter, fails, still, snap, confirms;
    private int[] undo = new int[3];           // position before the last action
    private long armedAt;
    private boolean onStack, hold;             // the last action should be on the stack; holding
    private int poolBefore = -1;               // the last action was a mana ability
    private double lastObs = Double.NaN;

    StepRunner(PlanPlayerController pc, Map<String, Object> steps, AgentLog log) {
        this.pc = pc;
        this.log = log;
        this.lines = MiniJson.arr(steps.get("lines"));
    }

    private Player me() { return pc.getPlayer(); }

    private static String s(Map<String, Object> m, String k, String d) { return MiniJson.str(m.get(k), d); }

    private List<Object> steps() { return MiniJson.arr(line.get("steps")); }

    /** The pilot's pick: null = no opinion, PASS = pass, else the action. */
    List<SpellAbility> choose() {
        long t0 = System.nanoTime();
        try {
            Game g = pc.getGame();
            int now = g.getPhaseHandler().getTurn();
            if (line != null && (now != turn || (!hold && !g.getPhaseHandler().getPhase().isMain()))) {
                end("exec_stop", "turn-or-phase-ended", t0);
            }
            if (line == null && !arm(g, now, t0)) return null;
            if (hold) return PASS;
            if (!g.getStack().isEmpty()) {
                if (onStack) fails = 0;
                onStack = false;
                SpellAbility top = g.getStack().peekAbility();
                return top != null && me().equals(top.getActivatingPlayer()) ? PASS : null;
            }
            boolean failed = onStack || (poolBefore >= 0 && me().getManaPool().totalMana() <= poolBefore);
            if (poolBefore >= 0 && !failed) fails = 0;
            onStack = false;
            poolBefore = -1;
            if (failed) {
                step = undo[0]; act = undo[1]; iter = undo[2];
                if (++fails >= 2) return end("exec_abort", "not-played", t0);
            }
            return next(t0);
        } catch (Exception e) {
            return end("exec_abort", "exception " + e, t0);
        }
    }

    private boolean arm(Game g, int now, long t0) {
        if (!g.getPhaseHandler().isPlayerTurn(me()) || !g.getPhaseHandler().getPhase().isMain()
                || !g.getStack().isEmpty()) return false;
        for (Object o : lines) {
            Map<String, Object> l = MiniJson.obj(o);
            if (!tried.add(now + ":" + s(l, "id", "?"))) continue;
            boolean ok = true;
            for (Map.Entry<String, Object> e : MiniJson.obj(l.get("pieces")).entrySet()) {
                ok &= has(ZoneType.smartValueOf(MiniJson.str(e.getValue(), "Battlefield")), e.getKey());
            }
            if (!ok) continue;
            line = l;
            turn = now;
            step = act = iter = fails = 0;
            restart();
            armedAt = t0;
            log("exec_arm", "", t0);
            return true;
        }
        return false;
    }

    private void restart() {
        still = confirms = 0;
        snap = -1;
        lastObs = Double.NaN;
    }

    private boolean has(ZoneType z, String name) {
        for (Card c : me().getCardsIn(z)) if (c.getName().equals(name)) return true;
        return false;
    }

    private List<SpellAbility> next(long t0) {
        List<Object> steps = steps();
        if ((System.nanoTime() - armedAt) / 1e6 > MiniJson.num(line.get("budget_ms"), 60000)) {
            return end("exec_stop", "budget", t0);
        }
        while (true) {
            if (step >= steps.size()) return end("exec_stop", "done", t0);
            Map<String, Object> st = MiniJson.obj(steps.get(step));
            boolean loop = st.containsKey("loop") || st.containsKey("until") || st.containsKey("max");
            List<Object> body = st.containsKey("loop") ? MiniJson.arr(st.get("loop"))
                                                     : Collections.singletonList(st);
            String why = act == 0 && loop ? until(st, iter) : null;
            Map<String, Object> a = MiniJson.obj(body.get(act));
            if (why == null && a.get("hold") == Boolean.TRUE) {   // pass every priority left this turn
                hold = true;
                log("exec_stop", "why=hold", t0);
                return PASS;
            }
            String[] miss = new String[1];
            SpellAbility sa = why != null || "pass".equals(s(a, "op", "")) ? null : resolve(a, miss);
            if (why == null && sa == null && loop && act == 0 && iter > 0 && miss[0] != null) {
                why = "exhausted " + miss[0];
            }
            if (why != null) {
                log("exec_stop", "why=" + why, t0);
                step++;
                iter = 0;
                restart();
                continue;
            }
            if (sa == null) return miss[0] == null ? end("exec_stop", "handoff", t0)
                                                   : end("exec_abort", "precondition " + miss[0], t0);
            log("exec_step", "op=" + s(a, "op", "activate") + " api=" + sa.getApi()
                    + " card=" + sa.getHostCard().getName() + targets(sa), t0);
            undo = new int[] {step, act, iter};
            if (++act >= body.size()) {
                act = 0;
                if (loop) iter++; else step++;
            }
            if (sa.isManaAbility()) poolBefore = me().getManaPool().totalMana();
            else onStack = true;
            List<SpellAbility> out = new ArrayList<>();
            out.add(sa);
            return out;
        }
    }

    /** The data-named SpellAbility, or null with the reason in miss[0]. */
    private SpellAbility resolve(Map<String, Object> a, String[] miss) {
        boolean cast = "cast".equals(s(a, "op", "activate"));
        String name = s(a, "card", "");
        String api = s(a, "api", null);
        String why = "not-found";
        for (Card c : me().getCardsIn(ZoneType.smartValueOf(s(a, "zone", cast ? "Hand" : "Battlefield")))) {
            if (!c.getName().equals(name)) continue;
            List<SpellAbility> all = new ArrayList<>(c.getNonManaAbilities());
            all.addAll(c.getManaAbilities());
            for (SpellAbility sa : all) {
                if (sa.isSpell() != cast || (api != null && sa.getApi() != ApiType.smartValueOf(api))) continue;
                sa.setActivatingPlayer(me());
                if (!sa.canPlay()) {
                    why = "canPlay";
                } else if (!bind(sa, MiniJson.obj(a.get("target")), a.containsKey("target"))) {
                    why = "target";
                } else if (!ComputerUtilCost.canPayCost(sa, me(), false)) {
                    sa.resetTargets();
                    why = "canPayCost";
                } else {
                    return sa;
                }
            }
        }
        miss[0] = why + " " + name;
        return null;
    }

    /** Set the one targeted ability in sa's chain from the bind spec. */
    private boolean bind(SpellAbility sa, Map<String, Object> t, boolean wanted) {
        SpellAbility tg = sa;
        while (tg != null && !tg.usesTargeting()) tg = tg.getSubAbility();
        if (tg == null || !wanted) return tg == null && !wanted;
        tg.resetTargets();
        GameObject o = pick(tg, t);
        if (o != null && tg.getTargets().add(o) && tg.isTargetNumberValid()) return true;
        tg.resetTargets();
        return false;
    }

    private GameObject pick(SpellAbility tg, Map<String, Object> t) {
        String card = s(t, "card", null);
        if (card != null) {
            Object tapped = t.get("tapped");
            for (Card c : me().getCardsIn(ZoneType.Battlefield)) {
                if (c.getName().equals(card) && (tapped == null || tapped.equals(c.isTapped()))
                        && tg.canTarget(c)) return c;
            }
            return null;
        }
        String who = s(t, "player", "");
        if (who.equals("self")) return tg.canTarget(me()) ? me() : null;
        if (!who.equals("opponent")) return null;
        String pol = s(t, "policy", "first");
        Player best = null;
        for (Player o : me().getOpponents()) {
            if (o.hasLost() || !tg.canTarget(o)) continue;
            if (best == null
                    || (pol.equals("lowest_life") && o.getLife() < best.getLife())
                    || (pol.equals("largest_library") && o.getCardsIn(ZoneType.Library).size()
                            > best.getCardsIn(ZoneType.Library).size())) best = o;
        }
        return best;
    }

    /** The stop predicate of this loop that holds after n passes, or null. */
    private String until(Map<String, Object> st, int n) {
        Map<String, Object> u = MiniJson.obj(st.get("until"));
        if (n >= MiniJson.num(st.get("max"), 500)) return "max";
        if (u.containsKey("count") && n >= MiniJson.num(u.get("count"), 0)) return "count";
        if (u.containsKey("mana_at_least")
                && me().getManaPool().totalMana() >= MiniJson.num(u.get("mana_at_least"), 0)) return "mana_at_least";
        if (u.containsKey("opponents_out") && obs("opp_life") <= 0) return "opponents_out";
        if (u.containsKey("power_vs_life")
                && obs("power") >= obs("opp_life") + MiniJson.num(u.get("power_vs_life"), 0)) return "power_vs_life";
        Map<String, Object> np = MiniJson.obj(u.get("no_progress"));
        if (!np.isEmpty() && n > snap) {
            snap = n;
            double v = obs(s(np, "of", "mana"));
            still = v == lastObs ? still + 1 : 0;
            lastObs = v;
            if (still >= MiniJson.num(np.get("after"), 2)) return "no_progress";
        }
        return null;
    }

    /** A board quantity: my pool, untapped power or permanents; the living
     *  opponents' total life or library. */
    private double obs(String of) {
        double v = 0;
        if (of.equals("mana")) return me().getManaPool().totalMana();
        if (of.equals("permanents")) return me().getCardsIn(ZoneType.Battlefield).size();
        if (of.equals("power")) {
            for (Card c : me().getCreaturesInPlay()) if (c.isUntapped()) v += Math.max(0, c.getNetPower());
            return v;
        }
        for (Player o : me().getOpponents()) {
            if (o.hasLost()) continue;
            v += of.equals("opp_library") ? o.getCardsIn(ZoneType.Library).size() : Math.max(0, o.getLife());
        }
        return v;
    }

    /** The armed line's trigger entry for this ability's host, or null. */
    private Map<String, Object> spec(SpellAbility sa) {
        if (line == null || sa.getHostCard() == null) return null;
        for (Object o : MiniJson.arr(line.get("triggers"))) {
            Map<String, Object> t = MiniJson.obj(o);
            if (sa.getHostCard().getName().equals(s(t, "card", null))) return t;
        }
        return null;
    }

    /** orderAndPlaySimultaneousSa: the triggers of the armed line whose data
     *  names a target, with that target set. Everything else, and any
     *  trigger whose target cannot be bound (which aborts the line), is left
     *  to super. */
    List<SpellAbility> bindTriggers(List<SpellAbility> sas) {
        List<SpellAbility> out = new ArrayList<>();
        long t0 = System.nanoTime();
        try {
            for (SpellAbility sa : sas) {
                t0 = System.nanoTime();
                Map<String, Object> t = spec(sa);
                if (t == null || !t.containsKey("target") || !sa.isTrigger() || sa.isCopied()
                        || sa.getApi() == ApiType.Charm) continue;
                SpellAbility w = sa instanceof WrappedAbility ? ((WrappedAbility) sa).getWrappedAbility() : sa;
                if (!bind(w, MiniJson.obj(t.get("target")), true)) {
                    end("exec_abort", "trigger-mismatch " + sa.getHostCard().getName(), t0);
                    break;
                }
                out.add(sa);
                log("exec_step", "op=bind card=" + sa.getHostCard().getName() + targets(w), t0);
            }
        } catch (Exception e) {
            end("exec_abort", "exception " + e, t0);
            out.clear();
        }
        return out;
    }

    /** confirmTrigger: null = no opinion. The armed line's named trigger is
     *  taken, unless its data declines it, or it is a loop's stop trigger
     *  ("stop": true) and that loop's stop predicate holds. */
    Boolean confirm(WrappedAbility w) {
        long t0 = System.nanoTime();
        try {
            Map<String, Object> t = spec(w);
            if (t == null) return null;
            String why = t.get("confirm") == Boolean.FALSE ? "declined" : null;
            if (why == null && t.get("stop") == Boolean.TRUE && step < steps().size()) {
                why = until(MiniJson.obj(steps().get(step)), confirms++);
            }
            log(why == null || why.equals("declined") ? "exec_step" : "exec_stop", "op=confirm answer=" + (why == null)
                    + (why == null ? "" : " why=" + why) + " card=" + w.getHostCard().getName(), t0);
            return why == null;
        } catch (Exception e) {
            end("exec_abort", "exception " + e, t0);
            return null;
        }
    }

    /** chooseBinary, branch exec-proto-binary only (outside owner decision
     *  4's API list, for the owner to rule on): the armed line's data answer
     *  to a named card's binary question, keyed by Forge's BinaryChoiceType
     *  ("choice": {"TapOrUntap": false} = untap); null = no opinion. */
    Boolean binary(SpellAbility sa, String kind) {
        long t0 = System.nanoTime();
        Map<String, Object> t = sa == null ? null : spec(sa);
        Object c = t == null ? null : MiniJson.obj(t.get("choice")).get(kind);
        if (!(c instanceof Boolean)) return null;
        log("exec_step", "op=choose kind=" + kind + " answer=" + c + " card=" + sa.getHostCard().getName(), t0);
        return (Boolean) c;
    }

    private List<SpellAbility> end(String event, String why, long t0) {
        if (line != null) log(event, "why=" + why, t0);
        line = null;
        onStack = hold = false;
        poolBefore = -1;
        return null;
    }

    private static String targets(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting() && !s.getTargets().isEmpty()) return " target=" + s.getTargets().get(0);
        }
        return "";
    }

    private void log(String event, String detail, long t0) {
        log.event(pc.getGame().getPhaseHandler().getTurn(), me().getName(), event,
                "line=" + s(line, "id", "?") + " step=" + step + " act=" + act + " it=" + iter
                + " ms=" + String.format("%.3f", (System.nanoTime() - t0) / 1e6)
                + " at=" + (System.nanoTime() - armedAt) / 1000000 + " " + detail);
    }
}
