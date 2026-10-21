package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestProof;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * QMS复开前反查MES已提交的独立复测事实。
 */
@FeignClient(contextId = "remoteMesChangePointEffectRetestService", value = Constants.Name)
public interface RemoteMesChangePointEffectRetestService {
    @InternalCall(userTokenRequired = true)
    @GetMapping("/mesChangePointEffectRetest/internal/{id}/proof")
    R<MesChangePointEffectRetestProof> proof(@PathVariable("id") Long id);
}
