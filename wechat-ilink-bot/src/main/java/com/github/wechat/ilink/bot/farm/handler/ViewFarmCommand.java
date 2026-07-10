package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.model.CropRegistry;
import com.github.wechat.ilink.bot.farm.model.CropStage;
import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import com.github.wechat.ilink.bot.persistence.StealRecordRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;

import java.util.List;

public class ViewFarmCommand implements Command {

    private final StealRecordRepository stealRepo;

    public ViewFarmCommand(StealRecordRepository stealRepo) {
        this.stealRepo = stealRepo;
    }

    @Override
    public String name() { return "VIEW_FARM"; }

    @Override
    public String description() { return "查看农场"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        List<FarmPlot> activePlots = session.getActivePlots();
        com.github.wechat.ilink.bot.farm.model.CropGrowth.refreshAll(activePlots);
        StringBuilder sb = new StringBuilder();
        sb.append("🌾 帮帮农场 - 我的土地\n");

        for (FarmPlot plot : activePlots) {
            String display = formatPlot(plot);
            sb.append(String.format("[%02d]%-18s", plot.getIndex() + 1, display));
            if ((plot.getIndex() + 1) % 3 == 0) {
                sb.append("\n");
            }
        }
        if (activePlots.size() % 3 != 0) {
            sb.append("\n");
        }

        sb.append("💰 金币: ").append(session.getGold())
                .append(" 📦 种子: ").append(session.getInventory().totalSeedCount());
        int stolen = stealRepo.sumStolenByVictim(session.getUserId());
        if (stolen > 0) {
            sb.append("\n🕵️ 提醒：你有 ").append(stolen).append(" 个作物已被偷（收获时少收）");
        }
        int pendingCompensation = stealRepo.sumCompensationByVictim(session.getUserId());
        if (pendingCompensation > 0) {
            sb.append("\n🎁 被偷补偿 ").append(pendingCompensation).append(" 金币待收获时到账");
        }
        return CommandResult.success(sb.toString());
    }

    private String formatPlot(FarmPlot plot) {
        if (plot.getStage() == CropStage.EMPTY) {
            return "🟫 空地";
        }
        com.github.wechat.ilink.bot.farm.model.Crop crop = CropRegistry.get(plot.getCropType());
        String emoji = crop != null ? crop.getEmoji() : "🌱";
        String stageName = stageName(plot.getStage());
        String status = "";
        if (plot.isHasPest()) status += "🐛";
        if (plot.isHasWeed()) status += "🌿";
        return emoji + crop.getName() + "-" + stageName + status;
    }

    private String stageName(CropStage stage) {
        switch (stage) {
            case SEED: return "种子";
            case SPROUT: return "幼苗";
            case GROWING: return "生长中";
            case MATURE: return "成熟✓";
            case WITHERED: return "枯萎✗";
            default: return "";
        }
    }
}
