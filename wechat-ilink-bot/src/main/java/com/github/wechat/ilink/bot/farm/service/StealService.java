package com.github.wechat.ilink.bot.farm.service;

import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.FarmDisplay;
import com.github.wechat.ilink.bot.farm.model.Crop;
import com.github.wechat.ilink.bot.farm.model.CropGrowth;
import com.github.wechat.ilink.bot.farm.model.CropRegistry;
import com.github.wechat.ilink.bot.farm.model.CropStage;
import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.persistence.FarmPlotRepository;
import com.github.wechat.ilink.bot.persistence.PlayerRepository;
import com.github.wechat.ilink.bot.persistence.StealRecordRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 偷菜编排：全服随机池选目标 → 偷成熟作物。
 *
 * 关键设计：偷菜只读受害者的地块、只写 steal_record（被偷量），绝不锁/改受害者会话——
 * 因农场刷盘是"整表重写"，直接改受害者内存态会被其刷盘覆盖，且跨玩家嵌套加锁在多 bot 线程下会死锁。
 * 受害者收获时从 steal_record 扣减产量（见 HarvestAllCommand），闭环成立且天然无死锁。
 *
 * 两步交互（因裸数字回复不走 # 路由）：#偷菜 列候选、#偷菜 <序号> 执行。
 */
public class StealService {

    private static final int CANDIDATE_COUNT = 3;
    private static final int SAMPLE_POOL = 20;
    private static final double STEAL_RATIO = 0.3;
    private static final long COOLDOWN_MS = 5 * 60 * 1000L;
    private static final long CANDIDATE_TTL_MS = 5 * 60 * 1000L;

    private final FarmPlotRepository plotRepo;
    private final PlayerRepository playerRepo;
    private final StealRecordRepository stealRepo;
    private final ActionRankRepository rankRepo;

    private final ConcurrentHashMap<String, CandidateSet> candidates = new ConcurrentHashMap<String, CandidateSet>();
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<String, Long>();

    public StealService(FarmPlotRepository plotRepo, PlayerRepository playerRepo,
                        StealRecordRepository stealRepo, ActionRankRepository rankRepo) {
        this.plotRepo = plotRepo;
        this.playerRepo = playerRepo;
        this.stealRepo = stealRepo;
        this.rankRepo = rankRepo;
    }

    /** #偷菜：列出最多 3 个有成熟作物的随机邻居，缓存供序号选取。 */
    public CommandResult listCandidates(String thiefId) {
        List<String> pool = plotRepo.findRandomActiveUserIds(thiefId, SAMPLE_POOL);
        List<String> ids = new ArrayList<String>();
        StringBuilder sb = new StringBuilder("🕵️ 可偷的邻居（发 #偷菜 序号 开偷）\n");
        for (String uid : pool) {
            List<FarmPlot> plots = plotRepo.findByUserId(uid);
            CropGrowth.refreshAll(plots);
            String sample = firstMatureSample(plots);
            if (sample == null) {
                continue;
            }
            ids.add(uid);
            sb.append(ids.size()).append(". ")
                    .append(FarmDisplay.name(uid, playerRepo.getNickname(uid)))
                    .append(" — ").append(sample).append("\n");
            if (ids.size() >= CANDIDATE_COUNT) {
                break;
            }
        }
        if (ids.isEmpty()) {
            return CommandResult.success("现在没有可偷的成熟作物，过会儿再来～");
        }
        candidates.put(thiefId, new CandidateSet(ids, System.currentTimeMillis()));
        return CommandResult.success(sb.toString().trim());
    }

    /** #偷菜 &lt;序号&gt;：对缓存候选中的第 index 位执行偷取。 */
    public CommandResult stealByIndex(PlayerSession thief, String indexArg) {
        String thiefId = thief.getUserId();
        CandidateSet set = candidates.get(thiefId);
        if (set == null || System.currentTimeMillis() - set.listedAt > CANDIDATE_TTL_MS) {
            return CommandResult.error("请先发 #偷菜 查看可偷的邻居");
        }
        int index = parseIndex(indexArg);
        if (index < 1 || index > set.ids.size()) {
            return CommandResult.error("序号不对，发 #偷菜 重新查看");
        }
        long cd = remainingCooldown(thiefId);
        if (cd > 0) {
            return CommandResult.error("手速太快啦，" + cd + " 秒后再来偷");
        }
        return doSteal(thief, set.ids.get(index - 1));
    }

    private CommandResult doSteal(PlayerSession thief, String victimId) {
        List<FarmPlot> plots = plotRepo.findByUserId(victimId);
        CropGrowth.refreshAll(plots);
        FarmPlot target = pickBest(victimId, thief.getUserId(), plots);
        if (target == null) {
            return CommandResult.error("这位邻居的菜被偷光了，换一家吧");
        }
        Crop crop = CropRegistry.get(target.getCropType());
        String plantedAt = String.valueOf(target.getPlantedAt());
        int remaining = crop.getYieldAmount() - stealRepo.sumStolen(victimId, target.getIndex(), plantedAt);
        int qty = Math.min(remaining, Math.max(1, (int) Math.round(crop.getYieldAmount() * STEAL_RATIO)));

        boolean ok = stealRepo.record(victimId, target.getIndex(), plantedAt, thief.getUserId(), qty);
        if (!ok) {
            return CommandResult.error("你已经偷过这块地啦，换一家吧");
        }
        thief.getInventory().addProduce(crop.getKey(), qty);
        thief.markDirty();
        int value = qty * crop.getSellPrice();
        rankRepo.incrementScore("STEAL", thief.getUserId(), value);
        cooldowns.put(thief.getUserId(), System.currentTimeMillis());

        return CommandResult.success("🥷 你从 "
                + FarmDisplay.name(victimId, playerRepo.getNickname(victimId))
                + " 的农场偷走了 " + qty + "个" + crop.getEmoji() + crop.getName()
                + "（价值" + value + "金币）！");
    }

    /** 选价值最高且尚可偷（未偷光、本贼未偷过）的成熟地块。 */
    private FarmPlot pickBest(String victimId, String thiefId, List<FarmPlot> plots) {
        FarmPlot best = null;
        int bestValue = 0;
        for (FarmPlot plot : plots) {
            if (plot.getStage() != CropStage.MATURE || plot.getCropType() == null) {
                continue;
            }
            Crop crop = CropRegistry.get(plot.getCropType());
            if (crop == null) {
                continue;
            }
            String plantedAt = String.valueOf(plot.getPlantedAt());
            int remaining = crop.getYieldAmount() - stealRepo.sumStolen(victimId, plot.getIndex(), plantedAt);
            if (remaining <= 0 || stealRepo.hasStolen(victimId, plot.getIndex(), plantedAt, thiefId)) {
                continue;
            }
            int value = remaining * crop.getSellPrice();
            if (value > bestValue) {
                bestValue = value;
                best = plot;
            }
        }
        return best;
    }

    private String firstMatureSample(List<FarmPlot> plots) {
        for (FarmPlot plot : plots) {
            if (plot.getStage() == CropStage.MATURE && plot.getCropType() != null) {
                Crop crop = CropRegistry.get(plot.getCropType());
                if (crop != null) {
                    return crop.getEmoji() + crop.getName();
                }
            }
        }
        return null;
    }

    private long remainingCooldown(String thiefId) {
        Long last = cooldowns.get(thiefId);
        if (last == null) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - last;
        return elapsed >= COOLDOWN_MS ? 0 : (COOLDOWN_MS - elapsed) / 1000L;
    }

    private static int parseIndex(String arg) {
        try {
            return Integer.parseInt(arg.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final class CandidateSet {
        private final List<String> ids;
        private final long listedAt;

        private CandidateSet(List<String> ids, long listedAt) {
            this.ids = ids;
            this.listedAt = listedAt;
        }
    }
}
