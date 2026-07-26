package com.tang.plugin.service.match.sku;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight color/size synonym expansion for {@link SkuMatcher} — aligned with frontend
 * spec-match seed data (subset). Expands tokens before overlap checks.
 */
final class SkuTokenAliases {

    private static final Map<String, String> COLOR_CANON = Map.ofEntries(
            Map.entry("红", "red"),
            Map.entry("红色", "red"),
            Map.entry("red", "red"),
            Map.entry("蓝", "blue"),
            Map.entry("蓝色", "blue"),
            Map.entry("blue", "blue"),
            Map.entry("黑", "black"),
            Map.entry("黑色", "black"),
            Map.entry("black", "black"),
            Map.entry("白", "white"),
            Map.entry("白色", "white"),
            Map.entry("white", "white"),
            Map.entry("酒红", "wine_red"),
            Map.entry("酒红色", "wine_red"),
            Map.entry("winered", "wine_red"),
            Map.entry("burgundy", "wine_red"),
            Map.entry("雾霾蓝", "haze_blue"),
            Map.entry("雾蓝", "haze_blue"),
            Map.entry("hazeblue", "haze_blue"),
            Map.entry("脏粉", "dusty_pink"),
            Map.entry("脏粉色", "dusty_pink"),
            Map.entry("dustypink", "dusty_pink"),
            Map.entry("银灰", "silver_grey"),
            Map.entry("silvergrey", "silver_grey"),
            Map.entry("silvergray", "silver_grey")
    );

    private SkuTokenAliases() {
    }

    static Set<String> expand(String raw) {
        Set<String> out = new LinkedHashSet<>();
        String n = SkuMatcher.normalize(raw);
        if (n.isEmpty()) {
            return out;
        }
        out.add(n);
        String canon = COLOR_CANON.get(n);
        if (canon != null) {
            out.add(canon);
        }
        return out;
    }

    static boolean tokensOverlap(String left, String right) {
        Set<String> a = expand(left);
        Set<String> b = expand(right);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        for (String x : a) {
            for (String y : b) {
                if (x.equals(y)) {
                    return true;
                }
                if (x.length() >= 2 && y.contains(x)) {
                    return true;
                }
                if (y.length() >= 2 && x.contains(y)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean hitsAnyExpanded(String opt, Set<String> skuTokens) {
        for (String s : skuTokens) {
            if (tokensOverlap(opt, s)) {
                return true;
            }
        }
        return false;
    }
}
