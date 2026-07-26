package com.tang.plugin.service.logistics;

import com.tang.plugin.enums.logistics.LogisticsType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticsTypeClassifierTest {

    private final LogisticsTypeClassifier classifier = new LogisticsTypeClassifier();

    @Test
    void generalWhenNoKeyword() {
        var r = classifier.classify("跨境 ONIKUMA CW905无线游戏鼠标");
        assertEquals(LogisticsType.GENERAL, r.type());
    }

    @Test
    void apparelHat() {
        var r = classifier.classify("夏季婴幼儿防晒帽 男童女童薄款空顶鹿角遮阳帽");
        assertEquals(LogisticsType.APPAREL, r.type());
        assertTrue(r.confidence() >= 0.7);
    }

    @Test
    void batteryPowerBank() {
        var r = classifier.classify("20000mAh 充电宝 快充移动电源");
        assertEquals(LogisticsType.BATTERY_MAGNETIC, r.type());
    }

    @Test
    void bladeKnife() {
        var r = classifier.classify("户外折叠刀 求生刀具");
        assertEquals(LogisticsType.BLADE, r.type());
    }

    @Test
    void foodSnack() {
        var r = classifier.classify("网红零食大礼包 糖果饼干");
        assertEquals(LogisticsType.FOOD, r.type());
    }

    // ── 新增品类测试 ──────────────────────────────────────────

    @Test
    void liquidPerfume() {
        var r = classifier.classify("法国进口香水 50ml 持久留香");
        assertEquals(LogisticsType.LIQUID, r.type());
    }

    @Test
    void powderMatcha() {
        var r = classifier.classify("日本宇治抹茶粉 100g 烘焙原料");
        assertEquals(LogisticsType.POWDER, r.type());
    }

    @Test
    void fragileCeramic() {
        var r = classifier.classify("陶瓷茶杯 日式手绘马克杯");
        assertEquals(LogisticsType.FRAGILE, r.type());
    }

    @Test
    void cosmeticLipstick() {
        var r = classifier.classify("哑光口红 持久不脱色 女生唇膏");
        assertEquals(LogisticsType.COSMETIC, r.type());
    }

    @Test
    void batteryMagneticKeyword() {
        var r = classifier.classify("手机壳 磁吸 MagSafe 保护套");
        assertEquals(LogisticsType.BATTERY_MAGNETIC, r.type());
    }

    // ── SKU 级分类测试 ──────────────────────────────────────────

    @Test
    void skuLevelMagneticOverride() {
        // 商品标题不含电池/磁吸关键词 → GENERAL
        var productLevel = classifier.classify("手机壳 iPhone 15 Pro 保护套");
        assertEquals(LogisticsType.GENERAL, productLevel.type());

        // SKU 属性含"磁吸" → BATTERY_MAGNETIC
        var skuLevel = classifier.classify("手机壳 iPhone 15 Pro 保护套", "颜色:透明 款式:磁吸款");
        assertEquals(LogisticsType.BATTERY_MAGNETIC, skuLevel.type());
        assertTrue(skuLevel.signals().stream().anyMatch(s -> s.startsWith("SKU:")));
    }

    @Test
    void skuLevelNoOverrideWhenSameAsProduct() {
        // 商品标题已命中电池，SKU 属性也含电池 → 不需要覆盖，但仍命中
        var r = classifier.classify("充电宝 移动电源", "颜色:黑色 容量:20000mAh 充电");
        assertEquals(LogisticsType.BATTERY_MAGNETIC, r.type());
    }

    @Test
    void skuLevelLiquidOverride() {
        var r = classifier.classify("护肤套装", "类型:液体款 含液");
        assertEquals(LogisticsType.LIQUID, r.type());
    }

    @Test
    void conflictingSpecialTypesFallToOther() {
        var r = classifier.classify("带电液体香水");
        assertEquals(LogisticsType.OTHER, r.type());
    }
}
