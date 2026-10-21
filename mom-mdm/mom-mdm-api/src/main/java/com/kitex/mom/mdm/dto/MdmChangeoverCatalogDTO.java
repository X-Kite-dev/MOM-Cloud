package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmChangeoverRuleEntity;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
public class MdmChangeoverCatalogDTO {
    private Map<String, String> familyByProduct;
    private Map<String, String> familyNames;
    private List<MdmChangeoverRuleEntity> rules;
    private List<String> productsWithoutFamily;
    private Boolean governanceRequired;
}
