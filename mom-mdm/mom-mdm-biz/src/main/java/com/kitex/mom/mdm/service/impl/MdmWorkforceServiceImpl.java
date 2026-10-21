package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmEmployeeWorkforceProfileDTO;
import com.kitex.mom.mdm.dto.MdmWorkforceAvailabilityQueryDTO;
import com.kitex.mom.mdm.dto.MdmWorkforceCandidateDTO;
import com.kitex.mom.mdm.dto.MdmWorkforceWindowDTO;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.service.MdmPlanResourceEventOutboxService;
import com.kitex.mom.mdm.service.MdmWorkforceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MdmWorkforceServiceImpl implements MdmWorkforceService {
    private static final int MAX_QUERY_DAYS = 730;
    private static final Set<String> EXCEPTION_TYPES = Set.of("OFF", "LEAVE", "TRAINING", "OVERTIME");
    private final MdmSkillMapper skillMapper;
    private final MdmEmployeeSkillMapper employeeSkillMapper;
    private final MdmEmployeeShiftPatternMapper shiftPatternMapper;
    private final MdmEmployeeCalendarExceptionMapper calendarExceptionMapper;
    private final MdmProcedureSkillRequirementMapper requirementMapper;
    private final MdmEmployeeMapper employeeMapper;
    private final MdmEmployeeTeamMapper employeeTeamMapper;
    private final MdmProcedureMapper procedureMapper;
    private final MdmClassesMapper classesMapper;
    private final MdmPlanResourceEventOutboxService resourceEventOutboxService;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    public List<MdmSkillEntity> listSkills(Boolean enabledOnly) {
        return skillMapper.selectList(new LambdaQueryWrapper<MdmSkillEntity>()
                .eq(Boolean.TRUE.equals(enabledOnly), MdmSkillEntity::getStatus, true)
                .orderByAsc(MdmSkillEntity::getSkillCategory)
                .orderByAsc(MdmSkillEntity::getSkillCode));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveSkill(MdmSkillEntity skill) {
        if (skill == null || StrUtil.isBlank(skill.getSkillCode()) || StrUtil.isBlank(skill.getSkillName())) {
            throw new CheckedException("技能编码和技能名称不能为空");
        }
        skill.setSkillCode(skill.getSkillCode().trim());
        skill.setSkillName(skill.getSkillName().trim());
        if (skill.getStatus() == null) {
            skill.setStatus(true);
        }
        if (skill.getCertificateRequired() == null) {
            skill.setCertificateRequired(false);
        }
        Long duplicate = skillMapper.selectCount(new LambdaQueryWrapper<MdmSkillEntity>()
                .eq(MdmSkillEntity::getSkillCode, skill.getSkillCode())
                .ne(skill.getId() != null, MdmSkillEntity::getId, skill.getId()));
        if (duplicate > 0) {
            throw new CheckedException("技能编码[" + skill.getSkillCode() + "]已存在");
        }
        if (Boolean.FALSE.equals(skill.getStatus()) && skill.getId() != null && activeReferenceCount(skill.getSkillCode()) > 0) {
            throw new CheckedException("技能仍被启用的员工资质或工序要求引用，不能停用");
        }
        if (skill.getId() == null) {
            skillMapper.insert(skill);
        } else if (skillMapper.updateById(skill) != 1) {
            throw new CheckedException("技能不存在或已删除");
        }
        return skill.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeSkill(Long id) {
        MdmSkillEntity skill = id == null ? null : skillMapper.selectById(id);
        if (skill == null) {
            throw new CheckedException("技能不存在或已删除");
        }
        long employeeCount = employeeSkillMapper.selectCount(new LambdaQueryWrapper<MdmEmployeeSkillEntity>()
                .eq(MdmEmployeeSkillEntity::getSkillCode, skill.getSkillCode()));
        long requirementCount = requirementMapper.selectCount(new LambdaQueryWrapper<MdmProcedureSkillRequirementEntity>()
                .eq(MdmProcedureSkillRequirementEntity::getSkillCode, skill.getSkillCode()));
        if (employeeCount + requirementCount > 0) {
            throw new CheckedException("技能仍被员工资质或工序要求引用，请先停用并解除引用");
        }
        return skillMapper.deleteById(id) == 1;
    }

    @Override
    public MdmEmployeeWorkforceProfileDTO getEmployeeProfile(String employeeCode) {
        MdmEmployeeEntity employee = requireEmployee(employeeCode);
        MdmEmployeeWorkforceProfileDTO result = new MdmEmployeeWorkforceProfileDTO();
        result.setEmployeeCode(employee.getEmployeeCode());
        result.setEmployeeName(employee.getName());
        result.setSkills(employeeSkillMapper.selectList(new LambdaQueryWrapper<MdmEmployeeSkillEntity>()
                .eq(MdmEmployeeSkillEntity::getEmployeeCode, employeeCode)
                .eq(MdmEmployeeSkillEntity::getStatus, true)
                .orderByAsc(MdmEmployeeSkillEntity::getSkillCode)));
        result.setShiftPatterns(shiftPatternMapper.selectList(new LambdaQueryWrapper<MdmEmployeeShiftPatternEntity>()
                .eq(MdmEmployeeShiftPatternEntity::getEmployeeCode, employeeCode)
                .orderByAsc(MdmEmployeeShiftPatternEntity::getDayOfWeek)
                .orderByAsc(MdmEmployeeShiftPatternEntity::getEffectiveFrom)));
        result.setCalendarExceptions(calendarExceptionMapper.selectList(
                new LambdaQueryWrapper<MdmEmployeeCalendarExceptionEntity>()
                        .eq(MdmEmployeeCalendarExceptionEntity::getEmployeeCode, employeeCode)
                        .orderByAsc(MdmEmployeeCalendarExceptionEntity::getCalendarDate)
                        .orderByAsc(MdmEmployeeCalendarExceptionEntity::getStartTime)));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveEmployeeProfile(MdmEmployeeWorkforceProfileDTO profile) {
        if (profile == null) {
            throw new CheckedException("人员能力档案不能为空");
        }
        MdmEmployeeEntity employee = requireEmployee(profile.getEmployeeCode());
        if (!"1".equals(employee.getStatus())) {
            throw new CheckedException("离职员工不能维护为可排产人员");
        }
        List<MdmSkillEntity> enabledSkills = listSkills(true);
        Map<String, MdmSkillEntity> skillMap = enabledSkills.stream()
                .collect(Collectors.toMap(MdmSkillEntity::getSkillCode, Function.identity(), (a, b) -> a));
        Map<String, MdmClassesEntity> shiftMap = classesMapper.selectList(new LambdaQueryWrapper<MdmClassesEntity>()
                        .eq(MdmClassesEntity::getStatus, true)).stream()
                .collect(Collectors.toMap(MdmClassesEntity::getShiftCode, Function.identity(), (a, b) -> a));
        List<MdmEmployeeSkillEntity> skills = Optional.ofNullable(profile.getSkills()).orElse(List.of());
        List<MdmEmployeeShiftPatternEntity> patterns = Optional.ofNullable(profile.getShiftPatterns()).orElse(List.of());
        List<MdmEmployeeCalendarExceptionEntity> exceptions = Optional.ofNullable(profile.getCalendarExceptions()).orElse(List.of());
        validateSkills(skills, skillMap);
        validatePatterns(patterns, shiftMap);
        validateExceptions(exceptions);
        Map<String, MdmEmployeeCalendarExceptionEntity> existingExceptions = calendarExceptionMapper.selectList(
                        new LambdaQueryWrapper<MdmEmployeeCalendarExceptionEntity>()
                                .eq(MdmEmployeeCalendarExceptionEntity::getEmployeeCode, employee.getEmployeeCode())).stream()
                .collect(Collectors.toMap(this::calendarExceptionKey, Function.identity(), (left, right) -> left));
        Map<String, MdmEmployeeSkillEntity> existingSkills = employeeSkillMapper.selectList(
                        new LambdaQueryWrapper<MdmEmployeeSkillEntity>()
                                .eq(MdmEmployeeSkillEntity::getEmployeeCode, employee.getEmployeeCode())).stream()
                .collect(Collectors.toMap(MdmEmployeeSkillEntity::getSkillCode, Function.identity(),
                        (left, right) -> left));

        shiftPatternMapper.delete(new LambdaQueryWrapper<MdmEmployeeShiftPatternEntity>()
                .eq(MdmEmployeeShiftPatternEntity::getEmployeeCode, employee.getEmployeeCode()));
        MomUser user = SecurityUtils.getUser();
        LocalDateTime verifiedTime = LocalDateTime.now();
        for (MdmEmployeeSkillEntity item : skills) {
            MdmSkillEntity master = skillMap.get(item.getSkillCode());
            MdmEmployeeSkillEntity existingSkill = existingSkills.remove(item.getSkillCode());
            boolean enabled = !Boolean.FALSE.equals(item.getStatus());
            item.setId(existingSkill == null ? null : existingSkill.getId());
            item.setEmployeeCode(employee.getEmployeeCode());
            item.setEmployeeName(employee.getName());
            item.setSkillName(master.getSkillName());
            item.setStatus(enabled);
            item.setInvalidTime(enabled ? null : existingSkill != null
                    && Boolean.FALSE.equals(existingSkill.getStatus()) && existingSkill.getInvalidTime() != null
                    ? existingSkill.getInvalidTime() : verifiedTime);
            item.setVerifiedBy(user == null ? null : user.getUsername());
            item.setVerifiedTime(verifiedTime);
            item.setFactoryCode(employee.getFactoryCode());
            require(existingSkill == null ? employeeSkillMapper.insert(item) == 1
                    : employeeSkillMapper.updateById(item) == 1, "员工资质保存失败");
        }
        // 从档案界面移除资质不再物理删除，而是保留停用事实，便于追溯并可靠触发排产影响评估。
        for (MdmEmployeeSkillEntity removed : existingSkills.values()) {
            if (Boolean.TRUE.equals(removed.getStatus())) {
                removed.setStatus(false);
                removed.setInvalidTime(verifiedTime);
                removed.setVerifiedBy(user == null ? null : user.getUsername());
                removed.setVerifiedTime(verifiedTime);
                require(employeeSkillMapper.updateById(removed) == 1, "移除员工资质失败");
            }
        }
        for (MdmEmployeeShiftPatternEntity item : patterns) {
            MdmClassesEntity shift = shiftMap.get(item.getShiftCode());
            item.setId(null);
            item.setEmployeeCode(employee.getEmployeeCode());
            item.setEmployeeName(employee.getName());
            item.setShiftName(shift.getShiftName());
            item.setStatus(!Boolean.FALSE.equals(item.getStatus()));
            shiftPatternMapper.insert(item);
        }
        for (MdmEmployeeCalendarExceptionEntity item : exceptions) {
            item.setEmployeeCode(employee.getEmployeeCode());
            item.setEmployeeName(employee.getName());
            item.setExceptionType(item.getExceptionType().toUpperCase(Locale.ROOT));
            item.setStatus(!Boolean.FALSE.equals(item.getStatus()));
            item.setReason(StrUtil.trim(item.getReason()));
            item.setFactoryCode(employee.getFactoryCode());
            MdmEmployeeCalendarExceptionEntity existing = existingExceptions.remove(calendarExceptionKey(item));
            if (existing == null) {
                item.setId(null);
                require(calendarExceptionMapper.insert(item) == 1, "人员日历例外保存失败");
            } else {
                item.setId(existing.getId());
                require(calendarExceptionMapper.updateById(item) == 1, "人员日历例外更新失败");
            }
        }
        for (MdmEmployeeCalendarExceptionEntity removed : existingExceptions.values()) {
            require(calendarExceptionMapper.deleteById(removed.getId()) == 1, "旧人员日历例外删除失败");
        }
        List<MdmEmployeeSkillEntity> persistedSkills = employeeSkillMapper.selectList(
                new LambdaQueryWrapper<MdmEmployeeSkillEntity>()
                        .eq(MdmEmployeeSkillEntity::getEmployeeCode, employee.getEmployeeCode()));
        resourceEventOutboxService.synchronizeEmployeeQualifications(employee, persistedSkills);
        resourceEventOutboxService.synchronizeEmployeeCalendar(employee, exceptions);
        return true;
    }

    @Override
    public List<MdmProcedureSkillRequirementEntity> getProcedureRequirements(String processCode) {
        requireProcedure(processCode);
        return requirementMapper.selectList(new LambdaQueryWrapper<MdmProcedureSkillRequirementEntity>()
                .eq(MdmProcedureSkillRequirementEntity::getProcessCode, processCode)
                .orderByAsc(MdmProcedureSkillRequirementEntity::getSkillCode));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveProcedureRequirements(String processCode,
                                             List<MdmProcedureSkillRequirementEntity> requirements) {
        MdmProcedureEntity procedure = requireProcedure(processCode);
        Map<String, MdmSkillEntity> skillMap = listSkills(true).stream()
                .collect(Collectors.toMap(MdmSkillEntity::getSkillCode, Function.identity(), (a, b) -> a));
        List<MdmProcedureSkillRequirementEntity> safe = Optional.ofNullable(requirements).orElse(List.of());
        Set<String> seen = new HashSet<>();
        for (MdmProcedureSkillRequirementEntity item : safe) {
            MdmSkillEntity skill = item == null ? null : skillMap.get(item.getSkillCode());
            if (skill == null) {
                throw new CheckedException("工序技能要求引用了不存在或停用的技能");
            }
            if (!seen.add(item.getSkillCode())) {
                throw new CheckedException("同一工序不能重复要求技能[" + item.getSkillCode() + "]");
            }
            if (item.getMinimumLevel() == null || item.getMinimumLevel() < 1 || item.getMinimumLevel() > 5) {
                throw new CheckedException("技能[" + item.getSkillCode() + "]最低等级必须为1至5");
            }
            if (item.getRequiredCount() == null || item.getRequiredCount() < 1 || item.getRequiredCount() > 20) {
                throw new CheckedException("技能[" + item.getSkillCode() + "]要求人数必须为1至20");
            }
        }
        requirementMapper.delete(new LambdaQueryWrapper<MdmProcedureSkillRequirementEntity>()
                .eq(MdmProcedureSkillRequirementEntity::getProcessCode, processCode));
        for (MdmProcedureSkillRequirementEntity item : safe) {
            MdmSkillEntity skill = skillMap.get(item.getSkillCode());
            item.setId(null);
            item.setProcessCode(procedure.getProcessCode());
            item.setProcessName(procedure.getProcessName());
            item.setSkillName(skill.getSkillName());
            item.setCertificateRequired(Boolean.TRUE.equals(item.getCertificateRequired())
                    || Boolean.TRUE.equals(skill.getCertificateRequired()));
            item.setRequiredCount(Optional.ofNullable(item.getRequiredCount()).orElse(1));
            item.setStatus(!Boolean.FALSE.equals(item.getStatus()));
            requirementMapper.insert(item);
        }
        return true;
    }

    @Override
    public List<MdmWorkforceCandidateDTO> availability(MdmWorkforceAvailabilityQueryDTO query) {
        validateAvailabilityQuery(query);
        List<String> employeeCodes = query.getEmployeeCodes().stream().filter(StrUtil::isNotBlank).distinct().toList();
        List<String> processCodes = query.getProcessCodes().stream().filter(StrUtil::isNotBlank).distinct().toList();
        Map<String, List<MdmProcedureSkillRequirementEntity>> requirements = requirementMapper.selectList(
                        new LambdaQueryWrapper<MdmProcedureSkillRequirementEntity>()
                                .in(MdmProcedureSkillRequirementEntity::getProcessCode, processCodes)
                                .eq(MdmProcedureSkillRequirementEntity::getStatus, true)).stream()
                .collect(Collectors.groupingBy(MdmProcedureSkillRequirementEntity::getProcessCode));
        Set<String> enabledSkillCodes = skillMapper.selectList(new LambdaQueryWrapper<MdmSkillEntity>()
                        .eq(MdmSkillEntity::getStatus, true)).stream()
                .map(MdmSkillEntity::getSkillCode).collect(Collectors.toSet());
        if (employeeCodes.isEmpty()) {
            return processCodes.stream().map(processCode -> {
                MdmWorkforceCandidateDTO candidate = new MdmWorkforceCandidateDTO();
                candidate.setProcessCode(processCode);
                candidate.setEmployeeActive(false);
                candidate.setQualified(false);
                candidate.setQualificationReason("仅返回工序技能要求快照，尚未提供候选员工");
                candidate.setRequirementSnapshot(new ArrayList<>(requirements.getOrDefault(processCode, List.of())));
                return candidate;
            }).toList();
        }
        Map<String, MdmEmployeeEntity> employees = employeeMapper.selectList(new LambdaQueryWrapper<MdmEmployeeEntity>()
                        .in(MdmEmployeeEntity::getEmployeeCode, employeeCodes)).stream()
                .collect(Collectors.toMap(MdmEmployeeEntity::getEmployeeCode, Function.identity(), (a, b) -> a));
        Map<String, List<MdmEmployeeSkillEntity>> skills = employeeSkillMapper.selectList(
                        new LambdaQueryWrapper<MdmEmployeeSkillEntity>()
                                .in(MdmEmployeeSkillEntity::getEmployeeCode, employeeCodes)
                                .eq(MdmEmployeeSkillEntity::getStatus, true)).stream()
                .filter(item -> enabledSkillCodes.contains(item.getSkillCode()))
                .collect(Collectors.groupingBy(MdmEmployeeSkillEntity::getEmployeeCode));
        Map<String, List<MdmEmployeeTeamEntity>> teams = employeeTeamMapper.selectList(
                        new LambdaQueryWrapper<MdmEmployeeTeamEntity>()
                                .in(MdmEmployeeTeamEntity::getEmployeeCode, employeeCodes)
                                .eq(MdmEmployeeTeamEntity::getStatus, true)).stream()
                .collect(Collectors.groupingBy(MdmEmployeeTeamEntity::getEmployeeCode));
        Map<String, List<MdmEmployeeShiftPatternEntity>> patterns = shiftPatternMapper.selectList(
                        new LambdaQueryWrapper<MdmEmployeeShiftPatternEntity>()
                                .in(MdmEmployeeShiftPatternEntity::getEmployeeCode, employeeCodes)
                                .eq(MdmEmployeeShiftPatternEntity::getStatus, true)).stream()
                .collect(Collectors.groupingBy(MdmEmployeeShiftPatternEntity::getEmployeeCode));
        Map<String, List<MdmEmployeeCalendarExceptionEntity>> exceptions = calendarExceptionMapper.selectList(
                        new LambdaQueryWrapper<MdmEmployeeCalendarExceptionEntity>()
                                .in(MdmEmployeeCalendarExceptionEntity::getEmployeeCode, employeeCodes)
                                .between(MdmEmployeeCalendarExceptionEntity::getCalendarDate,
                                        query.getStartTime().toLocalDate(), query.getEndTime().toLocalDate())
                                .eq(MdmEmployeeCalendarExceptionEntity::getStatus, true)).stream()
                .collect(Collectors.groupingBy(MdmEmployeeCalendarExceptionEntity::getEmployeeCode));
        Map<String, MdmClassesEntity> shifts = classesMapper.selectList(new LambdaQueryWrapper<MdmClassesEntity>()
                        .eq(MdmClassesEntity::getStatus, true)).stream()
                .collect(Collectors.toMap(MdmClassesEntity::getShiftCode, Function.identity(), (a, b) -> a));

        List<MdmWorkforceCandidateDTO> result = new ArrayList<>();
        for (String employeeCode : employeeCodes) {
            MdmEmployeeEntity employee = employees.get(employeeCode);
            List<MdmWorkforceWindowDTO> roster = buildRoster(patterns.getOrDefault(employeeCode, List.of()),
                    exceptions.getOrDefault(employeeCode, List.of()), shifts, query.getStartTime(), query.getEndTime());
            for (String processCode : processCodes) {
                List<MdmProcedureSkillRequirementEntity> required = requirements.getOrDefault(processCode, List.of());
                List<MdmEmployeeSkillEntity> employeeSkills = skills.getOrDefault(employeeCode, List.of());
                MdmWorkforceCandidateDTO candidate = new MdmWorkforceCandidateDTO();
                candidate.setEmployeeCode(employeeCode);
                candidate.setEmployeeName(employee == null ? employeeCode : employee.getName());
                candidate.setProcessCode(processCode);
                candidate.setEmployeeActive(employee != null && "1".equals(employee.getStatus()));
                candidate.setRequirementSnapshot(new ArrayList<>(required));
                candidate.setSkillSnapshot(new ArrayList<>(employeeSkills));
                candidate.setMatchedSkillCodes(required.stream().filter(requirement -> employeeSkills.stream()
                                .anyMatch(skill -> matches(requirement, skill) && overlaps(skill.getValidFrom(), skill.getValidTo(),
                                        query.getStartTime().toLocalDate(), query.getEndTime().toLocalDate())))
                        .map(MdmProcedureSkillRequirementEntity::getSkillCode).distinct().toList());
                List<MdmEmployeeTeamEntity> employeeTeams = teams.getOrDefault(employeeCode, List.of());
                candidate.setTeamCodes(employeeTeams.stream().map(MdmEmployeeTeamEntity::getTeamCode)
                        .filter(StrUtil::isNotBlank).distinct().toList());
                candidate.setTeamNames(employeeTeams.stream().map(MdmEmployeeTeamEntity::getTeamName)
                        .filter(StrUtil::isNotBlank).distinct().toList());
                if (!Boolean.TRUE.equals(candidate.getEmployeeActive())) {
                    candidate.setQualified(false);
                    candidate.setQualificationReason("员工不存在或已离职");
                } else {
                    String gap = qualificationGap(required, employeeSkills,
                            query.getStartTime().toLocalDate(), query.getEndTime().toLocalDate());
                    candidate.setQualified(gap == null);
                    candidate.setQualificationReason(gap == null
                            ? (required.isEmpty() ? "工序未设置专项技能要求" : "技能等级、证书与有效期满足要求") : gap);
                    // 返回完整在岗窗口和技能快照。单人模式继续使用qualified，多人模式由APS按技能人数覆盖并取共同窗口。
                    candidate.setAvailableWindows(new ArrayList<>(roster));
                }
                if (Boolean.TRUE.equals(candidate.getQualified()) && candidate.getAvailableWindows().isEmpty()) {
                    candidate.setQualificationReason(candidate.getQualificationReason() + "；查询范围内没有有效在岗班次");
                }
                result.add(candidate);
            }
        }
        return result;
    }

    private void validateSkills(List<MdmEmployeeSkillEntity> skills, Map<String, MdmSkillEntity> skillMap) {
        Set<String> seen = new HashSet<>();
        for (MdmEmployeeSkillEntity item : skills) {
            MdmSkillEntity skill = item == null ? null : skillMap.get(item.getSkillCode());
            if (skill == null) {
                throw new CheckedException("员工资质引用了不存在或停用的技能");
            }
            if (!seen.add(item.getSkillCode())) {
                throw new CheckedException("同一员工不能重复维护技能[" + item.getSkillCode() + "]");
            }
            if (item.getProficiencyLevel() == null || item.getProficiencyLevel() < 1 || item.getProficiencyLevel() > 5) {
                throw new CheckedException("技能[" + item.getSkillCode() + "]等级必须为1至5");
            }
            if (item.getValidFrom() != null && item.getValidTo() != null && item.getValidFrom().isAfter(item.getValidTo())) {
                throw new CheckedException("技能[" + item.getSkillCode() + "]有效开始日期不能晚于截止日期");
            }
            if (Boolean.TRUE.equals(skill.getCertificateRequired()) && StrUtil.isBlank(item.getCertificateNo())) {
                throw new CheckedException("技能[" + item.getSkillCode() + "]必须维护证书编号");
            }
        }
    }

    private void validatePatterns(List<MdmEmployeeShiftPatternEntity> patterns,
                                  Map<String, MdmClassesEntity> shiftMap) {
        for (MdmEmployeeShiftPatternEntity item : patterns) {
            if (item == null || item.getDayOfWeek() == null || item.getDayOfWeek() < 1 || item.getDayOfWeek() > 7) {
                throw new CheckedException("周班次星期必须为1至7");
            }
            if (!shiftMap.containsKey(item.getShiftCode())) {
                throw new CheckedException("周班次引用了不存在或停用的班次");
            }
            if (item.getEffectiveFrom() != null && item.getEffectiveTo() != null
                    && item.getEffectiveFrom().isAfter(item.getEffectiveTo())) {
                throw new CheckedException("周班次生效日期不能晚于失效日期");
            }
        }
        for (int i = 0; i < patterns.size(); i++) {
            for (int j = i + 1; j < patterns.size(); j++) {
                MdmEmployeeShiftPatternEntity a = patterns.get(i);
                MdmEmployeeShiftPatternEntity b = patterns.get(j);
                if (Objects.equals(a.getDayOfWeek(), b.getDayOfWeek()) && dateRangesOverlap(
                        a.getEffectiveFrom(), a.getEffectiveTo(), b.getEffectiveFrom(), b.getEffectiveTo())) {
                    throw new CheckedException("同一星期的班次生效区间不能重叠");
                }
            }
        }
    }

    private void validateExceptions(List<MdmEmployeeCalendarExceptionEntity> exceptions) {
        Set<String> identities = new HashSet<>();
        for (MdmEmployeeCalendarExceptionEntity item : exceptions) {
            String type = item == null || item.getExceptionType() == null ? ""
                    : item.getExceptionType().toUpperCase(Locale.ROOT);
            if (item == null || item.getCalendarDate() == null || !EXCEPTION_TYPES.contains(type)) {
                throw new CheckedException("个人日历例外必须填写日期，类型仅支持休息、请假、培训或加班");
            }
            if ((item.getStartTime() == null) != (item.getEndTime() == null)) {
                throw new CheckedException("个人日历例外开始、结束时间必须同时填写或同时留空");
            }
            if ("OVERTIME".equals(type) && item.getStartTime() == null) {
                throw new CheckedException("加班例外必须填写开始和结束时间");
            }
            if (!identities.add(calendarExceptionKey(item))) {
                throw new CheckedException("同一员工不能重复维护完全相同的日历例外");
            }
        }
        List<MdmEmployeeCalendarExceptionEntity> enabled = exceptions.stream()
                .filter(Objects::nonNull)
                .filter(item -> !Boolean.FALSE.equals(item.getStatus()))
                .toList();
        for (int i = 0; i < enabled.size(); i++) {
            for (int j = i + 1; j < enabled.size(); j++) {
                LocalDateTime aStart = calendarExceptionStart(enabled.get(i));
                LocalDateTime aEnd = calendarExceptionEnd(enabled.get(i), aStart);
                LocalDateTime bStart = calendarExceptionStart(enabled.get(j));
                LocalDateTime bEnd = calendarExceptionEnd(enabled.get(j), bStart);
                if (aStart.isBefore(bEnd) && bStart.isBefore(aEnd)) {
                    throw new CheckedException("启用的个人日历例外时间不能重叠，否则加班与休息、请假或培训会形成相互冲突的排产事实");
                }
            }
        }
    }

    private LocalDateTime calendarExceptionStart(MdmEmployeeCalendarExceptionEntity item) {
        return item.getStartTime() == null ? item.getCalendarDate().atStartOfDay()
                : item.getCalendarDate().atTime(item.getStartTime());
    }

    private LocalDateTime calendarExceptionEnd(MdmEmployeeCalendarExceptionEntity item, LocalDateTime start) {
        LocalDateTime end = item.getEndTime() == null ? item.getCalendarDate().plusDays(1).atStartOfDay()
                : item.getCalendarDate().atTime(item.getEndTime());
        return end.isAfter(start) ? end : end.plusDays(1);
    }

    private String calendarExceptionKey(MdmEmployeeCalendarExceptionEntity item) {
        return String.join("|", Objects.toString(item.getCalendarDate(), ""),
                StrUtil.blankToDefault(item.getExceptionType(), "").toUpperCase(Locale.ROOT),
                Objects.toString(item.getStartTime(), ""), Objects.toString(item.getEndTime(), ""),
                StrUtil.blankToDefault(item.getReason(), "").trim(),
                String.valueOf(!Boolean.FALSE.equals(item.getStatus())));
    }

    private List<MdmWorkforceWindowDTO> buildRoster(List<MdmEmployeeShiftPatternEntity> patterns,
                                                    List<MdmEmployeeCalendarExceptionEntity> exceptions, Map<String, MdmClassesEntity> shifts,
                                                    LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        List<MdmWorkforceWindowDTO> windows = new ArrayList<>();
        for (LocalDate date = rangeStart.toLocalDate(); !date.isAfter(rangeEnd.toLocalDate()); date = date.plusDays(1)) {
            for (MdmEmployeeShiftPatternEntity pattern : patterns) {
                if (pattern.getDayOfWeek() != date.getDayOfWeek().getValue()
                        || !within(date, pattern.getEffectiveFrom(), pattern.getEffectiveTo())) {
                    continue;
                }
                MdmClassesEntity shift = shifts.get(pattern.getShiftCode());
                if (shift == null || shift.getStartTime() == null || shift.getEndTime() == null) {
                    continue;
                }
                LocalDateTime start = date.atTime(shift.getStartTime());
                LocalDateTime end = date.atTime(shift.getEndTime());
                if (!end.isAfter(start)) {
                    end = end.plusDays(1);
                }
                windows.add(new MdmWorkforceWindowDTO(start, end, shift.getShiftCode(), shift.getShiftName(), "SHIFT_PATTERN"));
            }
        }
        for (MdmEmployeeCalendarExceptionEntity item : exceptions) {
            String type = item.getExceptionType().toUpperCase(Locale.ROOT);
            LocalDateTime start = item.getStartTime() == null ? item.getCalendarDate().atStartOfDay()
                    : item.getCalendarDate().atTime(item.getStartTime());
            LocalDateTime end = item.getEndTime() == null ? item.getCalendarDate().plusDays(1).atStartOfDay()
                    : item.getCalendarDate().atTime(item.getEndTime());
            if (!end.isAfter(start)) {
                end = end.plusDays(1);
            }
            if ("OVERTIME".equals(type)) {
                windows.add(new MdmWorkforceWindowDTO(start, end, null, "加班", "OVERTIME"));
            } else {
                windows = subtract(windows, start, end);
            }
        }
        windows = windows.stream().map(window -> new MdmWorkforceWindowDTO(
                        window.getStartTime().isBefore(rangeStart) ? rangeStart : window.getStartTime(),
                        window.getEndTime().isAfter(rangeEnd) ? rangeEnd : window.getEndTime(),
                        window.getShiftCode(), window.getShiftName(), window.getSourceType()))
                .filter(window -> window.getEndTime().isAfter(window.getStartTime()))
                .sorted(Comparator.comparing(MdmWorkforceWindowDTO::getStartTime)).toList();
        return merge(windows);
    }

    private List<MdmWorkforceWindowDTO> subtract(List<MdmWorkforceWindowDTO> windows,
                                                 LocalDateTime start, LocalDateTime end) {
        List<MdmWorkforceWindowDTO> result = new ArrayList<>();
        for (MdmWorkforceWindowDTO window : windows) {
            if (!window.getStartTime().isBefore(end) || !window.getEndTime().isAfter(start)) {
                result.add(window);
                continue;
            }
            if (window.getStartTime().isBefore(start)) {
                result.add(new MdmWorkforceWindowDTO(window.getStartTime(), start,
                        window.getShiftCode(), window.getShiftName(), window.getSourceType()));
            }
            if (window.getEndTime().isAfter(end)) {
                result.add(new MdmWorkforceWindowDTO(end, window.getEndTime(),
                        window.getShiftCode(), window.getShiftName(), window.getSourceType()));
            }
        }
        return result;
    }

    private List<MdmWorkforceWindowDTO> merge(List<MdmWorkforceWindowDTO> raw) {
        List<MdmWorkforceWindowDTO> result = new ArrayList<>();
        for (MdmWorkforceWindowDTO current : raw) {
            if (result.isEmpty()) {
                result.add(current);
                continue;
            }
            MdmWorkforceWindowDTO last = result.get(result.size() - 1);
            if (!current.getStartTime().isAfter(last.getEndTime())) {
                last.setEndTime(current.getEndTime().isAfter(last.getEndTime()) ? current.getEndTime() : last.getEndTime());
                if (!Objects.equals(last.getSourceType(), current.getSourceType())) {
                    last.setSourceType("SHIFT_AND_OVERTIME");
                }
            } else {
                result.add(current);
            }
        }
        return result;
    }

    private String qualificationGap(List<MdmProcedureSkillRequirementEntity> requirements,
                                    List<MdmEmployeeSkillEntity> skills, LocalDate start, LocalDate end) {
        for (MdmProcedureSkillRequirementEntity requirement : requirements) {
            boolean matched = skills.stream().anyMatch(skill -> matches(requirement, skill)
                    && overlaps(skill.getValidFrom(), skill.getValidTo(), start, end));
            if (!matched) {
                return "缺少有效技能[" + requirement.getSkillCode() + "/" + requirement.getSkillName()
                        + "]或等级、证书不满足";
            }
        }
        return null;
    }

    private boolean qualifiedForWindow(List<MdmProcedureSkillRequirementEntity> requirements,
                                       List<MdmEmployeeSkillEntity> skills, MdmWorkforceWindowDTO window) {
        LocalDate start = window.getStartTime().toLocalDate();
        LocalDate end = window.getEndTime().minusNanos(1).toLocalDate();
        return requirements.stream().allMatch(requirement -> skills.stream().anyMatch(skill ->
                matches(requirement, skill) && within(start, skill.getValidFrom(), skill.getValidTo())
                        && within(end, skill.getValidFrom(), skill.getValidTo())));
    }

    private boolean matches(MdmProcedureSkillRequirementEntity requirement, MdmEmployeeSkillEntity skill) {
        return Objects.equals(requirement.getSkillCode(), skill.getSkillCode())
                && Optional.ofNullable(skill.getProficiencyLevel()).orElse(0)
                >= Optional.ofNullable(requirement.getMinimumLevel()).orElse(1)
                && (!Boolean.TRUE.equals(requirement.getCertificateRequired()) || StrUtil.isNotBlank(skill.getCertificateNo()));
    }

    private void validateAvailabilityQuery(MdmWorkforceAvailabilityQueryDTO query) {
        if (query == null || query.getEmployeeCodes() == null || CollUtil.isEmpty(query.getProcessCodes())
                || query.getStartTime() == null || query.getEndTime() == null
                || !query.getEndTime().isAfter(query.getStartTime())) {
            throw new CheckedException("人员可用性查询必须提供员工、工序和合法时间范围");
        }
        if (query.getEndTime().isAfter(query.getStartTime().plusDays(MAX_QUERY_DAYS))) {
            throw new CheckedException("人员可用性单次查询不能超过" + MAX_QUERY_DAYS + "天");
        }
    }

    private MdmEmployeeEntity requireEmployee(String employeeCode) {
        if (StrUtil.isBlank(employeeCode)) {
            throw new CheckedException("员工编码不能为空");
        }
        MdmEmployeeEntity employee = employeeMapper.selectOne(new LambdaQueryWrapper<MdmEmployeeEntity>()
                .eq(MdmEmployeeEntity::getEmployeeCode, employeeCode));
        if (employee == null) {
            throw new CheckedException("员工[" + employeeCode + "]不存在或已删除");
        }
        return employee;
    }

    private MdmProcedureEntity requireProcedure(String processCode) {
        if (StrUtil.isBlank(processCode)) {
            throw new CheckedException("工序编码不能为空");
        }
        MdmProcedureEntity procedure = procedureMapper.selectOne(new LambdaQueryWrapper<MdmProcedureEntity>()
                .eq(MdmProcedureEntity::getProcessCode, processCode));
        if (procedure == null) {
            throw new CheckedException("工序[" + processCode + "]不存在或已删除");
        }
        return procedure;
    }

    private long activeReferenceCount(String skillCode) {
        return employeeSkillMapper.selectCount(new LambdaQueryWrapper<MdmEmployeeSkillEntity>()
                .eq(MdmEmployeeSkillEntity::getSkillCode, skillCode)
                .eq(MdmEmployeeSkillEntity::getStatus, true))
                + requirementMapper.selectCount(new LambdaQueryWrapper<MdmProcedureSkillRequirementEntity>()
                .eq(MdmProcedureSkillRequirementEntity::getSkillCode, skillCode)
                .eq(MdmProcedureSkillRequirementEntity::getStatus, true));
    }

    private boolean within(LocalDate value, LocalDate start, LocalDate end) {
        return (start == null || !value.isBefore(start)) && (end == null || !value.isAfter(end));
    }

    private boolean dateRangesOverlap(LocalDate aStart, LocalDate aEnd, LocalDate bStart, LocalDate bEnd) {
        LocalDate min = LocalDate.of(1900, 1, 1);
        LocalDate max = LocalDate.of(2999, 12, 31);
        return !Optional.ofNullable(aEnd).orElse(max).isBefore(Optional.ofNullable(bStart).orElse(min))
                && !Optional.ofNullable(bEnd).orElse(max).isBefore(Optional.ofNullable(aStart).orElse(min));
    }

    private boolean overlaps(LocalDate aStart, LocalDate aEnd, LocalDate bStart, LocalDate bEnd) {
        return dateRangesOverlap(aStart, aEnd, bStart, bEnd);
    }
}
