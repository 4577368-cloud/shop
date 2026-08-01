package com.tang.plugin.controller.bundle;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.bundle.BundlesFeatureVO;
import com.tang.plugin.domain.dto.bundle.ShopBundleStatusMapVO;
import com.tang.plugin.domain.dto.bundle.ShopBundleVO;
import com.tang.plugin.domain.query.bundle.ShopBundleCreateReq;
import com.tang.plugin.domain.query.bundle.ShopBundleUpdateReq;
import com.tang.plugin.domain.query.bundle.ShopComboSaveReq;
import com.tang.plugin.domain.dto.bundle.ShopComboSaveVO;
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

/**
 * Fixed product bundles — sub-feature of sourcing. Does not change match/publish paths.
 */
@RestController
@RequestMapping("/api/plugin/bundle")
public class BundleController {

    @Resource
    private ShopBundleService shopBundleService;
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
}
