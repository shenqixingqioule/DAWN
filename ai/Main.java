package ai;

import arc.util.Log;
import mindustry.Vars;
import mindustry.mod.Mod;
import mindustry.type.UnitType;
import ai.types.GuerrillaGroundAI;

public class Main extends Mod {
    @Override
    public void init() {
        for (UnitType unit : Vars.content.units()) {
            if (unit.name.equals("冲击") || unit.name.equals("进阶")) {
                unit.aiController = GuerrillaGroundAI::new;
                Log.info("已绑定自定义 AI: " + unit.name);
            }
        }
    }
}