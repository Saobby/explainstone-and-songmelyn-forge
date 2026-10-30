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
package com.ginka.explainstone.sound;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * 方块 → 声学材质的集中映射。
 * 优先使用标签和显式覆盖，其次按注册名与 SoundType 推断；其他模组的方块也能自动归类，
 * 需要特殊处理时调用 {@link #register(Block, SoundMaterial)} 覆盖。 喵
 */
public final class SoundMaterials {
    private static final Map<Block, SoundMaterial> OVERRIDES = new HashMap<>();
    private static final Map<Block, SoundMaterial> CACHE = new HashMap<>();
    private static final Map<SoundType, SoundMaterial> BY_SOUND = new HashMap<>();

    static {
        register(Blocks.BRICKS, SoundMaterial.BRICK);
        register(Blocks.NETHER_BRICKS, SoundMaterial.BRICK);
        register(Blocks.RED_NETHER_BRICKS, SoundMaterial.BRICK);
        register(Blocks.MUD_BRICKS, SoundMaterial.BRICK);
        register(Blocks.IRON_BLOCK, SoundMaterial.METAL);
        register(Blocks.GOLD_BLOCK, SoundMaterial.METAL);
        register(Blocks.NETHERITE_BLOCK, SoundMaterial.METAL);
        register(Blocks.IRON_BARS, SoundMaterial.METAL);
        register(Blocks.ANVIL, SoundMaterial.METAL);
        register(Blocks.CHIPPED_ANVIL, SoundMaterial.METAL);
        register(Blocks.DAMAGED_ANVIL, SoundMaterial.METAL);
        register(Blocks.SPONGE, SoundMaterial.SPONGE);
        register(Blocks.WET_SPONGE, SoundMaterial.SPONGE);
        register(Blocks.GRASS_BLOCK, SoundMaterial.GRASS);
        register(Blocks.SNOW_BLOCK, SoundMaterial.SNOW);
        register(Blocks.POWDER_SNOW, SoundMaterial.SNOW);

        BY_SOUND.put(SoundType.WOOL, SoundMaterial.WOOL);
        BY_SOUND.put(SoundType.WOOD, SoundMaterial.WOOD);
        BY_SOUND.put(SoundType.BAMBOO_WOOD, SoundMaterial.WOOD);
        BY_SOUND.put(SoundType.NETHER_WOOD, SoundMaterial.WOOD);
        BY_SOUND.put(SoundType.CHERRY_WOOD, SoundMaterial.WOOD);
        BY_SOUND.put(SoundType.STONE, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.GRAVEL, SoundMaterial.GRAVEL);
        BY_SOUND.put(SoundType.GRASS, SoundMaterial.GRASS);
        BY_SOUND.put(SoundType.WET_GRASS, SoundMaterial.GRASS);
        BY_SOUND.put(SoundType.SAND, SoundMaterial.SAND);
        BY_SOUND.put(SoundType.SNOW, SoundMaterial.SNOW);
        BY_SOUND.put(SoundType.POWDER_SNOW, SoundMaterial.SNOW);
        BY_SOUND.put(SoundType.GLASS, SoundMaterial.GLASS);
        BY_SOUND.put(SoundType.METAL, SoundMaterial.METAL);
        BY_SOUND.put(SoundType.ANVIL, SoundMaterial.METAL);
        BY_SOUND.put(SoundType.NETHERITE_BLOCK, SoundMaterial.METAL);
        BY_SOUND.put(SoundType.COPPER, SoundMaterial.METAL);
        BY_SOUND.put(SoundType.SLIME_BLOCK, SoundMaterial.WOOL);
        BY_SOUND.put(SoundType.HONEY_BLOCK, SoundMaterial.WOOL);
        BY_SOUND.put(SoundType.LADDER, SoundMaterial.WOOD);
        BY_SOUND.put(SoundType.SCAFFOLDING, SoundMaterial.WOOD);
        BY_SOUND.put(SoundType.BAMBOO, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.BAMBOO_SAPLING, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.SWEET_BERRY_BUSH, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.CROP, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.HARD_CROP, SoundMaterial.WOOD);
        BY_SOUND.put(SoundType.VINE, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.ROOTS, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.WEEPING_VINES, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.TWISTING_VINES, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.LILY_PAD, SoundMaterial.LEAVES);
    }

    private SoundMaterials() {
    }

    /** 给指定方块注册自定义声学材质（会清空推断缓存） 喵 */
    public static void register(Block block, SoundMaterial material) {
        OVERRIDES.put(block, material);
        CACHE.clear();
    }

    public static SoundMaterial of(BlockState state, LevelReader level, BlockPos pos) {
        Block block = state.getBlock();

        // 打开的门 / 活板门视为空气 喵
        if (block instanceof DoorBlock && state.getValue(BlockStateProperties.OPEN)) {
            return SoundMaterial.AIR;
        }
        if (block instanceof TrapDoorBlock && state.getValue(BlockStateProperties.OPEN)) {
            return SoundMaterial.AIR;
        }

        SoundMaterial cached = CACHE.get(block);
        if (cached != null) {
            return cached;
        }
        SoundMaterial material = resolve(state, block);
        CACHE.put(block, material);
        return material;
    }

    private static SoundMaterial resolve(BlockState state, Block block) {
        if (state.is(BlockTags.WOOL_CARPETS)) {
            return SoundMaterial.CARPET;
        }
        if (state.is(BlockTags.WOOL)) {
            return SoundMaterial.WOOL;
        }
        if (state.is(BlockTags.LEAVES)) {
            return SoundMaterial.LEAVES;
        }
        if (state.is(BlockTags.PLANKS)) {
            return SoundMaterial.WOOD;
        }
        if (state.is(BlockTags.LOGS)) {
            return SoundMaterial.LOG;
        }
        if (state.is(BlockTags.IMPERMEABLE)) {
            return SoundMaterial.GLASS;
        }
        if (state.is(BlockTags.SAND)) {
            return SoundMaterial.SAND;
        }
        if (state.is(BlockTags.DIRT)) {
            return state.is(Blocks.GRASS_BLOCK) ? SoundMaterial.GRASS : SoundMaterial.DIRT;
        }
        if (state.is(BlockTags.STONE_BRICKS)) {
            return SoundMaterial.STONE;
        }

        SoundMaterial override = OVERRIDES.get(block);
        if (override != null) {
            return override;
        }

        var key = ForgeRegistries.BLOCKS.getKey(block);
        String path = key == null ? "" : key.getPath();
        if (path.contains("concrete")) {
            return SoundMaterial.CONCRETE;
        }
        if (path.contains("glass")) {
            return SoundMaterial.GLASS;
        }
        if (path.contains("wool")) {
            return SoundMaterial.WOOL;
        }
        if (path.contains("carpet")) {
            return SoundMaterial.CARPET;
        }
        if (path.contains("leaves")) {
            return SoundMaterial.LEAVES;
        }
        if (path.contains("planks")) {
            return SoundMaterial.WOOD;
        }
        if (path.endsWith("_log") || path.contains("_wood") || path.contains("log_")) {
            return SoundMaterial.LOG;
        }
        if (path.contains("stone_brick") || path.contains("deepslate_brick")
                || path.contains("blackstone_brick") || path.contains("sandstone")
                || path.contains("terracotta")) {
            return SoundMaterial.STONE;
        }
        if (path.contains("brick")) {
            return SoundMaterial.BRICK;
        }
        if (path.contains("gravel")) {
            return SoundMaterial.GRAVEL;
        }
        if (path.contains("sand")) {
            return SoundMaterial.SAND;
        }
        if (path.contains("snow")) {
            return SoundMaterial.SNOW;
        }
        if (path.contains("sponge")) {
            return SoundMaterial.SPONGE;
        }
        if (path.contains("grass") || path.contains("dirt") || path.contains("podzol")
                || path.contains("mycelium") || path.contains("farmland") || path.contains("mud")) {
            return path.contains("grass") ? SoundMaterial.GRASS : SoundMaterial.DIRT;
        }
        if (path.contains("iron") || path.contains("gold") || path.contains("copper")
                || path.contains("netherite") || path.contains("anvil")) {
            return SoundMaterial.METAL;
        }
        if (path.contains("stone") || path.contains("cobble") || path.contains("deepslate")
                || path.contains("granite") || path.contains("diorite") || path.contains("andesite")
                || path.contains("tuff") || path.contains("quartz") || path.contains("basalt")) {
            return SoundMaterial.STONE;
        }

        SoundMaterial bySound = BY_SOUND.get(state.getSoundType());
        return bySound == null ? SoundMaterial.STONE : bySound;
    }
}
