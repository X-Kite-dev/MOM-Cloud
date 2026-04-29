package com.kitex.mom.pms.vo;

import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 采购订单供应商VO
 *
 * @author kitex
 * @date 2025-04-03 10:28:26
 */
@Data
@Schema(description = "采购订单供应商VO")
public class PmsOrderSupplierVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 采购订单号
     */
    @Schema(description = "采购订单号")
    private String pmsOrderNo;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 审批意见
     */
    @Schema(description = "审批意见")
    private String checkReason;

    /**
     * 工厂编码ID
     */
    @Schema(description = "工厂编码ID")
    private String factoryCode;

    /**
     * 合同总金额
     */
    @Schema(description = "合同总金额")
    private BigDecimal contractAmount;

    /**
     * 法人
     */
    @Schema(description = "法人")
    private String legalRepresentative;

    /**
     * 地址
     */
    @Schema(description = "地址")
    private String address;

    /**
     * 开户行
     */
    @Schema(description = "开户行")
    private String bank;

    /**
     * 开户行账户
     */
    @Schema(description = "开户行账户")
    private String account;

    /**
     * 签约负责人
     */
    @Schema(description = "签约负责人")
    private String contactPerson;

    /**
     * 手机号
     */
    @Schema(description = "联系方式")
    private String phoneNumber;

    /**
     * 采购订单明细
     */
    @Schema(description = "采购订单明细")
    private List<PmsOrderDetailEntity> pmsContractDetailList;
}