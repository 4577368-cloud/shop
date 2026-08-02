package com.tang.plugin.controller.bundle;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.bundle.BundlesFeatureVO;
import com.tang.plugin.domain.dto.bundle.ShopBundleCampaignVO;
import com.tang.plugin.domain.dto.bundle.ShopBundleStatusMapVO;
import com.tang.plugin.domain.dto.bundle.ShopBundleVO;
import com.tang.plugin.domain.dto.bundle.ShopComboSaveVO;
import com.tang.plugin.domain.dto.bundle.ShopGiftSaveVO;
import com.tang.plugin.domain.query.bundle.ShopBundleCreateReq;
import com.tang.plugin.domain.query.bundle.ShopBundleUpdateReq;
import com.tang.plugin.domain.query.bundle.ShopByobCampaignSaveReq;
import com.tang.plugin.domain.query.bundle.ShopComboSaveReq;
import com.tang.plugin.domain.query.bundle.ShopGiftSaveReq;
import com.tang.plugin.domain.query.bundle.ShopMixCampaignSaveReq;
import com.tang.plugin.service.bundle.ShopBundleCampaignService;
import com.tang.plugin.service.bundle.ShopBundleService;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed product bundles — sub-feature of sourcing. Does not change match/publish paths.
 */
@RestController
@RequestMapping("/api/plugin/bundle")
public class BundleController {

    @Resource
    private ShopBundleService shopBundleService;
    @Resource
    private ShopBundleCampaignService shopBundleCampaignService;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @GetMapping("/feature")
    public BundlesFeatureVO feature(HttpServletRequest request,
                                    @RequestParam("shopName") String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return shopBundleService.feature(shopName);
    }

    @GetMapping("/status-map")
    public ShopBundleStatusMapVO statusMap(HttpServletRequest request,
                                           @RequestParam("shopName") String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return shopBundleService.statusMap(shopName);
    }

    /** Bundle Hub campaign routes (literal paths before /{id}). */
    @GetMapping("/campaign/list")
    public Map<String, Object> campaignList(HttpServletRequest request,
                                            @RequestParam("shopName") String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        List<ShopBundleCampaignVO> items = shopBundleCampaignService.list(shopName);
        Map<String, Object> res = new HashMap<>();
        res.put("items", items);
        return res;
    }

    @GetMapping("/campaign/{id}")
    public ShopBundleCampaignVO campaignGet(HttpServletRequest request,
                                            @PathVariable("id") String id,
                                            @RequestParam("shopName") String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return shopBundleCampaignService.get(shopName, id);
    }

    @PostMapping("/campaign/mix/save")
    public ShopBundleCampaignVO saveMixCampaign(HttpServletRequest request,
                                                @RequestBody ShopMixCampaignSaveReq body) {
        if (body == null || StringUtils.isBlank(body.getShopName())) {
            throw new CustomException("shopName required");
        }
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), body.getShopName());
        return shopBundleCampaignService.saveMix(body);
    }

    @PostMapping("/campaign/byob/save")
    public ShopBundleCampaignVO saveByobCampaign(HttpServletRequest request,
                                                 @RequestBody ShopByobCampaignSaveReq body) {
        if (body == null || StringUtils.isBlank(body.getShopName())) {
            throw new CustomException("shopName required");
        }
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), body.getShopName());
        return shopBundleCampaignService.saveByob(body);
    }

    @PostMapping("/campaign/{id}/archive")
    public ShopBundleCampaignVO archiveCampaign(HttpServletRequest request,
                                                @PathVariable("id") String id,
                                                @RequestParam("shopName") String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return shopBundleCampaignService.archive(shopName, id);
    }

    @GetMapping("/{id}")
    public ShopBundleVO get(HttpServletRequest request,
                            @PathVariable("id") Long id,
                            @RequestParam("shopName") String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return shopBundleService.getById(shopName, id);
    }

    @PostMapping("/create")
    public ShopBundleVO create(HttpServletRequest request, @RequestBody ShopBundleCreateReq body) {
        if (body == null || StringUtils.isBlank(body.getShopName())) {
            throw new CustomException("shopName required");
        }
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), body.getShopName());
        return shopBundleService.createAndWait(body);
    }

    @PostMapping("/update")
    public ShopBundleVO update(HttpServletRequest request, @RequestBody ShopBundleUpdateReq body) {
        if (body == null || StringUtils.isBlank(body.getShopName())) {
            throw new CustomException("shopName required");
        }
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), body.getShopName());
        return shopBundleService.updateAndWait(body);
    }

    @PostMapping("/{id}/dissolve")
    public ShopBundleVO dissolve(HttpServletRequest request,
                                 @PathVariable("id") Long id,
                                 @RequestParam("shopName") String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return shopBundleService.dissolve(shopName, id);
    }

    /**
     * Track B — save same-product combo on the original product (no Fixed Bundle parent).
     */
    @PostMapping("/combo/save")
    public ShopComboSaveVO saveCombo(HttpServletRequest request, @RequestBody ShopComboSaveReq body) {
        if (body == null || StringUtils.isBlank(body.getShopName())) {
            throw new CustomException("shopName required");
        }
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), body.getShopName());
        return shopBundleService.saveSameProductCombo(body);
    }

    /**
     * Gift rule — separate from kit composer. Writes tangbuy_gift.rule on the trigger product.
     */
    @PostMapping("/gift/save")
    public ShopGiftSaveVO saveGift(HttpServletRequest request, @RequestBody ShopGiftSaveReq body) {
        if (body == null || StringUtils.isBlank(body.getShopName())) {
            throw new CustomException("shopName required");
        }
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), body.getShopName());
        return shopBundleService.saveGiftRule(body);
    }
}
