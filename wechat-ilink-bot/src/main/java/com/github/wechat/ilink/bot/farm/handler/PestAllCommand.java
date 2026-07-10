package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;

import java.util.List;

public class PestAllCommand implements Command {

    private final ActionRankRepository rankRepo;

    public PestAllCommand(ActionRankRepository rankRepo) {
        this.rankRepo = rankRepo;
    }

    @Override
    public String name() { return "PEST_ALL"; }

    @Override
    public String description() { return "一键除虫"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        List<FarmPlot> activePlots = session.getActivePlots();
        int cleaned = 0;
        for (FarmPlot plot : activePlots) {
            if (plot.isHasPest()) {
                plot.setHasPest(false);
                cleaned++;
            }
        }

        if (cleaned == 0) {
            return CommandResult.error("没有生虫的作物");
        }

        rankRepo.incrementScore("PEST", session.getUserId(), cleaned);
        return CommandResult.success("🐛 除虫完成！清除了 " + cleaned + " 块地的虫害");
    }
}
