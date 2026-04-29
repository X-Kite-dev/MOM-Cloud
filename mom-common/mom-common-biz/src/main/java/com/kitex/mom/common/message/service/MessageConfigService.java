package com.kitex.mom.common.message.service;

import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.mom.common.message.entity.MessageConfigEntity;
import com.kitex.mom.common.message.vo.MessageConfigExcelVO;

import java.util.List;

public interface MessageConfigService extends MPJDeepService<MessageConfigEntity> {
    Boolean saveDeep(MessageConfigEntity messageConfig);

    Boolean updateDeep(MessageConfigEntity messageConfig);

    Boolean removeDeep(Long[] ids);

    /**
     * 导出excel 表格
     *
     * @param messageConfig 查询条件
     * @param ids           导出指定ID
     * @return excel 文件流
     */
    List<MessageConfigExcelVO> exportExcel(MessageConfigEntity messageConfig, Long[] ids);

    /**
     * 通过业务编码查询消息推送配置
     *
     * @param code 业务编码
     * @return R 对象
     */
    MessageConfigEntity getConfigByCode(String code);
}