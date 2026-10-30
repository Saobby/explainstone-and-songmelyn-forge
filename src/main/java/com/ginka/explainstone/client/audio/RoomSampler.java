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
package com.ginka.explainstone.client.audio;

import com.ginka.explainstone.sound.SoundMaterial;
import com.ginka.explainstone.sound.SoundMaterials;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从音响位置向球面发射射线，重建房间边界并估算体积、表面积和 RT60。
 * 第一版采用正二十面体二级细分（162 个方向 / 320 个三角形）。 喵
 */
public final class RoomSampler {
    private static final Mesh MESH = buildMesh(2);
    private static final double MIN_VALID_RATIO = 0.60;
    private static final double MIN_VOLUME = 0.5;
    private static final double MIN_AREA = 0.5;
    private static final int MIN_TRIANGLES = 24;

    private RoomSampler() {
    }

    public static RoomAcoustics sample(ClientLevel level, Vec3 origin, double maxDistance) {
        int count = MESH.directions.length;
        Hit[] hits = new Hit[count];
        int valid = 0;
        for (int i = 0; i < count; i++) {
            hits[i] = raycast(level, origin, vec(MESH.directions[i]), maxDistance);
            if (hits[i] != null) {
                valid++;
            }
        }
        if (valid < count * MIN_VALID_RATIO) {
            return null;   // 太开放（室外/大洞口），不生成混响 喵
        }

        double volume = 0.0;
        double surfaceArea = 0.0;
        double absorptionLow = 0.0;
        double absorptionHigh = 0.0;
        int triangles = 0;

        for (int[] face : MESH.faces) {
            Hit a = hits[face[0]];
            Hit b = hits[face[1]];
            Hit c = hits[face[2]];
            if (a == null || b == null || c == null) {
                continue;
            }
            Vec3 pa = a.point();
            Vec3 pb = b.point();
            Vec3 pc = c.point();
            Vec3 ab = pb.subtract(pa);
            Vec3 ac = pc.subtract(pa);
            double area = ab.cross(ac).length() * 0.5;
            if (area < 1.0E-4) {
                continue;
            }

            // 房间是星形近似：每个边界三角形与音响位置组成一个四面体 喵
            Vec3 oa = pa.subtract(origin);
            Vec3 ob = pb.subtract(origin);
            Vec3 oc = pc.subtract(origin);
            volume += Math.abs(oa.dot(ob.cross(oc))) / 6.0;
            surfaceArea += area;

            // 用三角形中心方向再打一条射线来确定这块墙的材质 喵
            Vec3 centerDir = vec(MESH.directions[face[0]])
                    .add(vec(MESH.directions[face[1]]))
                    .add(vec(MESH.directions[face[2]]));
            if (centerDir.lengthSqr() < 1.0E-12) {
                centerDir = vec(MESH.directions[face[0]]);
            } else {
                centerDir = centerDir.normalize();
            }
            Hit materialHit = raycast(level, origin, centerDir, maxDistance);
            if (materialHit == null) {
                materialHit = a;
            }
            double alphaLow = materialHit.material().absorptionLow();
            double alphaHigh = materialHit.material().absorptionHigh();
            if (materialHit.incomplete()) {
                // 不完整方块的吸声系数也取平方根 喵
                alphaLow = Math.sqrt(alphaLow);
                alphaHigh = Math.sqrt(alphaHigh);
            }
            absorptionLow += area * alphaLow;
            absorptionHigh += area * alphaHigh;
            triangles++;
        }

        if (triangles < MIN_TRIANGLES || volume < MIN_VOLUME || surfaceArea < MIN_AREA) {
            return null;
        }

        // Sabine：RT60 = 0.161 * V / A，单位 m³ / m² 喵
        double rtLow = clamp(0.161 * volume / Math.max(absorptionLow, 0.05), 0.1, 20.0);
        double rtHigh = clamp(0.161 * volume / Math.max(absorptionHigh, 0.05), 0.1, 20.0);
        double rtMid = Math.sqrt(rtLow * rtHigh);
        if (!Double.isFinite(rtMid) || rtMid <= 0.0) {
            return null;
        }
        return new RoomAcoustics(volume, surfaceArea, absorptionLow, absorptionHigh,
                rtLow, rtHigh, rtMid);
    }

    private static Vec3 vec(double[] value) {
        return new Vec3(value[0], value[1], value[2]);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Mesh(double[][] directions, int[][] faces) {
    }

    private record Hit(Vec3 point, SoundMaterial material, boolean incomplete) {
    }

    /**
     * DDA 遍历到第一个真正撞上的方块形状；形状空隙（栅栏、门洞等）会让射线继续走。 喵
     */
    private static Hit raycast(ClientLevel level, Vec3 from, Vec3 direction, double maxDistance) {
        Vec3 to = from.add(direction.scale(maxDistance));
        // DDA 的 t 参数覆盖 from→to 整段射线，必须用整段位移而不是单位方向 喵
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        int x = (int) Math.floor(from.x);
        int y = (int) Math.floor(from.y);
        int z = (int) Math.floor(from.z);
        int stepX = dx > 0.0 ? 1 : dx < 0.0 ? -1 : 0;
        int stepY = dy > 0.0 ? 1 : dy < 0.0 ? -1 : 0;
        int stepZ = dz > 0.0 ? 1 : dz < 0.0 ? -1 : 0;
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY
                : (stepX > 0 ? (x + 1 - from.x) : (from.x - x)) / Math.abs(dx);
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY
                : (stepY > 0 ? (y + 1 - from.y) : (from.y - y)) / Math.abs(dy);
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                : (stepZ > 0 ? (z + 1 - from.z) : (from.z - z)) / Math.abs(dz);
        BlockPos sourcePos = BlockPos.containing(from);
        double t = 0.0;
        double maxDistanceSqr = maxDistance * maxDistance;

        for (int guard = 0; guard < 4096; guard++) {
            double tNext = Math.min(1.0, Math.min(tMaxX, Math.min(tMaxY, tMaxZ)));
            if (tNext > t
                    && !(x == sourcePos.getX() && y == sourcePos.getY() && z == sourcePos.getZ())) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.isLoaded(pos)) {
                    return null;
                }
                BlockState state = level.getBlockState(pos);
                if (!state.isAir()) {
                    SoundMaterial material = SoundMaterials.of(state, level, pos);
                    if (material != SoundMaterial.AIR) {
                        VoxelShape shape = state.getCollisionShape(level, pos);
                        if (!shape.isEmpty()) {
                            BlockHitResult hit = shape.clip(from, to, pos);
                            if (hit != null) {
                                Vec3 point = hit.getLocation();
                                if (point.distanceToSqr(from) <= maxDistanceSqr + 1.0E-6) {
                                    return new Hit(point, material,
                                            !Block.isShapeFullBlock(shape));
                                }
                            }
                        }
                    }
                }
            }

            if (tNext >= 1.0) {
                break;
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }
        return null;
    }

    private static Mesh buildMesh(int levels) {
        List<double[]> vertices = new ArrayList<>();
        double phi = (1.0 + Math.sqrt(5.0)) * 0.5;
        double[][] base = {
                {-1, phi, 0}, {1, phi, 0}, {-1, -phi, 0}, {1, -phi, 0},
                {0, -1, phi}, {0, 1, phi}, {0, -1, -phi}, {0, 1, -phi},
                {phi, 0, -1}, {phi, 0, 1}, {-phi, 0, -1}, {-phi, 0, 1}
        };
        for (double[] vertex : base) {
            vertices.add(normalize(vertex));
        }
        int[][] faces = {
                {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
                {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
                {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
                {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
        };

        for (int level = 0; level < levels; level++) {
            Map<Long, Integer> midpointCache = new HashMap<>();
            List<int[]> next = new ArrayList<>(faces.length * 4);
            for (int[] face : faces) {
                int ab = midpoint(vertices, midpointCache, face[0], face[1]);
                int bc = midpoint(vertices, midpointCache, face[1], face[2]);
                int ca = midpoint(vertices, midpointCache, face[2], face[0]);
                next.add(new int[]{face[0], ab, ca});
                next.add(new int[]{face[1], bc, ab});
                next.add(new int[]{face[2], ca, bc});
                next.add(new int[]{ab, bc, ca});
            }
            faces = next.toArray(new int[0][]);
        }

        double[][] directions = new double[vertices.size()][];
        for (int i = 0; i < vertices.size(); i++) {
            directions[i] = normalize(vertices.get(i));
        }
        return new Mesh(directions, faces);
    }

    private static int midpoint(List<double[]> vertices, Map<Long, Integer> cache, int a, int b) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        long key = ((long) min << 32) ^ (max & 0xffffffffL);
        Integer existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        double[] va = vertices.get(a);
        double[] vb = vertices.get(b);
        double[] mid = normalize(new double[]{va[0] + vb[0], va[1] + vb[1], va[2] + vb[2]});
        vertices.add(mid);
        int index = vertices.size() - 1;
        cache.put(key, index);
        return index;
    }

    private static double[] normalize(double[] value) {
        double length = Math.sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2]);
        if (length < 1.0E-12) {
            return new double[]{1.0, 0.0, 0.0};
        }
        return new double[]{value[0] / length, value[1] / length, value[2] / length};
    }
}
