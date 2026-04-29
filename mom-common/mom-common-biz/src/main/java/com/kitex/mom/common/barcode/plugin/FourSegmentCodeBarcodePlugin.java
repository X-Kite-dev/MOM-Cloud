package com.kitex.mom.common.barcode.plugin;

import cn.hutool.core.map.MapBuilder;
import cn.hutool.core.map.MapUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.barcode.dto.req.BarcodeBusinessFieldReq;
import com.kitex.mom.common.barcode.dto.req.BarcodeRuleReq;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteSupplierService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FourSegmentCodeBarcodePlugin implements BarcodePlugin {

    private BarcodeRuleReq barcodeRuleReq;

    @Resource
    private RemoteSupplierService mesdSupplierService;

    @Resource
    private RemoteMaterialService mdmMaterialService;

    /**
     * @return
     */
    @Override
    public String getName() {
        return "四段码";
    }

    /**
     * 正则表达式
     *
     * @return
     */
    @Override
    public String getRegular() {
        return "^([^\\/]+)\\/([^\\/]+)\\/([^\\/]+)\\/([^\\/]+)$";
    }

    /**
     * @param barcodeRuleReq
     */
    @Override
    public void setTemplate(BarcodeRuleReq barcodeRuleReq) {
        this.barcodeRuleReq = barcodeRuleReq;
    }

    /**
     * 解析插件 201581/70511000233/191101/1860
     *
     * @param barcode 条码
     * @return 解析结果
     */
    @Override
    public Map<String, String> render(String barcode) {

        String regular = this.barcodeRuleReq.getRegular();
        // 正则
        List<BarcodeBusinessFieldReq> businessReqList =
                this.barcodeRuleReq.getBusinessReqList();

        Pattern pattern = Pattern.compile(regular);
        Matcher matcher = pattern.matcher(barcode);

        MapBuilder<String, String> builder = MapUtil.builder();
        // 四段 供应商/物料/批次/数量
        if (matcher.matches()) {
            for (int i = 0; i < businessReqList.size(); i++) {
                BarcodeBusinessFieldReq barcodeBusinessFieldReq = businessReqList.get(i);
                builder.put(barcodeBusinessFieldReq.getValue(), matcher.group(i + 1));
            }
            Map<String, String> build = builder.build();
            // 查询对应的供应商名称和物料名称
            MdmSupplierEntity query = new MdmSupplierEntity();
            query.setSupplierCode(build.get("supplierCode"));
            MdmSupplierEntity supplierCode = mesdSupplierService.getDetails(query).getData();
            if (supplierCode != null) {
                builder.put("supplierName", supplierCode.getSupplierName());
            }
            MdmMaterialEntity mdmMaterial =
                    mdmMaterialService.getDetails(build.get("matCode")).getData();
            if (mdmMaterial != null) {
                builder.put("matName", mdmMaterial.getMaterialName());
            }

        } else {
            throw new CheckedException("无法匹配到对应的正则");
        }

        return builder.build();
    }

}
