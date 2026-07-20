package com.tang.plugin.service.logistics;

import com.tang.plugin.enums.logistics.ClassifySource;
import com.tang.plugin.enums.logistics.LogisticsType;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rule + keyword classifier for product logistics types. Deterministic and auditable — not a black-box
 * LLM. Priority (first match wins among high-risk / special classes): BATTERY_MAGNETIC → BLADE → FOOD
 * → APPAREL → GENERAL. Conflicting special classes fall to OTHER.
 */
@Component
public class LogisticsTypeClassifier {

    public record Result(LogisticsType type, double confidence, List<String> signals, ClassifySource source) {}

    private static final String[][] BATTERY = {
            {"锂电池", "电池", "充电宝", "带电", "含电", "充电仓", "蓄电池"},
            {"lithium", "battery", "power bank", "rechargeable"},
            {"磁吸", "磁力", "带磁"},
            {"magnet", "magnetic"}
    };
    private static final String[][] BLADE = {
            {"刀具", "刀片", "美工刀", "裁纸刀", "折叠刀", "瑞士军刀"},
            {"blade", "knife", "cutter", "scalpel"}
    };
    private static final String[][] FOOD = {
            {"食品", "零食", "饼干", "糖果", "茶叶", "保健品", "坚果", "巧克力"},
            {"food", "snack", "edible", "tea bag", "candy", "chocolate"}
    };
    private static final String[][] APPAREL = {
            {"服装", "连衣裙", "T恤", "外套", "裤子", "短裤", "袜子", "帽子", "防晒帽", "拖鞋", "凉鞋", "靴子", "鞋子"},
            {"apparel", "clothing", "dress", "shirt", "pants", "sock", "hat", "shoe", "sandal", "slipper"}
    };

    public Result classify(String title) {
        String text = StringUtils.defaultString(title).toLowerCase(Locale.ROOT);
        if (StringUtils.isBlank(text)) {
            return new Result(LogisticsType.OTHER, 0.2, List.of("无标题"), ClassifySource.RULE);
        }

        List<Hit> hits = new ArrayList<>();
        collect(hits, text, LogisticsType.BATTERY_MAGNETIC, BATTERY);
        collect(hits, text, LogisticsType.BLADE, BLADE);
        collect(hits, text, LogisticsType.FOOD, FOOD);
        collect(hits, text, LogisticsType.APPAREL, APPAREL);

        if (hits.isEmpty()) {
            return new Result(LogisticsType.GENERAL, 0.55, List.of("未命中特殊品类关键词 → 普货"), ClassifySource.RULE);
        }

        // Distinct special types among hits (apparel is special but lower risk).
        long specialDistinct = hits.stream()
                .map(Hit::type)
                .filter(t -> t != LogisticsType.APPAREL)
                .distinct()
                .count();
        if (specialDistinct >= 2) {
            List<String> signals = hits.stream().map(Hit::signal).distinct().limit(4).toList();
            return new Result(LogisticsType.OTHER, 0.5, signals, ClassifySource.KEYWORD);
        }

        // Priority order among hits.
        LogisticsType winner = pickWinner(hits);
        List<String> signals = hits.stream()
                .filter(h -> h.type == winner)
                .map(Hit::signal)
                .distinct()
                .limit(4)
                .toList();
        double confidence = winner.isHighRisk() ? 0.85 : (winner == LogisticsType.APPAREL ? 0.8 : 0.7);
        ClassifySource source = signals.stream().anyMatch(s -> s.startsWith("词:"))
                ? ClassifySource.KEYWORD : ClassifySource.RULE;
        return new Result(winner, confidence, signals, source);
    }

    private static LogisticsType pickWinner(List<Hit> hits) {
        for (LogisticsType prefer : List.of(
                LogisticsType.BATTERY_MAGNETIC, LogisticsType.BLADE, LogisticsType.FOOD, LogisticsType.APPAREL)) {
            for (Hit h : hits) {
                if (h.type == prefer) {
                    return prefer;
                }
            }
        }
        return hits.get(0).type;
    }

    private static void collect(List<Hit> out, String text, LogisticsType type, String[][] groups) {
        for (String[] group : groups) {
            for (String kw : group) {
                if (text.contains(kw.toLowerCase(Locale.ROOT))) {
                    out.add(new Hit(type, "词:" + kw));
                }
            }
        }
    }

    private record Hit(LogisticsType type, String signal) {}
}
