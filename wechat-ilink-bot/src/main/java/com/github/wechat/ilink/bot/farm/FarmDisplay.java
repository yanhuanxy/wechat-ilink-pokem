package com.github.wechat.ilink.bot.farm;

/** 玩家展示名解析：有昵称用昵称，否则用"农夫#<wxid尾号>"兜底（SDK 不提供昵称字段）。 */
public final class FarmDisplay {

    private FarmDisplay() {
    }

    public static String name(String userId, String nickname) {
        if (nickname != null && !nickname.trim().isEmpty()) {
            return nickname.trim();
        }
        return "农夫#" + tail(userId);
    }

    private static String tail(String userId) {
        if (userId == null || userId.isEmpty()) {
            return "????";
        }
        String local = userId;
        int at = local.indexOf('@');
        if (at > 0) {
            local = local.substring(0, at);
        }
        int len = local.length();
        return len <= 4 ? local : local.substring(len - 4);
    }
}
