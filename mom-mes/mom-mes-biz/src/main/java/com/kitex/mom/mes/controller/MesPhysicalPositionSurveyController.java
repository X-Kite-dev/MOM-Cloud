package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesPhysicalPositionSurveyDTO;
import com.kitex.mom.mes.service.impl.MesPhysicalPositionSurveyPrecheckService;
import com.kitex.mom.mes.service.MesPhysicalPositionSurveyService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 实际母件位置预检、永久采集和受控决策入口；不开放通用CRUD或转换执行。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesPhysicalTransform/position-surveys")
public class MesPhysicalPositionSurveyController {
    private final MesPhysicalPositionSurveyPrecheckService precheck;
    private final MesPhysicalPositionSurveyService surveys;

    /** 输入为严格原字符串，不允许端侧直接保存服务器证据或声明质量放行。 */
    @Operation(summary = "真实母件完整位置与来源当量预检")
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    @PostMapping(value = "/preview", consumes = "application/json")
    public R<MesPhysicalPositionSurveyDTO.Preview> preview(@RequestBody String rawJson) {
        return R.ok(precheck.preview(rawJson));
    }

    /** 端侧只发永久原号及采集原字符串，服务器同事务重读真实来源。 */
    @Operation(summary = "永久登记真实母件完整位置观察")
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    @PostMapping(value = "/{requestId}/capture", consumes = "application/json")
    public R<MesPhysicalPositionSurveyDTO.Receipt> capture(@PathVariable String requestId, @RequestBody String rawJson) {
        return R.ok(surveys.capture(requestId, rawJson));
    }

    /** 异人确认/驳回或本人取消；原观察不可修改，确认不代表质量放行。 */
    @Operation(summary = "受控确认、驳回或取消母件位置观察")
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    @PostMapping(value = "/{requestId}/decisions/{operationRequestId}", consumes = "application/json")
    public R<MesPhysicalPositionSurveyDTO.Receipt> decide(@PathVariable String requestId, @PathVariable String operationRequestId,
                                                       @RequestBody String rawJson) {
        return R.ok(surveys.decide(requestId, operationRequestId, rawJson));
    }

    /** 当前原账独立恢复，保留原号查证未知结果，不依据最新库存重算。 */
    @Operation(summary = "查询完整永久母件位置观察及全部原决策")
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    @GetMapping("/{requestId}/receipt")
    public R<MesPhysicalPositionSurveyDTO.Receipt> receipt(@PathVariable String requestId) {
        return R.ok(surveys.receipt(requestId));
    }
}
