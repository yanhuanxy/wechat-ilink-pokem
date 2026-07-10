package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.session.PlayerSession;

public class HelpCommand implements Command {

    @Override
    public String name() { return "HELP"; }

    @Override
    public String description() { return "显示菜单"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        StringBuilder sb = new StringBuilder();
        sb.append("╭┈┈ 🌱帮帮农场🌱 ┈┈╮\n");
        sb.append("🌴✨#我的信息  #查看农场✨🌴\n");
        sb.append("🌴✨#商店      #购买 小麦✨🌴\n");
        sb.append("🌴✨#背包      #道具✨🌴\n");
        sb.append("🌴✨#签到      #天气✨🌴\n");
        sb.append("🌴✨#种植 小麦  #收获✨🌴\n");
        sb.append("🌴✨#卖菜      #锄地✨🌴\n");
        sb.append("🌴✨#浇水      #除虫✨🌴\n");
        sb.append("🌴✨#施肥 小麦  #点券商店✨🌴\n");
        sb.append("🌴✨#改名 昵称  #偷菜✨🌴\n");
        sb.append("🌴✨#排行      #财富榜✨🌴\n");
        sb.append("🌴✨#等级榜    #偷菜榜✨🌴\n");
        sb.append("🌴✨#分享      #邀请✨🌴\n");
        sb.append("╰ 🐛┈┈┈┈┈┈┈┈┈🐛╯");
        return CommandResult.success(sb.toString());
    }
}
