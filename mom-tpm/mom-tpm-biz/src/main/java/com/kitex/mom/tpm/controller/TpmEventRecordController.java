package com.kitex.mom.tpm.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.entity.TpmEventRecordEntity;
import com.kitex.mom.tpm.service.TpmEventRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * TPM设备事件记录表
 *
 * @author kitex
 * @date 2025-03-18 10:07:01
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmEventRecord")
@Tag(description = "tpmEventRecord", name = "TPM设备事件记录表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmEventRecordController {

    private final TpmEventRecordService tpmEventRecordService;

    /**
     * 新增TPM设备事件记录表
     */
    @Operation(summary = "新增TPM设备事件记录表", description = "新增TPM设备事件记录表")
    @PostMapping
    public R saveBatch(@RequestBody List<TpmEventRecordEntity> tpmEventRecord) {
        return R.ok(tpmEventRecordService.saveBatch(tpmEventRecord));
    }

    /**
     * 根据设备编码查询事件记录
     */
    @Operation(summary = "根据设备编码查询事件记录", description = "根据设备编码查询事件记录")
    @GetMapping("/getByDeviceCode")
    public R getByDeviceCode(@RequestParam("deviceCode") String deviceCode) {
        return R.ok(tpmEventRecordService.list(new LambdaQueryWrapper<TpmEventRecordEntity>()
                .eq(TpmEventRecordEntity::getDeviceCode, deviceCode)
                .orderByDesc(TpmEventRecordEntity::getOccurredAt)));
    }

    @Operation(summary = "设备开机记录分页查询", description = "设备开机记录分页查询")
    @GetMapping("/page")
    public R<IPage<TpmEventRecordEntity>> page(Page<TpmEventRecordEntity> pageParam, TpmEventRecordEntity query) {
        QueryWrapper<TpmEventRecordEntity> autoQueryWrapper = new QueryWrapper<>();
        autoQueryWrapper.lambda()
                .eq(StrUtil.isNotBlank(query.getDeviceCode()), TpmEventRecordEntity::getDeviceCode, query.getDeviceCode())
                .eq(StrUtil.isNotBlank(query.getDeviceName()), TpmEventRecordEntity::getDeviceName, query.getDeviceName())
                .eq(StrUtil.isNotBlank(query.getEventSource()), TpmEventRecordEntity::getEventSource, query.getEventSource())
                .orderByDesc(TpmEventRecordEntity::getOccurredAt);
        return R.ok(tpmEventRecordService.page(pageParam, autoQueryWrapper));
    }

}