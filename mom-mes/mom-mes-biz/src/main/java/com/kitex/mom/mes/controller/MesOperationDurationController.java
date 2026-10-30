package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesOperationDurationDTO;
import com.kitex.mom.mes.service.impl.MesOperationDurationReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅供原回评岗位实名读取MES完整连续生产段。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesOperationDuration")
@Tag(name = "APS连续生产段回评证据")
public class MesOperationDurationController {
    private final MesOperationDurationReader reader;

    @Inner
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingEffect_capture')")
    @PostMapping("/internal/query")
    @Operation(summary = "按真实工序编码读取完整生产段及不可核验原因")
    public R<MesOperationDurationDTO.Evidence> query(@RequestBody MesOperationDurationDTO.Query query) {
        return R.ok(reader.read(query));
    }
}
