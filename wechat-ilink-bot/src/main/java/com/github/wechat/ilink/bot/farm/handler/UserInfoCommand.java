package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.session.PlayerSession;

public class UserInfoCommand implements Command {

    @Override
    public String name() { return "USER_INFO"; }

    @Override
    public String description() { return "查看玩家信息"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        int expNeeded = session.getLevel() * 100;
        StringBuilder sb = new StringBuilder();
        sb.append("👤 玩家: ")
                .append(com.github.wechat.ilink.bot.farm.FarmDisplay.name(session.getUserId(), session.getNickname()))
                .append("\n");
        sb.append("💰 金币: ").append(session.getGold()).append("\n");
        sb.append("⭐ 等级: Lv.").append(session.getLevel())
                .append(" (").append(session.getExp()).append("/").append(expNeeded).append(" EXP)\n");
        sb.append("📅 连续签到: ").append(session.getCheckinStreak()).append("天\n");
        sb.append("🎟️ 点券: ").append(session.getCoupon());
        return CommandResult.success(sb.toString());
    }
}
