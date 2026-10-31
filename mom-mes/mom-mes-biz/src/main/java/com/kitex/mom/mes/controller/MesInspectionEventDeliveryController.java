package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesInspectionEventDeliveryDTO;
import com.kitex.mom.mes.dto.MesInspectionEventReceiptDTO;
import com.kitex.mom.mes.service.impl.MesInspectionEventInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FROM之外必须校验独立服务签名；回执仅为RECEIVED。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/internal")
public class MesInspectionEventDeliveryController {
    private final MesInspectionEventInboxService inbox;

    @Inner
    @PostMapping("/inspection-event-delivery")
    public R<MesInspectionEventReceiptDTO> accept(
            @RequestBody MesInspectionEventDeliveryDTO command) {
        return R.ok(inbox.accept(command));
    }
}
