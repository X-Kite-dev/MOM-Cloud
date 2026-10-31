package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesParameterDispatchContextDTO;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(contextId = "remoteMesParameterContextService", value = Constants.Name)
public interface RemoteMesParameterContextService {
    @InternalCall(userTokenRequired = true)
    @PostMapping("/process-parameters/internal/dispatch-context")
    R<MesParameterDispatchContextDTO.Evidence> context(
            @RequestBody MesParameterDispatchContextDTO.Query query);

    @InternalCall(userTokenRequired = true)
    @PostMapping("/process-parameters/internal/preparation-options")
    R<MesParameterDispatchContextDTO.Options> preparationOptions(
            @RequestBody MesParameterDispatchContextDTO.OptionsQuery query);
}
