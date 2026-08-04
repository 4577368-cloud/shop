package com.tang.plugin.client.user;

import com.tang.common.core.domain.R;
import com.tang.plugin.client.user.dto.OAuthAppLoginRequest;
import com.tang.plugin.client.user.dto.OAuthAppResultResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(contextId = "sourcePluginUserPlatformClient", value = "tang-user")
public interface RemoteUserPlatformClient {

    @PostMapping("/platform/login")
    R<OAuthAppResultResponse> login(@RequestHeader HttpHeaders headers,
                                    @RequestBody OAuthAppLoginRequest request);
}
