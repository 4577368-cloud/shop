package com.tang.plugin.service.skualign;

import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.skualign.ProductSourceBinding;
import com.tang.plugin.domain.entity.skualign.VariantSkuBinding;
import com.tang.plugin.enums.match.BindingStatus;
import com.tang.plugin.enums.skualign.ProductOrigin;
import com.tang.plugin.enums.skualign.SourceRole;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.skualign.ProductSourceBindingRepository;
import com.tang.plugin.repository.skualign.VariantSkuBindingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductPrimaryOfferResolverTest {

    private static final String SHOP = "shop-a";
    private static final String PRODUCT = "gid://shopify/Product/1";

    @Mock
    private ProductSourceBindingRepository productSourceRepository;
    @Mock
    private ShopProductBindingRepository shopProductBindingRepository;
    @Mock
    private VariantSkuBindingRepository v1BindingRepository;

    @InjectMocks
    private ProductPrimaryOfferResolver resolver;

    @Test
    void prefersProductSourceBindingPrimaryOffer() {
        when(productSourceRepository.findByProduct(SHOP, PRODUCT)).thenReturn(Optional.of(
                new ProductSourceBinding()
                        .setPrimaryOfferId("primary-offer")
                        .setSupplementalOfferIdsJson("[{\"offerId\":\"sup-offer\",\"role\":\"SUPPLEMENT\"}]")));
        assertEquals("primary-offer", resolver.resolvePrimaryOffer(SHOP, PRODUCT));
    }

    @Test
    void ignoresNewestSupplementLegacyBinding() {
        when(productSourceRepository.findByProduct(SHOP, PRODUCT)).thenReturn(Optional.empty());
        when(v1BindingRepository.mapActiveByProduct(SHOP, PRODUCT)).thenReturn(Map.of(
                "v1", new VariantSkuBinding().setOfferId("primary-offer").setSourceRole(SourceRole.PRIMARY),
                "v2", new VariantSkuBinding().setOfferId("sup-offer").setSourceRole(SourceRole.SUPPLEMENT)));
        assertEquals("primary-offer", resolver.resolvePrimaryOffer(SHOP, PRODUCT));
    }

    @Test
    void ignoresSupplementWhenFallingBackToLegacyBindings() {
        when(productSourceRepository.findByProduct(SHOP, PRODUCT)).thenReturn(Optional.empty());
        when(v1BindingRepository.mapActiveByProduct(SHOP, PRODUCT)).thenReturn(Map.of(
                "v2", new VariantSkuBinding().setOfferId("sup-offer").setSourceRole(SourceRole.SUPPLEMENT)));
        when(shopProductBindingRepository.listBindableByShop(SHOP)).thenReturn(List.of(
                binding("v2", "sup-offer"),
                binding("v1", "primary-offer")));
        assertEquals("primary-offer", resolver.resolvePrimaryOffer(SHOP, PRODUCT));
    }

    private static ShopProductBinding binding(String variantId, String offerId) {
        return new ShopProductBinding()
                .setThirdPlatformItemId(PRODUCT)
                .setThirdPlatformSkuId(variantId)
                .setTangbuyProductId(offerId)
                .setBindStatus(BindingStatus.ACTIVE);
    }
}
