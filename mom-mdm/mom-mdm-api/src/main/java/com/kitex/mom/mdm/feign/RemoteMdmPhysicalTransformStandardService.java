package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmPhysicalTransformStandardDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 新MES派工冻结精确正式转换工程版本；必须携带原用户令牌，不允许匿名工程授权。
 */
@FeignClient(contextId = "remoteMdmPhysicalTransformStandardService", value = Constants.Name)
public interface RemoteMdmPhysicalTransformStandardService {
    /**
     * 原版本解析，不根据当前目录选择所谓最新版本。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mdmPhysicalTransformStandard/internal/resolve")
    R<MdmPhysicalTransformStandardDTO.Frozen> resolveReleased(
            @RequestBody MdmPhysicalTransformStandardDTO.ResolveQuery query);
}
