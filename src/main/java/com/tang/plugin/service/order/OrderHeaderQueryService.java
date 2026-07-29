package com.tang.plugin.service.order;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.order.ShopOrderHeaderVO;
import com.tang.plugin.domain.dto.order.ShopOrderLineItemVO;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrder;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrderLine;
import com.tang.plugin.repository.ThirdPlatformOrderLineRepository;
import com.tang.plugin.repository.ThirdPlatformOrderRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class OrderHeaderQueryService {

    @Resource
    private ThirdPlatformOrderRepository thirdPlatformOrderRepository;
    @Resource
    private ThirdPlatformOrderLineRepository thirdPlatformOrderLineRepository;

    public Optional<ThirdPlatformOrder> findByOuterOrderId(String shopName, String outerOrderId) {
        if (StringUtils.isAnyBlank(shopName, outerOrderId)) {
            return Optional.empty();
        }
        return thirdPlatformOrderRepository.findByOuterOrderId(shopName, outerOrderId);
    }

    public List<ThirdPlatformOrder> listByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("listByShop requires shopName");
        }
        return thirdPlatformOrderRepository.listByShop(shopName);
    }

    /** B1: header list with nested lineItems + draftOrderId. */
    public List<ShopOrderHeaderVO> listByShopWithLines(String shopName) {
        List<ThirdPlatformOrder> headers = listByShop(shopName);
        List<ShopOrderHeaderVO> result = new ArrayList<>(headers.size());
        for (ThirdPlatformOrder h : headers) {
            ShopOrderHeaderVO vo = toVo(h);
            List<ThirdPlatformOrderLine> lines =
                    thirdPlatformOrderLineRepository.listByOrder(shopName, h.getOuterOrderId());
            List<ShopOrderLineItemVO> items = new ArrayList<>();
            for (ThirdPlatformOrderLine line : lines) {
                if (line == null) continue;
                items.add(new ShopOrderLineItemVO()
                        .setLineId(line.getLineId())
                        .setTitle(line.getTitle())
                        .setSku(line.getSku())
                        .setQuantity(line.getQuantity())
                        .setImage(line.getPreviewImageUrl())
                        .setVariantId(line.getOuterVariantId())
                        .setPrice(line.getPrice())
                        .setBindingStatus(line.getBindingStatus() == null ? null : line.getBindingStatus().name())
                        .setTangbuySkuId(line.getTangbuySkuId())
                        .setTangbuyProductId(line.getTangbuyProductId()));
            }
            vo.setLineItems(items);
            result.add(vo);
        }
        return result;
    }

    public Optional<ShopOrderHeaderVO> findWithLines(String shopName, String outerOrderId) {
        return findByOuterOrderId(shopName, outerOrderId).map(h -> {
            ShopOrderHeaderVO vo = toVo(h);
            List<ShopOrderLineItemVO> items = new ArrayList<>();
            for (ThirdPlatformOrderLine line : thirdPlatformOrderLineRepository.listByOrder(shopName, outerOrderId)) {
                if (line == null) continue;
                items.add(new ShopOrderLineItemVO()
                        .setLineId(line.getLineId())
                        .setTitle(line.getTitle())
                        .setSku(line.getSku())
                        .setQuantity(line.getQuantity())
                        .setImage(line.getPreviewImageUrl())
                        .setVariantId(line.getOuterVariantId())
                        .setPrice(line.getPrice())
                        .setBindingStatus(line.getBindingStatus() == null ? null : line.getBindingStatus().name())
                        .setTangbuySkuId(line.getTangbuySkuId())
                        .setTangbuyProductId(line.getTangbuyProductId()));
            }
            vo.setLineItems(items);
            return vo;
        });
    }

    private ShopOrderHeaderVO toVo(ThirdPlatformOrder h) {
        ShopOrderHeaderVO vo = new ShopOrderHeaderVO();
        vo.setId(h.getId());
        vo.setShopName(h.getShopName());
        vo.setShopType(h.getShopType());
        vo.setOuterOrderId(h.getOuterOrderId());
        vo.setOrderName(h.getOrderName());
        vo.setFinancialStatus(h.getFinancialStatus());
        vo.setFulfillmentStatus(h.getFulfillmentStatus());
        vo.setCurrency(h.getCurrency());
        vo.setTotalPrice(h.getTotalPrice());
        vo.setPlatformCreatedAt(h.getPlatformCreatedAt());
        vo.setPlatformUpdatedAt(h.getPlatformUpdatedAt());
        vo.setDelFlag(h.getDelFlag());
        vo.setCreatedAt(h.getCreatedAt());
        vo.setUpdatedAt(h.getUpdatedAt());
        vo.setDraftOrderId(h.getDraftOrderId());
        return vo;
    }
}
