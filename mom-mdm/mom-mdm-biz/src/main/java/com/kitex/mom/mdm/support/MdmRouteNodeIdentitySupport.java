package com.kitex.mom.mdm.support;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;
import org.springframework.beans.BeanUtils;

import java.util.*;

/**
 * 路线节点身份协调器，不读取数据库，也不授予保存权限。
 *
 * <p>调用方先锁定真实父路线并校验图版本，再提供当前全部活节点。旧请求可省略节点编号，
 * 但仅能按当前唯一主工序桥接。新身份只由服务端分配，不能把旧节点改绑别的主工序。</p>
 */
public final class MdmRouteNodeIdentitySupport {

    private MdmRouteNodeIdentitySupport() {
    }

    /**
     * V2按客户端节点键协调，不再按主工序回落匹配；允许同一主工序出现多次。
     * 返回行暂保留clientNodeKey，供图/时间来源和画布统一映射，持久化不写该字段。
     */
    public static List<MdmProcessRouteWorkingEntity> reconcileNodes(MdmProcessRouteEntity route,
                                                                    List<MdmProcessRouteWorkingEntity> current, List<MdmProcessRouteWorkingEntity> requested) {
        Map<String, MdmProcessRouteWorkingEntity> originals = new HashMap<>();
        for (MdmProcessRouteWorkingEntity row : current) {
            require(row.getId() != null && Objects.equals(row.getRouteNodeCode(), "RN-" + row.getId())
                            && originals.putIfAbsent(row.getRouteNodeCode(), row) == null,
                    "当前路线节点身份不完整或重复，请先完成节点升级");
        }
        Set<String> keys = new HashSet<>();
        List<MdmProcessRouteWorkingEntity> result = new ArrayList<>();
        for (MdmProcessRouteWorkingEntity input : requested) {
            require(input != null && input.getClientNodeKey() != null && keys.add(input.getClientNodeKey()),
                    "V2节点键缺失或重复，请重新打开配置页面");
            require(input.getProcessCode() != null && !input.getProcessCode().isBlank(), "请选择节点主工序");
            String key = input.getClientNodeKey();
            MdmProcessRouteWorkingEntity saved = new MdmProcessRouteWorkingEntity();
            if (Boolean.TRUE.equals(input.getNewNode())) {
                require(key.matches("C-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                                && input.getId() == null && input.getRouteNodeCode() == null,
                        "新节点必须使用本次C-UUID键，不能指定已有身份");
                saved.setId(IdWorker.getId());
                saved.setRouteNodeCode("RN-" + saved.getId());
                saved.setTenantId(route.getTenantId());
                saved.setFactoryCode(route.getFactoryCode());
                saved.setDelFlag("0");
            } else {
                MdmProcessRouteWorkingEntity old = originals.get(key);
                require(old != null && Objects.equals(input.getId(), old.getId())
                                && Objects.equals(input.getRouteNodeCode(), key),
                        "既有节点ID与编号不一致或已失效，不能跨路线复用");
                require(Objects.equals(old.getProcessCode(), input.getProcessCode()), "既有节点不能改绑主工序");
                BeanUtils.copyProperties(old, saved);
                saved.setUpdateBy(null);
                saved.setUpdateTime(null);
            }
            copyBusiness(route, input, saved);
            saved.setClientNodeKey(key);
            saved.setPreRouteNodeCodes(input.getPreRouteNodeCodes());
            saved.setNodeTimingRulesJson(input.getNodeTimingRulesJson());
            // 旧页面可能省略质检配置，只允许显式提交0关闭已启用的节点。
            if (input.getIsInspection() != null) {
                require(Set.of("0", "1").contains(input.getIsInspection()), "请选择有效的工序质检配置");
                saved.setIsInspection(input.getIsInspection());
            }
            result.add(saved);
        }
        return result;
    }

    /**
     * 生成本次保存行；保留原ID、组织及审计字段，只复制允许编辑的业务属性。
     *
     * @param route           已锁定、已核真实组织的父路线
     * @param current         当前路线全部活节点，不含逻辑删除节点
     * @param requested       已经过DAG和时间配置规范化的请求
     * @param newProcessCodes 新页面明确新增的主工序；空集合仅表示未声明新建，不替换旧桥接协议
     * @return 可写入的完整节点集合；遗漏的旧节点由调用方受控删除
     */
    public static List<MdmProcessRouteWorkingEntity> reconcile(MdmProcessRouteEntity route,
                                                               List<MdmProcessRouteWorkingEntity> current, List<MdmProcessRouteWorkingEntity> requested,
                                                               List<String> newProcessCodes) {
        Set<String> explicitNew = new HashSet<>();
        if (newProcessCodes != null) {
            for (String code : newProcessCodes) {
                require(code != null && !code.isBlank() && explicitNew.add(code), "新增节点声明不能为空或重复");
                require(requested.stream().anyMatch(row -> code.equals(row.getProcessCode())), "新增节点声明不在本次路线中");
            }
        }
        Map<Long, MdmProcessRouteWorkingEntity> byId = new HashMap<>();
        Map<String, MdmProcessRouteWorkingEntity> byNode = new HashMap<>();
        Map<String, MdmProcessRouteWorkingEntity> byProcess = new HashMap<>();
        for (MdmProcessRouteWorkingEntity row : current) {
            require(row.getId() != null && row.getRouteNodeCode() != null
                    && row.getRouteNodeCode().equals("RN-" + row.getId()), "路线节点身份缺失，请先执行节点身份升级");
            require(byId.putIfAbsent(row.getId(), row) == null
                    && byNode.putIfAbsent(row.getRouteNodeCode(), row) == null
                    && byProcess.putIfAbsent(row.getProcessCode(), row) == null, "当前路线节点不唯一，请先处理主数据冲突");
        }
        List<MdmProcessRouteWorkingEntity> result = new ArrayList<>();
        Set<Long> retained = new HashSet<>();
        for (MdmProcessRouteWorkingEntity input : requested) {
            String node = input.getRouteNodeCode();
            boolean hasNode = node != null && !node.isEmpty();
            boolean isNew = explicitNew.contains(input.getProcessCode());
            require(!isNew || input.getId() == null && !hasNode, "明确新增节点不能携带原ID或原节点编号");
            MdmProcessRouteWorkingEntity old = isNew ? null : input.getId() != null ? byId.get(input.getId())
                    : hasNode ? byNode.get(node) : byProcess.get(input.getProcessCode());
            require(input.getId() == null || old != null, "节点ID已失效或不属于当前路线，请重新打开路线");
            require(!hasNode || old != null && Objects.equals(node, old.getRouteNodeCode()),
                    "节点编号已失效或与原ID不一致，不能跨路线复用或自行指定新编号");
            if (old != null) {
                require(Objects.equals(old.getProcessCode(), input.getProcessCode()), "既有路线节点不能改绑其他主工序");
                require(retained.add(old.getId()), "同一路线节点不能重复提交");
            }
            MdmProcessRouteWorkingEntity saved = new MdmProcessRouteWorkingEntity();
            if (old != null) {
                BeanUtils.copyProperties(old, saved);
                saved.setUpdateBy(null);
                saved.setUpdateTime(null);
            } else {
                saved.setId(IdWorker.getId());
                saved.setRouteNodeCode("RN-" + saved.getId());
                saved.setTenantId(route.getTenantId());
                saved.setFactoryCode(route.getFactoryCode());
                saved.setDelFlag("0");
            }
            copyBusiness(route, input, saved);
            result.add(saved);
        }
        return result;
    }

    /**
     * 只允许节点业务值进入存储，客户端组织和审计字段不可信。
     */
    private static void copyBusiness(MdmProcessRouteEntity route, MdmProcessRouteWorkingEntity input,
                                     MdmProcessRouteWorkingEntity saved) {
        saved.setRouteCode(route.getRouteCode());
        saved.setRouteName(route.getRouteName());
        saved.setProcessCode(input.getProcessCode().trim());
        saved.setProcessName(input.getProcessName());
        saved.setOrderNum(input.getOrderNum());
        saved.setPreRouteCode(input.getPreRouteCode());
        saved.setNextRouteCode(input.getNextRouteCode());
        saved.setWorkshopCode(input.getWorkshopCode());
        saved.setWorkshopName(input.getWorkshopName());
        saved.setType(input.getType());
        saved.setTimeCheck(input.getTimeCheck());
        saved.setTimeVal(input.getTimeVal());
        saved.setTimingRulesJson(input.getTimingRulesJson());
        saved.setOverlapEnabled(input.getOverlapEnabled());
        saved.setTransferBatchQuantity(input.getTransferBatchQuantity());
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }
}
