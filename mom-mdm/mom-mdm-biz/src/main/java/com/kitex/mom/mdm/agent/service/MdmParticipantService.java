package com.kitex.mom.mdm.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.admin.api.dto.AgentParticipantAccount;
import com.kitex.admin.api.feign.RemoteAgentParticipantAccountService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.agent.dto.MdmParticipantModels.*;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;
import com.kitex.mom.mdm.enums.FactoryModelPermissionEnum;
import com.kitex.mom.mdm.enums.FactoryModelTypeEnum;
import com.kitex.mom.mdm.mapper.MdmFactoryModelMapper;
import com.kitex.mom.mdm.mapper.MdmUserFactoryModelBindMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 制造协作选人：工厂有效授权与实名账号、固定用途办理权限的实时交集。 */
@Service
@RequiredArgsConstructor
public class MdmParticipantService {
    private final MdmAgentActualScopeAccess scope;
    private final MdmFactoryModelMapper factories;
    private final MdmUserFactoryModelBindMapper bindings;
    private final RemoteAgentParticipantAccountService accounts;

    public ParticipantPage search(Search query) {
        if (query == null || query.current() < 1 || query.current() > 100000 || query.size() < 1 || query.size() > 50)
            throw new CheckedException("参与人分页参数不合法，页大小1至50");
        List<Participant> all = resolve(query.factoryCode(), query.purpose(), null, query.keyword());
        int from = (int) Math.min(all.size(), (query.current() - 1) * query.size());
        int to = (int) Math.min(all.size(), from + query.size());
        return new ParticipantPage(query.current(), query.size(), all.size(), List.copyOf(all.subList(from, to)));
    }

    public List<Participant> verify(Verify query) {
        if (query == null || query.userIds() == null || query.userIds().isEmpty() || query.userIds().size() > 20
                || new HashSet<>(query.userIds()).size() != query.userIds().size())
            throw new CheckedException("参与人核验需要1至20个不重复账号ID");
        List<Long> ids = query.userIds().stream().map(this::id).toList();
        List<Participant> result = resolve(query.factoryCode(), query.purpose(), ids, null);
        if (result.size() != ids.size()) throw new CheckedException("所选账号不存在、已停用、工厂权限失效或缺少办理权限，请重新选择");
        return result;
    }

    private List<Participant> resolve(String factory, String purpose, List<Long> selected, String keyword) {
        if (purpose == null || !Set.of("OPERATIONS", "HANDOVER").contains(purpose)) throw new CheckedException("参与人用途不合法");
        scope.require(factory, "read");
        MdmFactoryModelEntity node = factories.selectOne(new LambdaQueryWrapper<MdmFactoryModelEntity>()
                .eq(MdmFactoryModelEntity::getCode, factory).eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.FACTORY.getCode())
                .eq(MdmFactoryModelEntity::getStatus, true));
        List<MdmUserFactoryModelBindEntity> active = bindings.selectList(new LambdaQueryWrapper<MdmUserFactoryModelBindEntity>()
                .eq(MdmUserFactoryModelBindEntity::getFactoryModelId, node.getId()).eq(MdmUserFactoryModelBindEntity::getStatus, true)
                .in(selected != null, MdmUserFactoryModelBindEntity::getUserId, selected)
                .orderByAsc(MdmUserFactoryModelBindEntity::getUserId).last("LIMIT 2001"));
        if (active.size() > 2000) throw new CheckedException("当前工厂有效绑定超过2000条，请由管理员整理工厂成员；未返回截断人数");
        Map<String, List<String>> proofs = new TreeMap<>();
        for (var binding : active) {
            var permission = FactoryModelPermissionEnum.getByCode(binding.getPermissionType());
            if (binding.getUserId() == null || permission == null || !permission.hasPermission(FactoryModelPermissionEnum.READ)) continue;
            proofs.computeIfAbsent(String.valueOf(binding.getUserId()), ignored -> new ArrayList<>())
                    .add(binding.getId() + ":" + permission.getCode());
        }
        List<String> ids = new ArrayList<>(proofs.keySet());
        List<Participant> result = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += 500) {
            var response = accounts.resolve(new AgentParticipantAccount.Query(purpose,
                    List.copyOf(ids.subList(start, Math.min(ids.size(), start + 500))), keyword));
            if (response == null || response.getCode() != 0 || response.getData() == null)
                throw new CheckedException("账号权限服务暂不可用，不能以历史绑定名称代替实名核验");
            for (AgentParticipantAccount user : response.getData()) {
                if (!proofs.containsKey(user.userId())) throw new CheckedException("账号服务返回了范围外参与人");
                List<String> bindingProof = proofs.get(user.userId());
                Collections.sort(bindingProof);
                String proof = hash(TenantContextHolder.getTenantId() + "|" + factory + "|" + node.getId() + "|"
                        + purpose + "|" + user + "|" + String.join(",", bindingProof));
                result.add(new Participant(user.userId(), user.username(), user.displayName(), user.departmentId(),
                        factory, purpose, user.authorities(), proof));
            }
        }
        result.sort(Comparator.comparing(p -> id(p.userId())));
        return List.copyOf(result);
    }

    private Long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            return Long.valueOf(value);
        } catch (IllegalArgumentException ex) { throw new CheckedException("参与人ID必须为正整数文本"); }
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("参与人摘要生成失败", ex); }
    }
}
