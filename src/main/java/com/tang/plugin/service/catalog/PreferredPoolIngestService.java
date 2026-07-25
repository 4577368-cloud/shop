package com.tang.plugin.service.catalog;

import com.tang.plugin.config.TangbuyAdminProxyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Best-effort server-side preferred-pool submit after SKU/image bind so logistics can resolve goodsId
 * without relying on the browser admin token.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreferredPoolIngestService {

    private static final Pattern OFFER_ID = Pattern.compile("^\\d{8,20}$");

    private final TangbuyAdminProxyProperties adminProps;

    public void scheduleIngestAfterBind(String tangbuyProductId) {
        String offerId = StringUtils.trimToNull(tangbuyProductId);
        if (offerId == null || !OFFER_ID.matcher(offerId).matches()) {
            return;
        }
        if (!adminProps.isConfigured()) {
            log.debug("Preferred pool ingest skipped (admin token not configured) offerId={}", offerId);
            return;
        }
        CompletableFuture.runAsync(() -> submitPoolAdd(offerId));
    }

    void submitPoolAdd(String offerId1688) {
        String path = "/product-mall/admin/preferred/pool/add";
        String url = StringUtils.removeEnd(adminProps.getBaseUrl(), "/") + path;
        String body = """
                {"providerItemId":"%s","providerType":"alibaba","saveSource":"LINK","level":"S",\
                "suitableCountryList":[],"labelIdList":[],"operateUserId":1,"operateUserName":"admin",\
                "operateDept":"100","ownerSource":"OPERATE"}\
                """.formatted(offerId1688);
        try {
            String raw = RestClient.create()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, adminProps.resolvedAuthorization())
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            log.info("Preferred pool ingest offerId={} response={}", offerId1688, StringUtils.left(raw, 200));
        } catch (RestClientException e) {
            log.warn("Preferred pool ingest failed offerId={}: {}", offerId1688, e.getMessage());
        }
    }
}
