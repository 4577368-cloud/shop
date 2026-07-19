package com.tang.plugin.domain.dto.match.image;

/** Which image the backend chose to search 1688 with (A3-2a). */
public enum ImageSource {
    /** Original 1688/source image recovered via the publish record → catalog (best recall). */
    ORIGINAL,
    /** The mirrored Shopify-rehosted product image (fallback when no source image is known). */
    SHOPIFY
}
