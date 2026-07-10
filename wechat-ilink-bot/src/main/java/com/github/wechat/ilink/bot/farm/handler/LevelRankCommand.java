package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.RankFormatter;
import com.github.wechat.ilink.bot.persistence.PlayerRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;

public class LevelRankCommand implements Command {

    private final PlayerRepository playerRepo;

    public LevelRankCommand(PlayerRepository playerRepo) {
        this.playerRepo = playerRepo;
    }

    @Override
    public String name() { return "LEVEL_RANK"; }

    @Override
    public String description() { return "等级排行"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        return CommandResult.success(
                RankFormatter.render("⭐ 等级排行榜", playerRepo.topByLevel(10), playerRepo, "级"));
    }
}
