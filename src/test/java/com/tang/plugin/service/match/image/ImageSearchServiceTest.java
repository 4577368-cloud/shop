package com.tang.plugin.service.match.image;

import com.tang.plugin.domain.dto.match.image.ImageSearchProductVO;
import com.tang.plugin.domain.dto.match.image.ImageSearchResultVO;
import com.tang.plugin.domain.dto.match.image.ImageUploadResultVO;
import com.tang.plugin.domain.dto.match.image.OfferImageSearchItemVO;
import com.tang.plugin.domain.dto.match.image.OfferImageSearchResultVO;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.service.match.image.SearchImageResolver.QueryPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A3-3b unit tests for the official-image-search swap. Mocks the AOP clients + resolver + LLM to lock:
 * the tier selection (original / title), the alicdn-vs-upload token decision (imageAddress for alicdn,
 * upload→imageId otherwise, reused across tiers), and the offer→normalized item mapping (monthSold→
 * soldCount, repurchaseRate, subjectTrans→title, similarityScore null). No Spring, no network.
 */
@ExtendWith(MockitoExtension.class)
class ImageSearchServiceTest {

    private static final String SHOP = "a33b-shop";
    private static final String ITEM = "gid://shopify/Product/500001";
    private static final String ALICDN = "https://cbu01.alicdn.com/img/ibank/x.jpg";
    private static final String SHOPIFY = "https://cdn.shopify.com/s/files/1/x.jpg";

    @Mock
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Mock
    private Alibaba1688ImageSearchClient imageSearchClient;
    @Mock
    private Alibaba1688ImageUploadClient imageUploadClient;
    @Mock
    private SearchImageResolver searchImageResolver;
    @Mock
    private LlmVisionClient llmVisionClient;

    @InjectMocks
    private ImageSearchService service;

    private void mirror(String primaryImage, String title) {
        when(thirdPlatformProductRepository.listByShop(SHOP)).thenReturn(List.of(new ThirdPlatformProduct()
                .setShopName(SHOP).setShopType("SHOPIFY").setThirdPlatformItemId(ITEM)
                .setTitle(title).setPrimaryImageUrl(primaryImage)));
    }

    private static OfferImageSearchResultVO oneOffer() {
        return new OfferImageSearchResultVO().setItems(List.of(new OfferImageSearchItemVO()
                .setOfferId("710891050473")
                .setSubject("拉布布盲盒")
                .setSubjectTrans("Labubu Blind Box")
                .setImageUrl("https://cbu01.alicdn.com/o.jpg")
                .setPrice("5.9")
                .setMonthSold(1234)
                .setRepurchaseRate("13%")
                .setCompanyName("义乌某某商行")
                .setMinOrderQuantity(2)
                .setDetailUrl("https://detail.1688.com/offer/710891050473.html")));
    }

    @Test
    void tier1_alicdnOriginal_usesImageAddress_noUpload() {
        mirror(SHOPIFY, "whatever");
        when(searchImageResolver.resolveOriginalImageUrl(SHOP, ITEM)).thenReturn(ALICDN);
        when(imageSearchClient.searchByImage(eq(ALICDN), isNull(), isNull(), isNull(), eq("en"), eq(1), eq(4)))
                .thenReturn(oneOffer());

        ImageSearchResultVO res = service.searchByShopProduct(SHOP, ITEM, 4);

        assertEquals("ORIGINAL", res.getImageSource());
        assertEquals("NONE", res.getQuerySource());
        assertEquals(1, res.getItems().size());
        verify(imageUploadClient, never()).uploadByUrl(any());
    }

    @Test
    void tier2_shopifyImage_uploadsThenSearchesByImageId_withTitleKeyword() {
        mirror(SHOPIFY, "Cute Plush Toy");
        when(searchImageResolver.resolveOriginalImageUrl(SHOP, ITEM)).thenReturn(null);
        when(searchImageResolver.titleQueryPlan("Cute Plush Toy"))
                .thenReturn(new QueryPlan("Cute Plush Toy", "Cute Plush Toy"));
        when(imageUploadClient.uploadByUrl(SHOPIFY)).thenReturn(new ImageUploadResultVO().setImageId("IMG1"));
        when(imageSearchClient.searchByImage(isNull(), eq("IMG1"), eq("Cute Plush Toy"), isNull(), eq("en"), eq(1), eq(4)))
                .thenReturn(oneOffer());

        ImageSearchResultVO res = service.searchByShopProduct(SHOP, ITEM, 4);

        assertEquals("SHOPIFY", res.getImageSource());
        assertEquals("TITLE", res.getQuerySource());
        assertEquals("Cute Plush Toy", res.getAppliedQuery());
        verify(imageUploadClient).uploadByUrl(SHOPIFY);
    }

    @Test
    void mapping_translatesFieldsAndNullsSimilarity() {
        mirror(SHOPIFY, "whatever");
        when(searchImageResolver.resolveOriginalImageUrl(SHOP, ITEM)).thenReturn(ALICDN);
        when(imageSearchClient.searchByImage(eq(ALICDN), isNull(), isNull(), isNull(), eq("en"), eq(1), eq(4)))
                .thenReturn(oneOffer());

        ImageSearchProductVO item = service.searchByShopProduct(SHOP, ITEM, 4).getItems().get(0);

        assertEquals("710891050473", item.getProductId());
        assertEquals("Labubu Blind Box", item.getTitle()); // subjectTrans preferred
        assertEquals("5.9", item.getPrice());
        assertEquals(1234L, item.getSoldCount());
        assertEquals("13%", item.getRepurchaseRate());
        assertEquals("义乌某某商行", item.getSupplier());
        assertEquals(2L, item.getMinOrderQty());
        assertNull(item.getSimilarityScore());
        assertNull(item.getSkuId());
    }
}
