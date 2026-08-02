package com.tang.plugin.service.bundle;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.bundle.ShopBundleCampaignVO;
import com.tang.plugin.domain.entity.bundle.ShopBundleCampaign;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.domain.query.bundle.ShopByobCampaignSaveReq;
import com.tang.plugin.domain.query.bundle.ShopMixCampaignSaveReq;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.bundle.ShopBundleCampaignRepository;
import com.tang.plugin.service.bundle.component.ShopifyProductBundleComponent;
import com.tang.plugin.service.user.ShopifyStoreAuthService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class ShopBundleCampaignService {

    @Resource
    private ShopBundleCampaignRepository campaignRepository;
    @Resource
    private ShopifyProductBundleComponent bundleComponent;
    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;

    public List<ShopBundleCampaignVO> list(String shopName) {
        List<ShopBundleCampaignVO> out = new ArrayList<>();
        for (ShopBundleCampaign row : campaignRepository.listByShop(shopName)) {
            out.add(toVo(row));
        }
        return out;
    }

    public ShopBundleCampaignVO get(String shopName, String id) {
        ShopBundleCampaign row = campaignRepository.findById(id)
                .orElseThrow(() -> new CustomException("Campaign not found"));
        if (!shopName.equals(row.getShopName())) {
            throw new CustomException("Campaign shop mismatch");
        }
        return toVo(row);
    }

    public ShopBundleCampaignVO saveMix(ShopMixCampaignSaveReq req) {
        if (req == null || StringUtils.isBlank(req.getShopName()) || StringUtils.isBlank(req.getTitle())) {
            throw new CustomException("shopName and title required");
        }
        if (req.getRule() == null) {
            throw new CustomException("rule required");
        }
        List<String> pool = normalizePool(req.getPoolProductIds());
        JSONObject rule = JSON.parseObject(JSON.toJSONString(req.getRule()));
        if (rule == null) throw new CustomException("invalid rule");
        rule.put("kind", "mix_match");
        int minQty = Math.max(2, rule.getIntValue("minQty", 2));
        rule.put("minQty", minQty);
        if (pool.size() < minQty) {
            throw new CustomException("pool must contain at least minQty products");
        }
        for (String pid : pool) {
            if (!shopProductBindingRepository.hasActiveItemBinding(req.getShopName(), pid)) {
                throw new CustomException("All pool products must have an ACTIVE source binding: " + pid);
            }
        }

        String status = StringUtils.defaultIfBlank(req.getStatus(), "ACTIVE").trim().toUpperCase();
        ShopBundleCampaign row;
        Set<String> previousPool = new HashSet<>();
        if (StringUtils.isNotBlank(req.getId())) {
            row = campaignRepository.findById(req.getId())
                    .orElseThrow(() -> new CustomException("Campaign not found"));
            if (!req.getShopName().equals(row.getShopName())) {
                throw new CustomException("Campaign shop mismatch");
            }
            if (!"mix_match".equals(row.getPlayType())) {
                throw new CustomException("Campaign playType mismatch");
            }
            previousPool.addAll(parsePool(row.getPoolJson()));
            row.setTitle(req.getTitle().trim())
                    .setStatus(status)
                    .setRuleJson(rule.toJSONString())
                    .setPoolJson(JSON.toJSONString(pool));
            campaignRepository.update(row);
        } else {
            row = new ShopBundleCampaign()
                    .setId(newId())
                    .setShopName(req.getShopName())
                    .setPlayType("mix_match")
                    .setTitle(req.getTitle().trim())
                    .setStatus(status)
                    .setRuleJson(rule.toJSONString())
                    .setPoolJson(JSON.toJSONString(pool));
            campaignRepository.insert(row);
        }

        ShopifyStoreAuth auth = requireAuth(req.getShopName());
        JSONObject metafieldRule = new JSONObject(rule);
        metafieldRule.put("campaignId", row.getId());
        metafieldRule.put("label", row.getTitle());
        String ruleJson = metafieldRule.toJSONString();

        Set<String> next = new HashSet<>(pool);
        for (String removed : previousPool) {
            if (!next.contains(removed)) {
                bundleComponent.clearMixRuleMetafield(
                        req.getShopName(), auth.getShopDomain(), auth.getAccessToken(), removed);
            }
        }
        if ("ACTIVE".equals(status)) {
            for (String pid : pool) {
                bundleComponent.writeMixRuleMetafield(
                        req.getShopName(), auth.getShopDomain(), auth.getAccessToken(), pid, ruleJson);
            }
        } else {
            for (String pid : pool) {
                bundleComponent.clearMixRuleMetafield(
                        req.getShopName(), auth.getShopDomain(), auth.getAccessToken(), pid);
            }
        }

        JSONObject refs = new JSONObject();
        refs.put("metafieldNamespace", "tangbuy_mix");
        refs.put("metafieldKey", "rule");
        row.setShopifyRefsJson(refs.toJSONString());
        campaignRepository.update(row);
        return toVo(row);
    }

    public ShopBundleCampaignVO saveByob(ShopByobCampaignSaveReq req) {
        if (req == null || StringUtils.isBlank(req.getShopName()) || StringUtils.isBlank(req.getTitle())) {
            throw new CustomException("shopName and title required");
        }
        if (req.getRule() == null) {
            throw new CustomException("rule required");
        }
        JSONObject rule = JSON.parseObject(JSON.toJSONString(req.getRule()));
        if (rule == null) throw new CustomException("invalid rule");
        rule.put("kind", "byob");
        JSONArray slots = rule.getJSONArray("slots");
        if (slots == null || slots.isEmpty()) {
            throw new CustomException("slots required");
        }

        LinkedHashSet<String> allPool = new LinkedHashSet<>();
        for (int i = 0; i < slots.size(); i++) {
            JSONObject slot = slots.getJSONObject(i);
            if (slot == null) continue;
            JSONArray poolArr = slot.getJSONArray("poolProductIds");
            if (poolArr == null) continue;
            for (int j = 0; j < poolArr.size(); j++) {
                String pid = numericId(poolArr.getString(j));
                if (StringUtils.isNotBlank(pid)) allPool.add(pid);
            }
        }
        for (String pid : allPool) {
            if (!shopProductBindingRepository.hasActiveItemBinding(req.getShopName(), pid)) {
                throw new CustomException("Slot pool products must have ACTIVE binding: " + pid);
            }
        }

        String status = StringUtils.defaultIfBlank(req.getStatus(), "DRAFT").trim().toUpperCase();
        ShopBundleCampaign row;
        Set<String> previousPool = new HashSet<>();
        if (StringUtils.isNotBlank(req.getId())) {
            row = campaignRepository.findById(req.getId())
                    .orElseThrow(() -> new CustomException("Campaign not found"));
            if (!req.getShopName().equals(row.getShopName())) {
                throw new CustomException("Campaign shop mismatch");
            }
            if (!"byob".equals(row.getPlayType())) {
                throw new CustomException("Campaign playType mismatch");
            }
            previousPool.addAll(parsePool(row.getPoolJson()));
            row.setTitle(req.getTitle().trim())
                    .setStatus(status)
                    .setRuleJson(rule.toJSONString())
                    .setPoolJson(JSON.toJSONString(new ArrayList<>(allPool)));
            campaignRepository.update(row);
        } else {
            row = new ShopBundleCampaign()
                    .setId(newId())
                    .setShopName(req.getShopName())
                    .setPlayType("byob")
                    .setTitle(req.getTitle().trim())
                    .setStatus(status)
                    .setRuleJson(rule.toJSONString())
                    .setPoolJson(JSON.toJSONString(new ArrayList<>(allPool)));
            campaignRepository.insert(row);
        }

        ShopifyStoreAuth auth = requireAuth(req.getShopName());
        JSONObject metafieldRule = new JSONObject(rule);
        metafieldRule.put("campaignId", row.getId());
        metafieldRule.put("label", row.getTitle());
        metafieldRule.put("status", status);
        String ruleJson = metafieldRule.toJSONString();

        Set<String> next = new HashSet<>(allPool);
        for (String removed : previousPool) {
            if (!next.contains(removed)) {
                bundleComponent.clearByobRuleMetafield(
                        req.getShopName(), auth.getShopDomain(), auth.getAccessToken(), removed);
            }
        }
        for (String pid : allPool) {
            bundleComponent.writeByobRuleMetafield(
                    req.getShopName(), auth.getShopDomain(), auth.getAccessToken(), pid, ruleJson);
        }

        JSONObject refs = new JSONObject();
        refs.put("metafieldNamespace", "tangbuy_byob");
        refs.put("metafieldKey", "rule");
        row.setShopifyRefsJson(refs.toJSONString());
        campaignRepository.update(row);
        return toVo(row);
    }

    public ShopBundleCampaignVO archive(String shopName, String id) {
        ShopBundleCampaign row = campaignRepository.findById(id)
                .orElseThrow(() -> new CustomException("Campaign not found"));
        if (!shopName.equals(row.getShopName())) {
            throw new CustomException("Campaign shop mismatch");
        }
        ShopifyStoreAuth auth = requireAuth(shopName);
        for (String pid : parsePool(row.getPoolJson())) {
            if ("mix_match".equals(row.getPlayType())) {
                bundleComponent.clearMixRuleMetafield(
                        shopName, auth.getShopDomain(), auth.getAccessToken(), pid);
            } else if ("byob".equals(row.getPlayType())) {
                bundleComponent.clearByobRuleMetafield(
                        shopName, auth.getShopDomain(), auth.getAccessToken(), pid);
            }
        }
        campaignRepository.softDelete(shopName, id);
        row.setStatus("ARCHIVED").setDelFlag(1);
        return toVo(row);
    }

    private ShopifyStoreAuth requireAuth(String shopName) {
        return shopifyStoreAuthService.findActiveFreshByShopName(shopName)
                .orElseThrow(() -> new CustomException("Shopify store not authorized: " + shopName));
    }

    private static ShopBundleCampaignVO toVo(ShopBundleCampaign row) {
        List<String> pool = parsePool(row.getPoolJson());
        return new ShopBundleCampaignVO()
                .setId(row.getId())
                .setShopName(row.getShopName())
                .setPlayType(row.getPlayType())
                .setTitle(row.getTitle())
                .setStatus(row.getStatus())
                .setRuleJson(row.getRuleJson())
                .setPoolJson(row.getPoolJson())
                .setShopifyRefsJson(row.getShopifyRefsJson())
                .setLinkedBundleId(row.getLinkedBundleId())
                .setPoolCount(pool.size())
                .setUpdatedAt(row.getUpdatedAt());
    }

    private static List<String> normalizePool(List<String> raw) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (raw != null) {
            for (String id : raw) {
                String n = numericId(id);
                if (StringUtils.isNotBlank(n)) set.add(n);
            }
        }
        return new ArrayList<>(set);
    }

    private static List<String> parsePool(String json) {
        List<String> list = new ArrayList<>();
        if (StringUtils.isBlank(json)) return list;
        try {
            JSONArray arr = JSON.parseArray(json);
            if (arr == null) return list;
            for (int i = 0; i < arr.size(); i++) {
                String n = numericId(arr.getString(i));
                if (StringUtils.isNotBlank(n)) list.add(n);
            }
        } catch (Exception ignored) {
            /* malformed */
        }
        return list;
    }

    private static String newId() {
        return "camp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String numericId(String gidOrId) {
        return ShopifyProductBundleComponent.numericProductId(gidOrId);
    }
}
