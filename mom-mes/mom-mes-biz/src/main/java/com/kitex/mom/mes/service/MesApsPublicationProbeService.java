package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesApsPublicationProbeDTO;
import com.kitex.mom.mes.entity.MesApsScheduleSyncEntity;

import java.util.List;

/** 永久应用账只读取证服务；不提供回执改写、删除或“强制视为未应用”。 */
public interface MesApsPublicationProbeService extends IService<MesApsScheduleSyncEntity> {
    /** 等待同案件应用事务结束后，核对原载荷与当前真实计划；不移动现场工序。 */
    MesApsPublicationProbeDTO.Reply probe(MesApsPublicationProbeDTO.Query query);

    /** 按真实组织及唯一案件查原发布链，用于浏览器丢失原号后的只读恢复。 */
    List<MesApsPublicationProbeDTO.ReceiptRef> catalog(MesApsPublicationProbeDTO.Scope scope);
}
