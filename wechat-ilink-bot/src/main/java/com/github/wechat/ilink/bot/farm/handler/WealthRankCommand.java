package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.RankFormatter;
import com.github.wechat.ilink.bot.persistence.PlayerRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;

public class WealthRankCommand implements Command {

    private final PlayerRepository playerRepo;

    public WealthRankCommand(PlayerRepository playerRepo) {
        this.playerRepo = playerRepo;
    }

    @Override
    public String name() { return "WEALTH_RANK"; }

    @Override
    public String description() { return "财富排行"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        return CommandResult.success(
                RankFormatter.render("💰 财富排行榜", playerRepo.topByGold(10), playerRepo, "金币"));
    }
}
