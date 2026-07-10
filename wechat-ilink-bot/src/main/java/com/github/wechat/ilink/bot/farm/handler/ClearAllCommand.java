package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.model.CropStage;
import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;

import java.util.List;

public class ClearAllCommand implements Command {

    private final ActionRankRepository rankRepo;

    public ClearAllCommand(ActionRankRepository rankRepo) {
        this.rankRepo = rankRepo;
    }

    @Override
    public String name() { return "CLEAR_ALL"; }

    @Override
    public String description() { return "一键锄地"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        List<FarmPlot> activePlots = session.getActivePlots();
        int cleared = 0;
        for (FarmPlot plot : activePlots) {
            if (plot.getStage() == CropStage.WITHERED) {
                plot.clear();
                cleared++;
            }
        }

        if (cleared == 0) {
            return CommandResult.error("没有枯萎的作物需要清理");
        }

        rankRepo.incrementScore("WEED", session.getUserId(), cleared);
        return CommandResult.success("🧹 清理完成！清除了 " + cleared + " 块枯萎作物");
    }
}
