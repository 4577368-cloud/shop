package com.tang.plugin.service.remote;

import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Skeleton region resolver. Replace with real remoteResourceSdkClient later.
 */
@Slf4j
@Component
public class RemoteResourceSdkClient {

    public DataRegion getDataRegionByCountryCode(String countryCode) {
        log.info("getDataRegionByCountryCode countryCode={}", countryCode);
        return new DataRegion().setCountryCode(countryCode).setCountryId(hashCountry(countryCode));
    }

    private Long hashCountry(String countryCode) {
        if (countryCode == null) {
            return null;
        }
        return (long) Math.abs(countryCode.toUpperCase().hashCode());
    }

    @Data
    @Accessors(chain = true)
    public static class DataRegion {
        private String countryCode;
        private Long countryId;
    }
}
