package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.service.StealService;
import com.github.wechat.ilink.bot.session.PlayerSession;

/** #偷菜 列候选、#偷菜 &lt;序号&gt; 偷取。逻辑在 StealService，本类只做入参分发。 */
public class StealCommand implements Command {

    private final StealService stealService;

    public StealCommand(StealService stealService) {
        this.stealService = stealService;
    }

    @Override
    public String name() { return "STEAL"; }

    @Override
    public String description() { return "偷菜"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        if (args.length == 0 || args[0].trim().isEmpty()) {
            return stealService.listCandidates(session.getUserId());
        }
        return stealService.stealByIndex(session, args[0]);
    }
}
