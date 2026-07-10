package com.github.wechat.ilink.bot.mode;

import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.engine.GameEngine;
import com.github.wechat.ilink.bot.engine.ResponseRenderer;
import com.github.wechat.ilink.bot.farm.service.StealService;
import com.github.wechat.ilink.bot.session.PlayerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FarmMode implements BotMode {

    private static final Logger log = LoggerFactory.getLogger(FarmMode.class);

    @Override
    public BotModeType type() {
        return BotModeType.FARM;
    }

    @Override
    public ModeOutcome handleText(ModeContext ctx, PlayerSession session, String text) {
        String userId = session.getUserId();
        String commandText = text.substring(1).trim();
        if (commandText.isEmpty()) {
            try {
                ctx.sender().sendText(userId, "输入『帮助』查看可用命令");
            } catch (Exception ignored) {
            }
            return ModeOutcome.handled();
        }

        ModeSender sender = ctx.sender();
        GameEngine engine = ctx.engine();
        ResponseRenderer renderer = ctx.renderer();

        try {
            CommandResult result = engine.dispatch(userId, commandText);

            Object imageBase64 = result.getData().get(CommandResult.IMAGE_DATA_KEY);
            if (imageBase64 instanceof byte[]) {
                sendImageResult(sender, userId, result, (byte[]) imageBase64);
            } else {
                String response = renderer.render(result);
                if (response != null && !response.isEmpty()) {
                    sender.sendText(userId, response);
                }
            }

            // 偷菜辅通道：被偷者若近期活跃（内存有 context token）则推一条通知，否则静默跳过。
            // 补偿金币 100% 在其收获时到账，通知仅补实时性，不可靠符合预期。
            pushVictimNotify(sender, result);
        } catch (Exception e) {
            log.error("游戏处理出错, userId={}", userId, e);
            try {
                sender.sendText(userId, "出了点问题，输入'#帮助'查看可用命令");
            } catch (Exception ignored) {
            }
        }
        return ModeOutcome.handled();
    }

    private void sendImageResult(ModeSender sender, String userId,
                                 CommandResult result, byte[] imageBytes) {
        try {
            sender.sendImage(userId, imageBytes, "bot-qrcode.png", result.getMessage());
        } catch (Exception e) {
            log.error("发送二维码图片失败, userId={}", userId, e);
            try {
                sender.sendText(userId, result.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    /** 偷菜被偷通知：从 CommandResult.data 取被偷者与文案，最佳 effort 推送（被偷者不活跃或发送失败则静默跳过）。 */
    private void pushVictimNotify(ModeSender sender, CommandResult result) {
        Object victimId = result.getData().get(StealService.VICTIM_NOTIFY_USER_ID);
        Object notifyText = result.getData().get(StealService.VICTIM_NOTIFY_TEXT);
        if (!(victimId instanceof String) || !(notifyText instanceof String)) {
            return;
        }
        try {
            sender.sendText((String) victimId, (String) notifyText);
        } catch (Exception e) {
            log.warn("被偷通知推送跳过(被偷者不活跃或发送失败), victim={}", victimId);
        }
    }
}
