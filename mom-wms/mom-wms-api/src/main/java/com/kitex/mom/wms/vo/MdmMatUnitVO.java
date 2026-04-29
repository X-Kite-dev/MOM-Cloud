package com.kitex.mom.wms.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 物料主数据-单位换算
 *
 * @author kitex
 * @since 2024年12月11日 18:56:32
 */
@Data
@Accessors(chain = true)
public class MdmMatUnitVO implements Serializable {

    private static final long serialVersionUID = -2787478820630216119L;

    private String id;

    /**
     * 物料编码
     */
    private String code;

    /**
     * 非标准单位
     */
    private String nonStandardUnit;

    /**
     * 标准单位
     */
    private String standardUnit;

    /**
     * 标准单位
     */
    private String unitName;

    /**
     * 比率
     */
    private String ratio;

}
