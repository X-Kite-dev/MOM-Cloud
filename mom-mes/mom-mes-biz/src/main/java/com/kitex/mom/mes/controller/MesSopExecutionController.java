package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesSopAcknowledgeCommand;
import com.kitex.mom.mes.service.impl.MesSopExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

/** 指定执行人的原冻结SOP查阅、签收、原件代理与回执恢复。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/arrange/sop")
public class MesSopExecutionController {
    private final MesSopExecutionService service;

    @GetMapping("/{id}")
    public R<?> view(@PathVariable Long id, @RequestParam(defaultValue = "1") long current) {
        return R.ok(service.view(id, current));
    }

    @PostMapping("/{id}/acknowledge")
    @SysLog("执行人签收冻结SOP引用清单")
    public R<?> acknowledge(@PathVariable Long id, @RequestBody MesSopAcknowledgeCommand command) {
        return R.ok(service.acknowledge(id, command));
    }

    /** 以原派工和冻结文件key取原件，不接受任意文档地址或版本替换。 */
    @GetMapping("/{id}/formal-original")
    public ResponseEntity<byte[]> original(@PathVariable Long id, @RequestParam String documentKey) {
        return service.original(id, documentKey);
    }

    /** 本人的精确签收原请求回执，不以重复生成请求号处理未知结果。 */
    @GetMapping("/{id}/requests/{requestId}")
    public R<?> receipt(@PathVariable Long id, @PathVariable String requestId) {
        return R.ok(service.receipt(id, requestId));
    }
}
