package com.kitex.mom.mdm.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmDurationCalibrationDTO;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.service.MdmDurationCalibrationService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmDurationCalibration")
public class MdmDurationCalibrationController {
    private final MdmDurationCalibrationService service;
    private final MdmDurationCalibrationPolicyMapper policyMapper;
    private final MdmDurationCalibrationRunMapper runMapper;
    private final MdmDurationCalibrationProposalMapper proposalMapper;
    private final MdmDurationCalibrationSampleMapper sampleMapper;
    private final MdmDurationCalibrationLogMapper logMapper;

    @GetMapping("/policy/page")
    @PreAuthorize("@pms.hasPermission('mdm_durationCalibration_view')")
    public R<Page<MdmDurationCalibrationPolicyEntity>> policyPage(SearchPage page,
                                                                  @ParameterObject MdmDurationCalibrationPolicyEntity query) {
        return R.ok(policyMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<MdmDurationCalibrationPolicyEntity>()
                .like(StrUtil.isNotBlank(query.getPolicyNo()), MdmDurationCalibrationPolicyEntity::getPolicyNo, query.getPolicyNo())
                .eq(StrUtil.isNotBlank(query.getTargetType()), MdmDurationCalibrationPolicyEntity::getTargetType, query.getTargetType())
                .eq(StrUtil.isNotBlank(query.getScopeType()), MdmDurationCalibrationPolicyEntity::getScopeType, query.getScopeType())
                .eq(StrUtil.isNotBlank(query.getStatus()), MdmDurationCalibrationPolicyEntity::getStatus, query.getStatus())
                .orderByDesc(MdmDurationCalibrationPolicyEntity::getCreateTime)));
    }

    @PostMapping("/policy")
    @SysLog("新增实际工时校准策略草稿")
    @PreAuthorize("@pms.hasPermission('mdm_durationCalibration_policy')")
    public R<Long> savePolicy(@RequestBody MdmDurationCalibrationPolicyEntity policy) {
        return R.ok(service.savePolicy(policy));
    }

    @PutMapping("/policy")
    @SysLog("修改实际工时校准策略草稿")
    @PreAuthorize("@pms.hasPermission('mdm_durationCalibration_policy')")
    public R<Boolean> updatePolicy(@RequestBody MdmDurationCalibrationPolicyEntity policy) {
        return R.ok(service.updatePolicy(policy));
    }

    @PutMapping("/policy/status")
    @SysLog("启用或退役实际工时校准策略")
    @PreAuthorize("@pms.hasPermission('mdm_durationCalibration_approve')")
    public R<Boolean> policyStatus(@RequestBody MdmDurationCalibrationDTO.PolicyAction action) {
        return R.ok(service.changePolicyStatus(action));
    }

    @GetMapping("/run/page")
    @PreAuthorize("@pms.hasPermission('mdm_durationCalibration_view')")
    public R<Page<MdmDurationCalibrationRunEntity>> runPage(SearchPage page,
                                                            @ParameterObject MdmDurationCalibrationRunEntity query) {
        return R.ok(runMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<MdmDurationCalibrationRunEntity>()
                .like(StrUtil.isNotBlank(query.getRunNo()), MdmDurationCalibrationRunEntity::getRunNo, query.getRunNo())
                .eq(query.getPolicyId() != null, MdmDurationCalibrationRunEntity::getPolicyId, query.getPolicyId())
                .eq(StrUtil.isNotBlank(query.getTargetType()), MdmDurationCalibrationRunEntity::getTargetType, query.getTargetType())
                .eq(StrUtil.isNotBlank(query.getStatus()), MdmDurationCalibrationRunEntity::getStatus, query.getStatus())
                .orderByDesc(MdmDurationCalibrationRunEntity::getRequestedTime)));
    }

    @PostMapping("/run")
    @SysLog("执行实际工时校准")
    @PreAuthorize("@pms.hasPermission('mdm_durationCalibration_run')")
    public R<MdmDurationCalibrationRunEntity> run(@RequestBody MdmDurationCalibrationDTO.RunCommand command) {
        return R.ok(service.execute(command));
    }

    @GetMapping("/proposal/page")
    @PreAuthorize("@pms.hasPermission('mdm_durationCalibration_view')")
    public R<Page<MdmDurationCalibrationProposalEntity>> proposalPage(SearchPage page,
                                                                      @ParameterObject MdmDurationCalibrationProposalEntity query) {
        return R.ok(proposalMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<MdmDurationCalibrationProposalEntity>()
                .like(StrUtil.isNotBlank(query.getProposalNo()), MdmDurationCalibrationProposalEntity::getProposalNo, query.getProposalNo())
                .eq(query.getRunId() != null, MdmDurationCalibrationProposalEntity::getRunId, query.getRunId())
                .eq(StrUtil.isNotBlank(query.getTargetType()), MdmDurationCalibrationProposalEntity::getTargetType, query.getTargetType())
                .eq(StrUtil.isNotBlank(query.getProcessCode()), MdmDurationCalibrationProposalEntity::getProcessCode, query.getProcessCode())
                .eq(StrUtil.isNotBlank(query.getStatus()), MdmDurationCalibrationProposalEntity::getStatus, query.getStatus())
                .orderByDesc(MdmDurationCalibrationProposalEntity::getCreateTime)));
    }

    @PutMapping("/proposal")
    @SysLog("调整实际工时校准建议")
    @PreAuthorize("@pms.hasPermission('mdm_durationCalibration_review')")
    public R<Boolean> updateProposal(@RequestBody MdmDurationCalibrationDTO.ProposalUpdate command) {
        return R.ok(service.updateProposal(command));
    }

    @PutMapping("/proposal/submit")
    @SysLog("提交实际工时校准建议")
    @PreAuthorize("@pms.hasPermission('mdm_durationCalibration_review')")
    public R<Boolean> submitProposal(@RequestBody MdmDurationCalibrationDTO.ProposalAction action) {
        if (action == null || !"SUBMIT".equalsIgnoreCase(action.getAction())) {
            throw new CheckedException("该入口只允许提交校准建议");
        }
        return R.ok(service.changeProposalStatus(action));
    }

    @PutMapping("/proposal/decision")
    @SysLog("批准、驳回或退役实际工时校准建议")
    @PreAuthorize("@pms.hasPermission('mdm_durationCalibration_approve')")
    public R<Boolean> decideProposal(@RequestBody MdmDurationCalibrationDTO.ProposalAction action) {
        if (action == null || !List.of("APPROVE", "REJECT", "RETIRE")
                .contains(StrUtil.blankToDefault(action.getAction(), "").toUpperCase())) {
            throw new CheckedException("该入口只允许批准、驳回或退役校准建议");
        }
        return R.ok(service.changeProposalStatus(action));
    }

    @GetMapping("/proposal/{id}/samples")
    @PreAuthorize("@pms.hasPermission('mdm_durationCalibration_view')")
    public R<List<MdmDurationCalibrationSampleEntity>> samples(@PathVariable Long id) {
        return R.ok(sampleMapper.selectList(new LambdaQueryWrapper<MdmDurationCalibrationSampleEntity>()
                .eq(MdmDurationCalibrationSampleEntity::getProposalId, id)
                .orderByDesc(MdmDurationCalibrationSampleEntity::getOccurredTime)));
    }

    @GetMapping("/{objectType}/{id}/logs")
    @PreAuthorize("@pms.hasPermission('mdm_durationCalibration_view')")
    public R<List<MdmDurationCalibrationLogEntity>> logs(@PathVariable String objectType, @PathVariable Long id) {
        return R.ok(logMapper.selectList(new LambdaQueryWrapper<MdmDurationCalibrationLogEntity>()
                .eq(MdmDurationCalibrationLogEntity::getObjectType, objectType.toUpperCase())
                .eq(MdmDurationCalibrationLogEntity::getObjectId, id)
                .orderByDesc(MdmDurationCalibrationLogEntity::getOperatedTime)));
    }

    @PostMapping("/internal/catalog")
    @Inner
    public R<MdmDurationCalibrationDTO.Catalog> catalog(@RequestBody MdmDurationCalibrationDTO.CatalogQuery query) {
        return R.ok(service.catalog(query));
    }
}
