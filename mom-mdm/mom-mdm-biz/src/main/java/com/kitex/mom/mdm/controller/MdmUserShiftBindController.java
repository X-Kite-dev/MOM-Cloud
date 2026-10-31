package com.kitex.mom.mdm.controller;


import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.mdm.agent.service.MdmAgentActualScopeAccess;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmUserShiftBindEntity;
import com.kitex.mom.mdm.service.MdmClassesService;
import com.kitex.mom.mdm.service.MdmUserShiftBindService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmUserShiftBind")
@Tag(description = "mdmUserShiftBind", name = "用户-车间-工作中心绑定")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmUserShiftBindController {

    private final MdmUserShiftBindService service;
    private final MdmClassesService classesService;
    private final MdmAgentActualScopeAccess scopeAccess;


    @Operation(summary = "当前用户绑定信息", description = "当前用户绑定信息")
    @GetMapping("/currentBindings")
    public R<String> currentBindings() {
        scopeAccess.require(FactoryContextHolder.getFactoryCode(), "read");
        // 旧绑定只有用户主键，不能表达工厂及有效班期；读取实时计算且不产生写入。
        String shiftCode = classesService.getClassesRule(LocalDateTime.now());
        // R.ok(String)是提示消息重载，班次属于响应数据，须明确使用双参重载。
        return R.ok(shiftCode, null);
    }

    @Operation(summary = "当前用户绑定信息", description = "当前用户绑定信息")
    @PostMapping("/bind")
    public R<Void> bind() {
        scopeAccess.require(FactoryContextHolder.getFactoryCode(), "read");
        MdmUserShiftBindEntity mdmUserShiftBind = new MdmUserShiftBindEntity();
        MomUser currentUser = SecurityUtils.getUser();
        mdmUserShiftBind.setUserId(currentUser.getId());
        String classes = classesService.getClassesRule(LocalDateTime.now());
        if (StrUtil.isEmpty(classes)) {
            throw new RuntimeException("获取当前班次失败，请先维护班次");
        }
        mdmUserShiftBind.setShiftCode(classes);
        service.saveOrUpdate(mdmUserShiftBind);
        return R.ok();
    }

}
