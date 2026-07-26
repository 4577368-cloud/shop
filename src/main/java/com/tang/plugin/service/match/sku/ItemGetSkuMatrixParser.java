package com.tang.plugin.service.match.sku;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.plugin.domain.dto.match.sku.OfferSkuAttributeVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuVO;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Tangbuy itemGet {@code productSkus} → {@link OfferSkuVO} matrix for {@link SkuMatcher}. */
public final class ItemGetSkuMatrixParser {

    private ItemGetSkuMatrixParser() {
    }

    public static List<OfferSkuVO> parseOfferSkus(JSONArray skus) {
        List<OfferSkuVO> out = new ArrayList<>();
        if (skus == null || skus.isEmpty()) {
            return out;
        }
        for (int i = 0; i < skus.size(); i++) {
            JSONObject sku = skus.getJSONObject(i);
            if (sku == null) {
                continue;
            }
            String skuId = sku.getString("skuId");
            if (StringUtils.isBlank(skuId)) {
                continue;
            }
            OfferSkuVO vo = new OfferSkuVO()
                    .setSkuId(skuId.trim())
                    .setSkuAttributes(parseAttributes(sku.getJSONArray("skuAttributes")));
            if (sku.get("price") != null) {
                vo.setPrice(String.valueOf(sku.get("price")));
            }
            out.add(vo);
        }
        return out;
    }

    private static List<OfferSkuAttributeVO> parseAttributes(JSONArray attrs) {
        List<OfferSkuAttributeVO> list = new ArrayList<>();
        if (attrs == null) {
            return list;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < attrs.size(); i++) {
            JSONObject attr = attrs.getJSONObject(i);
            if (attr == null) {
                continue;
            }
            String value = StringUtils.firstNonBlank(
                    attr.getString("attrValueTrans"),
                    attr.getString("attrValue"),
                    attr.getString("value"),
                    attr.getString("valueTrans"));
            if (StringUtils.isBlank(value) || !seen.add(value.trim())) {
                continue;
            }
            list.add(new OfferSkuAttributeVO()
                    .setAttributeName(StringUtils.firstNonBlank(
                            attr.getString("attrNameTrans"),
                            attr.getString("attrName"),
                            attr.getString("attributeName")))
                    .setValue(value.trim())
                    .setValueTrans(attr.getString("attrValueTrans")));
        }
        return list;
    }
}
