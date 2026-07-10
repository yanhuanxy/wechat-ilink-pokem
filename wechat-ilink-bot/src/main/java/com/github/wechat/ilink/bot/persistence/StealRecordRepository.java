package com.github.wechat.ilink.bot.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 偷菜记录：跨玩家写入，独立于 farm_plot 的会话刷盘（后者是全量重写，会覆盖直接改的地块）。
 * 因此被偷量落在本表，收获时由受害者读本表扣减产量——避免偷菜去锁/改受害者会话，天然无死锁。
 */
public class StealRecordRepository {

    private static final Logger log = LoggerFactory.getLogger(StealRecordRepository.class);
    private final DatabaseManager dbManager;

    public StealRecordRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /** 记一次偷取。compensation 为系统给被偷者的补偿金币（收获时到账）。PK 冲突（同一贼同一地同一轮已偷）返回 false。 */
    public boolean record(String victimId, int plotIndex, String plantedAt, String thiefId, int amount, int compensation) {
        String sql = "INSERT OR IGNORE INTO steal_record " +
                "(victim_id, plot_index, planted_at, thief_id, amount, compensation, stolen_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, victimId);
            ps.setInt(2, plotIndex);
            ps.setString(3, plantedAt);
            ps.setString(4, thiefId);
            ps.setInt(5, amount);
            ps.setInt(6, compensation);
            ps.setString(7, now());
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.error("写偷菜记录失败: victim={}, plot={}, thief={}", victimId, plotIndex, thiefId, e);
            return false;
        }
    }

    /** 某地某成熟周期已被偷走的总量（受害者收获时据此扣减）。 */
    public int sumStolen(String victimId, int plotIndex, String plantedAt) {
        String sql = "SELECT COALESCE(SUM(amount), 0) FROM steal_record " +
                "WHERE victim_id = ? AND plot_index = ? AND planted_at = ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, victimId);
            ps.setInt(2, plotIndex);
            ps.setString(3, plantedAt);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            log.error("查询被偷量失败: victim={}, plot={}", victimId, plotIndex, e);
            return 0;
        }
    }

    /** 该贼是否已偷过某地某成熟周期（防同一人重复偷）。 */
    public boolean hasStolen(String victimId, int plotIndex, String plantedAt, String thiefId) {
        String sql = "SELECT 1 FROM steal_record " +
                "WHERE victim_id = ? AND plot_index = ? AND planted_at = ? AND thief_id = ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, victimId);
            ps.setInt(2, plotIndex);
            ps.setString(3, plantedAt);
            ps.setString(4, thiefId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (Exception e) {
            log.error("查询是否已偷失败: victim={}, plot={}, thief={}", victimId, plotIndex, thiefId, e);
            return false;
        }
    }

    /** 受害者当前未收获作物被偷的总量（#农场 展示"被偷提醒"用）。 */
    public int sumStolenByVictim(String victimId) {
        String sql = "SELECT COALESCE(SUM(amount), 0) FROM steal_record WHERE victim_id = ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, victimId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            log.error("查询受害者被偷总量失败: victim={}", victimId, e);
            return 0;
        }
    }

    /** 某地某成熟周期累计的补偿金币（受害者收获时据此发放，与 sumStolen 同作用域，配合 clearPlot）。 */
    public int sumCompensation(String victimId, int plotIndex, String plantedAt) {
        String sql = "SELECT COALESCE(SUM(compensation), 0) FROM steal_record " +
                "WHERE victim_id = ? AND plot_index = ? AND planted_at = ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, victimId);
            ps.setInt(2, plotIndex);
            ps.setString(3, plantedAt);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            log.error("查询被偷补偿失败: victim={}, plot={}", victimId, plotIndex, e);
            return 0;
        }
    }

    /** 受害者全部待入账补偿（#农场 展示"补偿待到账"用）。 */
    public int sumCompensationByVictim(String victimId) {
        String sql = "SELECT COALESCE(SUM(compensation), 0) FROM steal_record WHERE victim_id = ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, victimId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            log.error("查询受害者补偿总量失败: victim={}", victimId, e);
            return 0;
        }
    }

    /** 收获/清理地块后删除该地全部偷菜记录（跨成熟周期）。 */
    public void clearPlot(String victimId, int plotIndex) {
        String sql = "DELETE FROM steal_record WHERE victim_id = ? AND plot_index = ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, victimId);
            ps.setInt(2, plotIndex);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("清理偷菜记录失败: victim={}, plot={}", victimId, plotIndex, e);
        }
    }

    private static String now() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
    }
}
