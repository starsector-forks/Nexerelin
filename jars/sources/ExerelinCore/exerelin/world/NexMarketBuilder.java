package exerelin.world;

import java.util.List;
import java.util.ArrayList;
import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.util.Pair;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import exerelin.ExerelinConstants;
import exerelin.campaign.SectorManager;
import exerelin.plugins.ExerelinModPlugin;
import exerelin.utilities.ExerelinConfig;
import exerelin.utilities.ExerelinFactionConfig;
import exerelin.utilities.ExerelinFactionConfig.BonusSeed;
import exerelin.utilities.ExerelinFactionConfig.IndustrySeed;
import exerelin.utilities.ExerelinUtilsAstro;
import exerelin.utilities.ExerelinUtilsFaction;
import exerelin.utilities.ExerelinUtilsMarket;
import exerelin.world.ExerelinProcGen.ProcGenEntity;
import exerelin.world.ExerelinProcGen.EntityType;
import exerelin.world.industry.IndustryClassGen;
import exerelin.world.industry.bonus.BonusGen;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * How it works.
 * Stage 1: init markets (add to economy, set size, add population industry), etc.
 *	Add defenses & starport based on size
 * Stage 2: for each market, for all possible industries, get priority, add to market in order of priority till max industries reached
 *	Priority is based on local resources and hazard rating
 * Stage 3: add key manufacturing from config to randomly picked planet
 * Stage 4: distribute bonus items to markets
 * 
 * Notes:
 *	Free stations in asteroid belt should have ore mining,
 *	free stations in ring or orbiting gas giant should have gas extraction
*/

// TODO: overhaul pending
@SuppressWarnings("unchecked")
public class NexMarketBuilder
{
	public static Logger log = Global.getLogger(NexMarketBuilder.class);
	//private List possibleMarketConditions;
	
	public static final String INDUSTRY_CONFIG_FILE = "data/config/exerelin/industryClassDefs.csv";
	public static final String BONUS_CONFIG_FILE = "data/config/exerelin/industry_bonuses.csv";
	//public static final Map<MarketArchetype, Float> STATION_ARCHETYPE_QUOTAS = new HashMap<>(MarketArchetype.values().length);
	// this proportion of TT markets with no military bases will have Cabal submarkets (Underworld)
	public static final float CABAL_MARKET_MULT = 0.4f;	
	// this is the chance a market with a military base will still be a candidate for Cabal markets
	public static final float CABAL_MILITARY_MARKET_CHANCE = 0.5f;
	public static final float LUDDIC_MAJORITY_CHANCE = 0.05f;	// how many markets have Luddic majority even if they aren't Luddic at start
	public static final float LUDDIC_MINORITY_CHANCE = 0.15f;	// how many markets that start under Church control are non-Luddic
	public static final float MILITARY_BASE_CHANCE = 0.5f;	// if meets size requirements
	public static final float MILITARY_BASE_CHANCE_PIRATE = 0.5f;
	
	//protected static final float SUPPLIES_SUPPLY_DEMAND_RATIO_MIN = 1.3f;
	//protected static final float SUPPLIES_SUPPLY_DEMAND_RATIO_MAX = 0.5f;	// lower than min so it can swap autofacs for shipbreakers if needed
	
	protected static final int[] PLANET_SIZE_ROTATION = new int[] {4, 5, 6, 7, 6, 5};
	protected static final int[] MOON_SIZE_ROTATION = new int[] {3, 4, 5, 6, 5, 4};
	protected static final int[] STATION_SIZE_ROTATION = new int[] {3, 4, 5, 4, 3};
	
	protected static final List<IndustryClassGen> industryClasses = new ArrayList<>();
	protected static final List<IndustryClassGen> industryClassesOrdered = new ArrayList<>();	// sorted by priority
	protected static final Map<String, IndustryClassGen> industryClassesById = new HashMap<>();
	protected static final Map<String, IndustryClassGen> industryClassesByIndustryId = new HashMap<>();
	protected static final List<IndustryClassGen> specialIndustryClasses = new ArrayList<>();
	protected static final List<BonusGen> bonuses = new ArrayList<>();
	protected static final Map<String, BonusGen> bonusesById = new HashMap<>();
	
	// not a literally accurate count since it disregards HQs
	// only used to handle the market size rotation
	protected int numStations = 0;
	protected int numPlanets = 0;
	protected int numMoons = 0;
	
	protected List<ProcGenEntity> markets = new ArrayList<>();
	protected Map<String, List<ProcGenEntity>> marketsByFactionId = new HashMap<>();
	
	protected final ExerelinProcGen procGen;
	protected final Random random;
	
	static {
		loadIndustries();
		loadBonuses();
	}
	
	protected static void loadIndustries()
	{
		try {			
			JSONArray configJson = Global.getSettings().getMergedSpreadsheetDataForMod("id", INDUSTRY_CONFIG_FILE, ExerelinConstants.MOD_ID);
			for(int i=0; i<configJson.length(); i++)
			{
				JSONObject row = configJson.getJSONObject(i);
				String id = row.getString("id");
				String name = row.getString("name");
				String classDef = row.getString("class");
				float priority = (float)row.optDouble("priority", 0);
				boolean special = row.optBoolean("special", false);
				String requiredMod = row.optString("requiredMod", "");
				
				if (!requiredMod.isEmpty() && !Global.getSettings().getModManager().isModEnabled(requiredMod))
					continue;
				
				IndustryClassGen gen = IndustryClassGen.loadIndustryClassGen(id, name, priority, classDef, special);
				
				industryClasses.add(gen);
				industryClassesById.put(id, gen);
				if (special) {
					log.info("Added special industry class: " + gen.getId());
					specialIndustryClasses.add(gen);
				}
				Set<String> industryIds = gen.getIndustryIds();
				for (String industryId : industryIds)
				{
					industryClassesByIndustryId.put(industryId, gen);
					//log.info("Adding industry ID key " + industryId + " for industry class def " + def.name);
				}
			}
			industryClassesOrdered.addAll(industryClasses);
			Collections.sort(industryClassesOrdered);
			
		} catch (IOException | JSONException ex) {	// fail-deadly to make sure errors don't go unnoticed
			log.error(ex);
			throw new IllegalStateException("Error loading industries for procgen: " + ex);
		}	
	}
	
	protected static void loadBonuses()
	{
		try {			
			JSONArray configJson = Global.getSettings().getMergedSpreadsheetDataForMod("id", BONUS_CONFIG_FILE, ExerelinConstants.MOD_ID);
			for(int i=0; i<configJson.length(); i++)
			{
				JSONObject row = configJson.getJSONObject(i);
				String id = row.getString("id");
				String name = row.getString("name");
				String classDef = row.getString("class");
				String requiredMod = row.optString("requiredMod", "");
				
				if (!requiredMod.isEmpty() && !Global.getSettings().getModManager().isModEnabled(requiredMod))
					continue;
				
				BonusGen bonus = BonusGen.loadBonusGen(id, name, classDef);
				bonuses.add(bonus);
				bonusesById.put(id, bonus);
			}
			
		} catch (IOException | JSONException ex) {	// fail-deadly to make sure errors don't go unnoticed
			log.error(ex);
			throw new IllegalStateException("Error loading bonus items for procgen: " + ex);
		}	
	}
	
	public NexMarketBuilder(ExerelinProcGen procGen)
	{
		this.procGen = procGen;
		random = procGen.getRandom();
	}
		
	// =========================================================================
	// other stuff
	
	protected int getSizeFromRotation(int[] array, int num)
	{
		return array[num % array.length];
	}
	
	protected void addCabalSubmarkets()
	{
		// add Cabal submarkets
		if (ExerelinModPlugin.HAVE_UNDERWORLD)
		{
			List<MarketAPI> cabalCandidates = new ArrayList<>();
			List<MarketAPI> cabalCandidatesBackup = new ArrayList<>();
			for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy())
			{
				if (!market.getFactionId().equals(Factions.TRITACHYON)) continue;
				if ((market.hasIndustry(Industries.MILITARYBASE) || market.hasIndustry(Industries.HIGHCOMMAND)) && random.nextFloat() > CABAL_MILITARY_MARKET_CHANCE) 
				{
					cabalCandidatesBackup.add(market);
					continue;
				}
				
				//log.info("Cabal candidate added: " + market.getName() + " (size " + market.getSize() + ")");
				cabalCandidates.add(market);
			}
			if (cabalCandidates.isEmpty())
				cabalCandidates = cabalCandidatesBackup;
			
			Comparator<MarketAPI> marketSizeComparator = new Comparator<MarketAPI>() {

				public int compare(MarketAPI m1, MarketAPI m2) {
				   int size1 = m1.getSize();
					int size2 = m2.getSize();

					if (size1 > size2) return -1;
					else if (size2 > size1) return 1;
					else return 0;
				}};
			
			Collections.sort(cabalCandidates, marketSizeComparator);
			
			try {
				for (int i=0; i<cabalCandidates.size()*CABAL_MARKET_MULT; i++)
				{
					MarketAPI market = cabalCandidates.get(i);
					market.addSubmarket("uw_cabalmarket");
					market.addCondition("cabal_influence");
					log.info("Added Cabal submarket to " + market.getName() + " (size " + market.getSize() + ")");
				}
			} catch (RuntimeException rex) {
				// old SS+ version, do nothing
			}
		}
	}
		
	protected int getWantedMarketSize(ProcGenEntity data, String factionId)
	{
		if (data.forceMarketSize != -1) return data.forceMarketSize;
		
		boolean isStation = data.type == EntityType.STATION; 
		boolean isMoon = data.type == EntityType.MOON;
		int size = 1;
		if (data.isHQ) {
			if (factionId.equals(Factions.PLAYER)) size = 5;
			else size = 7;
		}
		else {
			if (isStation) size = getSizeFromRotation(STATION_SIZE_ROTATION, numStations);
			else if (isMoon) size = getSizeFromRotation(MOON_SIZE_ROTATION, numMoons);
			else size = getSizeFromRotation(PLANET_SIZE_ROTATION, numPlanets);
		}
		
		if (ExerelinUtilsFaction.isPirateFaction(factionId)) {
			size--;
			if (size < 3) size = 3;
		}
		
		return size;
	}
	
	protected int getMaxProductiveIndustries(ProcGenEntity ent)
	{
		int max = 4;
		int size = ent.market.getSize();
		if (size <= 4) max = 1;
		if (size <= 6) max = 2;
		if (size <= 8) max = 3;
		if (ent.isHQ) max += 1;
		
		return max;
	}
	
	protected float getSpecialIndustryChance(ProcGenEntity ent)
	{
		return ent.market.getSize() * 0.08f;
	}
	
	protected void addSpaceportOrMegaport(MarketAPI market, EntityType type, int marketSize)
	{
		int size = marketSize;
		if (type == EntityType.STATION) size +=1;
		if (random.nextBoolean()) size += 1;
		
		if (size > 6) market.addIndustry(Industries.MEGAPORT);
		else market.addIndustry(Industries.SPACEPORT);
	}
	
	/**
	 * Adds patrol/military bases, ground defenses and defense stations as appropriate to the market.
	 * @param entity
	 * @param marketSize
	 */
	protected void addMilitaryStructures(ProcGenEntity entity, int marketSize)
	{
		MarketAPI market = entity.market;
		
		boolean isPirate = ExerelinUtilsFaction.isPirateFaction(market.getFactionId());
		boolean isMoon = entity.type == EntityType.MOON;
		boolean isStation = entity.type == EntityType.STATION;
		
		int sizeForBase = 6;
		if (isMoon) sizeForBase = 5;
		else if (isStation) sizeForBase = 5;
		if (isPirate) sizeForBase -= 1;
		
		// add military base if needed
		boolean haveBase = market.hasIndustry(Industries.MILITARYBASE) || market.hasIndustry(Industries.HIGHCOMMAND);
		if (!haveBase && marketSize >= sizeForBase)
		{
			float roll = (random.nextFloat() + random.nextFloat())*0.5f;
			float req = MILITARY_BASE_CHANCE;
			if (isPirate) req = MILITARY_BASE_CHANCE_PIRATE;
			if (roll > req)
			{
				market.addIndustry(Industries.MILITARYBASE);
				haveBase = true;
			}
				
		}
		
		int sizeForPatrol = 5;
		if (isMoon || isStation) sizeForPatrol = 4;
		
		// add patrol HQ if needed
		if (!haveBase && marketSize >= sizeForPatrol)
		{
			float roll = (random.nextFloat() + random.nextFloat())*0.5f;
			float req = MILITARY_BASE_CHANCE;
			if (isPirate) req = MILITARY_BASE_CHANCE_PIRATE;
			if (roll > req)
				market.addIndustry(Industries.PATROLHQ);
		}
		
		// add ground defenses
		int sizeForHeavyGun = 7, sizeForGun = 4;
		if (isMoon || isStation)
		{
			sizeForHeavyGun -=1;
			sizeForGun -=1;
		}
		if (haveBase)
		{
			sizeForHeavyGun -=1;
			sizeForGun -=1;
		}
		if (marketSize > sizeForHeavyGun)
			market.addIndustry(Industries.HEAVYBATTERIES);
		else if (marketSize > sizeForGun)
			market.addIndustry(Industries.GROUNDDEFENSES);
		
		// add stations
		if (!entity.isHQ)	// already added for HQs
		{
			int size1 = 4, size2 = 6, size3 = 8;
			if (isStation)
			{
				size1 -= 2; 
				size2 -= 2; 
				size3 -= 2;
			}
			else if (isMoon)
			{
				size1 -= 1; 
				size2 -= 1; 
				size3 -= 1;
			}
			if (haveBase)
			{
				size1 -= 1; 
				size2 -= 1; 
				size3 -= 1;
			}
			
			int sizeIndex = -1;
			if (marketSize > size3)
				sizeIndex = 2;
			else if (marketSize > size2)
				sizeIndex = 1;
			else if (marketSize > size1)
				sizeIndex = 0;
			
			if (sizeIndex > 0)
			{
				String station = ExerelinConfig.getExerelinFactionConfig(market.getFactionId())
						.getRandomDefenceStation(random, sizeIndex);
				if (station != null) {
					//log.info("Adding station: " + station);
					market.addIndustry(station);
				}
			}
		}
	}
	
	// =========================================================================
	// main market adding methods
	
	protected MarketAPI initMarket(ProcGenEntity data, String factionId)
	{
		return initMarket(data, factionId, getWantedMarketSize(data, factionId));
	}
	
	protected MarketAPI initMarket(ProcGenEntity data, String factionId, int marketSize)
	{
		log.info("Creating market for " + data.name + " (" + data.type + "), faction " + factionId);
		
		SectorEntityToken entity = data.entity;
		// don't make the markets too big; they'll screw up the economy big time
		
		String planetType = data.planetType;
		boolean isStation = data.type == EntityType.STATION; 
		boolean isMoon = data.type == EntityType.MOON;
		
		MarketAPI market = entity.getMarket();
		if (market == null) {
			market = Global.getFactory().createMarket(entity.getId(), entity.getName(), marketSize);
			entity.setMarket(market);
			market.setPrimaryEntity(entity);
		}
		else 
		{
			market.setSize(marketSize);
		}
		data.market = market;
		market.setFactionId(factionId);
		market.setPlanetConditionMarketOnly(false);
		
		market.addCondition("population_" + marketSize);
		if (market.hasCondition(Conditions.DECIVILIZED))
		{
			market.removeCondition(Conditions.DECIVILIZED);
			market.addCondition(Conditions.DECIVILIZED_SUBPOP);
		}
		
		// add basic industries
		market.addIndustry(Industries.POPULATION);
		addSpaceportOrMegaport(market, data.type, marketSize);
		
		if (data.isHQ)
		{
			if (factionId.equals(Factions.PLAYER)) {
				market.addIndustry(Industries.MILITARYBASE);
				market.addIndustry(ExerelinConfig.getExerelinFactionConfig(factionId).getRandomDefenceStation(random, 1));
			}
			else {
				market.addIndustry(Industries.HIGHCOMMAND);
				market.addIndustry(ExerelinConfig.getExerelinFactionConfig(factionId).getRandomDefenceStation(random, 2));
			}
			
			if (data == procGen.getHomeworld()) 
			{
				market.addIndustry(Industries.WAYSTATION);
			}
			
			if (factionId.equals(Factions.DIKTAT))
				market.addIndustry("lionsguard");
			else if (factionId.equals("dassault_mikoyan"))
				market.addIndustry("6emebureau");
		}
		
		addMilitaryStructures(data, marketSize);
		
		// planet/terrain type stuff
		if (!isStation)
		{
			if (planetType.equals("terran-eccentric") && !isMoon)
			{
				// add mirror/shade
				LocationAPI system = entity.getContainingLocation();
				SectorEntityToken mirror = system.addCustomEntity(entity.getId() + "_mirror", "Stellar Mirror", "stellar_mirror", factionId);
				mirror.setCircularOrbitPointingDown(entity, ExerelinUtilsAstro.getCurrentOrbitAngle(entity.getOrbitFocus(), entity) + 180, 
						entity.getRadius() + 150, data.entity.getOrbit().getOrbitalPeriod());
				mirror.setCustomDescriptionId("stellar_mirror");
				SectorEntityToken shade = system.addCustomEntity(entity.getId() + "_shade", "Stellar Shade", "stellar_shade", factionId);
				shade.setCircularOrbitPointingDown(entity, ExerelinUtilsAstro.getCurrentOrbitAngle(entity.getOrbitFocus(), entity), 
						entity.getRadius() + 150, data.entity.getOrbit().getOrbitalPeriod());		
				shade.setCustomDescriptionId("stellar_shade");
				
				((PlanetAPI)entity).getSpec().setRotation(0);	// planet don't spin
			}
		}
		else {
			SectorEntityToken token = data.primary;
			if (token instanceof PlanetAPI)
			{
				PlanetAPI planet = (PlanetAPI) token;
				if (planet.isGasGiant())
				{
					log.info(market.getName() + " orbits gas giant " + planet.getName() + ", checking for volatiles");
					MarketAPI planetMarket = planet.getMarket();
					if (planetMarket.hasCondition(Conditions.VOLATILES_TRACE))
						market.addCondition(Conditions.VOLATILES_TRACE);
					if (planetMarket.hasCondition(Conditions.VOLATILES_DIFFUSE))
						market.addCondition(Conditions.VOLATILES_ABUNDANT);
					if (planetMarket.hasCondition(Conditions.VOLATILES_ABUNDANT))
						market.addCondition(Conditions.VOLATILES_ABUNDANT);
					if (planetMarket.hasCondition(Conditions.VOLATILES_PLENTIFUL))
						market.addCondition(Conditions.VOLATILES_PLENTIFUL);
				}
			}
			
			if (data.terrain != null)
			{
				if (data.terrain.getType().equals(Terrain.ASTEROID_BELT))
				{
					log.info(market.getName() + " is in asteroid belt, adding ore condition");
					market.addCondition(Conditions.ORE_SPARSE);
				}
					
				if (data.terrain.getType().equals(Terrain.ASTEROID_FIELD))
				{
					log.info(market.getName() + " is in asteroid ring, adding ore condition");
					market.addCondition(Conditions.ORE_MODERATE);
				}
				if (data.terrain.getType().equals(Terrain.RING))
				{
					log.info(market.getName() + " is in ring, adding volatiles condition");
					market.addCondition(Conditions.VOLATILES_TRACE);
				}
			}
		}
				
		// free port status, tariffs
		ExerelinFactionConfig config = ExerelinConfig.getExerelinFactionConfig(factionId);
		if (config.freeMarket)
		{
			market.addCondition(Conditions.FREE_PORT);
		}
		
		market.getTariff().modifyFlat("generator", Global.getSector().getFaction(factionId).getTariffFraction());
		ExerelinUtilsMarket.setTariffs(market);
					
		// submarkets
		SectorManager.updateSubmarkets(market, Factions.NEUTRAL, factionId);
		market.addSubmarket(Submarkets.SUBMARKET_STORAGE);
		
		Global.getSector().getEconomy().addMarket(market, true);
		entity.setFaction(factionId);	// http://fractalsoftworks.com/forum/index.php?topic=8581.0
		
		// only do this when game has finished loading, so it doesn't mess with our income/debts
		//if (factionId.equals(Factions.PLAYER))
		//	market.setPlayerOwned(true);
				
		procGen.pickEntityInteractionImage(data.entity, market, planetType, data.type);
		
		if (!data.isHQ)
		{
			if (isStation) numStations++;
			else if (isMoon) numMoons++;
			else numPlanets++;
		}
		
		market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
		for (MarketConditionAPI cond : market.getConditions())
		{
			cond.setSurveyed(true);
		}
		
		markets.add(data);
		if (!marketsByFactionId.containsKey(factionId))
			marketsByFactionId.put(factionId, new ArrayList<ProcGenEntity>());
		marketsByFactionId.get(factionId).add(data);
		
		return market;
	}
	
	/**
	 * Adds industries specified in the faction config to the most suitable markets of that faction.
	 * @param factionId
	 */
	public void addKeyIndustriesForFaction(String factionId)
	{
		ExerelinFactionConfig conf = ExerelinConfig.getExerelinFactionConfig(factionId);
		List<ProcGenEntity> entities = marketsByFactionId.get(factionId);
		
		// for each seed, add N industries to factions' markets
		for (IndustrySeed seed : conf.industrySeeds)
		{
			float countRaw = seed.mult * entities.size();
			int count = (int)(seed.roundUp ? Math.ceil(countRaw) : Math.floor(countRaw));
			
			if (count == 0) continue;
			
			IndustryClassGen gen = industryClassesByIndustryId.get(seed.industryId);
			
			// order entities by reverse priority, highest priority markets get the industries
			List<Pair<ProcGenEntity, Float>> ordered = new ArrayList<>();	// float is priority value
			for (ProcGenEntity entity : entities)
			{
				if (entity.market.getIndustries().size() >= 16)
					continue;
				
				// already present?
				if (entity.market.hasIndustry(seed.industryId))
				{
					count -= 1;
					continue;
				}
				
				// this industry isn't usable on this market
				if (!gen.canApply(entity))
					continue;
				ordered.add(new Pair<>(entity, gen.getWeight(entity)));
			}
			
			Collections.sort(ordered, new Comparator<Pair<ProcGenEntity, Float>>() {
				public int compare(Pair<ProcGenEntity, Float> p1, Pair<ProcGenEntity, Float> p2) {
					return p1.two.compareTo(p2.two);
				}
			});
			
			for (int i = 0; i < count; i++)
			{
				if (ordered.isEmpty()) break;
				Pair<ProcGenEntity, Float> highest = ordered.remove(ordered.size() - 1);
				log.info("Adding key industry " + gen.getName() + " to market " + highest.one.name
						+ " (priority " + highest.two + ")");
				highest.one.market.addIndustry(seed.industryId);
				highest.one.numProductiveIndustries += 1;
			}
		}
	}
	
	/**
	 * Removes {@code IndustryClassGen}s in reverse order from {@code from},
	 * and adds them to {@code picker} with the appropriate weight, 
	 * until {@code from} is empty or we've reached the next highest priority.
	 * @param ent
	 * @param picker
	 * @param from
	 */
	protected void loadPicker(ProcGenEntity ent, WeightedRandomPicker<IndustryClassGen> picker, List<IndustryClassGen> from)
	{
		Float currPriority = null;
		while (true)
		{
			log.info("Remaining for picker: " + from.size());
			if (from.isEmpty()) break;
			int nextIndex = from.size() - 1;
			float priority = from.get(nextIndex).getPriority();
			if (currPriority == null)
				currPriority = priority;
			else if (priority < currPriority)
				break;
			
			IndustryClassGen next = from.remove(from.size() - 1);
			float weight = next.getWeight(ent);
			if (weight <= 0) continue;
			log.info("\tAdding industry class to picker: " + next.getName() 
					+ ", priority " + priority + ", weight " + weight);
			picker.add(next, weight);
		}
	}
	
	/**
	 * Fills the market with productive industries, up to the permitted number depending on size.
	 * Added industries depend on local market conditions.
	 * @param entity
	 */
	public void addIndustriesToMarket(ProcGenEntity entity)
	{
		int max = getMaxProductiveIndustries(entity);
		if (entity.numProductiveIndustries >= max)
			return;
		
		String factionId = entity.market.getFactionId();
		ExerelinFactionConfig conf = ExerelinConfig.getExerelinFactionConfig(factionId);
		
		List<IndustryClassGen> availableIndustries = new ArrayList<>();
		for (IndustryClassGen gen : industryClassesOrdered)
		{
			if (gen.isSpecial()) continue;
			if (!gen.canApply(entity)) continue;
			
			availableIndustries.add(gen);
		}
		
		WeightedRandomPicker<IndustryClassGen> picker = new WeightedRandomPicker<>();
		// add as many industries as we're allowed to
		int tries = 0;
		while (entity.numProductiveIndustries < max)
		{
			tries++;
			if (tries > 50) break;
			
			if (entity.market.getIndustries().size() >= 16)
				break;
			
			if (availableIndustries.isEmpty())
				break;
			
			if (picker.isEmpty())
				loadPicker(entity, picker, availableIndustries);
			
			IndustryClassGen gen = picker.pickAndRemove();
			if (gen == null)
			{
				log.info("Picker is empty, skipping");
				continue;
			}
				
			
			log.info("Adding industry " + gen.getName() + " to market " + entity.name);
			gen.apply(entity);
		}
	}
	
	public void addSpecialIndustries(ProcGenEntity entity)
	{
		if (entity.market.getIndustries().size() >= 16) return;
		float specialChance = getSpecialIndustryChance(entity);
		if (random.nextFloat() > specialChance) return;
		
		WeightedRandomPicker<IndustryClassGen> specialPicker = new WeightedRandomPicker<>();
		for (IndustryClassGen gen : specialIndustryClasses)
		{
			if (!gen.canApply(entity))
				continue;
			
			float weight = gen.getWeight(entity);
			if (weight <= 0) continue;
			specialPicker.add(gen, weight);
		}
		IndustryClassGen picked = specialPicker.pick();
		if (picked != null)
		{
			log.info("Adding special industry " + picked.getName() + " to market " + entity.name);
			picked.apply(entity);
		}
	}
	
	public void addIndustriesToMarkets()
	{
		for (ProcGenEntity ent : markets)
		{
			addIndustriesToMarket(ent);
			addSpecialIndustries(ent);
		}
			
	}
	
	/**
	 * Add bonus items (AI cores, synchrotrons, etc.) to a faction's industries.
	 * @param factionId
	 */
	public void addFactionBonuses(String factionId)
	{
		ExerelinFactionConfig conf = ExerelinConfig.getExerelinFactionConfig(factionId);
		List<Industry> industries = new ArrayList<>();
		for (ProcGenEntity ent : marketsByFactionId.get(factionId))
		{
			industries.addAll(ent.market.getIndustries());
		}
		
		for (BonusSeed bonusEntry : conf.bonusSeeds)
		{
			int count = bonusEntry.count;
			if (bonusEntry.mult > 0)
				count += Math.round(marketsByFactionId.get(factionId).size() * bonusEntry.mult);
			
			if (count <= 0)
				continue;
			
			BonusGen bonus = bonusesById.get(bonusEntry.id);
			
			// order industries by reverse priority, highest priority markets get the bonuses
			List<Pair<Industry, Float>> ordered = new ArrayList<>();	// float is priority value
			for (Industry ind : industries)
			{
				ProcGenEntity ent = procGen.procGenEntitiesByToken.get(ind.getMarket().getPrimaryEntity());
				// this bonus isn't usable for this industry
				if (!bonus.canApply(ind, ent))
					continue;
				
				ordered.add(new Pair<>(ind, bonus.getPriority(ind, ent)));
			}
			
			Collections.sort(ordered, new Comparator<Pair<Industry, Float>>() {
				public int compare(Pair<Industry, Float> p1, Pair<Industry, Float> p2) {
					return p1.two.compareTo(p2.two);
				}
			});
			
			for (int i = 0; i < count; i++)
			{
				if (ordered.isEmpty()) break;
				Pair<Industry, Float> highest = ordered.remove(ordered.size() - 1);
				Industry ind = highest.one;
				ProcGenEntity ent = procGen.procGenEntitiesByToken.get(ind.getMarket().getPrimaryEntity());
				
				log.info("Adding bonus " + bonus.getName() + " to industry " + ind.getNameForModifier() 
						+ " on " + ind.getMarket().getName());
				bonus.apply(ind, ent);
			}
		}		
	}
}