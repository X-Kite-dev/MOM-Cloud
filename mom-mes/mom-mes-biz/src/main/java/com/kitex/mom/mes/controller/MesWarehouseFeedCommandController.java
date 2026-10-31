package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesWarehouseFeedCommandDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedPreparationDTO;
import com.kitex.mom.mes.service.MesWarehouseFeedCommandService;
import com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * 原新增投料权限下的本人稳定命令；不授予查询他人正文或取消已投实物的权限。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehouseFeedCommand")
@Tag(name = "投料原号与永久回执")
@PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_add')")
public class MesWarehouseFeedCommandController {
    private final MesWarehouseFeedCommandService commands;
    private final MesPhysicalTransformCodec codec;

    @Operation(summary = "读取稳定投料实际身份")
    @GetMapping("/context")
    public R<MesWarehouseFeedCommandDTO.Context> context() {
        return R.ok(commands.context());
    }

    @Operation(summary = "从正式签收通知读取投料线索，不代表可用量")
    @GetMapping("/receipt-source/{noticeId}")
    public R<MesWarehouseFeedPreparationDTO.ReceiptSeed> receiptSeed(@PathVariable Long noticeId) {
        return R.ok(commands.receiptSeed(noticeId));
    }

    @Operation(summary = "分页选择当前工厂派工，原工单精确过滤")
    @GetMapping("/candidates")
    public R<MesWarehouseFeedPreparationDTO.Candidates> candidates(@RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) String taskCode,
            @RequestParam(required = false) String keyword) {
        return R.ok(commands.candidates(current, size, taskCode, keyword));
    }

    @Operation(summary = "读取实际派工计量与投料来源候选，非预留")
    @GetMapping("/prepare")
    public R<MesWarehouseFeedPreparationDTO.Observation> prepare(@ModelAttribute MesWarehouseFeedPreparationDTO.Query query) {
        return R.ok(commands.prepare(query));
    }

    @Operation(summary = "按完整原文同号投料")
    @PostMapping(consumes = "application/json")
    public R<MesWarehouseFeedCommandDTO.Receipt> execute(@RequestBody String raw) {
        return R.ok(commands.execute(raw));
    }

    @Operation(summary = "本人原号独立最新核账")
    @GetMapping("/{requestId}/receipt")
    public R<MesWarehouseFeedCommandDTO.Receipt> receipt(@PathVariable String requestId) {
        return R.ok(commands.receipt(requestId));
    }

    @Operation(summary = "永久作废未执行原文，不撤销已投实物")
    @PostMapping(value = "/cancel", consumes = "application/json")
    public R<MesWarehouseFeedCommandDTO.Receipt> cancel(@RequestBody String wrapper) {
        var value = codec.objectDocument(wrapper, 256 * 1024);
        MesPhysicalTransformCodec.require(value.size() == 2
                        && Set.of("originalRaw", "reason").stream().allMatch(key -> value.has(key) && value.get(key).isTextual()),
                "作废须保留完整originalRaw及文本reason，禁止重建命令");
        return R.ok(commands.cancel(value.get("originalRaw").textValue(), value.get("reason").textValue()));
    }
}
