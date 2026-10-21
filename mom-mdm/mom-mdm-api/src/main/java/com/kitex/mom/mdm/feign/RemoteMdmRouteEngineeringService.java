package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** PLAN、APS与MES精确消费已批准工程版本的内部合同。 */
@FeignClient(contextId = "remoteMdmRouteEngineeringService", value = Constants.Name)
public interface RemoteMdmRouteEngineeringService {

    /**
     * 返回当前租户、工厂和产品的已发布候选。候选只用于人工选择，
     * 是否仍可生产必须在保存或下达时调用精确取包再次核验。
     */
    @InternalCall(userTokenRequired = true)
    @GetMapping("/mdmRouteEngineeringRelease/internal/production-candidates")
    R<List<MdmRouteEngineeringDTO.ProductionCandidate>> productionCandidates(
            @RequestParam("productCode") String productCode);

    /** 按releaseId、contentHash和产品编码精确取包，并重新核验当前全部工程来源。 */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mdmRouteEngineeringRelease/internal/resolve-for-production")
    R<MdmRouteEngineeringDTO.ProductionPackage> resolveForProduction(
            @RequestBody MdmRouteEngineeringDTO.ProductionQuery query);

    /** MES返工执行方案读取精确原生产版本、稳定节点下的已批准覆盖候选。 */
    @InternalCall(userTokenRequired = true)
    @GetMapping("/mdmRouteEngineeringRelease/internal/rework-override-candidates")
    R<List<MdmRouteEngineeringDTO.ReworkOverrideCandidate>> reworkOverrideCandidates(
            @RequestParam("baseReleaseId") Long baseReleaseId,
            @RequestParam("baseContentHash") String baseContentHash,
            @RequestParam("targetRouteNodeCode") String targetRouteNodeCode);

    /** MES冻结选中的单节点返工覆盖包。 */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mdmRouteEngineeringRelease/internal/resolve-for-rework-override")
    R<MdmRouteEngineeringDTO.ReworkOverridePackage> resolveForReworkOverride(
            @RequestBody MdmRouteEngineeringDTO.ReworkOverrideQuery query);
}
