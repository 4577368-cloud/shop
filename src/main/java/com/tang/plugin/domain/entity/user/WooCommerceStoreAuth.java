package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class WooCommerceStoreAuth {
    private Long id;
    private Long userId;
    private String shopName;
    private String siteUrl;
    private String consumerKey;
    private String consumerSecret;
    private String keyPermissions;
    private String keyId;
    private Integer authStatus;
    private Integer status;
    private String apiVersion;
    private Boolean sslEnabled;
    private String webhookSecret;
    private Instant lastSyncTime;
    private Instant createTime;
    private Instant updateTime;
    private Integer delFlag;
    private String remark;
}
