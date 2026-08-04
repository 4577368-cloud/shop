package com.tang.plugin.controller.order;

import com.tang.plugin.domain.dto.order.ShopOrderHeaderVO;
import com.tang.plugin.domain.dto.order.ShopOrderShippingAddressVO;
import com.tang.plugin.service.order.OrderHeaderQueryService;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/plugin/order/header")
public class OrderHeaderAdminController {

    @Resource
    private OrderHeaderQueryService orderHeaderQueryService;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @GetMapping("/get")
    public ShopOrderHeaderVO findByOuterOrderId(HttpServletRequest request,
                                                @RequestParam String shopName,
                                                @RequestParam String outerOrderId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return orderHeaderQueryService.findWithLines(shopName, outerOrderId).orElse(null);
    }

    @GetMapping("/list")
    public List<ShopOrderHeaderVO> listByShop(HttpServletRequest request,
                                              @RequestParam String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return orderHeaderQueryService.listByShopWithLines(shopName);
    }

    /**
     * Supplement / correct Shopify shipping recipient for international logistics.
     * Does not write back to Shopify Admin — local draft address only.
     */
    @PutMapping("/shipping-address")
    public ShopOrderShippingAddressVO updateShippingAddress(HttpServletRequest request,
                                                            @RequestParam String shopName,
                                                            @RequestParam String outerOrderId,
                                                            @RequestBody ShopOrderShippingAddressVO body) {
        Long userId = (Long) request.getAttribute("userId");
        shopAccessGuard.assertOwner(userId, shopName);
        return orderHeaderQueryService.updateShippingAddress(shopName, outerOrderId, userId, body);
    }
}
