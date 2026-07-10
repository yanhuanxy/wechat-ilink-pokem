package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.model.Crop;
import com.github.wechat.ilink.bot.farm.model.CropGrowth;
import com.github.wechat.ilink.bot.farm.model.CropRegistry;
import com.github.wechat.ilink.bot.farm.model.CropStage;
import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import com.github.wechat.ilink.bot.persistence.StealRecordRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HarvestAllCommand implements Command {

    private final StealRecordRepository stealRepo;

    public HarvestAllCommand(StealRecordRepository stealRepo) {
        this.stealRepo = stealRepo;
    }

    @Override
    public String name() { return "HARVEST_ALL"; }

    @Override
    public String description() { return "一键收获"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        List<FarmPlot> activePlots = session.getActivePlots();
        CropGrowth.refreshAll(activePlots);
        Map<String, Integer> harvested = new LinkedHashMap<String, Integer>();
        int totalExp = 0;
        int count = 0;
        int totalStolen = 0;

        for (FarmPlot plot : activePlots) {
            if (plot.getStage() != CropStage.MATURE) {
                continue;
            }
            Crop crop = CropRegistry.get(plot.getCropType());
            if (crop != null) {
                int stolen = stealRepo.sumStolen(session.getUserId(), plot.getIndex(),
                        String.valueOf(plot.getPlantedAt()));
                int actualYield = Math.max(0, crop.getYieldAmount() - stolen);
                session.getInventory().addProduce(crop.getKey(), actualYield);
                Integer prev = harvested.get(crop.getName());
                harvested.put(crop.getName(), (prev != null ? prev : 0) + actualYield);
                totalExp += crop.getExpReward();
                totalStolen += stolen;
                count++;
                stealRepo.clearPlot(session.getUserId(), plot.getIndex());
            }
            plot.harvest();
        }

        if (count == 0) {
            return CommandResult.error("没有成熟的作物可以收获");
        }

        session.addExp(totalExp);
        return CommandResult.success(render(harvested, totalExp, totalStolen));
    }

    private String render(Map<String, Integer> harvested, int totalExp, int totalStolen) {
        StringBuilder sb = new StringBuilder();
        sb.append("✨ 收获结果\n");
        for (Map.Entry<String, Integer> entry : harvested.entrySet()) {
            sb.append("收获 ").append(entry.getKey())
                    .append(" x").append(entry.getValue()).append("个\n");
        }
        if (totalStolen > 0) {
            sb.append("🕵️ 被偷走 ").append(totalStolen).append(" 个\n");
        }
        sb.append("获得经验: ").append(totalExp);
        return sb.toString();
    }
}
