package com.github.wechat.ilink.bot.farm;

import com.github.wechat.ilink.bot.persistence.PlayerRepository;

import java.util.Map;

/** 榜单渲染：把 userId→分值 映射渲染成带昵称的名次列表（替换裸 wxid）。 */
public final class RankFormatter {

    private RankFormatter() {
    }

    public static String render(String title, Map<String, Integer> scores, PlayerRepository playerRepo, String unit) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n");
        if (scores.isEmpty()) {
            sb.append("暂无数据");
            return sb.toString();
        }
        int rank = 1;
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            sb.append(rank++).append(". ")
                    .append(FarmDisplay.name(entry.getKey(), playerRepo.getNickname(entry.getKey())))
                    .append(" - ").append(entry.getValue()).append(unit).append("\n");
        }
        return sb.toString().trim();
    }
}
