package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.model.CropStage;
import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;

import java.util.List;

public class WaterAllCommand implements Command {

    private final ActionRankRepository rankRepo;

    public WaterAllCommand(ActionRankRepository rankRepo) {
        this.rankRepo = rankRepo;
    }

    @Override
    public String name() { return "WATER_ALL"; }

    @Override
    public String description() { return "一键浇水"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        List<FarmPlot> activePlots = session.getActivePlots();
        int watered = 0;
        for (FarmPlot plot : activePlots) {
            if (plot.getStage() != CropStage.EMPTY && plot.getStage() != CropStage.WITHERED) {
                plot.addWater();
                watered++;
            }
        }

        if (watered == 0) {
            return CommandResult.error("没有需要浇水的作物");
        }

        rankRepo.incrementScore("WATER", session.getUserId(), watered);
        return CommandResult.success("💧 浇水完成！浇了 " + watered + " 块地");
    }
}
