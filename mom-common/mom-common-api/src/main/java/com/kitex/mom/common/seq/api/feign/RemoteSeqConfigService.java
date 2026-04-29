package com.kitex.mom.common.seq.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.common.Constants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 远程序列号配置服务
 */
@FeignClient(contextId = "remoteSeqConfigService", value = Constants.Name)
public interface RemoteSeqConfigService {

    /**
     * 获取序列号
     *
     * @return R
     */
    @NoToken
    @GetMapping("/mdmSeq/create")
    R<String> create(@RequestParam("type") String type);
}
