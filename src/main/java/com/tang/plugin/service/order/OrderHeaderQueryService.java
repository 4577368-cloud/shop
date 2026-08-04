package com.tang.plugin.service.order;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.order.ShopOrderHeaderVO;
import com.tang.plugin.domain.dto.order.ShopOrderLineItemVO;
import com.tang.plugin.domain.dto.order.ShopOrderShippingAddressVO;
import com.tang.plugin.domain.entity.order.TDraftOrderAddressDO;
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
    @Resource
    private DraftOrderAssembler draftOrderAssembler;

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

    /** B1: header list with nested lineItems + draftOrderId + shippingAddress. */
    public List<ShopOrderHeaderVO> listByShopWithLines(String shopName) {
        List<ThirdPlatformOrder> headers = listByShop(shopName);
        List<ShopOrderHeaderVO> result = new ArrayList<>(headers.size());
        for (ThirdPlatformOrder h : headers) {
            result.add(enrich(h));
        }
        return result;
    }

    public Optional<ShopOrderHeaderVO> findWithLines(String shopName, String outerOrderId) {
        return findByOuterOrderId(shopName, outerOrderId).map(this::enrich);
    }

    public ShopOrderShippingAddressVO updateShippingAddress(String shopName, String outerOrderId,
                                                            Long userId, ShopOrderShippingAddressVO patch) {
        ThirdPlatformOrder header = findByOuterOrderId(shopName, outerOrderId)
                .orElseThrow(() -> new CustomException("Order not found", 404, "ORDER_NOT_FOUND"));
        Long draftId = header.getDraftOrderId();
        if (draftId == null) {
            throw new CustomException("Draft order missing for shipping address", 409, "DRAFT_MISSING");
        }
        TDraftOrderAddressDO row = new TDraftOrderAddressDO()
                .setEmail(patch.getEmail())
                .setFirstName(patch.getFirstName())
                .setLastName(patch.getLastName())
                .setName(patch.getName())
                .setCompany(patch.getCompany())
                .setPhone(patch.getPhone())
                .setAddress1(patch.getAddress1())
                .setAddress2(patch.getAddress2())
                .setCity(patch.getCity())
                .setZip(patch.getZip())
                .setProvince(patch.getProvince())
                .setCountry(StringUtils.defaultIfBlank(patch.getCountry(), patch.getCountryCode()))
                .setCountryCode(patch.getCountryCode());
        TDraftOrderAddressDO saved = draftOrderAssembler.upsertAddressFields(draftId, userId, row);
        return toAddressVo(draftId, saved);
    }

    private ShopOrderHeaderVO enrich(ThirdPlatformOrder h) {
        ShopOrderHeaderVO vo = toVo(h);
        List<ShopOrderLineItemVO> items = new ArrayList<>();
        for (ThirdPlatformOrderLine line :
                thirdPlatformOrderLineRepository.listByOrder(h.getShopName(), h.getOuterOrderId())) {
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
        if (h.getDraftOrderId() != null) {
            draftOrderAssembler.findAddressByDraftOrderId(h.getDraftOrderId())
                    .ifPresent(addr -> vo.setShippingAddress(toAddressVo(h.getDraftOrderId(), addr)));
        }
        return vo;
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

    static ShopOrderShippingAddressVO toAddressVo(Long draftOrderId, TDraftOrderAddressDO addr) {
        ShopOrderShippingAddressVO vo = new ShopOrderShippingAddressVO()
                .setDraftOrderId(draftOrderId)
                .setEmail(addr.getEmail())
                .setFirstName(addr.getFirstName())
                .setLastName(addr.getLastName())
                .setName(addr.getName())
                .setCompany(addr.getCompany())
                .setPhone(addr.getPhone())
                .setAddress1(addr.getAddress1())
                .setAddress2(addr.getAddress2())
                .setCity(addr.getCity())
                .setProvince(addr.getProvince())
                .setZip(addr.getZip())
                .setCountry(addr.getCountry())
                .setCountryCode(addr.getCountryCode());
        vo.setIncomplete(isIncomplete(vo));
        return vo;
    }

    static boolean isIncomplete(ShopOrderShippingAddressVO vo) {
        if (vo == null) return true;
        String joined = (StringUtils.trimToEmpty(vo.getFirstName()) + " "
                + StringUtils.trimToEmpty(vo.getLastName())).trim();
        String displayName = StringUtils.isNotBlank(vo.getName()) ? vo.getName()
                : (StringUtils.isNotBlank(joined) ? joined : null);
        return StringUtils.isAnyBlank(
                displayName,
                vo.getAddress1(),
                vo.getCity(),
                vo.getCountryCode(),
                vo.getPhone());
    }
}
