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
 * LLM. Priority (first match wins among high-risk / special classes):
 * BATTERY_MAGNETIC → LIQUID → POWDER → BLADE → FOOD → COSMETIC → FRAGILE → APPAREL → GENERAL.
 * Conflicting special classes fall to OTHER.
 *
 * Supports both product-level (title only) and SKU-level (title + skuAttrText) classification.
 * When skuAttrText is provided, SKU-level signals are merged with product-level signals,
 * and the highest-risk type wins.
 */
@Component
public class LogisticsTypeClassifier {

    public record Result(LogisticsType type, double confidence, List<String> signals, ClassifySource source) {}

    // ── 关键词库（扩充版） ──────────────────────────────────────────

    private static final String[][] BATTERY = {
            {"锂电池", "电池", "充电宝", "带电", "含电", "充电仓", "蓄电池", "移动电源", "电芯", "纽扣电池", "干电池"},
            {"lithium", "battery", "power bank", "rechargeable", "18650", "aa battery", "aaa battery"},
            {"磁吸", "磁力", "带磁", "磁铁", "磁石", "强磁"},
            {"magnet", "magnetic"}
    };
    private static final String[][] BLADE = {
            {"刀具", "刀片", "美工刀", "裁纸刀", "折叠刀", "瑞士军刀", "匕首", "菜刀", "水果刀", "剪刀", "剃须刀", "剃刀"},
            {"blade", "knife", "cutter", "scalpel", "razor", "scissor"}
    };
    private static final String[][] FOOD = {
            {"食品", "零食", "饼干", "糖果", "茶叶", "保健品", "坚果", "巧克力", "咖啡豆", "蜂蜜", "奶粉", "蛋白粉", "维他命", "维生素"},
            {"food", "snack", "edible", "tea bag", "candy", "chocolate", "honey", "protein powder", "vitamin", "supplement"}
    };
    private static final String[][] APPAREL = {
            {"服装", "连衣裙", "T恤", "外套", "裤子", "短裤", "袜子", "帽子", "防晒帽", "拖鞋", "凉鞋", "靴子", "鞋子",
                    "内衣", "文胸", "纸尿裤", "围巾", "手套", "羽绒服", "卫衣", "衬衫", "半身裙", "背心"},
            {"apparel", "clothing", "dress", "shirt", "pants", "sock", "hat", "shoe", "sandal", "slipper",
                    "underwear", "glove", "scarf", "coat", "jacket"}
    };
    private static final String[][] LIQUID = {
            {"液体", "香水", "精油", "爽肤水", "卸妆水", "洗手液", "洗洁精", "洗衣液", "墨水", "胶水", "溶剂",
                    "饮料", "果汁", "酒", "白酒", "红酒", "酱油", "醋", "消毒液", "漂白剂"},
            {"liquid", "perfume", "essential oil", "toner", "cleansing water", "ink", "glue", "solvent",
                    "beverage", "juice", "wine", "soy sauce", "detergent", "bleach"}
    };
    private static final String[][] POWDER = {
            {"粉末", "面粉", "淀粉", "胡椒粉", "辣椒粉", "抹茶粉", "可可粉", "奶粉", "蛋白粉", "爽身粉",
                    "散粉", "粉底", "痱子粉", "小苏打"},
            {"powder", "flour", "starch", "pepper powder", "matcha powder", "cocoa powder",
                    "talcum powder", "baking soda"}
    };
    private static final String[][] FRAGILE = {
            {"玻璃", "陶瓷", "瓷器", "易碎", "镜片", "水杯", "花瓶", "灯泡", "灯管", "屏幕", "显示器", "相框"},
            {"glass", "ceramic", "fragile", "mirror", "vase", "bulb", "screen", "monitor", "frame"}
    };
    private static final String[][] COSMETIC = {
            {"化妆品", "口红", "唇膏", "眼影", "粉底液", "遮瑕", "腮红", "指甲油", "卸妆", "防晒霜",
                    "面霜", "乳液", "精华", "面膜", "润肤", "护手霜", "身体乳"},
            {"cosmetic", "lipstick", "eyeshadow", "foundation", "concealer", "blush", "nail polish",
                    "sunscreen", "moisturizer", "serum", "mask", "hand cream", "body lotion"}
    };

    /** SKU 属性文本专用关键词（检测 SKU 规格中的特殊属性，如"磁吸款"、"含电池款"）。 */
    private static final String[][] SKU_ATTR_BATTERY = {
            {"磁吸", "带磁", "磁铁", "含电", "带电", "电池", "充电", "无线充"},
            {"magnetic", "battery", "rechargeable", "wireless charg"}
    };
    private static final String[][] SKU_ATTR_LIQUID = {
            {"液体", "液体款", "含液", "带液"},
            {"liquid"}
    };
    private static final String[][] SKU_ATTR_POWDER = {
            {"粉末", "粉状"},
            {"powder"}
    };

    /** Product-level classify (title only) — backward compatible. */
    public Result classify(String title) {
        return classify(title, null);
    }

    /**
     * SKU-level classify: merge product-title signals with SKU-attribute signals.
     * If SKU attributes hit a higher-risk type than the title, the SKU type wins.
     *
     * @param title       商品标题
     * @param skuAttrText SKU 属性文本（如 "颜色:红色 尺码:L 款式:磁吸款"），可为 null
     */
    public Result classify(String title, String skuAttrText) {
        String titleText = StringUtils.defaultString(title).toLowerCase(Locale.ROOT);
        String attrText = StringUtils.defaultString(skuAttrText).toLowerCase(Locale.ROOT);

        if (StringUtils.isBlank(titleText) && StringUtils.isBlank(attrText)) {
            return new Result(LogisticsType.OTHER, 0.2, List.of("无标题且无SKU属性"), ClassifySource.RULE);
        }

        List<Hit> hits = new ArrayList<>();
        collect(hits, titleText, LogisticsType.BATTERY_MAGNETIC, BATTERY);
        collect(hits, titleText, LogisticsType.LIQUID, LIQUID);
        collect(hits, titleText, LogisticsType.POWDER, POWDER);
        collect(hits, titleText, LogisticsType.BLADE, BLADE);
        collect(hits, titleText, LogisticsType.FOOD, FOOD);
        collect(hits, titleText, LogisticsType.COSMETIC, COSMETIC);
        collect(hits, titleText, LogisticsType.FRAGILE, FRAGILE);
        collect(hits, titleText, LogisticsType.APPAREL, APPAREL);

        // SKU 属性信号叠加
        if (StringUtils.isNotBlank(attrText)) {
            collect(hits, attrText, LogisticsType.BATTERY_MAGNETIC, SKU_ATTR_BATTERY, "SKU:");
            collect(hits, attrText, LogisticsType.LIQUID, SKU_ATTR_LIQUID, "SKU:");
            collect(hits, attrText, LogisticsType.POWDER, SKU_ATTR_POWDER, "SKU:");
        }

        if (hits.isEmpty()) {
            return new Result(LogisticsType.GENERAL, 0.55, List.of("未命中特殊品类关键词 → 普货"), ClassifySource.RULE);
        }

        // Distinct special types among hits (apparel is special but lower risk).
        long specialDistinct = hits.stream()
                .map(Hit::type)
                .filter(t -> t != LogisticsType.APPAREL && t != LogisticsType.FRAGILE)
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
        ClassifySource source = signals.stream().anyMatch(s -> s.startsWith("词:") || s.startsWith("SKU:"))
                ? ClassifySource.KEYWORD : ClassifySource.RULE;
        return new Result(winner, confidence, signals, source);
    }

    private static LogisticsType pickWinner(List<Hit> hits) {
        for (LogisticsType prefer : List.of(
                LogisticsType.BATTERY_MAGNETIC, LogisticsType.LIQUID, LogisticsType.POWDER,
                LogisticsType.BLADE, LogisticsType.FOOD, LogisticsType.COSMETIC,
                LogisticsType.FRAGILE, LogisticsType.APPAREL)) {
            for (Hit h : hits) {
                if (h.type == prefer) {
                    return prefer;
                }
            }
        }
        return hits.get(0).type;
    }

    private static void collect(List<Hit> out, String text, LogisticsType type, String[][] groups) {
        collect(out, text, type, groups, "词:");
    }

    private static void collect(List<Hit> out, String text, LogisticsType type, String[][] groups, String prefix) {
        for (String[] group : groups) {
            for (String kw : group) {
                if (text.contains(kw.toLowerCase(Locale.ROOT))) {
                    out.add(new Hit(type, prefix + kw));
                }
            }
        }
    }

    private record Hit(LogisticsType type, String signal) {}
}
