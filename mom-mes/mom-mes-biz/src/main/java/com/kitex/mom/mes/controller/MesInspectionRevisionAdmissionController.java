package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesInspectionRevisionAdmissionDTO;
import com.kitex.mom.mes.dto.MesInspectionRevisionDeliveryDTO;
import com.kitex.mom.mes.service.impl.MesInspectionRevisionAdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅内部独立签名运输；不暴露管理员强制解除或改判入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/internal/inspection-revision-admission")
public class MesInspectionRevisionAdmissionController {
    private final MesInspectionRevisionAdmissionService service;

    @Inner
    @PostMapping
    public R<MesInspectionRevisionAdmissionDTO> receive(
            @RequestBody MesInspectionRevisionDeliveryDTO command) {
        return R.ok(service.receive(command));
    }
}
