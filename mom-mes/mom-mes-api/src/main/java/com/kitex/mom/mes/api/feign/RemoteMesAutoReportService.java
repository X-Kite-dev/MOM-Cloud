package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesAutoReportEventDTO;
import com.kitex.mom.mes.dto.MesAutoReportResultDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * IoT、设备网关或标准集成服务调用的MES自动报工契约。
 */
@FeignClient(contextId = "remoteMesAutoReportService", value = Constants.Name)
public interface RemoteMesAutoReportService {
    @InternalCall
    @PostMapping("/mesAutoReport/internal/event")
    R<MesAutoReportResultDTO> accept(@RequestBody MesAutoReportEventDTO event);

    @InternalCall
    @PostMapping("/mesAutoReport/internal/receipt")
    R<MesAutoReportResultDTO> receipt(@RequestBody MesAutoReportEventDTO event);
}
