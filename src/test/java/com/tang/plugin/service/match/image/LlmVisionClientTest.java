package com.tang.plugin.service.match.image;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the vision-LLM request build + response parse/sanitize. No network.
 */
class LlmVisionClientTest {

    @Test
    void buildRequestBody_hasMultimodalContentAndParams() {
        String body = LlmVisionClient.buildRequestBody("Qwen-VL", LlmVisionClient.SUBJECT_PROMPT, "https://cdn/x.jpg");
        JSONObject root = JSONObject.parseObject(body);
        assertEquals("Qwen-VL", root.getString("model"));
        assertEquals(0, root.getIntValue("temperature"));
        var content = root.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        assertEquals("text", content.getJSONObject(0).getString("type"));
        assertEquals(LlmVisionClient.SUBJECT_PROMPT, content.getJSONObject(0).getString("text"));
        assertEquals("image_url", content.getJSONObject(1).getString("type"));
        assertEquals("https://cdn/x.jpg",
                content.getJSONObject(1).getJSONObject("image_url").getString("url"));
    }

    @Test
    void parseSubject_extractsAndSanitizes() {
        String resp = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"毛绒玩偶\"}}]}";
        assertEquals("毛绒玩偶", LlmVisionClient.parseSubject(resp));
    }

    @Test
    void parseSubject_handlesMissingFields() {
        assertNull(LlmVisionClient.parseSubject(null));
        assertNull(LlmVisionClient.parseSubject(""));
        assertNull(LlmVisionClient.parseSubject("{}"));
        assertNull(LlmVisionClient.parseSubject("{\"choices\":[]}"));
        assertNull(LlmVisionClient.parseSubject("{\"choices\":[{\"message\":{\"content\":\"\"}}]}"));
    }

    @Test
    void sanitizeSubject_stripsPunctuationQuotesAndExtraLines() {
        assertEquals("粉色鼠标", LlmVisionClient.sanitizeSubject("“粉色鼠标”。"));
        assertEquals("保温杯", LlmVisionClient.sanitizeSubject("保温杯\n这是一个解释"));
        assertEquals("无线鼠标", LlmVisionClient.sanitizeSubject("  无线鼠标  "));
        assertNull(LlmVisionClient.sanitizeSubject("   "));
        assertNull(LlmVisionClient.sanitizeSubject("！？。，"));
    }

    @Test
    void sanitizeSubject_capsLength() {
        String longWord = "毛绒玩偶".repeat(10);
        assertTrue(LlmVisionClient.sanitizeSubject(longWord).length() <= 16);
    }
}
