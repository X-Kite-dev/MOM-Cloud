package com.kitex.mom.qms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.qms.Constants;
import com.kitex.mom.qms.dto.QmsCapaExternalCreateCommand;
import com.kitex.mom.qms.dto.QmsCapaLinkDTO;
import com.kitex.mom.qms.dto.QmsCapaMesRetestReopenCommand;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 业务模块创建并读取QMS CAPA/8D的内部契约。
 */
@FeignClient(contextId = "remoteQmsCapaService", value = Constants.Name)
public interface RemoteQmsCapaService {

    @InternalCall(userTokenRequired = true)
    @PostMapping("/capa/internal/external")
    R<QmsCapaLinkDTO> createExternal(@RequestBody QmsCapaExternalCreateCommand command);

    @InternalCall(userTokenRequired = true)
    @GetMapping("/capa/internal/link/{id}")
    R<QmsCapaLinkDTO> getLink(@PathVariable("id") Long id);

    @InternalCall(userTokenRequired = true)
    @PostMapping("/capa/internal/mes-retest/reopen")
    R<QmsCapaLinkDTO> reopenFromMesRetest(@RequestBody QmsCapaMesRetestReopenCommand command);
}
