package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.RankFormatter;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.persistence.PlayerRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;

public class PestRankCommand implements Command {

    private final ActionRankRepository rankRepo;
    private final PlayerRepository playerRepo;

    public PestRankCommand(ActionRankRepository rankRepo, PlayerRepository playerRepo) {
        this.rankRepo = rankRepo;
        this.playerRepo = playerRepo;
    }

    @Override
    public String name() { return "PEST_RANK"; }

    @Override
    public String description() { return "驱虫排行"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        return CommandResult.success(
                RankFormatter.render("🐛 驱虫排行榜", rankRepo.getTopScores("PEST", 10), playerRepo, "分"));
    }
}
