package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.agent.dto.MesAgentObjectCandidateDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 当前实名工厂的MES生产工单候选投影。
 * 总数、页内摘要和原号重号校验使用同一独立只读RR快照，不修改原业务或启动运行。
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('mesTask:show')")
public class MesAgentObjectCandidateService {
    private final MesTaskMapper tasks;
    private final RemoteMdmFactoryAccessService factories;

    /**
     * 对齐浏览器 ECMAScript \s 边界判定，不能通过trim改变原业务编号。
     */
    private static boolean boundaryWhitespace(String value) {
        int first = value.codePointAt(0);
        int last = value.codePointBefore(value.length());
        return first == 0xFEFF || Character.isWhitespace(first) || Character.isSpaceChar(first)
                || last == 0xFEFF || Character.isWhitespace(last) || Character.isSpaceChar(last);
    }

    /**
     * 可空文本保持原文，同时拒绝超长内容及所有ISO控制字符。
     */
    private static boolean bounded(String value, int limit) {
        return value == null || value.length() <= limit && value.codePoints().noneMatch(Character::isISOControl);
    }

    /**
     * 失败时返回明确业务错误，不伪装成当前工厂无对象。
     */
    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }

    /**
     * 有界分页仅读小摘要；失效权限、坏摘要或数据库故障必须明确失败。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public MesAgentObjectCandidateDTO.CandidatePage candidates(MesAgentObjectCandidateDTO.Query command) {
        require(command != null, "请提供工单候选查询");
        Scope scope = scope(command.factoryCode());
        int current = command.current() == null ? 1 : command.current();
        int size = command.size() == null ? 10 : command.size();
        require(current >= 1 && current <= 100_000 && size >= 1 && size <= 50
                && (long) (current - 1) * size <= 1_000_000, "对象候选分页超过安全边界");
        String keyword = command.keyword();
        require(bounded(keyword, 100), "对象候选关键词超过100字或含控制字符");
        String exact = command.exactBusinessNo();
        require(exact == null || bounded(exact, 100) && !exact.isEmpty() && !boundaryWhitespace(exact)
                && !StringUtils.hasText(keyword), "精确原编号须完整且不能混用关键词");
        LambdaQueryWrapper<MesTaskEntity> query = rowScope(scope)
                .select(MesTaskEntity::getId, MesTaskEntity::getCode,
                        MesTaskEntity::getMatName, MesTaskEntity::getStatus,
                        MesTaskEntity::getMatCode, MesTaskEntity::getPlanStartDate,
                        MesTaskEntity::getPlanEndDate)
                .orderByDesc(MesTaskEntity::getId);
        if (exact != null) {
            // 等值约束在count/limit之前；主键与重号资格仍沿原同工厂只读快照核验。
            query.eq(MesTaskEntity::getCode, exact);
        } else if (StringUtils.hasText(keyword)) {
            // 查询词通过MyBatis绑定，原业务编号只读原文，绝不trim后输出或回填。
            String search = keyword.trim();
            query.and(part -> part.like(MesTaskEntity::getCode, search)
                    .or().like(MesTaskEntity::getMatName, search)
                    .or().like(MesTaskEntity::getMatCode, search));
        }
        Page<MesTaskEntity> page = tasks.selectPage(new Page<>(current, size), query);
        require(page.getCurrent() == current && page.getSize() == size && page.getTotal() >= 0
                        && page.getRecords() != null && page.getRecords().size() <= size,
                "对象候选分页结果不完整或超过安全边界");
        Map<String, Boolean> duplicated = duplicateCodes(scope, page.getRecords());
        List<MesAgentObjectCandidateDTO.Candidate> records = page.getRecords().stream()
                .map(row -> candidate(row, duplicated)).toList();
        return new MesAgentObjectCandidateDTO.CandidatePage(scope.tenant().toString(), scope.factory(),
                "MES_PRODUCTION_TASK", current, size, Long.toString(page.getTotal()), records, exact);
    }

    /**
     * 权限只取实名用户并核验MDM READ，工厂请求头不能代替实际授权。
     */
    private Scope scope(String requestedFactory) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && user.getTenantId() != null
                        && Objects.equals(user.getTenantId(), tenant) && tenant != null && tenant > 0
                        && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StringUtils.hasText(factory) && bounded(factory, 100) && factory.equals(requestedFactory),
                "对象候选须实名同租户实际工厂读取，禁止跳过隔离或扩范围");
        R<Boolean> check = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
                .setFactoryCode(factory).setRequiredPermission("read"));
        require(check != null && check.isOk() && Boolean.TRUE.equals(check.getData()),
                "当前实名没有该工厂实际READ或授权无法核验");
        return new Scope(tenant, factory);
    }

    /**
     * 分页及重号查询都显式限制租户、工厂和未删除，不依赖通用列表或隐式插件补范围。
     */
    private LambdaQueryWrapper<MesTaskEntity> rowScope(Scope scope) {
        return new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getTenantId, scope.tenant())
                .eq(MesTaskEntity::getFactoryCode, scope.factory()).eq(MesTaskEntity::getDelFlag, "0");
    }

    /**
     * 每页最多50个原号；按数据库等值语义计数整个当前工厂未删除范围。
     * 不带分页关键词或当前页限制，不加载重号全行，避免漏掉其他页/不匹配摘要的同号记录。
     */
    private Map<String, Boolean> duplicateCodes(Scope scope, List<MesTaskEntity> rows) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (MesTaskEntity row : rows) {
            String businessNo = row.getCode();
            if (businessNo == null || !bounded(businessNo, 500) || result.containsKey(businessNo)) {
                continue;
            }
            Long count = tasks.selectCount(rowScope(scope).eq(MesTaskEntity::getCode, businessNo));
            require(count != null && count >= 1, "对象候选原号无法在同一快照中核验");
            result.put(businessNo, count > 1);
        }
        return result;
    }

    /**
     * 坏原号保留不可选行；控制符或超展示边界的原号只置null，不清洗成另一个可用号。
     */
    private MesAgentObjectCandidateDTO.Candidate candidate(MesTaskEntity row, Map<String, Boolean> duplicated) {
        require(row.getId() != null && row.getId() > 0, "对象候选主键无效");
        String businessNo = row.getCode();
        String label = row.getMatName();
        String status = row.getStatus();
        String description = row.getMatCode();
        String plannedStart = row.getPlanStartDate() == null ? null : row.getPlanStartDate().toString();
        String plannedEnd = row.getPlanEndDate() == null ? null : row.getPlanEndDate().toString();
        require(bounded(label, 500) && bounded(status, 100) && bounded(description, 500)
                        && bounded(plannedStart, 40) && bounded(plannedEnd, 40),
                "对象候选摘要超限或含控制字符，不能返回部分候选页");
        String reason = unavailableReason(businessNo, duplicated);
        String displayedNo = bounded(businessNo, 500) ? businessNo : null;
        return new MesAgentObjectCandidateDTO.Candidate(row.getId().toString(), displayedNo, label, status,
                description, plannedStart, plannedEnd, reason == null, reason);
    }

    /**
     * 不可选原因均为固定安全中文，不回显不受控原数据。
     */
    private String unavailableReason(String businessNo, Map<String, Boolean> duplicated) {
        if (businessNo == null || businessNo.isEmpty()) {
            return "原业务编号缺失，无法选择";
        }
        if (!bounded(businessNo, 500)) {
            return "原业务编号含控制字符或超过500字，无法安全展示和选择";
        }
        if (boundaryWhitespace(businessNo)) {
            return "原业务编号含边界空白，无法按原号安全选择";
        }
        if (businessNo.length() > 100) {
            return "原业务编号超过100字，无法选择";
        }
        if (Boolean.TRUE.equals(duplicated.get(businessNo))) {
            return "当前租户工厂内原业务编号重复，无法确定唯一对象";
        }
        return null;
    }

    /**
     * 只保存已经核验的当前实名范围，不允许调用者传入租户。
     */
    private record Scope(Long tenant, String factory) {
    }
}
