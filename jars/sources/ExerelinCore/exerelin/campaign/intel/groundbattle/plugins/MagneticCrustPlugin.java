package exerelin.campaign.intel.groundbattle.plugins;

import com.fs.starfarer.api.combat.MutableStat;
import exerelin.campaign.intel.groundbattle.GroundUnit;
import exerelin.campaign.intel.groundbattle.IndustryForBattle;

public class MagneticCrustPlugin extends MarketConditionPlugin {
	
	public static float DMG_MULT = 0.85f;
	
	@Override
	public MutableStat modifyDamageDealt(GroundUnit unit, MutableStat dmg) {
		if (unit.isAttacker())
			dmg.modifyMult(conditionId, DMG_MULT, intel.getMarket().getCondition(conditionId).getName());
		return dmg;
	}
	
	@Override
	public void reportUnitMoved(GroundUnit unit, IndustryForBattle lastLoc) {
		if (unit.isAttacker()) {
			if (lastLoc == null) // deployment
				unit.reorganize(1);
			else unit.reorganize(2);	// after movement; 2 turns since 1 turn of reorganization is removed at the end of this turn
		}
			
	}
}
