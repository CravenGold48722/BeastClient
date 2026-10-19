/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hack;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hacks.ophack.NBTEditorHack;
import net.wurstclient.hacks.itemhandler.ItemHandlerHack;
import net.wurstclient.hacks.spawnradius.SpawnRadiusHack;
import net.wurstclient.hacks.*;
import net.wurstclient.util.json.JsonException;

public final class HackList implements UpdateListener
{
	public final AimAssistHack aimAssistHack = new AimAssistHack();
	public final AirMinerHack airMinerHack = new AirMinerHack();
	public final AirPlaceHack airPlaceHack = new AirPlaceHack();
	public final AirWalkHack airWalkHack = new AirWalkHack();
	public final AltGuiHack altGuiHack = new AltGuiHack();
	public final AnchorAuraHack anchorAuraHack = new AnchorAuraHack();
	public final AntiAfkHack antiAfkHack = new AntiAfkHack();
	public final AntiBlastHack antiBlastHack = new AntiBlastHack();
	public final AntiBlindHack antiBlindHack = new AntiBlindHack();
	public final AntiBreakHack antiBreakHack = new AntiBreakHack();
	public final AntiCactusHack antiCactusHack = new AntiCactusHack();
	public final AntiEntityPushHack antiEntityPushHack =
		new AntiEntityPushHack();
	public final AntiCheatDetectHack antiCheatDetectHack =
		new AntiCheatDetectHack();
	public final AntiDropHack antiDropHack = new AntiDropHack();
	public final AntiFovHack antiFovHack = new AntiFovHack();
	public final AntiHungerHack antiHungerHack = new AntiHungerHack();
	public final AntiKnockbackHack antiKnockbackHack = new AntiKnockbackHack();
	public final AntiProjectileHack antiProjectileHack =
		new AntiProjectileHack();
	public final AntisocialHack antisocialHack = new AntisocialHack();
	public final AntiSpamHack antiSpamHack = new AntiSpamHack();
	public final AntiVoidHack antiVoidHack = new AntiVoidHack();
	public final AntiWaterPushHack antiWaterPushHack = new AntiWaterPushHack();
	public final AntiWobbleHack antiWobbleHack = new AntiWobbleHack();
	public final AreaNukerHack areaNukerHack = new AreaNukerHack();
	public final ArrowDmgHack arrowDmgHack = new ArrowDmgHack();
	public final AutoArmorHack autoArmorHack = new AutoArmorHack();
	public final AutoBuildHack autoBuildHack = new AutoBuildHack();
	public final AutoChatHack autoChatHack = new AutoChatHack();
	public final AutoClickerHack autoClickerHack = new AutoClickerHack();
	public final AutoCompleteHack autoCompleteHack = new AutoCompleteHack();
	public final AutoDisenchantHack autoDisenchantHack =
		new AutoDisenchantHack();
	public final AutoDropHack autoDropHack = new AutoDropHack();
	public final AutoFlyHack autoFlyHack = new AutoFlyHack();
	public final AutoLeaveHack autoLeaveHack = new AutoLeaveHack();
	public final AutoLibrarianHack autoLibrarianHack = new AutoLibrarianHack();
	public final AutoEatHack autoEatHack = new AutoEatHack();
	public final AutoFarmHack autoFarmHack = new AutoFarmHack();
	public final AutoFishHack autoFishHack = new AutoFishHack();
	public final AutoLootHack autoLootHack = new AutoLootHack();
	public final AutoMineHack autoMineHack = new AutoMineHack();
	public final AutoPotionHack autoPotionHack = new AutoPotionHack();
	public final AutoReconnectHack autoReconnectHack = new AutoReconnectHack();
	public final AutoRespawnHack autoRespawnHack = new AutoRespawnHack();
	public final AutoSignHack autoSignHack = new AutoSignHack();
	public final AutoSoupHack autoSoupHack = new AutoSoupHack();
	public final AutoSpawnProoferHack autoSpawnProoferHack =
		new AutoSpawnProoferHack();
	public final AutoSprintHack autoSprintHack = new AutoSprintHack();
	public final AutoStealHack autoStealHack = new AutoStealHack();
	public final AutoSwimHack autoSwimHack = new AutoSwimHack();
	public final AutoSwitchHack autoSwitchHack = new AutoSwitchHack();
	public final AutoSwordHack autoSwordHack = new AutoSwordHack();
	public final AutoToolHack autoToolHack = new AutoToolHack();
	public final AutoTotemHack autoTotemHack = new AutoTotemHack();
	public final AutoTraderHack autoTraderHack = new AutoTraderHack();
	public final AutoWalkHack autoWalkHack = new AutoWalkHack();
	public final BacktrackHack backtrackHack = new BacktrackHack();
	public final BarrierEspHack barrierEspHack = new BarrierEspHack();
	public final BeaconExploitHack beaconExploitHack = new BeaconExploitHack();
	public final BedBreakAuraHack bedBreakAuraHack = new BedBreakAuraHack();
	public final BedEspHack bedEspHack = new BedEspHack();
	public final BaseFinderHack baseFinderHack = new BaseFinderHack();
	public final BedrockEscapeHack bedrockEscapeHack = new BedrockEscapeHack();
	public final BedrockStashHack bedrockStashHack = new BedrockStashHack();
	public final BlinkHack blinkHack = new BlinkHack();
	public final BoatFlyHack boatFlyHack = new BoatFlyHack();
	public final BoatPhaseHack boatPhaseHack = new BoatPhaseHack();
	public final BonemealAuraHack bonemealAuraHack = new BonemealAuraHack();
	public final BookBotHack bookBotHack = new BookBotHack();
	public final BowAimbotHack bowAimbotHack = new BowAimbotHack();
	public final BreadcrumbsHack breadcrumbsHack = new BreadcrumbsHack();
	public final BuildRandomHack buildRandomHack = new BuildRandomHack();
	public final BunnyHopHack bunnyHopHack = new BunnyHopHack();
	public final CameraDistanceHack cameraDistanceHack =
		new CameraDistanceHack();
	public final CameraNoClipHack cameraNoClipHack = new CameraNoClipHack();
	public final CaveFinderHack caveFinderHack = new CaveFinderHack();
	public final ChatTranslatorHack chatTranslatorHack =
		new ChatTranslatorHack();
	public final CheatDetectorHack cheatDetectorHack = new CheatDetectorHack();
	public final ChestEspHack chestEspHack = new ChestEspHack();
	public final ChestSearchHack chestSearchHack = new ChestSearchHack();
	public final ChorusFruitHack chorusFruitHack = new ChorusFruitHack();
	public final ClickAuraHack clickAuraHack = new ClickAuraHack();
	public final ClickGuiHack clickGuiHack = new ClickGuiHack();
	public final ClientChatOverlayHack clientChatOverlayHack =
		new ClientChatOverlayHack();
	public final ClutchFallHack clutchFallHack = new ClutchFallHack();
	public final CoordLoggerHack coordLoggerHack = new CoordLoggerHack();
	public final CrashChestHack crashChestHack = new CrashChestHack();
	public final CreativeFlightHack creativeFlightHack =
		new CreativeFlightHack();
	public final CriticalsHack criticalsHack = new CriticalsHack();
	public final CrystalAuraHack crystalAuraHack = new CrystalAuraHack();
	public final CustomTotemHack customTotemHack = new CustomTotemHack();
	public final DamageDetectHack damageDetectHack = new DamageDetectHack();
	public final DamageEspHack damageEspHack = new DamageEspHack();
	public final DerpHack derpHack = new DerpHack();
	public final DolphinHack dolphinHack = new DolphinHack();
	public final DurabilityHudHack durabilityHudHack = new DurabilityHudHack();
	public final DuraSwapHack duraSwapHack = new DuraSwapHack();
	public final ElytraBounceHack elytraBounceHack = new ElytraBounceHack();
	public final ElytraDiveHack elytraDiveHack = new ElytraDiveHack();
	public final ElytraFlightHack elytraFlightHack = new ElytraFlightHack();
	public final ElytraInfoHack elytraInfoHack = new ElytraInfoHack();
	public final ElytraPitchHack elytraPitchHack = new ElytraPitchHack();
	public final ElytraWalkHack elytraWalkHack = new ElytraWalkHack();
	public final EnchantmentHandlerHack enchantmentHandlerHack =
		new EnchantmentHandlerHack();
	public final EntityControlHack entityControlHack = new EntityControlHack();
	public final EntityCountHack entityCountHack = new EntityCountHack();
	public final ExcavatorHack excavatorHack = new ExcavatorHack();
	public final ExtraElytraHack extraElytraHack = new ExtraElytraHack();
	public final FakeLagHack fakeLagHack = new FakeLagHack();
	public final FancyChatHack fancyChatHack = new FancyChatHack();
	public final FastBreakHack fastBreakHack = new FastBreakHack();
	public final FastFillHack fastFillHack = new FastFillHack();
	public final FastLadderHack fastLadderHack = new FastLadderHack();
	public final FastPlaceHack fastPlaceHack = new FastPlaceHack();
	public final FeedAuraHack feedAuraHack = new FeedAuraHack();
	public final FightBotHack fightBotHack = new FightBotHack();
	public final FishHack fishHack = new FishHack();
	public final FlickerHack flickerHack = new FlickerHack();
	public final FlightHack flightHack = new FlightHack();
	public final FollowHack followHack = new FollowHack();
	public final ForceOpHack forceOpHack = new ForceOpHack();
	public final FreecamHack freecamHack = new FreecamHack();
	public final FullbrightHack fullbrightHack = new FullbrightHack();
	public final FunCreepersHack funCreepersHack = new FunCreepersHack();
	public final GameStatsHack gameStatsHack = new GameStatsHack();
	public final GlideHack glideHack = new GlideHack();
	public final HandNoClipHack handNoClipHack = new HandNoClipHack();
	public final HeadRollHack headRollHack = new HeadRollHack();
	public final HealthTagsHack healthTagsHack = new HealthTagsHack();
	public final HighJumpHack highJumpHack = new HighJumpHack();
	public final InfiniChatHack infiniChatHack = new InfiniChatHack();
	public final InfiniteReachHack infiniteReachHack = new InfiniteReachHack();
	public final InstaBuildHack instaBuildHack = new InstaBuildHack();
	public final InstacartHack instacartHack = new InstacartHack();
	public final InstantBunkerHack instantBunkerHack = new InstantBunkerHack();
	public final InventorySorterHack inventorySorterHack =
		new InventorySorterHack();
	public final InstantCommandHack instantCommandHack =
		new InstantCommandHack();
	public final InvWalkHack invWalkHack = new InvWalkHack();
	public final ItemEspHack itemEspHack = new ItemEspHack();
	public final ItemGeneratorHack itemGeneratorHack = new ItemGeneratorHack();
	public final ItemHandlerHack itemHandlerHack = new ItemHandlerHack();
	public final JesusHack jesusHack = new JesusHack();
	public final JetpackHack jetpackHack = new JetpackHack();
	public final JoinDropHack joinDropHack = new JoinDropHack();
	public final KaboomHack kaboomHack = new KaboomHack();
	public final KickForensicsHack kickForensicsHack = new KickForensicsHack();
	public final KillauraLegitHack killauraLegitHack = new KillauraLegitHack();
	public final KillauraHack killauraHack = new KillauraHack();
	public final KillPotionHack killPotionHack = new KillPotionHack();
	public final LavaWaterEspHack lavaWaterEspHack = new LavaWaterEspHack();
	public final LiquidsHack liquidsHack = new LiquidsHack();
	public final LivestreamDetectorHack livestreamDetectorHack =
		new LivestreamDetectorHack();
	public final LocatorTriangulationHack locatorTriangulationHack =
		new LocatorTriangulationHack();
	public final LogoutSpotsHack logoutSpotsHack = new LogoutSpotsHack();
	public final LootRunnerHack lootRunnerHack = new LootRunnerHack();
	public final LootSearchHack lootSearchHack = new LootSearchHack();
	public final LootSorterHack lootSorterHack = new LootSorterHack();
	public final LsdHack lsdHack = new LsdHack();
	public final MaceAssistHack maceAssistHack = new MaceAssistHack();
	public final MaceDmgHack maceDmgHack = new MaceDmgHack();
	public final MapaHack mapaHack = new MapaHack();
	public final MassTpaHack massTpaHack = new MassTpaHack();
	public final MeasurementEspHack measurementEspHack =
		new MeasurementEspHack();
	public final MentionHack mentionHack = new MentionHack();
	public final MicDetectHack micDetectHack = new MicDetectHack();
	public final MileyCyrusHack mileyCyrusHack = new MileyCyrusHack();
	public final MiningEvidenceHack miningEvidenceHack =
		new MiningEvidenceHack();
	public final MobEspHack mobEspHack = new MobEspHack();
	public final MobHealthHack mobHealthHack = new MobHealthHack();
	public final MobOwnersHack mobOwnersHack = new MobOwnersHack();
	public final MobSearchHack mobSearchHack = new MobSearchHack();
	public final MobSpawnEspHack mobSpawnEspHack = new MobSpawnEspHack();
	public final MobViewHack mobViewHack = new MobViewHack();
	public final MultiAuraHack multiAuraHack = new MultiAuraHack();
	public final MusicAuraHack musicAuraHack = new MusicAuraHack();
	public final NameProtectHack nameProtectHack = new NameProtectHack();
	public final NameTagsHack nameTagsHack = new NameTagsHack();
	public final NavigatorHack navigatorHack = new NavigatorHack();
	public final NBTEditorHack nbtEditorHack = new NBTEditorHack();
	public final NbtFilterHack nbtFilterHack = new NbtFilterHack();
	public final NbtSizeCounterHack nbtSizeCounterHack =
		new NbtSizeCounterHack();
	public final NecoModeHack necoModeHack = new NecoModeHack();
	public final NewChunksHack newChunksHack = new NewChunksHack();
	public final NewerNewChunksHack newerNewChunksHack =
		new NewerNewChunksHack();
	public final NoBackgroundHack noBackgroundHack = new NoBackgroundHack();
	public final NoClipHack noClipHack = new NoClipHack();
	public final NoFallHack noFallHack = new NoFallHack();
	public final NoFireOverlayHack noFireOverlayHack = new NoFireOverlayHack();
	public final NoFogHack noFogHack = new NoFogHack();
	public final NoGoZoneHack noGoZoneHack = new NoGoZoneHack();
	public final NoHurtcamHack noHurtcamHack = new NoHurtcamHack();
	public final NoLevitationHack noLevitationHack = new NoLevitationHack();
	public final NoOverlayHack noOverlayHack = new NoOverlayHack();
	public final NoPlayerChatHack noPlayerChatHack = new NoPlayerChatHack();
	public final NoPumpkinHack noPumpkinHack = new NoPumpkinHack();
	public final NoShieldOverlayHack noShieldOverlayHack =
		new NoShieldOverlayHack();
	public final NoSlowdownHack noSlowdownHack = new NoSlowdownHack();
	public final NoVignetteHack noVignetteHack = new NoVignetteHack();
	public final NoWeatherHack noWeatherHack = new NoWeatherHack();
	public final NoWebHack noWebHack = new NoWebHack();
	public final NukerHack nukerHack = new NukerHack();
	public final NukerLegitHack nukerLegitHack = new NukerLegitHack();
	public final OpenWaterEspHack openWaterEspHack = new OpenWaterEspHack();
	public final OppStatsHack oppStatsHack = new OppStatsHack();
	public final OutreachHack outreachHack = new OutreachHack();
	public final OverlayHack overlayHack = new OverlayHack();
	public final PacketRateHack packetRateHack = new PacketRateHack();
	public final PanicHack panicHack = new PanicHack();
	public final ParkourHack parkourHack = new ParkourHack();
	public final PearlDetectHack pearlDetectHack = new PearlDetectHack();
	public final PearlDropHack pearlDropHack = new PearlDropHack();
	public final PearlEspHack pearlEspHack = new PearlEspHack();
	public final PearlInterceptHack pearlInterceptHack =
		new PearlInterceptHack();
	public final PearlLauncherHack pearlLauncherHack = new PearlLauncherHack();
	public final PlaceNBreakHack placeNBreakHack = new PlaceNBreakHack();
	public final PlayerEspHack playerEspHack = new PlayerEspHack();
	public final PlayerMuteHack playerMuteHack = new PlayerMuteHack();
	public final PlayerSonarHack playerSonarHack = new PlayerSonarHack();
	public final PortalEspHack portalEspHack = new PortalEspHack();
	public final PortalGuiHack portalGuiHack = new PortalGuiHack();
	public final PotEspHack potEspHack = new PotEspHack();
	public final PotionSaverHack potionSaverHack = new PotionSaverHack();
	public final ProjectileEspHack projectileEspHack = new ProjectileEspHack();
	public final ProphuntEspHack prophuntEspHack = new ProphuntEspHack();
	public final ProtectHack protectHack = new ProtectHack();
	public final QuickShulkerHack quickShulkerHack = new QuickShulkerHack();
	public final RadarHack radarHack = new RadarHack();
	public final RainbowUiHack rainbowUiHack = new RainbowUiHack();
	public final ReachHack reachHack = new ReachHack();
	public final RedstoneEspHack redstoneEspHack = new RedstoneEspHack();
	public final RemoteEnderChestHack remoteEnderChestHack =
		new RemoteEnderChestHack();
	public final RemoteViewHack remoteViewHack = new RemoteViewHack();
	public final RenderAdjustHack renderAdjustHack = new RenderAdjustHack();
	public final RestockHack restockHack = new RestockHack();
	public final RoofEspHack roofEspHack = new RoofEspHack();
	public final SafeTpHack safeTpHack = new SafeTpHack();
	public final SafeWalkHack safeWalkHack = new SafeWalkHack();
	public final ScaffoldWalkHack scaffoldWalkHack = new ScaffoldWalkHack();
	public final SearchHack searchHack = new SearchHack();
	public final ShearAuraHack shearAuraHack = new ShearAuraHack();
	public final ShieldSwingHack shieldSwingHack = new ShieldSwingHack();
	public final SignEspHack signEspHack = new SignEspHack();
	public final SignFramePTHack signFramePTHack = new SignFramePTHack();
	public final SilkOnlyHack silkOnlyHack = new SilkOnlyHack();
	public final SimulationSonarHack simulationSonarHack =
		new SimulationSonarHack();
	public final SkinDerpHack skinDerpHack = new SkinDerpHack();
	public final SkyBuildEspHack skyBuildEspHack = new SkyBuildEspHack();
	public final SneakHack sneakHack = new SneakHack();
	public final SnowShoeHack snowShoeHack = new SnowShoeHack();
	public final SoulChaliceHack soulChaliceHack = new SoulChaliceHack();
	public final SoundMuteHack soundMuteHack = new SoundMuteHack();
	public final SourceFillHack sourceFillHack = new SourceFillHack();
	public final SpawnerEspHack spawnerEspHack = new SpawnerEspHack();
	public final SpawnRadiusHack spawnRadiusHack = new SpawnRadiusHack();
	public final SpeedHackHack speedHackHack = new SpeedHackHack();
	public final SpeedNukerHack speedNukerHack = new SpeedNukerHack();
	public final SpiderHack spiderHack = new SpiderHack();
	public final StasisDetectorHack stasisDetectorHack =
		new StasisDetectorHack();
	public final StaffMonitorHack staffMonitorHack = new StaffMonitorHack();
	public final StairMakerHack stairMakerHack = new StairMakerHack();
	public final StepHack stepHack = new StepHack();
	public final SuperInstaMineHack superInstaMineHack =
		new SuperInstaMineHack();
	public final SusNoMoreHack susNoMoreHack = new SusNoMoreHack();
	public final TargetPlaceHack targetPlaceHack = new TargetPlaceHack();
	public final TelemetricsHack telemetricsHack = new TelemetricsHack();
	public final TeleportHack teleportHack = new TeleportHack();
	public final TemplateToolHack templateToolHack = new TemplateToolHack();
	public final TextureRotatorHack textureRotatorHack =
		new TextureRotatorHack();
	public final ThrowHack throwHack = new ThrowHack();
	public final TillauraHack tillauraHack = new TillauraHack();
	public final TimerHack timerHack = new TimerHack();
	public final TiredHack tiredHack = new TiredHack();
	public final TooManyHaxHack tooManyHaxHack = new TooManyHaxHack();
	public final TownyHack townyHack = new TownyHack();
	public final TpAuraHack tpAuraHack = new TpAuraHack();
	public final TrajectoriesHack trajectoriesHack = new TrajectoriesHack();
	public final TreeBotHack treeBotHack = new TreeBotHack();
	public final TrialSpawnerEspHack trialSpawnerEspHack =
		new TrialSpawnerEspHack();
	public final TridentEspHack tridentEspHack = new TridentEspHack();
	public final TriggerBotHack triggerBotHack = new TriggerBotHack();
	public final TrollPotionHack trollPotionHack = new TrollPotionHack();
	public final TrueSightHack trueSightHack = new TrueSightHack();
	public final TunnelHoleStairEspHack tunnelHoleStairEspHack =
		new TunnelHoleStairEspHack();
	public final TunnellerHack tunnellerHack = new TunnellerHack();
	public final UntouchableHack untouchableHack = new UntouchableHack();
	public final UseItemSpamHack useItemSpamHack = new UseItemSpamHack();
	public final VaultRollHack vaultRollHack = new VaultRollHack();
	public final VeinMinerHack veinMinerHack = new VeinMinerHack();
	public final ViewmodelHack viewmodelHack = new ViewmodelHack();
	public final VillagerRollHack villagerRollHack = new VillagerRollHack();
	public final WardenEspHack wardenEspHack = new WardenEspHack();
	public final WaypointsHack waypointsHack = new WaypointsHack();
	public final WebhookAlertHack webhookAlertHack = new WebhookAlertHack();
	public final WorkstationEspHack workstationEspHack =
		new WorkstationEspHack();
	public final WindChargeKeyHack windChargeKeyHack = new WindChargeKeyHack();
	public final XCarryHack xCarryHack = new XCarryHack();
	public final XpGuiHack xpGuiHack = new XpGuiHack();
	public final XRayHack xRayHack = new XRayHack();
	
	private final TreeMap<String, Hack> hax =
		new TreeMap<>(String::compareToIgnoreCase);
	
	private final EnabledHacksFile enabledHacksFile;
	private final ImportantHacksFile importantHacksFile;
	private final Path profilesFolder =
		WurstClient.INSTANCE.getWurstFolder().resolve("enabled hacks");
	
	private final EventManager eventManager =
		WurstClient.INSTANCE.getEventManager();
	
	public HackList(Path enabledHacksFile, Path importantHacksFile)
	{
		this.enabledHacksFile = new EnabledHacksFile(enabledHacksFile);
		this.importantHacksFile = new ImportantHacksFile(importantHacksFile);
		
		try
		{
			for(Field field : HackList.class.getDeclaredFields())
			{
				if(!field.getName().endsWith("Hack"))
					continue;
				
				Hack hack = (Hack)field.get(this);
				hax.put(hack.getName(), hack);
			}
			
		}catch(Exception e)
		{
			String message = "Initializing Wurst hacks";
			CrashReport report = CrashReport.forThrowable(e, message);
			throw new ReportedException(report);
		}
		
		this.importantHacksFile.load(this);
		eventManager.add(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		enabledHacksFile.load(this);
		eventManager.remove(UpdateListener.class, this);
	}
	
	public void saveEnabledHax()
	{
		enabledHacksFile.save(this);
	}
	
	public void saveImportantHax()
	{
		importantHacksFile.save(this);
	}
	
	/**
	 * Turns the "Only above ground" filter of every hack that has one on or
	 * off (AboveGroundFilter / .aboveground, ported from the CevAPI fork).
	 */
	public void setAboveGroundFilterEnabled(boolean enabled)
	{
		AboveGroundFilterManager.toggle(this, enabled);
	}
	
	public void setAboveGroundFilterY(int y)
	{
		AboveGroundFilterManager.setY(this, y);
	}
	
	public Hack getHackByName(String name)
	{
		return hax.get(name);
	}
	
	public Collection<Hack> getAllHax()
	{
		return Collections.unmodifiableCollection(hax.values());
	}
	
	public int countHax()
	{
		return hax.size();
	}
	
	public ArrayList<Path> listProfiles()
	{
		if(!Files.isDirectory(profilesFolder))
			return new ArrayList<>();
		
		try(Stream<Path> files = Files.list(profilesFolder))
		{
			return files.filter(Files::isRegularFile)
				.collect(Collectors.toCollection(ArrayList::new));
			
		}catch(IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	public void loadProfile(String fileName) throws IOException, JsonException
	{
		enabledHacksFile.loadProfile(this, profilesFolder.resolve(fileName));
	}
	
	public void saveProfile(String fileName) throws IOException, JsonException
	{
		enabledHacksFile.saveProfile(this, profilesFolder.resolve(fileName));
	}
}
