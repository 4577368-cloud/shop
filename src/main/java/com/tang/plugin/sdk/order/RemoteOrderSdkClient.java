package com.tang.plugin.sdk.order;

import com.tang.common.core.domain.R;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.client.order.RemoteOrderFeignClient;
import com.tang.plugin.client.order.dto.UniOrderAddressDTO;
import com.tang.plugin.client.order.dto.UniOrderCreateBodyDTO;
import com.tang.plugin.client.order.dto.UniOrderCreateDTO;
import com.tang.plugin.client.order.dto.UniOrderItemCreateDTO;
import com.tang.plugin.domain.dto.order.UniOrderCreateResDTO;
import com.tang.plugin.domain.entity.order.TDraftOrderAddressDO;
import com.tang.plugin.domain.entity.order.TDraftOrderDO;
import com.tang.plugin.domain.entity.order.TDraftOrderLineDO;
import com.tang.plugin.domain.entity.order.TOrderLinePurchaseDO;
import com.tang.plugin.domain.query.order.DraftOrderPackageCreateReq;
import com.tang.plugin.mapper.order.TDraftOrderAddressMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bridge to tang-order RemoteOrderService#uniOrder.
 * When remote is disabled or Feign bean absent, returns a local stub tradeNo for local E2E.
 */
@Slf4j
@Component
public class RemoteOrderSdkClient {

    @Value("${tang.plugin.remote.order.enabled:false}")
    private boolean remoteEnabled;

    @Resource
    private ObjectProvider<RemoteOrderFeignClient> remoteOrderFeignClient;
    @Resource
    private TDraftOrderAddressMapper draftOrderAddressMapper;

    public UniOrderCreateResDTO uniOrder(TDraftOrderDO order,
                                         List<TDraftOrderLineDO> lines,
                                         List<TOrderLinePurchaseDO> purchases,
                                         DraftOrderPackageCreateReq packageCreateInfo) {
        if (remoteEnabled) {
            RemoteOrderFeignClient feign = remoteOrderFeignClient.getIfAvailable();
            if (feign == null) {
                throw new CustomException(
                        "Remote order enabled but Feign client missing; set tang.plugin.remote.order.url");
            }
            UniOrderCreateBodyDTO body = buildBody(order, lines, purchases, packageCreateInfo);
            try {
                R<UniOrderCreateResDTO> res = feign.uniOrder(body);
                if (res == null || !res.isSuccess() || res.getData() == null
                        || StringUtils.isBlank(res.getData().getTradeNo())) {
                    throw new CustomException("error create order."
                            + (res == null ? "" : StringUtils.defaultString(res.getMsg())));
                }
                log.info("Remote uniOrder ok draftId={} tradeNo={}",
                        order.getId(), res.getData().getTradeNo());
                return res.getData();
            } catch (CustomException e) {
                throw e;
            } catch (Exception e) {
                log.error("Remote uniOrder failed draftId={}", order.getId(), e);
                throw new CustomException("error create order." + e.getMessage());
            }
        }
        return stub(order, purchases);
    }

    private UniOrderCreateBodyDTO buildBody(TDraftOrderDO order,
                                            List<TDraftOrderLineDO> lines,
                                            List<TOrderLinePurchaseDO> purchases,
                                            DraftOrderPackageCreateReq packageCreateInfo) {
        Map<Long, TDraftOrderLineDO> lineMap = lines.stream()
                .collect(Collectors.toMap(TDraftOrderLineDO::getId, l -> l, (a, b) -> a));
        Map<String, List<TOrderLinePurchaseDO>> byShop = purchases.stream()
                .collect(Collectors.groupingBy(p -> StringUtils.defaultIfBlank(p.getThirdShopId(), "DEFAULT")));

        TDraftOrderAddressDO address = draftOrderAddressMapper.selectOne(
                new LambdaQueryWrapper<TDraftOrderAddressDO>()
                        .eq(TDraftOrderAddressDO::getOrderId, order.getId())
                        .eq(TDraftOrderAddressDO::getDelFlag, 0)
                        .last("LIMIT 1"));

        List<UniOrderCreateDTO> orders = new ArrayList<>();
        for (Map.Entry<String, List<TOrderLinePurchaseDO>> e : byShop.entrySet()) {
            List<TOrderLinePurchaseDO> group = e.getValue();
            TOrderLinePurchaseDO first = group.get(0);
            UniOrderCreateDTO dto = new UniOrderCreateDTO()
                    .setOuterOrderNo(String.valueOf(order.getId()))
                    .setOrderLanguage(StringUtils.defaultIfBlank(order.getLanguage(), "en"))
                    .setLang(StringUtils.defaultIfBlank(order.getLanguage(), "en"))
                    .setDevice("pc")
                    .setCurrency("CNY")
                    .setDestination(order.getCountry())
                    .setDataSource(first.getDataSource())
                    .setStoreSource(first.getProviderType())
                    .setStoreId(e.getKey())
                    .setShopName(first.getShopName())
                    .setShopUrl(first.getShopUrl());
            if (address != null) {
                dto.setAddress(new UniOrderAddressDTO()
                        .setEmail(address.getEmail())
                        .setFirstName(address.getFirstName())
                        .setLastName(address.getLastName())
                        .setName(address.getName())
                        .setCompany(address.getCompany())
                        .setPhone(address.getPhone())
                        .setAddress1(address.getAddress1())
                        .setAddress2(address.getAddress2())
                        .setCity(address.getCity())
                        .setZip(address.getZip())
                        .setProvince(address.getProvince())
                        .setCountry(address.getCountry())
                        .setCountryCode(address.getCountryCode()));
                dto.setDestination(StringUtils.defaultIfBlank(address.getCountryCode(), address.getCountry()));
            }
            BigDecimal total = BigDecimal.ZERO;
            List<UniOrderItemCreateDTO> items = new ArrayList<>();
            for (TOrderLinePurchaseDO p : group) {
                TDraftOrderLineDO line = lineMap.get(p.getOrderLineId());
                UniOrderItemCreateDTO item = new UniOrderItemCreateDTO()
                        .setDataSource(p.getDataSource())
                        .setOrderLanguage(dto.getOrderLanguage())
                        .setActPrice(p.getPrice())
                        .setUnitPrice(p.getPrice())
                        .setGoodsId(p.getGoodsId())
                        .setGoodsImg(p.getGoodsImg())
                        .setGoodsName(p.getGoodsName())
                        .setSkuId(p.getSkuId())
                        .setNums(p.getNums())
                        .setDiscountAmount(p.getDiscountAmount() == null ? BigDecimal.ZERO : p.getDiscountAmount())
                        .setPluginOrderLineId(p.getOrderLineId())
                        .setPluginOrderPurchaseLineId(p.getId())
                        .setSaleType(1)
                        .setShowWay(0)
                        .setGoodsType(line == null || line.getGoodsType() == null ? 0 : line.getGoodsType())
                        .setUseStockFlag(false);
                items.add(item);
                if (p.getPurchaseAmount() != null) {
                    total = total.add(p.getPurchaseAmount());
                } else if (p.getPrice() != null && p.getNums() != null) {
                    total = total.add(p.getPrice().multiply(BigDecimal.valueOf(p.getNums())));
                }
            }
            dto.setOrderItems(items).setTotalAmount(total);
            orders.add(dto);
        }

        UniOrderCreateBodyDTO body = new UniOrderCreateBodyDTO()
                .setPluginOrderIds(List.of(order.getId()))
                .setOrders(orders)
                .setLang(StringUtils.defaultIfBlank(order.getLanguage(), "en"))
                .setCurrency("CNY")
                .setDevice("pc")
                .setPluginType(order.getChannel())
                .setSaleType(1)
                .setMaterialFlag(false);
        if (packageCreateInfo != null && packageCreateInfo.getLineId() != null) {
            body.setPackageBzNo("TL" + System.currentTimeMillis());
            body.setPackageAmountPre(BigDecimal.ZERO);
        }
        return body;
    }

    private UniOrderCreateResDTO stub(TDraftOrderDO order, List<TOrderLinePurchaseDO> purchases) {
        String tradeNo = "PAY" + System.currentTimeMillis();
        String orderNo = "TO" + Instant.now().getEpochSecond();
        Map<String, String> map = new HashMap<>();
        int i = 1;
        BigDecimal total = BigDecimal.ZERO;
        for (TOrderLinePurchaseDO p : purchases) {
            String itemNo = "TI" + Instant.now().getEpochSecond() + String.format("%02d", i++);
            map.put(String.valueOf(p.getId()), itemNo);
            if (p.getPurchaseAmount() != null) {
                total = total.add(p.getPurchaseAmount());
            }
        }
        log.info("RemoteOrderSdkClient stub uniOrder draftId={} tradeNo={} items={}",
                order.getId(), tradeNo, map.size());
        return new UniOrderCreateResDTO()
                .setTradeNo(tradeNo)
                .setOrderNo(orderNo)
                .setType("dropship")
                .setExpireTime(Instant.now().plus(30, ChronoUnit.MINUTES))
                .setTotalAmount(total)
                .setOrderNoMap(map);
    }
}
