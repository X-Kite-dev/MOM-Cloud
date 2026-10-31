package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesReworkInspectionDeliveryDTO;
import com.kitex.mom.mes.service.impl.MesReworkInspectionDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FROM标记不能授权质量放行，必须通过独立写签名和原MES业务门禁。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/internal")
public class MesReworkInspectionDeliveryController {
    private final MesReworkInspectionDeliveryService delivery;

    @Inner
    @PostMapping("/rework-inspection-delivery")
    public R<Void> accept(@RequestBody MesReworkInspectionDeliveryDTO command) {
        delivery.accept(command);
        return R.ok();
    }
}
