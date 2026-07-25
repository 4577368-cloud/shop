package com.tang.plugin.utils;

import org.apache.commons.lang3.StringUtils;

/**
 * Downscale remote product thumbnails for list/grid (Shopify CDN, 1688 alicdn). Safe no-op for unknown hosts.
 */
public final class CdnThumbUrl {

    private CdnThumbUrl() {}

    public static String apply(String src, int pixelWidth) {
        if (StringUtils.isBlank(src) || pixelWidth < 1) {
            return src;
        }
        String raw = src.trim();

        if (raw.contains("cdn.shopify.com") || raw.contains("shopifycdn.com")) {
            try {
                int q = raw.indexOf('?');
                String base = q >= 0 ? raw.substring(0, q) : raw;
                String query = q >= 0 ? raw.substring(q + 1) : "";
                StringBuilder sb = new StringBuilder(base).append("?width=").append(pixelWidth)
                        .append("&height=").append(pixelWidth);
                if (StringUtils.isNotBlank(query)) {
                    sb.append('&').append(query);
                }
                return sb.toString();
            } catch (Exception ignored) {
                return raw;
            }
        }

        if (raw.contains("alicdn.com") || raw.contains("1688.com")) {
            if (raw.matches(".*_\\d+x\\d+.*")) {
                return raw;
            }
            int q = raw.indexOf('?');
            String base = q >= 0 ? raw.substring(0, q) : raw;
            String query = q >= 0 ? raw.substring(q) : "";
            String suffix = "_" + pixelWidth + "x" + pixelWidth + "q90";
            String lower = base.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                String stem = base.replaceAll("(?i)\\.jpe?g$", "");
                return stem + suffix + ".jpg" + query;
            }
            if (lower.endsWith(".png")) {
                String stem = base.replaceAll("(?i)\\.png$", "");
                return stem + suffix + ".png" + query;
            }
            String sep = raw.contains("?") ? "&" : "?";
            return raw + sep + "x-oss-process=image/resize,w_" + pixelWidth;
        }

        return raw;
    }
}
