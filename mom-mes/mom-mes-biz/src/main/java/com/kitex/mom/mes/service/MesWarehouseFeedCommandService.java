package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesWarehouseFeedCommandDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedPreparationDTO;
import com.kitex.mom.mes.entity.MesWarehouseFeedCommandEntity;

/**
 * 稳定投料同号执行/核账/永久作废；不提供通用修改删除接口。
 */
public interface MesWarehouseFeedCommandService extends IService<MesWarehouseFeedCommandEntity> {
    /**
     * 返回当前认证组织和本人，不授予任何业务许可。
     */
    MesWarehouseFeedCommandDTO.Context context();

    MesWarehouseFeedPreparationDTO.ReceiptSeed receiptSeed(Long noticeId);

    MesWarehouseFeedPreparationDTO.Candidates candidates(long current, long size, String taskCode, String keyword);

    /**
     * 实际派工、单位、冻结BOM绑定及来源全集的只读观察，不创建预留或执行许可。
     */
    MesWarehouseFeedPreparationDTO.Observation prepare(MesWarehouseFeedPreparationDTO.Query query);

    /**
     * 完整原文首次实际投料，同号仅回放原终态，任何必要写失败整笔回滚。
     */
    MesWarehouseFeedCommandDTO.Receipt execute(String raw);

    /**
     * 独立最新只读核账；ABSENT不能证明未来不会执行。
     */
    MesWarehouseFeedCommandDTO.Receipt receipt(String requestId);

    /**
     * 同原号锁只封存未执行原文，已成功只返回原成功，不做实物退料。
     */
    MesWarehouseFeedCommandDTO.Receipt cancel(String raw, String reason);
}
