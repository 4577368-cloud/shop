package com.tang.plugin.domain.dto.match.image;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * A3-3a: result of uploading an image to 1688 ({@code com.alibaba.fenxiao.crossborder:product.image.upload}).
 * The {@link #imageId} is the token consumed by the image search ({@code imageQuery}) to avoid re-passing a
 * publicly-reachable {@code imageAddress} — the enabler for searching by a Shopify-CDN image.
 */
@Data
@Accessors(chain = true)
public class ImageUploadResultVO {
    private String imageId;
}
