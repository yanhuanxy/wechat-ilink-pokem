package com.github.wechat.ilink.bot.farm.model;

import java.util.List;

/**
 * 作物成熟推进——读时计算。
 *
 * 历史上 stage 种下后恒为 SEED（无任何代码推进），作物永不成熟、收获形同虚设。
 * 这里按 plantedAt 与 growTimeMinutes 的经过时间把 stage 线性映射到 SEED→SPROUT→GROWING→MATURE，
 * 在读取地块（查看/收获/偷菜）前调用刷新即可，无需定时器（与 FertilizeCommand 拨快 plantedAt 的设计一致）。
 * 本期不做枯萎：WITHERED / EMPTY 保持原状。
 */
public final class CropGrowth {

    private CropGrowth() {
    }

    /** 依据经过时间计算当前应处阶段；非在长作物（空地/枯萎/无作物）返回原 stage。 */
    public static CropStage computeStage(String cropType, Long plantedAt, CropStage stored, long now) {
        if (cropType == null || plantedAt == null
                || stored == CropStage.EMPTY || stored == CropStage.WITHERED) {
            return stored;
        }
        Crop crop = CropRegistry.get(cropType);
        if (crop == null || crop.getGrowTimeMinutes() <= 0) {
            return stored;
        }
        long grownMs = (now - plantedAt);
        long totalMs = crop.getGrowTimeMinutes() * 60L * 1000L;
        double ratio = (double) grownMs / (double) totalMs;
        if (ratio >= 1.0) {
            return CropStage.MATURE;
        }
        if (ratio >= 0.66) {
            return CropStage.GROWING;
        }
        if (ratio >= 0.33) {
            return CropStage.SPROUT;
        }
        return CropStage.SEED;
    }

    /** 就地刷新单块地的 stage。 */
    public static void refresh(FarmPlot plot) {
        if (plot == null) {
            return;
        }
        CropStage next = computeStage(plot.getCropType(), plot.getPlantedAt(), plot.getStage(), System.currentTimeMillis());
        if (next != plot.getStage()) {
            plot.setStage(next);
        }
    }

    /** 批量刷新（查看/收获前一次性推进所有地块）。 */
    public static void refreshAll(List<FarmPlot> plots) {
        if (plots == null) {
            return;
        }
        for (FarmPlot plot : plots) {
            refresh(plot);
        }
    }
}
