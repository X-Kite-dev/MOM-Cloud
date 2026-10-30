package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesProductionFeedBatchEntity;
import com.kitex.mom.mes.service.impl.material.MesProductionFeedAllocationPlanner.OutputKind;

import java.util.List;

/**
 * 同一次生产全部真实GOOD/NG产出的独立总投料原账。
 * 仅可信生产协调器在保存真实产出的同一事务调用，不是客户端skip耗料或通用CRUD。
 * 当前未接组合生产入口，完整产出库存、计数、逐份额恢复及原号回执协调仍须完成后开放。
 */
public interface MesProductionFeedBatchService extends IService<MesProductionFeedBatchEntity> {
    /**
     * 从本事务首次真实报告、原工单/BOM与锁定投料重新计算，一次CAS总耗料并永久保存逐产出份额。
     * 不调用旧GOOD/NG耗用，不执行第二次库存OUT；全部必要写、精度和双向全集失败整笔回滚。
     */
    MesProductionFeedBatchEntity recordFeed(String requestId, String arrangeCode, List<SourceRef> sources);

    /**
     * 当前事务内的原账回读，仅同实际租户/工厂/本人；不是独立HTTP提交未知恢复接口。
     */
    MesProductionFeedBatchEntity original(String requestId);

    /**
     * 服务器协调器指向一份真实正量产出；数量/单位/权限不从此引用中取得。
     */
    record SourceRef(String outputKey, OutputKind kind, Long sourceId) {
    }
}
