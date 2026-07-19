package com.tang.plugin.service.match.image;

import com.tang.plugin.domain.entity.publish.ProductPublishRecord;
import com.tang.plugin.repository.ProductPublishRecordRepository;
import com.tang.plugin.service.catalog.TangbuyCatalogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * A3-2a policy helper: decides the original source image (tier 1) and the title-derived query plan
 * (tier 2). The escalation to the LLM tier and the actual gateway calls live in
 * {@link ImageSearchService}; this class only holds the deterministic, testable decisions.
 */
@Slf4j
@Component
public class SearchImageResolver {

    /** Max characters of the title used as a retrieval query (avoids over-long noisy queries). */
    static final int MAX_QUERY_LEN = 30;
    /** Minimum count of meaningful (letter/CJK) characters for a title to be usable. */
    static final int MIN_MEANINGFUL_CHARS = 2;

    /** Lowercased titles that carry no product subject and must not be used as a query. */
    private static final Set<String> GENERIC_TITLES = Set.of(
            "gift card", "default title", "untitled", "product", "products",
            "new product", "sample", "test", "test product", "default", "n/a", "无标题");

    @Resource
    private ProductPublishRecordRepository productPublishRecordRepository;
    @Resource
    private TangbuyCatalogService tangbuyCatalogService;

    /**
     * Tier 1: if this shop product was published from the Tangbuy catalog, recover the catalog
     * candidate's original (1688) image URL. Returns null when there is no such record or no image.
     */
    public String resolveOriginalImageUrl(String shopName, String thirdPlatformItemId) {
        return productPublishRecordRepository
                .findByShopAndShopifyProductId(shopName, thirdPlatformItemId)
                .map(ProductPublishRecord::getCandidateId)
                .flatMap(tangbuyCatalogService::findById)
                .map(p -> StringUtils.trimToNull(p.getImageUrl()))
                .orElse(null);
    }

    /**
     * Tier 2: build a query plan from the shop product title, or null when the title is unusable/too
     * generic (which lets the caller escalate to the LLM tier).
     */
    public QueryPlan titleQueryPlan(String title) {
        if (!isUsableTitle(title)) {
            return null;
        }
        String normalized = normalizeQuery(title);
        if (StringUtils.isBlank(normalized)) {
            return null;
        }
        // retrieval value is truncated to keep the gateway query focused; display value stays readable.
        String retrieval = normalized.length() > MAX_QUERY_LEN
                ? normalized.substring(0, MAX_QUERY_LEN) : normalized;
        return new QueryPlan(retrieval, normalized);
    }

    /** A title is unusable when blank, too short (meaningful chars), or a known generic placeholder. */
    static boolean isUsableTitle(String title) {
        if (StringUtils.isBlank(title)) {
            return false;
        }
        String trimmed = title.trim();
        if (GENERIC_TITLES.contains(trimmed.toLowerCase())) {
            return false;
        }
        return meaningfulCharCount(trimmed) >= MIN_MEANINGFUL_CHARS;
    }

    /** Count letters and CJK ideographs, ignoring digits, whitespace, and punctuation. */
    static int meaningfulCharCount(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                count++;
            }
        }
        return count;
    }

    /** Collapse whitespace to single spaces and trim. */
    static String normalizeQuery(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /**
     * Query decision carrying both the value sent to 1688 ({@code retrievalValue}) and the value shown
     * to the user ({@code displayValue}), kept separate so they can diverge later without coupling.
     */
    public record QueryPlan(String retrievalValue, String displayValue) {
    }
}
