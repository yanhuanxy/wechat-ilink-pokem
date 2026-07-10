package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.session.PlayerSession;

/** #排行：排行榜总入口，列出可查的各类榜单。 */
public class RankMenuCommand implements Command {

    @Override
    public String name() { return "RANK_MENU"; }

    @Override
    public String description() { return "排行榜菜单"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        String menu = "🏆 排行榜\n"
                + "#财富榜 — 金币最多\n"
                + "#等级榜 — 等级最高\n"
                + "#偷菜榜 — 偷菜大盗\n"
                + "#浇水排行 — 浇水劳模\n"
                + "#驱虫排行 — 除虫劳模\n"
                + "#除草排行 — 锄地劳模";
        return CommandResult.success(menu);
    }
}
