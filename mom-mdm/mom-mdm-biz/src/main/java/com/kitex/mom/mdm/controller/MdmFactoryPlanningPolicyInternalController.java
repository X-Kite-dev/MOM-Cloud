package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.service.MdmFactoryPlanningPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 供 PLAN、MES 和 MomAI 读取唯一生效计划权威的内部接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmFactoryPlanningPolicy/internal")
public class MdmFactoryPlanningPolicyInternalController {
    private final MdmFactoryPlanningPolicyService service;

    @Inner
    @GetMapping("/active")
    public R<MdmFactoryPlanningPolicyDTO> active(@RequestParam String factoryCode) {
        return R.ok(service.requireActive(factoryCode));
    }

    /**
     * 固定路径版本供受控Agent工具调用；真实工厂仍由请求头和领域服务双重核验。
     */
    @Inner
    @GetMapping("/active/{factoryCode}")
    public R<MdmFactoryPlanningPolicyDTO> activeByPath(@PathVariable String factoryCode) {
        return R.ok(service.requireActive(factoryCode));
    }
}
