package com.kitex.mom.mdm.mq;

import lombok.Data;
import lombok.ToString;

/**
 * 刷新实体
 */
@Data
@ToString
public class RefreshMdmEntity {
    /**
     * 类型 使用类名
     */
    private String type;

    /**
     * 对象
     */
    private Object obj;


}
