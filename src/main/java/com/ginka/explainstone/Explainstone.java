/*
 * explainstone —— 讲解石 / 颂美铃 / 广播发信器（Minecraft Forge 1.20.1 模组）
 *
 *  作者 / Author : GINKA_3L96WW1
 *
 * 本文件由作者手工编写；贴图与模型由 tools/ 下的脚本程序化生成。
 * 保留此注释即可随意修改、分发。 喵
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.ginka.explainstone;

import com.ginka.explainstone.speaker.AudioStore;
import com.ginka.explainstone.speaker.AudioUsageRegistry;
import com.ginka.explainstone.speaker.BroadcastRegistry;
import com.ginka.explainstone.speaker.PendingImports;
import com.ginka.explainstone.speaker.PlaybackPersistence;
import com.ginka.explainstone.speaker.PlaybackRegistry;
import com.ginka.explainstone.speaker.SpeakerNetworking;
import com.ginka.explainstone.speaker.SpeakerRegistry;
import com.ginka.explainstone.speaker.TimelineClock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Explainstone.MOD_ID)
public class Explainstone {
	// 定义模组ID喵
	public static final String MOD_ID = "explainstone";
	// 定义日志记录器，方便在控制台看输出喵
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 每 5 秒清理一次超时的分块上传 喵 */
	private int uploadCleanupTicker;

	/**
	 * 主类用「无参构造 + 静态方法取上下文」，不要写成
	 * {@code Explainstone(FMLJavaModLoadingContext context)} 喵。
	 *
	 * Forge 1.20.1 的构造器参数注入要到 47.3.10 才支持；47.0 ~ 47.3.9（例如 47.2.20）
	 * 的 FMLModContainer 只会 getDeclaredConstructor() 找无参构造，带参构造会让整个模组
	 * 在加载阶段抛 NoSuchMethodException。无参构造在所有 47.x 上都能用。 喵
	 *
	 * 另外注意：47.2.x 的 FMLJavaModLoadingContext 还不是 ModLoadingContext 的子类，
	 * 它上面没有 registerConfig；配置注册必须走 ModLoadingContext.get() 才通吃 47.x。 喵
	 */
	@SuppressWarnings("removal")
	public Explainstone() {
		IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
		ModLoadingContext context = ModLoadingContext.get();

		// 打印一句欢迎语喵
		LOGGER.info("Hello Forge world!");

		// 注册方块、物品、方块实体喵
		ModBlocks.register(modEventBus);

		// 网络包 + 服务端处理器（界面操作、音频导入/下发） 喵
		SpeakerNetworking.register();

		// 配置文件分两份：服务端（世界级、会同步给客户端）+ 客户端（只影响本机听觉与界面） 喵
		context.registerConfig(ModConfig.Type.SERVER, ExplainstoneServerConfig.SPEC);
		if (FMLEnvironment.dist.isClient()) {
			context.registerConfig(ModConfig.Type.CLIENT, ExplainstoneClientConfig.SPEC);
		}

		// 所有变体都放进创造模式物品栏的「红石」栏 喵
		modEventBus.addListener(this::addCreative);

		// 玩家进服时补发当前在播的频道 喵
		MinecraftForge.EVENT_BUS.register(this);
	}

	private void addCreative(BuildCreativeModeTabContentsEvent event) {
		if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
			event.accept(ModBlocks.SPEAKER_BLOCK);
			event.accept(ModBlocks.STONE_SPEAKER_BLOCK);
			event.accept(ModBlocks.COBBLESTONE_SPEAKER_BLOCK);
			event.accept(ModBlocks.GRANITE_SPEAKER_BLOCK);
			event.accept(ModBlocks.DIORITE_SPEAKER_BLOCK);
			event.accept(ModBlocks.ANDESITE_SPEAKER_BLOCK);
			event.accept(ModBlocks.SONGMELYN_BLOCK);
			event.accept(ModBlocks.BROADCAST_TRANSMITTER);
		}
	}

	@SubscribeEvent
	public void onServerAboutToStart(ServerAboutToStartEvent event) {
		// 世界加载前先读「音频 → 音响」引用表和上次的播放进度 喵
		// 音频仓库也要先扫一遍：可用性索引是播放列表位图的唯一来源 喵
		TimelineClock.reset();
		AudioStore.load(event.getServer());
		AudioUsageRegistry.load(event.getServer());
		SpeakerRegistry.load(event.getServer());
		PlaybackPersistence.load(event.getServer());
	}

	@SubscribeEvent
	public void onServerStopped(ServerStoppedEvent event) {
		// 先保存播放进度（顺序 + 已播放 tick），再清空内存会话 喵
		PlaybackPersistence.save(event.getServer());
		PlaybackPersistence.clear();
		PlaybackRegistry.clear();
		BroadcastRegistry.clear();
		SpeakerRegistry.save();
		SpeakerRegistry.clear();
		SpeakerNetworking.clearUploads();
		AudioUsageRegistry.save();
		AudioUsageRegistry.clear();
		PendingImports.clear();
		AudioStore.clear();
		TimelineClock.reset();
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase == TickEvent.Phase.END && ++this.uploadCleanupTicker >= 100) {
			this.uploadCleanupTicker = 0;
			SpeakerNetworking.cleanupUploads();
			// 待上传条目超时就从播放列表里摘掉（防止元数据垃圾永久挂着） 喵
			PendingImports.tick(event.getServer());
			// 每 5 秒写一次播放进度 / 收信音响登记表，崩溃时最多丢 5 秒 喵
			PlaybackPersistence.save();
			SpeakerRegistry.save();
		}
	}

	@SubscribeEvent
	public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			SpeakerNetworking.removePlayerUploads(player);
		}
	}

	@SubscribeEvent
	public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			// 补发附近正在播放的讲解石会话 + 频道广播 喵
			PlaybackRegistry.syncNear(player);
			BroadcastRegistry.onJoin(player);
		}
	}

	/** 客户端右键方块时打开界面（DistExecutor 保证专用服务端不会加载客户端类） 喵 */
	public static void openSpeakerScreen(BlockPos pos) {
		DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
				() -> () -> com.ginka.explainstone.client.ExplainstoneClient.openScreen(pos));
	}
}
