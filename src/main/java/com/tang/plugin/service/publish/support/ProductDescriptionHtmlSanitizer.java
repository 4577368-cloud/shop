package com.tang.plugin.service.publish.support;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/**
 * Light cleanup for 1688 / upstream HTML before writing Shopify descriptionHtml.
 * Keeps product images and structure; strips obvious junk that pollutes PDP.
 */
public final class ProductDescriptionHtmlSanitizer {

    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "(?is)<(script|style)\\b[^>]*>.*?</\\1>");
    private static final Pattern BAIDU_PAN_ANCHOR = Pattern.compile(
            "(?is)<a\\b[^>]*href\\s*=\\s*[\"'][^\"']*(?:pan\\.baidu\\.com|baidu\\.com/s/)[^\"']*[\"'][^>]*>.*?</a>");
    private static final Pattern BAIDU_PAN_URL = Pattern.compile(
            "(?i)https?://(?:pan\\.)?baidu\\.com/s/\\S+");
    private static final Pattern BAIDU_PWD_LINE = Pattern.compile(
            "(?is)(?:提取码|密码|pwd)\\s*[:：]?\\s*[a-z0-9]{3,8}");
    private static final Pattern LITERAL_NULL_TAG = Pattern.compile(
            "(?is)<(p|span|div|li|td|h[1-6])\\b[^>]*>\\s*null\\s*</\\1>");
    private static final Pattern EMPTY_OFFER_TEMPLATE = Pattern.compile(
            "(?is)<div\\b[^>]*id\\s*=\\s*[\"']offer-template-\\d+[\"'][^>]*>\\s*</div>");
    private static final Pattern EMPTY_PARAS = Pattern.compile(
            "(?is)<p\\b[^>]*>\\s*(?:&nbsp;|\\s|<br\\s*/?>)*</p>");

    private ProductDescriptionHtmlSanitizer() {}

    public static String sanitize(String html) {
        if (StringUtils.isBlank(html)) {
            return html;
        }
        String out = html;
        out = SCRIPT_STYLE.matcher(out).replaceAll("");
        out = BAIDU_PAN_ANCHOR.matcher(out).replaceAll("");
        out = BAIDU_PAN_URL.matcher(out).replaceAll("");
        out = BAIDU_PWD_LINE.matcher(out).replaceAll("");
        out = LITERAL_NULL_TAG.matcher(out).replaceAll("");
        out = EMPTY_OFFER_TEMPLATE.matcher(out).replaceAll("");
        out = EMPTY_PARAS.matcher(out).replaceAll("");
        out = out.replace("\u0000", "");
        // Collapse runs of whitespace between tags a bit without destroying text nodes.
        out = out.replaceAll("(?i)(</?(?:div|p|ul|ol|li|table|tr|td|h[1-6])\\b[^>]*>)\\s+", "$1");
        return out.trim();
    }
}
