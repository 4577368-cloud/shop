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
            String normalized = normalizeAliCibUrl(raw);
            if (normalized.matches(".*_\\d+x\\d+q90\\..*") && normalized.contains("-0-cib_")) {
                normalized = normalizeAliCibUrl(normalized);
            }
            if (normalized.matches(".*_\\d+x\\d+.*") && !normalized.contains("-0-cib_")) {
                return normalized;
            }
            int q = normalized.indexOf('?');
            String base = q >= 0 ? normalized.substring(0, q) : normalized;
            String query = q >= 0 ? normalized.substring(q) : "";
            if (base.toLowerCase().matches(".*-0-cib\\.jpe?g$")) {
                return appendOssResize(normalized, pixelWidth);
            }
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
            return appendOssResize(normalized, pixelWidth);
        }

        return raw;
    }

    /** itemGet URLs often end with -0-cib; CDN serves -0-cib.jpg. */
    static String normalizeAliCibUrl(String src) {
        if (StringUtils.isBlank(src) || !src.contains("alicdn.com")) {
            return src;
        }
        String raw = src.trim();
        int q = raw.indexOf('?');
        String path = q >= 0 ? raw.substring(0, q) : raw;
        String query = q >= 0 ? raw.substring(q) : "";
        if (path.matches("(?i).+-0-cib_\\d+x\\d+q90\\.(jpe?g|png|webp)$")) {
            String stem = path.replaceAll("(?i)_\\d+x\\d+q90\\.(jpe?g|png|webp)$", "");
            return stem + ".jpg" + query;
        }
        if (path.matches("(?i).+-0-cib$")) {
            return path + ".jpg" + query;
        }
        return raw;
    }

    static String appendOssResize(String url, int pixelWidth) {
        String withoutOss = url.replaceAll("[?&]x-oss-process=[^&]*", "").replaceAll("\\?$", "");
        String sep = withoutOss.contains("?") ? "&" : "?";
        return withoutOss + sep + "x-oss-process=image/resize,w_" + pixelWidth;
    }
}
