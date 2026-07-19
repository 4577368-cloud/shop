package com.tang.plugin.service.match.image;

import com.tang.plugin.service.match.image.SearchImageResolver.QueryPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the A3-2a title-usability / query-plan rules. Locks the "unusable/too-generic"
 * boundary and the retrieval-vs-display separation. No Spring, no network, no DB.
 */
class SearchImageResolverTest {

    private final SearchImageResolver resolver = new SearchImageResolver();

    @Test
    void usableTitle_producesPlanWithRetrievalAndDisplay() {
        QueryPlan plan = resolver.titleQueryPlan("厂家直销拉布布盲盒公仔");
        assertNotNull(plan);
        assertEquals("厂家直销拉布布盲盒公仔", plan.displayValue());
        assertEquals("厂家直销拉布布盲盒公仔", plan.retrievalValue());
    }

    @Test
    void longTitle_retrievalTruncated_displayFull() {
        String longTitle = "跨境 ONIKUMA CW905无线游戏鼠标2.4G鼠标电脑电竞技吃鸡无线鼠标加长加长";
        QueryPlan plan = resolver.titleQueryPlan(longTitle);
        assertNotNull(plan);
        assertEquals(SearchImageResolver.normalizeQuery(longTitle), plan.displayValue());
        assertEquals(SearchImageResolver.MAX_QUERY_LEN, plan.retrievalValue().length());
        assertTrue(plan.displayValue().length() > plan.retrievalValue().length());
    }

    @Test
    void whitespaceCollapsedInPlan() {
        QueryPlan plan = resolver.titleQueryPlan("  无线   游戏鼠标  ");
        assertNotNull(plan);
        assertEquals("无线 游戏鼠标", plan.displayValue());
    }

    @Test
    void blankOrGenericTitle_yieldsNoPlan() {
        assertNull(resolver.titleQueryPlan(null));
        assertNull(resolver.titleQueryPlan("   "));
        assertNull(resolver.titleQueryPlan("Gift Card"));
        assertNull(resolver.titleQueryPlan("default title"));
        assertNull(resolver.titleQueryPlan("无标题"));
    }

    @Test
    void tooShortMeaningfulTitle_yieldsNoPlan() {
        // digits/punctuation are not meaningful chars; needs >= 2 letters/CJK.
        assertNull(resolver.titleQueryPlan("A"));
        assertNull(resolver.titleQueryPlan("1234 -"));
        assertFalse(SearchImageResolver.isUsableTitle("X"));
        assertTrue(SearchImageResolver.isUsableTitle("鼠标"));
    }

    @Test
    void meaningfulCharCount_ignoresDigitsAndPunctuation() {
        assertEquals(0, SearchImageResolver.meaningfulCharCount("2.4 123 -_/"));
        assertEquals(1, SearchImageResolver.meaningfulCharCount("2.4G"));
        assertEquals(2, SearchImageResolver.meaningfulCharCount("鼠标"));
    }
}
