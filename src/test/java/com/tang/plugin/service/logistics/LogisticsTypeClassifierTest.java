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
}
