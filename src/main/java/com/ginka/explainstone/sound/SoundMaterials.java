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
    /** 无法识别的方块 / 自定义 SoundType 的最终回退值 喵 */
    public static final SoundMaterial FALLBACK = SoundMaterial.STONE;

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

        // 其余原版 SoundType：保证每个原版方块都能得到明确材质 喵
        BY_SOUND.put(SoundType.EMPTY, SoundMaterial.AIR);
        BY_SOUND.put(SoundType.AMETHYST, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.AMETHYST_CLUSTER, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.LARGE_AMETHYST_BUD, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.MEDIUM_AMETHYST_BUD, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.SMALL_AMETHYST_BUD, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.ANCIENT_DEBRIS, SoundMaterial.METAL);
        BY_SOUND.put(SoundType.AZALEA, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.AZALEA_LEAVES, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.FLOWERING_AZALEA, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.BIG_DRIPLEAF, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.SMALL_DRIPLEAF, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.CAVE_VINES, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.CHERRY_LEAVES, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.CHERRY_SAPLING, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.FUNGUS, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.FROGLIGHT, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.FROGSPAWN, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.GLOW_LICHEN, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.HANGING_ROOTS, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.MANGROVE_ROOTS, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.MUDDY_MANGROVE_ROOTS, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.NETHER_SPROUTS, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.NETHER_WART, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.PINK_PETALS, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.SCULK_VEIN, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.SHROOMLIGHT, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.SPORE_BLOSSOM, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.STEM, SoundMaterial.LEAVES);
        BY_SOUND.put(SoundType.WART_BLOCK, SoundMaterial.LEAVES);

        BY_SOUND.put(SoundType.BAMBOO_WOOD_HANGING_SIGN, SoundMaterial.WOOD);
        BY_SOUND.put(SoundType.CHERRY_WOOD_HANGING_SIGN, SoundMaterial.WOOD);
        BY_SOUND.put(SoundType.NETHER_WOOD_HANGING_SIGN, SoundMaterial.WOOD);
        BY_SOUND.put(SoundType.HANGING_SIGN, SoundMaterial.WOOD);
        BY_SOUND.put(SoundType.CHISELED_BOOKSHELF, SoundMaterial.WOOD);

        BY_SOUND.put(SoundType.LANTERN, SoundMaterial.METAL);
        BY_SOUND.put(SoundType.CHAIN, SoundMaterial.METAL);
        BY_SOUND.put(SoundType.LODESTONE, SoundMaterial.METAL);

        BY_SOUND.put(SoundType.CANDLE, SoundMaterial.WOOL);
        BY_SOUND.put(SoundType.SCULK, SoundMaterial.WOOL);
        BY_SOUND.put(SoundType.SCULK_CATALYST, SoundMaterial.WOOL);
        BY_SOUND.put(SoundType.SCULK_SENSOR, SoundMaterial.WOOL);
        BY_SOUND.put(SoundType.SCULK_SHRIEKER, SoundMaterial.WOOL);
        BY_SOUND.put(SoundType.MOSS_CARPET, SoundMaterial.CARPET);
        BY_SOUND.put(SoundType.MOSS, SoundMaterial.LEAVES);

        BY_SOUND.put(SoundType.BASALT, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.BONE_BLOCK, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.CALCITE, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.CORAL_BLOCK, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.DEEPSLATE, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.DEEPSLATE_BRICKS, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.DEEPSLATE_TILES, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.DRIPSTONE_BLOCK, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.POINTED_DRIPSTONE, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.POLISHED_DEEPSLATE, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.TUFF, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.GILDED_BLACKSTONE, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.NETHERRACK, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.NETHER_ORE, SoundMaterial.STONE);
        BY_SOUND.put(SoundType.NETHER_GOLD_ORE, SoundMaterial.STONE);

        BY_SOUND.put(SoundType.DECORATED_POT, SoundMaterial.BRICK);
        BY_SOUND.put(SoundType.DECORATED_POT_CRACKED, SoundMaterial.BRICK);
        BY_SOUND.put(SoundType.MUD_BRICKS, SoundMaterial.BRICK);
        BY_SOUND.put(SoundType.NETHER_BRICKS, SoundMaterial.BRICK);

        BY_SOUND.put(SoundType.MUD, SoundMaterial.DIRT);
        BY_SOUND.put(SoundType.PACKED_MUD, SoundMaterial.DIRT);
        BY_SOUND.put(SoundType.ROOTED_DIRT, SoundMaterial.DIRT);
        BY_SOUND.put(SoundType.SOUL_SOIL, SoundMaterial.DIRT);
        BY_SOUND.put(SoundType.SOUL_SAND, SoundMaterial.SAND);
        BY_SOUND.put(SoundType.SUSPICIOUS_SAND, SoundMaterial.SAND);
        BY_SOUND.put(SoundType.SUSPICIOUS_GRAVEL, SoundMaterial.GRAVEL);
        BY_SOUND.put(SoundType.NYLIUM, SoundMaterial.GRASS);
    }

    private SoundMaterials() {
    }

    /** 给指定方块注册自定义声学材质（会清空推断缓存） 喵 */
    public static void register(Block block, SoundMaterial material) {
        OVERRIDES.put(block, material);
        CACHE.clear();
    }

    /** 给自定义 SoundType 注册声学材质（适用于其他模组的方块） 喵 */
    public static void register(SoundType soundType, SoundMaterial material) {
        BY_SOUND.put(soundType, material);
        CACHE.clear();
    }

    public static SoundMaterial of(BlockState state, LevelReader level, BlockPos pos) {
        try {
            return ofInternal(state, state.getBlock(), level, pos);
        } catch (Throwable t) {
            // 其他模组的异常方块/自定义 SoundType 不应导致声音或房间采样崩溃 喵
            return FALLBACK;
        }
    }

    private static SoundMaterial ofInternal(BlockState state, Block block,
                                            LevelReader level, BlockPos pos) {
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

        // 名称规则补充：SoundType 相同但声学上应区分开的方块 喵
        if (path.contains("sculk_vein")) {
            return SoundMaterial.LEAVES;
        }
        if (path.contains("sculk")) {
            return SoundMaterial.WOOL;
        }
        if (path.contains("amethyst")) {
            return SoundMaterial.STONE;
        }
        if (path.contains("coral")) {
            return SoundMaterial.STONE;
        }
        if (path.contains("bone")) {
            return SoundMaterial.STONE;
        }
        if (path.contains("ice")) {
            return SoundMaterial.GLASS;
        }
        if (path.contains("glowstone") || path.contains("sea_lantern")
                || path.contains("beacon")) {
            return SoundMaterial.GLASS;
        }
        if (path.contains("candle") || path.contains("cake")
                || path.contains("shulker_box") || path.contains("honey")) {
            return SoundMaterial.WOOL;
        }
        if (path.contains("jack_o_lantern")) {
            return SoundMaterial.LEAVES;
        }
        if (path.equals("lantern") || path.equals("soul_lantern")
                || path.equals("chain") || path.contains("lodestone")
                || path.contains("ancient_debris")) {
            return SoundMaterial.METAL;
        }
        if (path.contains("bookshelf") || path.endsWith("_sign")) {
            return SoundMaterial.WOOD;
        }
        if (path.contains("bamboo_block") || path.contains("bamboo_mosaic")) {
            return SoundMaterial.WOOD;
        }
        if (path.contains("decorated_pot")) {
            return SoundMaterial.BRICK;
        }
        if (path.contains("soul_soil")) {
            return SoundMaterial.DIRT;
        }
        if (path.equals("moss_block")) {
            return SoundMaterial.LEAVES;
        }
        if (path.contains("moss_carpet")) {
            return SoundMaterial.CARPET;
        }
        if (path.contains("mushroom_stem")) {
            return SoundMaterial.WOOD;
        }
        if (path.contains("crimson_stem") || path.contains("warped_stem")) {
            return SoundMaterial.LOG;
        }
        if (path.contains("azalea") || path.contains("dripleaf")
                || path.contains("spore_blossom") || path.contains("pink_petals")
                || path.contains("froglight") || path.contains("frogspawn")
                || path.contains("shroomlight") || path.contains("wart")
                || path.contains("vine") || path.contains("roots")
                || path.contains("glow_lichen") || path.contains("stem")) {
            return SoundMaterial.LEAVES;
        }
        if (path.contains("nylium")) {
            return SoundMaterial.GRASS;
        }
        if (path.contains("hay_block") || path.contains("target")) {
            return SoundMaterial.GRASS;
        }
        if (path.contains("dried_kelp")) {
            return SoundMaterial.LEAVES;
        }
        if (path.contains("cactus") || path.contains("pumpkin")
                || path.contains("melon") || path.contains("cocoa")) {
            return SoundMaterial.LEAVES;
        }
        if (path.contains("purpur") || path.contains("prismarine")
                || path.contains("obsidian") || path.contains("magma")) {
            return SoundMaterial.STONE;
        }
        if (path.endsWith("_ore")) {
            return SoundMaterial.STONE;
        }

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
                || path.contains("terracotta") || path.contains("quartz")) {
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
        return bySound == null ? FALLBACK : bySound;
    }
}
