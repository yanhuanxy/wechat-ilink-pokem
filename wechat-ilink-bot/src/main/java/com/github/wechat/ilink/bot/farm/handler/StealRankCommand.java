package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.RankFormatter;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.persistence.PlayerRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;

public class StealRankCommand implements Command {

    private final ActionRankRepository rankRepo;
    private final PlayerRepository playerRepo;

    public StealRankCommand(ActionRankRepository rankRepo, PlayerRepository playerRepo) {
        this.rankRepo = rankRepo;
        this.playerRepo = playerRepo;
    }

    @Override
    public String name() { return "STEAL_RANK"; }

    @Override
    public String description() { return "偷菜大盗排行"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        return CommandResult.success(
                RankFormatter.render("🥷 偷菜大盗榜", rankRepo.getTopScores("STEAL", 10), playerRepo, "金币"));
    }
}
