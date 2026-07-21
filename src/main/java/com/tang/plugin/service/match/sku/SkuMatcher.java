package com.tang.plugin.service.match.sku;

import com.tang.plugin.domain.dto.match.sku.OfferSkuAttributeVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuVO;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * S1-b1 pure matcher: aligns each Shopify variant (option1/2/3) to the most similar 1688 offer SKU by
 * comparing normalized spec tokens against the offer SKU attributes ({@code value} + {@code valueTrans}).
 *
 * <p>Deterministic and side-effect free (no Spring, no network, no persistence) so the alignment policy is
 * fully unit-testable. Scoring is the fraction of a variant's option values that hit some attribute value
 * of a candidate SKU (equality, or containment for tokens ≥2 chars). A single-SKU offer maps every variant
 * to that lone SKU. A best score below {@link #ACCEPT_SCORE} is reported as {@code matched=false} (left for
 * a human / a later LLM tier) rather than force-binding a wrong SKU.
 */
public final class SkuMatcher {

    /** Minimum overlap score to accept an automatic per-variant binding. */
    public static final double ACCEPT_SCORE = 0.5d;

    private SkuMatcher() {
    }

    /** Alignment of one Shopify variant to a 1688 SKU. {@code skuId}/{@code specLabel} null when unmatched. */
    public record VariantAlignment(String variantGid, String optionLabel, String skuId, String specLabel,
                                   double score, boolean matched) {
    }

    /**
     * True when at least one Shopify option token does not appear in any SKU attribute across the matrix.
     * Used for NO_SOURCE review (e.g. shop has XXL but matrix has no XXL).
     */
    public static boolean hasOptionAbsentFromMatrix(ThirdPlatformSku variant, List<OfferSkuVO> skus) {
        Set<String> optionTokens = variantTokens(variant);
        if (optionTokens.isEmpty() || skus == null || skus.isEmpty()) {
            return false;
        }
        Set<String> matrixValues = new LinkedHashSet<>();
        for (OfferSkuVO sku : skus) {
            matrixValues.addAll(skuTokens(sku));
        }
        for (String opt : optionTokens) {
            if (!hitsAny(opt, matrixValues)) {
                return true;
            }
        }
        return false;
    }

    public static List<VariantAlignment> align(List<ThirdPlatformSku> variants, List<OfferSkuVO> skus) {
        List<VariantAlignment> out = new ArrayList<>();
        if (variants == null) {
            return out;
        }
        List<OfferSkuVO> offerSkus = skus == null ? List.of() : skus;
        boolean singleSku = offerSkus.size() == 1;
        for (ThirdPlatformSku variant : variants) {
            out.add(alignOne(variant, offerSkus, singleSku));
        }
        return out;
    }

    private static VariantAlignment alignOne(ThirdPlatformSku variant, List<OfferSkuVO> skus, boolean singleSku) {
        String variantGid = variant.getThirdPlatformSkuId();
        String optionLabel = optionLabel(variant);
        if (skus.isEmpty()) {
            return new VariantAlignment(variantGid, optionLabel, null, null, 0d, false);
        }
        Set<String> optionTokens = variantTokens(variant);

        OfferSkuVO best = null;
        double bestScore = -1d;
        for (OfferSkuVO sku : skus) {
            double score = overlap(optionTokens, skuTokens(sku));
            if (score > bestScore) {
                bestScore = score;
                best = sku;
            }
        }
        // A lone SKU is the only possible target: bind it even when the labels don't textually overlap.
        boolean matched = singleSku || bestScore >= ACCEPT_SCORE;
        double score = singleSku ? Math.max(bestScore, optionTokens.isEmpty() ? 1d : bestScore) : bestScore;
        if (!matched || best == null) {
            return new VariantAlignment(variantGid, optionLabel, null, null, Math.max(bestScore, 0d), false);
        }
        return new VariantAlignment(variantGid, optionLabel, best.getSkuId(), specLabel(best), score, true);
    }

    /** Fraction of option tokens that hit some sku token; 0 when either side is empty. */
    private static double overlap(Set<String> optionTokens, Set<String> skuTokens) {
        if (optionTokens.isEmpty() || skuTokens.isEmpty()) {
            return 0d;
        }
        int hits = 0;
        for (String opt : optionTokens) {
            if (hitsAny(opt, skuTokens)) {
                hits++;
            }
        }
        return (double) hits / optionTokens.size();
    }

    private static boolean hitsAny(String opt, Set<String> skuTokens) {
        for (String s : skuTokens) {
            if (opt.equals(s)) {
                return true;
            }
            if (opt.length() >= 2 && s.contains(opt)) {
                return true;
            }
            if (s.length() >= 2 && opt.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> variantTokens(ThirdPlatformSku variant) {
        Set<String> tokens = new LinkedHashSet<>();
        addToken(tokens, variant.getOption1());
        addToken(tokens, variant.getOption2());
        addToken(tokens, variant.getOption3());
        return tokens;
    }

    private static Set<String> skuTokens(OfferSkuVO sku) {
        Set<String> tokens = new LinkedHashSet<>();
        if (sku.getSkuAttributes() != null) {
            for (OfferSkuAttributeVO a : sku.getSkuAttributes()) {
                addToken(tokens, a.getValueTrans());
                addToken(tokens, a.getValue());
            }
        }
        return tokens;
    }

    private static void addToken(Set<String> tokens, String raw) {
        String n = normalize(raw);
        if (!n.isEmpty()) {
            tokens.add(n);
        }
    }

    /** Lowercase and strip whitespace + common separators/punctuation; keep letters/digits/CJK. */
    static String normalize(String s) {
        if (StringUtils.isBlank(s)) {
            return "";
        }
        return s.toLowerCase().replaceAll("[\\s\\-_/|,.，、;:；：()（）\\[\\]【】]", "").trim();
    }

    /** Human-readable spec of a SKU: join each attribute's translated (fallback raw) value. */
    public static String specLabel(OfferSkuVO sku) {
        List<String> parts = new ArrayList<>();
        if (sku.getSkuAttributes() != null) {
            for (OfferSkuAttributeVO a : sku.getSkuAttributes()) {
                String v = StringUtils.firstNonBlank(a.getValueTrans(), a.getValue());
                if (StringUtils.isNotBlank(v)) {
                    parts.add(v.trim());
                }
            }
        }
        return parts.isEmpty() ? "" : String.join(" / ", parts);
    }

    private static String optionLabel(ThirdPlatformSku variant) {
        List<String> parts = new ArrayList<>();
        for (String opt : List.of(
                StringUtils.trimToEmpty(variant.getOption1()),
                StringUtils.trimToEmpty(variant.getOption2()),
                StringUtils.trimToEmpty(variant.getOption3()))) {
            if (StringUtils.isNotBlank(opt)) {
                parts.add(opt);
            }
        }
        return parts.isEmpty()
                ? StringUtils.defaultIfBlank(StringUtils.trimToNull(variant.getSku()), "默认规格")
                : String.join(" / ", parts);
    }
}
