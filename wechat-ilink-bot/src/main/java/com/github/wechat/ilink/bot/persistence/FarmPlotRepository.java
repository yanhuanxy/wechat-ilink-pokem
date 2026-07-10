package com.github.wechat.ilink.bot.persistence;

import com.github.wechat.ilink.bot.farm.model.CropStage;
import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class FarmPlotRepository {

    private static final Logger log = LoggerFactory.getLogger(FarmPlotRepository.class);
    private final DatabaseManager dbManager;

    public FarmPlotRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public List<FarmPlot> findByUserId(String userId) {
        String sql = "SELECT * FROM farm_plot WHERE user_id = ? ORDER BY plot_index";
        List<FarmPlot> plots = new ArrayList<FarmPlot>();
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                FarmPlot plot = new FarmPlot(rs.getInt("plot_index"));
                String cropType = rs.getString("crop_type");
                if (cropType != null) {
                    plot.setCropType(cropType);
                    plot.setStage(CropStage.valueOf(rs.getString("stage")));
                    plot.setWaterCount(rs.getInt("water_count"));
                    String plantedAt = rs.getString("planted_at");
                    if (plantedAt != null) {
                        plot.setPlantedAt(Long.parseLong(plantedAt));
                    }
                    plot.setHasPest(rs.getInt("has_pest") == 1);
                    plot.setHasWeed(rs.getInt("has_weed") == 1);
                }
                plots.add(plot);
            }
        } catch (Exception e) {
            log.error("查询地块失败: userId={}", userId, e);
        }
        return plots;
    }

    /**
     * 随机挑选有在种作物的其他玩家（偷菜候选池）。是否成熟由调用方按 CropGrowth 读时判定，
     * 故此处只按 crop_type 非空筛选，取样上限 limit（调用方再过滤出真正可偷的）。
     */
    public List<String> findRandomActiveUserIds(String excludeUserId, int limit) {
        String sql = "SELECT DISTINCT user_id FROM farm_plot " +
                "WHERE user_id != ? AND crop_type IS NOT NULL AND planted_at IS NOT NULL " +
                "ORDER BY RANDOM() LIMIT ?";
        List<String> userIds = new ArrayList<String>();
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, excludeUserId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                userIds.add(rs.getString("user_id"));
            }
        } catch (Exception e) {
            log.error("查询偷菜候选失败: exclude={}", excludeUserId, e);
        }
        return userIds;
    }

    public void replaceByUserId(String userId, List<FarmPlot> plots) {
        String deleteSql = "DELETE FROM farm_plot WHERE user_id = ?";
        String insertSql = "INSERT INTO farm_plot (user_id, plot_index, crop_type, stage, water_count, planted_at, has_pest, has_weed) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            try (PreparedStatement ps = dbManager.getConnection().prepareStatement(deleteSql)) {
                ps.setString(1, userId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = dbManager.getConnection().prepareStatement(insertSql)) {
                for (FarmPlot plot : plots) {
                    ps.setString(1, userId);
                    ps.setInt(2, plot.getIndex());
                    ps.setString(3, plot.getCropType());
                    ps.setString(4, plot.getStage().name());
                    ps.setInt(5, plot.getWaterCount());
                    ps.setString(6, plot.getPlantedAt() != null ? String.valueOf(plot.getPlantedAt()) : null);
                    ps.setInt(7, plot.isHasPest() ? 1 : 0);
                    ps.setInt(8, plot.isHasWeed() ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (Exception e) {
            log.error("保存地块失败: userId={}", userId, e);
        }
    }
}
