package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.session.PlayerSession;

/** #改名 <昵称>：玩家自设农场昵称（SDK 无昵称字段），榜单/偷菜界面展示用。 */
public class RenameCommand implements Command {

    private static final int MAX_LEN = 12;

    @Override
    public String name() { return "RENAME"; }

    @Override
    public String description() { return "修改农场昵称"; }

    @Override
    public CommandResult execute(PlayerSession session, String[] args) {
        String nickname = (args.length > 0) ? args[0].trim() : "";
        if (nickname.isEmpty()) {
            return CommandResult.error("请输入昵称，如：#改名 大丰收");
        }
        if (nickname.length() > MAX_LEN) {
            return CommandResult.error("昵称最多 " + MAX_LEN + " 个字符");
        }
        session.setNickname(nickname);
        return CommandResult.success("✅ 昵称已改为：" + nickname);
    }
}
