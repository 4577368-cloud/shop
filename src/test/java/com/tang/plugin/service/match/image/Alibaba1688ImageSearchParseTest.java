package com.tang.plugin.service.match.image;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.image.OfferImageSearchItemVO;
import com.tang.plugin.domain.dto.match.image.OfferImageSearchResultVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the official imageQuery response normalization (A3-3a). Locks the envelope walk
 * (result.result.data[]), field mapping (multilingual title, priceInfo flavours, promotionURL → detailUrl),
 * empty-result-as-success, and auth-error classification. No Spring, no network.
 */
class Alibaba1688ImageSearchParseTest {

    private final Alibaba1688ImageSearchClient client = new Alibaba1688ImageSearchClient();

    /** Envelope shaped after the official doc sample. */
    private static final String SAMPLE = """
            {
              "result": {
                "success": true,
                "code": "200",
                "message": "ok",
                "result": {
                  "totalRecords": 2,
                  "totalPage": 1,
                  "pageSize": 5,
                  "currentPage": 1,
                  "data": [
                    {
                      "imageUrl": "https://cbu01.alicdn.com/x.jpg",
                      "subject": "植绒拉布布盲盒",
                      "subjectTrans": "Flocked Labubu Blind Box",
                      "priceInfo": { "price": "5.9", "consignPrice": "6.1", "promotionPrice": "5.62" },
                      "offerId": 710891050473,
                      "monthSold": 1234,
                      "repurchaseRate": "13%",
                      "minOrderQuantity": 2,
                      "promotionURL": "https://detail.1688.com/offer/710891050473.html"
                    },
                    {
                      "subject": "no price offer",
                      "offerId": 999
                    }
                  ]
                }
              }
            }
            """;

    @Test
    void parse_mapsDocSample() {
        OfferImageSearchResultVO vo = client.parse(SAMPLE, "img-1");
        assertEquals("img-1", vo.getImageId());
        assertEquals(2, vo.getTotalRecords());
        assertEquals(1, vo.getCurrentPage());
        assertEquals(2, vo.getItems().size());

        OfferImageSearchItemVO first = vo.getItems().get(0);
        assertEquals("710891050473", first.getOfferId());
        assertEquals("Flocked Labubu Blind Box", first.getSubjectTrans());
        assertEquals("5.9", first.getPrice());
        assertEquals("6.1", first.getConsignPrice());
        assertEquals("5.62", first.getPromotionPrice());
        assertEquals(2, first.getMinOrderQuantity());
        assertEquals(1234, first.getMonthSold());
        assertEquals("https://detail.1688.com/offer/710891050473.html", first.getDetailUrl());

        // Missing priceInfo must not blow up; offerId coerced from number.
        OfferImageSearchItemVO second = vo.getItems().get(1);
        assertEquals("999", second.getOfferId());
        assertNotNull(vo.getItems());
    }

    @Test
    void parse_successWithoutInnerResult_isEmpty() {
        String raw = "{\"result\":{\"success\":true,\"result\":null}}";
        OfferImageSearchResultVO vo = client.parse(raw, null);
        assertNotNull(vo.getItems());
        assertTrue(vo.getItems().isEmpty());
    }

    @Test
    void parse_businessFailure_classifiesAuthError() {
        String raw = "{\"result\":{\"success\":false,\"message\":\"access_token is invalid or expired\"}}";
        CustomException ex = assertThrows(CustomException.class, () -> client.parse(raw, null));
        assertTrue(ex.getMessage().startsWith(Alibaba1688ImageSearchClient.ERR_TOKEN_INVALID), ex.getMessage());
    }

    @Test
    void parse_topLevelErrorCode_classifiesGatewayBusy() {
        String raw = "{\"error_code\":\"500\",\"error_message\":\"system busy\"}";
        CustomException ex = assertThrows(CustomException.class, () -> client.parse(raw, null));
        assertTrue(ex.getMessage().startsWith(Alibaba1688ImageSearchClient.ERR_GATEWAY_BUSY), ex.getMessage());
    }
}
