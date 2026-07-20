package com.tang.plugin.service.catalog;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.tang.plugin.domain.dto.catalog.TangbuyCatalogProduct;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TangbuyCatalogServiceMallMappingTest {

    @Test
    void fromMallRow_mapsCoreFields_andSkipsOffStatus() {
        JSONObject on = JSON.parseObject("""
                {
                  "itemId": 94418754404416,
                  "itemName": "测试商品",
                  "price": 13.8,
                  "finalPrice": 27.6,
                  "status": "ON",
                  "detailUrl": "https://www.tangbuy.cc/product?dataSource=SHOP&id=94418754404416",
                  "dataSource": "SHOP",
                  "providerShopName": "供应商A",
                  "imageList": ["https://example.com/a.jpg", "https://example.com/b.jpg"]
                }
                """);
        TangbuyCatalogProduct p = TangbuyCatalogService.fromMallRow(on);
        assertNotNull(p);
        assertEquals("94418754404416", p.getCandidateId());
        assertEquals("94418754404416", p.getTangbuyProductId());
        assertEquals("测试商品", p.getTitle());
        assertEquals(new BigDecimal("13.8"), p.getPrice());
        assertEquals("CNY", p.getCurrency());
        assertEquals("https://example.com/a.jpg", p.getImageUrl());
        assertEquals("https://www.tangbuy.cc/product?dataSource=SHOP&id=94418754404416", p.getTangbuyUrl());
        assertEquals("SHOP", p.getUpstreamPlatform());
        assertEquals("供应商A", p.getSupplierShop());
        assertNull(p.getOfferId1688());

        JSONObject off = JSON.parseObject("""
                {"itemId":1,"itemName":"x","price":1,"status":"OFF","imageList":[]}
                """);
        assertNull(TangbuyCatalogService.fromMallRow(off));
    }
}
