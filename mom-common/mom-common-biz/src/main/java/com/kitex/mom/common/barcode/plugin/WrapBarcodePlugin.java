package com.kitex.mom.common.barcode.plugin;

import cn.hutool.core.map.MapBuilder;
import cn.hutool.core.map.MapUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.barcode.dto.req.BarcodeBusinessFieldReq;
import com.kitex.mom.common.barcode.dto.req.BarcodeRuleReq;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 箱码生成规则
 *
 * @author kitex
 * @since 2025-03-24
 */
@Component
public class WrapBarcodePlugin implements BarcodePlugin {

    private BarcodeRuleReq barcodeRuleReq;

    @Override
    public String getName() {
        return "箱码";
    }

    @Override
    public String getRegular() {
        return "^690\\d{68}\\/\\d{3}\\/50$";
    }

    @Override
    public void setTemplate(BarcodeRuleReq barcodeRuleReq) {
        this.barcodeRuleReq = barcodeRuleReq;
    }

    @Override
    public Map<String, String> render(String barcode) {
        String regular = this.barcodeRuleReq.getRegular();
        // 正则
        List<BarcodeBusinessFieldReq> businessReqList = this.barcodeRuleReq.getBusinessReqList();
        Pattern pattern = Pattern.compile(regular);
        Matcher matcher = pattern.matcher(barcode);

        MapBuilder<String, String> builder = MapUtil.builder();
        if (matcher.matches()) {
            for (int i = 0; i < businessReqList.size(); i++) {
                BarcodeBusinessFieldReq barcodeBusinessFieldReq = businessReqList.get(i);
                builder.put(barcodeBusinessFieldReq.getValue(), matcher.group(i + 1));
            }
        } else {
            throw new CheckedException("无法匹配到对应的正则");
        }
        return builder.build();
    }
}
