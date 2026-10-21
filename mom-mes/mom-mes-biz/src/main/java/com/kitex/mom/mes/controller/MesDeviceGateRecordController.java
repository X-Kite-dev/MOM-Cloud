package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesDeviceGateRecordEntity;
import com.kitex.mom.mes.service.MesDeviceGateRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesDeviceGateRecord")
@Tag(name = "MES设备运行门审计")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesDeviceGateRecordController {

    private final MesDeviceGateRecordService service;

    @Operation(summary = "设备运行门判定分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<IPage<MesDeviceGateRecordEntity>> page(Page<MesDeviceGateRecordEntity> page,
                                                    MesDeviceGateRecordEntity query) {
        QueryWrapper<MesDeviceGateRecordEntity> wrapper = QueryGenerator.initQueryWrapper(query);
        wrapper.lambda().orderByDesc(MesDeviceGateRecordEntity::getCheckTime)
                .orderByDesc(MesDeviceGateRecordEntity::getId);
        return R.ok(service.page(page, wrapper));
    }
}
