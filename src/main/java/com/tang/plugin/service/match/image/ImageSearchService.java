package com.tang.plugin.service.match.image;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.image.ImageSearchProductVO;
import com.tang.plugin.domain.dto.match.image.ImageSearchResultVO;
import com.tang.plugin.domain.dto.match.image.ImageSource;
import com.tang.plugin.domain.dto.match.image.OfferImageSearchItemVO;
import com.tang.plugin.domain.dto.match.image.OfferImageSearchResultVO;
import com.tang.plugin.domain.dto.match.image.QuerySource;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.service.match.image.SearchImageResolver.QueryPlan;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * A3-2a orchestrator for stateless 1688 image-search preview. The backend alone decides the search
 * image and any correction query via a tiered, gracefully-degrading policy:
 *
 * <ol>
 *   <li><b>Original image</b> (tier 1): products we published from the catalog → search the original
 *       1688 image with no query. LLM is never invoked here.</li>
 *   <li><b>Title query</b> (tier 2): otherwise search the mirrored Shopify image using the product
 *       title as a correction query, when the title is usable.</li>
 *   <li><b>LLM query</b> (tier 3): when the title is unusable/too-generic, or its search came back
 *       empty, ask the vision LLM for a subject word and search with that.</li>
 * </ol>
 *
 * Any LLM failure/timeout returns null and degrades to the title (possibly empty) or pure-image
 * result — the chain is never interrupted. Read-only; no persistence, no binding (that is A3-2b).
 *
 * <p>The gateway is the official 1688 cross-border image search ({@link Alibaba1688ImageSearchClient}, AOP):
 * the correction query maps to the native {@code keyword}. Because the official API accepts a publicly-reachable
 * {@code imageAddress} only for alicdn-hosted images, a non-alicdn (e.g. Shopify-CDN) image is first
 * uploaded via {@link Alibaba1688ImageUploadClient} to obtain an {@code imageId}, resolved once per call
 * and reused across the title/LLM tiers.
 */
@Slf4j
@Service
public class ImageSearchService {

    public static final String ERR_NO_PRIMARY_IMAGE = "NO_PRIMARY_IMAGE";
    public static final String ERR_PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";

    /** Multilingual translation language for the official {@code *Trans} fields. */
    private static final String SEARCH_COUNTRY = "en";

    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Resource
    private Alibaba1688ImageSearchClient imageSearchClient;
    @Resource
    private Alibaba1688ImageUploadClient imageUploadClient;
    @Resource
    private SearchImageResolver searchImageResolver;
    @Resource
    private LlmVisionClient llmVisionClient;

    /**
     * Resolve the best search image + query for a mirrored shop product and query 1688.
     *
     * @param shopName            shop identifier
     * @param thirdPlatformItemId the mirrored SPU id (Shopify product GID in the mirror)
     * @param limit               candidates to fetch (null/&lt;=0 → client default)
     */
    public ImageSearchResultVO searchByShopProduct(String shopName, String thirdPlatformItemId, Integer limit) {
        if (StringUtils.isAnyBlank(shopName, thirdPlatformItemId)) {
            throw new CustomException("image search requires shopName and thirdPlatformItemId");
        }
        ThirdPlatformProduct product = findMirrorProduct(shopName, thirdPlatformItemId);

        // Tier 1: original source image (no query, no LLM). Catalog images are alicdn-hosted.
        String originalImage = searchImageResolver.resolveOriginalImageUrl(shopName, thirdPlatformItemId);
        if (StringUtils.isNotBlank(originalImage)) {
            List<ImageSearchProductVO> items = search(resolveToken(originalImage), limit, null);
            return result(items, ImageSource.ORIGINAL, QuerySource.NONE, null);
        }

        // Shopify image path (tiers 2/3). Resolve the search token (upload → imageId when not alicdn) once.
        String shopifyImage = product.getPrimaryImageUrl();
        if (StringUtils.isBlank(shopifyImage)) {
            throw new CustomException(ERR_NO_PRIMARY_IMAGE + ": 该商品无主图，无法进行 1688 图搜");
        }
        SearchToken token = resolveToken(shopifyImage);

        // Tier 2: title query, when usable.
        QueryPlan titlePlan = searchImageResolver.titleQueryPlan(product.getTitle());
        List<ImageSearchProductVO> titleItems = null;
        if (titlePlan != null) {
            titleItems = search(token, limit, titlePlan.retrievalValue());
            if (!titleItems.isEmpty()) {
                return result(titleItems, ImageSource.SHOPIFY, QuerySource.TITLE, titlePlan.displayValue());
            }
            // title present but empty recall → escalate to LLM below.
        }

        // Tier 3: LLM subject query (title unusable, or its recall was empty).
        String subject = llmVisionClient.describeSubject(shopifyImage);
        if (StringUtils.isNotBlank(subject)) {
            List<ImageSearchProductVO> llmItems = search(token, limit, subject);
            return result(llmItems, ImageSource.SHOPIFY, QuerySource.LLM, subject);
        }

        // Degrade: keep the (empty) title attempt if we had one, else pure image search.
        if (titlePlan != null) {
            return result(titleItems, ImageSource.SHOPIFY, QuerySource.TITLE, titlePlan.displayValue());
        }
        List<ImageSearchProductVO> pureItems = search(token, limit, null);
        return result(pureItems, ImageSource.SHOPIFY, QuerySource.NONE, null);
    }

    /**
     * Decide how the official image search should reference the image: pass an alicdn-hosted url directly
     * as {@code imageAddress}, otherwise upload it once to obtain an {@code imageId} the gateway accepts.
     */
    private SearchToken resolveToken(String imageUrl) {
        if (isAlicdn(imageUrl)) {
            return SearchToken.address(imageUrl);
        }
        String imageId = imageUploadClient.uploadByUrl(imageUrl).getImageId();
        return SearchToken.id(imageId);
    }

    private List<ImageSearchProductVO> search(SearchToken token, Integer limit, String keyword) {
        OfferImageSearchResultVO res = imageSearchClient.searchByImage(
                token.imageAddress(), token.imageId(), keyword, null, SEARCH_COUNTRY, 1, limit);
        return map(res.getItems());
    }

    private ThirdPlatformProduct findMirrorProduct(String shopName, String thirdPlatformItemId) {
        return thirdPlatformProductRepository.listByShop(shopName).stream()
                .filter(p -> thirdPlatformItemId.equals(p.getThirdPlatformItemId()))
                .findFirst()
                .orElseThrow(() -> new CustomException(ERR_PRODUCT_NOT_FOUND
                        + ": 未找到店铺商品(" + shopName + "/" + thirdPlatformItemId + ")，请先同步商品"));
    }

    /** Map official offers to the stable normalized item shape. Gateway relevance order is preserved. */
    private static List<ImageSearchProductVO> map(List<OfferImageSearchItemVO> offers) {
        List<ImageSearchProductVO> items = new ArrayList<>();
        if (offers == null) {
            return items;
        }
        for (OfferImageSearchItemVO o : offers) {
            items.add(new ImageSearchProductVO()
                    .setProductId(StringUtils.defaultString(o.getOfferId()))
                    .setTitle(StringUtils.trimToEmpty(StringUtils.firstNonBlank(o.getSubjectTrans(), o.getSubject())))
                    .setImageUrl(o.getImageUrl())
                    .setDetailUrl(o.getDetailUrl())
                    .setPrice(o.getPrice())
                    .setSupplier(o.getCompanyName())
                    .setSoldCount(o.getMonthSold() == null ? null : o.getMonthSold().longValue())
                    .setRepurchaseRate(o.getRepurchaseRate())
                    .setSimilarityScore(null)
                    .setMinOrderQty(o.getMinOrderQuantity() == null ? null : o.getMinOrderQuantity().longValue())
                    .setInventory(null)
                    .setSkuId(null));
        }
        return items;
    }

    private static boolean isAlicdn(String url) {
        return StringUtils.containsIgnoreCase(url, "alicdn.com");
    }

    private static ImageSearchResultVO result(List<ImageSearchProductVO> items, ImageSource imageSource,
                                              QuerySource querySource, String appliedQuery) {
        return new ImageSearchResultVO()
                .setItems(items)
                .setImageSource(imageSource.name())
                .setQuerySource(querySource.name())
                .setAppliedQuery(appliedQuery);
    }

    /** Either a direct {@code imageAddress} (alicdn) or an uploaded {@code imageId}; exactly one is set. */
    private record SearchToken(String imageAddress, String imageId) {
        static SearchToken address(String imageAddress) {
            return new SearchToken(imageAddress, null);
        }

        static SearchToken id(String imageId) {
            return new SearchToken(null, imageId);
        }
    }
}
