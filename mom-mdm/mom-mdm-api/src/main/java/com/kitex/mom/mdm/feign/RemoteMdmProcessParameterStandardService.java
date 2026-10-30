package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmProcessParameterStandardDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * MES冻结工艺参数标准使用的MDM内部接口。
 */
@FeignClient(contextId = "remoteMdmProcessParameterStandardService", value = Constants.Name)
public interface RemoteMdmProcessParameterStandardService {

    @InternalCall(userTokenRequired = true)
    @PostMapping("/mdmProcessParameterStandard/internal/resolve")
    R<MdmProcessParameterStandardDTO.FrozenCatalog> resolveReleased(
            @RequestBody MdmProcessParameterStandardDTO.ResolveQuery query);

    /**
     * 节点冻结协议专用入口；旧入口不允许忽略节点读取NODE_V2正式内容。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mdmProcessParameterStandard/internal/resolve-nodes")
    R<MdmProcessParameterStandardDTO.FrozenCatalog> resolveNodes(
            @RequestBody MdmProcessParameterStandardDTO.ResolveQuery query);
}
