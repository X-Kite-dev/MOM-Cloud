package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesMobileExecutionDTO;
import com.kitex.mom.mes.service.MesTerminalExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 移动派工工作台入口；权限与实名保护同时位于服务层。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/arrange/mobile-execution")
public class MesMobileExecutionController {
    private final MesTerminalExecutionService service;

    /** 事实快照与动作可用性，不表示开工/完工全部门禁已经通过。 */
    @GetMapping("/{arrangeId}")
    public R<MesMobileExecutionDTO.View> view(@PathVariable String arrangeId) {
        return R.ok(service.view(arrangeId));
    }

    /** POST不使用通用防重复注解替代永久原号查重。 */
    @PostMapping("/command")
    public R<MesMobileExecutionDTO.Receipt> command(@RequestBody MesMobileExecutionDTO.Command command) {
        return R.ok(service.command(command));
    }

    /** 查询原号；ABSENT/HTTP失败不能解释为未执行。 */
    @GetMapping("/receipt/{requestId}")
    public R<MesMobileExecutionDTO.Receipt> receipt(@PathVariable String requestId) {
        return R.ok(service.receipt(requestId));
    }
}
