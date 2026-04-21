package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.model.entity.CityInfo;
import com.atguigu.lease.model.entity.DistrictInfo;
import com.atguigu.lease.model.entity.PaymentType;
import com.atguigu.lease.model.entity.ProvinceInfo;
import com.atguigu.lease.web.app.service.ApartmentInfoService;
import com.atguigu.lease.web.app.service.CityInfoService;
import com.atguigu.lease.web.app.service.DistrictInfoService;
import com.atguigu.lease.web.app.service.PaymentTypeService;
import com.atguigu.lease.web.app.service.ProvinceInfoService;
import com.atguigu.lease.web.app.service.RoomInfoService;
import com.atguigu.lease.web.app.vo.room.RoomItemVo;
import com.atguigu.lease.web.app.vo.room.RoomQueryVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class AssistantRoomTools extends AbstractAssistantTools {

    private final ApartmentInfoService apartmentInfoService;
    private final RoomInfoService roomInfoService;
    private final ProvinceInfoService provinceInfoService;
    private final CityInfoService cityInfoService;
    private final DistrictInfoService districtInfoService;
    private final PaymentTypeService paymentTypeService;

    public AssistantRoomTools(ApartmentInfoService apartmentInfoService,
                              RoomInfoService roomInfoService,
                              ProvinceInfoService provinceInfoService,
                              CityInfoService cityInfoService,
                              DistrictInfoService districtInfoService,
                              PaymentTypeService paymentTypeService) {
        this.apartmentInfoService = apartmentInfoService;
        this.roomInfoService = roomInfoService;
        this.provinceInfoService = provinceInfoService;
        this.cityInfoService = cityInfoService;
        this.districtInfoService = districtInfoService;
        this.paymentTypeService = paymentTypeService;
    }

    @Tool(description = "根据公寓ID查询公寓详情")
    public AssistantToolResult getApartmentDetail(@ToolParam(description = "公寓ID", required = true) Long apartmentId,
                                                  ToolContext toolContext) {
        return executeTool("getApartmentDetail", toolContext, "公寓详情查询成功",
                () -> apartmentInfoService.getDetailById(apartmentId));
    }

    @Tool(description = "根据房间ID查询房间详情")
    public AssistantToolResult getRoomDetail(@ToolParam(description = "房间ID", required = true) Long roomId,
                                             ToolContext toolContext) {
        return executeTool("getRoomDetail", toolContext, "房间详情查询成功",
                () -> roomInfoService.getDetailById(roomId));
    }

    @Tool(description = "按自然语言偏好搜索房源。省、市、区和支付方式都可以直接传中文名称，支付方式可选；如果未传排序，则默认按租金升序；如果只是粗筛，位置加预算就足够。")
    public AssistantToolResult searchRooms(@ToolParam(description = "页码，默认 1") Integer pageNumber,
                                           @ToolParam(description = "每页条数，默认 10") Integer pageSize,
                                           @ToolParam(description = "省份名称，可选，例如 北京市 或 河北省") String provinceName,
                                           @ToolParam(description = "城市名称，可选，例如 北京市 或 广州市") String cityName,
                                           @ToolParam(description = "区县名称，可选，例如 昌平区 或 天河区") String districtName,
                                           @ToolParam(description = "最低租金，可选") BigDecimal minRent,
                                           @ToolParam(description = "最高租金，可选") BigDecimal maxRent,
                                           @ToolParam(description = "支付方式名称，可选，例如 月付、季付、半年付、年付") String paymentTypeName,
                                           @ToolParam(description = "排序方式，可选：asc 或 desc，默认 asc") String orderType,
                                           ToolContext toolContext) {
        return executeTool("searchRooms", toolContext, "房源列表查询成功", () -> {
            ResolvedRegion region = resolveRegion(provinceName, cityName, districtName);

            RoomQueryVo queryVo = new RoomQueryVo();
            queryVo.setProvinceId(region.provinceId());
            queryVo.setCityId(region.cityId());
            queryVo.setDistrictId(region.districtId());
            queryVo.setMinRent(minRent);
            queryVo.setMaxRent(maxRent);
            queryVo.setPaymentTypeId(resolvePaymentTypeId(paymentTypeName));
            queryVo.setOrderType(normalizeOrderType(orderType));

            IPage<RoomItemVo> result = roomInfoService.pageItem(
                    new Page<>(safePageNumber(pageNumber), safePageSize(pageSize)),
                    queryVo
            );
            return AssistantPageResult.from(result);
        });
    }

    @Tool(description = "查询某个公寓下的房间列表")
    public AssistantToolResult listRoomsByApartment(@ToolParam(description = "公寓ID", required = true) Long apartmentId,
                                                    @ToolParam(description = "页码，默认 1") Integer pageNumber,
                                                    @ToolParam(description = "每页条数，默认 10") Integer pageSize,
                                                    ToolContext toolContext) {
        return executeTool("listRoomsByApartment", toolContext, "公寓下房间列表查询成功", () -> {
            IPage<RoomItemVo> result = roomInfoService.pageItemByApartmentId(
                    new Page<>(safePageNumber(pageNumber), safePageSize(pageSize)),
                    apartmentId
            );
            return AssistantPageResult.from(result);
        });
    }

    private ResolvedRegion resolveRegion(String provinceName, String cityName, String districtName) {
        Long resolvedProvinceId = resolveProvinceId(provinceName);
        Long resolvedCityId = resolveCityId(cityName, resolvedProvinceId);
        Long resolvedDistrictId = resolveDistrictId(districtName, resolvedCityId);

        // 直辖市场景下，用户经常把“北京市”直接当城市传入，而 city_info 里可能存的是“市辖区”。
        if (resolvedCityId == null && hasText(cityName) && resolvedProvinceId == null) {
            resolvedProvinceId = resolveProvinceId(cityName);
        }

        return new ResolvedRegion(resolvedProvinceId, resolvedCityId, resolvedDistrictId);
    }

    private Long resolveProvinceId(String provinceName) {
        if (!hasText(provinceName)) {
            return null;
        }
        LambdaQueryWrapper<ProvinceInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(ProvinceInfo::getName, buildRegionCandidates(provinceName, "省", "市", "自治区", "特别行政区"))
                .last("limit 1");
        return provinceInfoService.list(queryWrapper).stream()
                .findFirst()
                .map(ProvinceInfo::getId)
                .orElse(null);
    }

    private Long resolveCityId(String cityName, Long provinceId) {
        if (!hasText(cityName)) {
            return null;
        }
        LambdaQueryWrapper<CityInfo> queryWrapper = new LambdaQueryWrapper<>();
        if (provinceId != null) {
            queryWrapper.eq(CityInfo::getProvinceId, provinceId.intValue());
        }
        queryWrapper.in(CityInfo::getName, buildRegionCandidates(cityName, "市", "自治州", "地区", "盟"))
                .last("limit 1");
        return cityInfoService.list(queryWrapper).stream()
                .findFirst()
                .map(CityInfo::getId)
                .orElse(null);
    }

    private Long resolveDistrictId(String districtName, Long cityId) {
        if (!hasText(districtName)) {
            return null;
        }
        LambdaQueryWrapper<DistrictInfo> queryWrapper = new LambdaQueryWrapper<>();
        if (cityId != null) {
            queryWrapper.eq(DistrictInfo::getCityId, cityId.intValue());
        }
        queryWrapper.in(DistrictInfo::getName, buildRegionCandidates(districtName, "区", "县", "市"))
                .last("limit 1");
        return districtInfoService.list(queryWrapper).stream()
                .findFirst()
                .map(DistrictInfo::getId)
                .orElse(null);
    }

    private Long resolvePaymentTypeId(String paymentTypeName) {
        if (!hasText(paymentTypeName)) {
            return null;
        }
        String normalizedKeyword = normalizeKeyword(paymentTypeName);
        return paymentTypeService.list().stream()
                .filter(paymentType -> matchesPaymentType(paymentType, normalizedKeyword))
                .map(PaymentType::getId)
                .findFirst()
                .orElse(null);
    }

    private boolean matchesPaymentType(PaymentType paymentType, String normalizedKeyword) {
        String normalizedName = normalizeKeyword(paymentType.getName());
        String normalizedInfo = normalizeKeyword(paymentType.getAdditionalInfo());
        return normalizedName.equals(normalizedKeyword)
                || normalizedName.contains(normalizedKeyword)
                || normalizedKeyword.contains(normalizedName)
                || (!normalizedInfo.isEmpty() && normalizedInfo.contains(normalizedKeyword));
    }

    private Set<String> buildRegionCandidates(String rawText, String... suffixes) {
        String compact = compactText(rawText);
        String stripped = stripRegionSuffix(compact);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, compact);
        addCandidate(candidates, stripped);
        for (String suffix : suffixes) {
            addCandidate(candidates, compact + suffix);
            addCandidate(candidates, stripped + suffix);
        }
        return candidates;
    }

    private String stripRegionSuffix(String text) {
        String result = text;
        String[] suffixes = {"特别行政区", "自治区", "自治州", "地区", "省", "市", "区", "县", "盟"};
        for (String suffix : suffixes) {
            if (result.endsWith(suffix) && result.length() > suffix.length()) {
                result = result.substring(0, result.length() - suffix.length());
                break;
            }
        }
        return result;
    }

    private void addCandidate(Set<String> candidates, String text) {
        if (hasText(text)) {
            candidates.add(text);
        }
    }

    private String normalizeOrderType(String orderType) {
        return "desc".equalsIgnoreCase(orderType) ? "desc" : "asc";
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String compactText(String text) {
        return text == null ? "" : text.trim().replace(" ", "");
    }

    private String normalizeKeyword(String text) {
        return compactText(text)
                .replace("，", "")
                .replace(",", "")
                .replace("。", "")
                .replace("、", "")
                .replace("(", "")
                .replace(")", "")
                .toLowerCase(Locale.ROOT);
    }

    private record ResolvedRegion(Long provinceId, Long cityId, Long districtId) {
    }
}
