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

import com.ginka.explainstone.speaker.BroadcastRegistry;
import com.ginka.explainstone.speaker.PlaybackRegistry;
import com.ginka.explainstone.speaker.SpeakerNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Explainstone.MOD_ID)
public class Explainstone {
	// 定义模组ID喵
	public static final String MOD_ID = "explainstone";
	// 定义日志记录器，方便在控制台看输出喵
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public Explainstone(FMLJavaModLoadingContext context) {
		IEventBus modEventBus = context.getModEventBus();

		// 打印一句欢迎语喵
		LOGGER.info("Hello Forge world!");

		// 注册方块、物品、方块实体喵
		ModBlocks.register(modEventBus);

		// 网络包 + 服务端处理器（界面操作、音频导入/下发） 喵
		SpeakerNetworking.register();

		// 配置文件：config/explainstone-common.toml 喵
		context.registerConfig(ModConfig.Type.COMMON, ExplainstoneConfig.SPEC);

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
	public void onServerStopped(ServerStoppedEvent event) {
		PlaybackRegistry.clear();
		BroadcastRegistry.clear();
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
