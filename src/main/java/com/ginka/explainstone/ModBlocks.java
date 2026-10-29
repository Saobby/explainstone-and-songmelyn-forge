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

import com.ginka.explainstone.speaker.SpeakerBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Function;

/**
 * 讲解石的所有变体：外壳纹理不同，方块行为完全一样（共用同一个 BlockEntity 类型）。 喵
 */
public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Explainstone.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Explainstone.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Explainstone.MOD_ID);

    public static final RegistryObject<Block> SPEAKER_BLOCK = register("speaker_block", SpeakerBlock::new);
    public static final RegistryObject<Block> STONE_SPEAKER_BLOCK = register("stone_speaker_block", SpeakerBlock::new);
    public static final RegistryObject<Block> COBBLESTONE_SPEAKER_BLOCK = register("cobblestone_speaker_block", SpeakerBlock::new);
    public static final RegistryObject<Block> GRANITE_SPEAKER_BLOCK = register("granite_speaker_block", SpeakerBlock::new);
    public static final RegistryObject<Block> DIORITE_SPEAKER_BLOCK = register("diorite_speaker_block", SpeakerBlock::new);
    public static final RegistryObject<Block> ANDESITE_SPEAKER_BLOCK = register("andesite_speaker_block", SpeakerBlock::new);
    /** 颂美铃：和讲解石功能完全一样，只是外形是壁挂式教室广播音响（碰撞箱按模型收窄） 喵 */
    public static final RegistryObject<Block> SONGMELYN_BLOCK = register("songmelyn_block", SongmelynBlock::new);
    public static final RegistryObject<Block> BROADCAST_TRANSMITTER =
            register("broadcast_transmitter", BroadcastTransmitterBlock::new, false);

    public static final RegistryObject<BlockEntityType<SpeakerBlockEntity>> SPEAKER_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("speaker_block", () -> BlockEntityType.Builder.of(
                    SpeakerBlockEntity::new,
                    SPEAKER_BLOCK.get(),
                    STONE_SPEAKER_BLOCK.get(),
                    COBBLESTONE_SPEAKER_BLOCK.get(),
                    GRANITE_SPEAKER_BLOCK.get(),
                    DIORITE_SPEAKER_BLOCK.get(),
                    ANDESITE_SPEAKER_BLOCK.get(),
                    SONGMELYN_BLOCK.get(),
                    BROADCAST_TRANSMITTER.get()
            ).build(null));

    private static RegistryObject<Block> register(String name, Function<BlockBehaviour.Properties, Block> factory) {
        return register(name, factory, true);
    }

    private static RegistryObject<Block> register(String name, Function<BlockBehaviour.Properties, Block> factory,
                                                  boolean nonOpaque) {
        RegistryObject<Block> block = BLOCKS.register(name, () -> factory.apply(defaultProperties(nonOpaque)));
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    private static BlockBehaviour.Properties defaultProperties(boolean nonOpaque) {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of().strength(1.05F);
        return nonOpaque ? properties.noOcclusion() : properties;
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
