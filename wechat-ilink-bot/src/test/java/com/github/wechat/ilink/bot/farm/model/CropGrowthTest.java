package com.github.wechat.ilink.bot.farm.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CropGrowthTest {

    // 小麦 growTime = 10 分钟 = 600000ms
    private static final long WHEAT_MS = 10L * 60 * 1000;

    @Test
    void computeStage_justPlanted_isSeed() {
        assertEquals(CropStage.SEED,
                CropGrowth.computeStage("wheat", 0L, CropStage.SEED, 0L));
    }

    @Test
    void computeStage_fortyPercent_isSprout() {
        assertEquals(CropStage.SPROUT,
                CropGrowth.computeStage("wheat", 0L, CropStage.SEED, (long) (WHEAT_MS * 0.4)));
    }

    @Test
    void computeStage_seventyPercent_isGrowing() {
        assertEquals(CropStage.GROWING,
                CropGrowth.computeStage("wheat", 0L, CropStage.SEED, (long) (WHEAT_MS * 0.7)));
    }

    @Test
    void computeStage_fullTime_isMature() {
        assertEquals(CropStage.MATURE,
                CropGrowth.computeStage("wheat", 0L, CropStage.SEED, WHEAT_MS));
    }

    @Test
    void computeStage_pastFullTime_staysMature() {
        assertEquals(CropStage.MATURE,
                CropGrowth.computeStage("wheat", 0L, CropStage.SEED, WHEAT_MS * 5));
    }

    @Test
    void computeStage_emptyPlot_unchanged() {
        assertEquals(CropStage.EMPTY,
                CropGrowth.computeStage(null, null, CropStage.EMPTY, WHEAT_MS));
    }

    @Test
    void computeStage_withered_unchanged() {
        assertEquals(CropStage.WITHERED,
                CropGrowth.computeStage("wheat", 0L, CropStage.WITHERED, WHEAT_MS));
    }

    @Test
    void refresh_advancesPlotStageInPlace() {
        FarmPlot plot = new FarmPlot(0);
        plot.plant("wheat");
        plot.setPlantedAt(0L);
        CropGrowth.refresh(plot);
        assertEquals(CropStage.MATURE, plot.getStage());
    }

    @Test
    void refresh_nullPlot_noException() {
        CropGrowth.refresh(null);
    }
}
