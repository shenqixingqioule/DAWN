package ai.types;

import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.IntSeq;
import arc.struct.ObjectMap;
import arc.util.Log;
import mindustry.ai.types.GroundAI;
import mindustry.content.Blocks;
import mindustry.entities.Units;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.world.blocks.defense.turrets.Turret;

public class GuerrillaGroundAI extends GroundAI {

    public static float unitSearchMult = 1.5f;
    public static float retreatSelfDefMult = 0.5f;
    public static float defaultBuildingSafeDist = 60f;
    public static float scoreHpWeight = 60f;
    public static float scoreDistWeight = 40f;
    public static int retargetInterval = 10;
    public static int stateCleanupInterval = 300;

    public static class State {
        public Unit unit;
        public float hpThreshold = 0.333f;
        public float hysteresis = 20f;
        public float combatKeepDist = 40f;
        public float turretSafetyFactor = 1.25f;
        public float ownRangeFactor = 0.9f;
        public float buildingSearchMult = 10f;
        public float repairSearchMult = 12.5f;
        public float repairArriveDist = 10f;
        public boolean attackBuildings = true;
        public boolean configured = false;

        public Building repairTarget;
        public Building lockedBuilding;
        public Unit lockedUnit;
        public int targetTimer = retargetInterval;
        public final Vec2 tmpVec = new Vec2();
    }

    public static ObjectMap<Integer, State> states = new ObjectMap<>();
    public static int tickCounter = 0;

    private State getState(Unit unit) {
        State st = states.get(unit.id);
        if (st == null || st.unit != unit) {
            st = new State();
            st.unit = unit;
            states.put(unit.id, st);
        }
        return st;
    }

    public static void reset() {
        states.clear();
        tickCounter = 0;
    }

    private static void cleanupStates() {
        IntSeq dead = new IntSeq();
        for (ObjectMap.Entry<Integer, State> e : states.entries()) {
            State st = e.value;
            if (st.unit == null || !st.unit.isValid()) dead.add(e.key);
        }
        for (int i = 0; i < dead.size; i++) {
            states.remove(dead.get(i));
        }
    }

    private void autoConfig(Unit unit, State st) {
        if (st.configured) return;
        st.configured = true;

        UnitType type = unit.type;
        float r = type.maxRange > 0 ? type.maxRange : 80f;
        float speed = type.speed;
        float hp = type.health;
        float hit = type.hitSize;

        st.hpThreshold        = Mathf.clamp(0.45f - hp / 6000f, 0.15f, 0.45f);
        st.combatKeepDist     = Math.max(20f, r * 0.3f);
        st.hysteresis         = Math.max(10f, r * 0.15f + speed * 3f);
        st.turretSafetyFactor = Mathf.clamp(1.5f - speed / 25f - hit / 200f, 1.05f, 1.5f);
        st.ownRangeFactor     = Mathf.clamp(0.95f - hit / 300f, 0.75f, 0.95f);
        st.buildingSearchMult = Math.max(8f, r / 6f);
        st.repairSearchMult   = Math.max(10f, r / 5f);
        st.repairArriveDist   = Math.max(8f, hit * 1.5f);
        st.attackBuildings    = r >= 120f;
    }

    private float safeRange(Unit u) {
        float r = u.range();
        return r > 0 ? r : 80f;
    }

    private float unitSearchRange(Unit u) { return safeRange(u) * unitSearchMult; }
    private float buildingSearchRange(Unit u, State st) { return safeRange(u) * st.buildingSearchMult; }
    private float repairSearchRange(Unit u, State st) { return safeRange(u) * st.repairSearchMult; }
    private float retreatSelfDefRange(Unit u) { return safeRange(u) * retreatSelfDefMult; }

    private float desiredDistanceTo(Unit u, Building b, State st) {
        float ownEngage = safeRange(u) * st.ownRangeFactor;
        float hostileSafe = (b.block instanceof Turret)
                ? ((Turret) b.block).range * st.turretSafetyFactor
                : defaultBuildingSafeDist;
        return Math.min(hostileSafe, ownEngage);
    }

    private boolean validTarget(Teamc t) {
        return (t instanceof Unit && ((Unit) t).isValid())
            || (t instanceof Building && ((Building) t).isValid());
    }

    private Unit findBestEnemyUnit(Unit self, float range) {
        Unit[] best = {null};
        float[] bestScore = {-Float.MAX_VALUE};

        Units.nearbyEnemies(self.team, self.x, self.y, range, u -> {
            if (!u.isValid()) return;
            float dist = self.dst(u);
            if (dist > range) return;

            float hpPercent = u.maxHealth > 0 ? u.health / u.maxHealth : 1f;
            float distScore = 1f - dist / range;
            float score = (1f - hpPercent) * scoreHpWeight + distScore * scoreDistWeight;

            if (score > bestScore[0]) {
                bestScore[0] = score;
                best[0] = u;
            }
        });
        return best[0];
    }

    private Building findBestEnemyBuilding(Unit self, float range) {
        Building[] best = {null};
        float[] bestScore = {-Float.MAX_VALUE};

        Units.nearbyBuildings(self.x, self.y, range, b -> {
            if (!b.isValid()) return;
            if (b.team == self.team) return;
            if (b.team == Team.derelict) return;

            float dist = self.dst(b);
            if (dist > range) return;

            float hpPercent = b.maxHealth > 0 ? b.health / b.maxHealth : 1f;
            float distScore = 1f - dist / range;
            float score = (1f - hpPercent) * scoreHpWeight + distScore * scoreDistWeight;

            if (score > bestScore[0]) {
                bestScore[0] = score;
                best[0] = b;
            }
        });
        return best[0];
    }

    private void validateLocks(Unit unit, State st) {
        if (st.lockedUnit != null &&
            (!st.lockedUnit.isValid() || unit.dst(st.lockedUnit) > unitSearchRange(unit))) {
            st.lockedUnit = null;
        }
        if (st.lockedBuilding != null &&
            (!st.lockedBuilding.isValid() || unit.dst(st.lockedBuilding) > buildingSearchRange(unit, st))) {
            st.lockedBuilding = null;
        }
        if (st.repairTarget != null && !st.repairTarget.isValid()) {
            st.repairTarget = null;
        }
    }

    private void updateTargeting(Unit unit) {
        State st = getState(unit);

        validateLocks(unit, st);

        if (st.targetTimer < retargetInterval) {
            st.targetTimer++;
            return;
        }
        st.targetTimer = 0;

        float hpPercent = unit.maxHealth > 0 ? unit.health / unit.maxHealth : 1f;

        if (hpPercent < st.hpThreshold) {
            if (st.repairTarget == null) {
                st.repairTarget = Units.closestBuilding(
                        unit.team, unit.x, unit.y, repairSearchRange(unit, st),
                        b -> b.isValid() && b.block == Blocks.repairTurret && b.team == unit.team
                );
            }
            st.lockedBuilding = null;

            Teamc t = target;
            if (st.lockedUnit != null && st.lockedUnit.isValid()) {
                target = st.lockedUnit;
            } else if (t instanceof Unit && ((Unit) t).isValid()
                    && unit.within((Unit) t, retreatSelfDefRange(unit))) {
                target = t;
            } else {
                target = findBestEnemyUnit(unit, retreatSelfDefRange(unit));
            }
            return;
        }

        st.repairTarget = null;

        if (st.lockedUnit != null && st.lockedUnit.isValid()) {
            target = st.lockedUnit;
            return;
        }
        if (st.lockedBuilding != null && st.lockedBuilding.isValid()) {
            target = st.lockedBuilding;
            return;
        }

        Unit bu = findBestEnemyUnit(unit, unitSearchRange(unit));
        if (bu != null) {
            st.lockedUnit = bu;
            target = bu;
            return;
        }

        if (st.attackBuildings) {
            Building bb = findBestEnemyBuilding(unit, buildingSearchRange(unit, st));
            if (bb != null) {
                st.lockedBuilding = bb;
                target = bb;
                return;
            }
        }

        target = null;
    }

    private Vec2 findRetreatPoint(Unit unit, State st) {
        Building home = Units.closestBuilding(unit.team, unit.x, unit.y, 1000f,
                b -> b.isValid() && b.team == unit.team && b.block != null);
        if (home != null) {
            st.tmpVec.set(home.x, home.y);
            return st.tmpVec;
        }

        Unit[] nearest = {null};
        float[] closest = {Float.MAX_VALUE};
        Units.nearbyEnemies(unit.team, unit.x, unit.y, 600f, e -> {
            if (!e.isValid()) return;
            float d = unit.dst(e);
            if (d < closest[0]) {
                closest[0] = d;
                nearest[0] = e;
            }
        });

        if (nearest[0] != null) {
            float ang = nearest[0].angleTo(unit);
            st.tmpVec.trns(ang, 200f).add(unit.x, unit.y);
        } else {
            st.tmpVec.set(unit.x, unit.y);
        }
        return st.tmpVec;
    }

    private void updateMovement(Unit unit) {
        State st = getState(unit);
        float hpPercent = unit.maxHealth > 0 ? unit.health / unit.maxHealth : 1f;

        Teamc t = target;

        if (hpPercent < st.hpThreshold) {
            if (t != null && t instanceof Unit && ((Unit) t).isValid()) {
                unit.lookAt(t.x(), t.y());
            } else if (st.repairTarget != null && st.repairTarget.isValid()) {
                unit.lookAt(st.repairTarget.x, st.repairTarget.y);
            }

            if (st.repairTarget != null && st.repairTarget.isValid()) {
                st.tmpVec.set(st.repairTarget.x, st.repairTarget.y);
                unit.approach(st.tmpVec);
                if (unit.within(st.repairTarget, st.repairArriveDist)) {
                    unit.vel.setZero();
                }
            } else {
                Vec2 rp = findRetreatPoint(unit, st);
                if (unit.within(rp.x, rp.y, 20f)) {
                    unit.vel.setZero();
                } else {
                    unit.approach(rp);
                }
            }
            return;
        }

        if (t == null || !validTarget(t)) {
            unit.vel.setZero();
            return;
        }

        if (t instanceof Unit) {
            Unit tu = (Unit) t;
            float desired = Math.max(st.combatKeepDist, safeRange(unit) * 0.4f);
            float dist = unit.dst(tu);
            float angle = tu.angleTo(unit);

            if (dist < desired - st.hysteresis) {
                st.tmpVec.trns(angle, desired).add(tu.x, tu.y);
                unit.approach(st.tmpVec);
            } else if (dist > desired + st.hysteresis) {
                st.tmpVec.set(tu.x, tu.y);
                unit.approach(st.tmpVec);
            } else {
                unit.vel.setZero();
            }
            unit.lookAt(tu.x, tu.y);
            return;
        }

        if (t instanceof Building) {
            Building tb = (Building) t;
            float desired = desiredDistanceTo(unit, tb, st);
            float dist = unit.dst(tb);
            float angle = tb.angleTo(unit);

            if (dist < desired - st.hysteresis) {
                st.tmpVec.trns(angle, desired).add(tb.x, tb.y);
                unit.approach(st.tmpVec);
            } else if (dist > desired + st.hysteresis) {
                st.tmpVec.set(tb.x, tb.y);
                unit.approach(st.tmpVec);
            } else {
                unit.vel.setZero();
            }
            unit.lookAt(tb.x, tb.y);
            return;
        }

        unit.vel.setZero();
    }

    private boolean shouldShoot(Unit unit) {
        State st = getState(unit);
        float hpPercent = unit.maxHealth > 0 ? unit.health / unit.maxHealth : 1f;
        Teamc t = target;

        if (t == null || !validTarget(t)) return false;

        if (hpPercent < st.hpThreshold) {
            if (t instanceof Unit) {
                return unit.within((Unit) t, retreatSelfDefRange(unit));
            }
            return false;
        }

        float range = unit.range();
        if (t instanceof Unit) {
            return unit.within((Unit) t, range);
        }
        if (t instanceof Building) {
            return unit.within((Building) t, range);
        }
        return false;
    }

    @Override
    public void updateUnit() {
        try {
            Unit unit = this.unit;
            if (unit == null || !unit.isValid()) return;

            State st = getState(unit);
            autoConfig(unit, st);

            updateTargeting(unit);
            updateMovement(unit);

            boolean shoot = shouldShoot(unit);
            unit.controlWeapons(shoot, shoot);

            tickCounter++;
            if (tickCounter % stateCleanupInterval == 0) {
                cleanupStates();
            }
        } catch (Exception err) {
            Log.err("[GuerrillaGroundAI] updateUnit: " + err);
        }
    }
}