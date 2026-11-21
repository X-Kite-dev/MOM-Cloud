package com.kitex.mom.qms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.qms.Constants;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 质检feign
 */
@FeignClient(contextId = "remoteQualityStandardService", value = Constants.Name)
public interface RemoteQualityStandardService {

    @GetMapping("/standard/list")
    R<List<QmsQualityStandardEntity>> list(@SpringQueryMap QmsQualityStandardEntity query);

    @GetMapping("/standard/getRandomStandard")
    R<QmsQualityStandardEntity> getRandomStandard(@SpringQueryMap QmsQualityStandardEntity query);

    /** 供中检调度匹配实际工厂已发布的工序标准。 */
    @InternalCall
    @GetMapping("/standard/internal/patrol-standard")
    R<QmsQualityStandardEntity> getPatrolStandard(@RequestParam("matCode") String matCode);
}
