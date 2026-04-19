package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.model.entity.CityInfo;
import com.atguigu.lease.model.entity.DistrictInfo;
import com.atguigu.lease.model.entity.PaymentType;
import com.atguigu.lease.model.entity.ProvinceInfo;
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

    private final RoomInfoService roomInfoService;
    private final ProvinceInfoService provinceInfoService;
    private final CityInfoService cityInfoService;
    private final DistrictInfoService districtInfoService;
    private final PaymentTypeService paymentTypeService;

    public AssistantRoomTools(RoomInfoService roomInfoService,
                              ProvinceInfoService provinceInfoService,
                              CityInfoService cityInfoService,
                              DistrictInfoService districtInfoService,
                              PaymentTypeService paymentTypeService) {
        this.roomInfoService = roomInfoService;
        this.provinceInfoService = provinceInfoService;
        this.cityInfoService = cityInfoService;
        this.districtInfoService = districtInfoService;
        this.paymentTypeService = paymentTypeService;
    }

    @Tool(description = "Get room detail by room id.")
    public AssistantToolResult getRoomDetail(@ToolParam(description = "Room id", required = true) Long roomId,
                                             ToolContext toolContext) {
        return executeTool("getRoomDetail", toolContext, "房间详情查询成功",
                () -> roomInfoService.getDetailById(roomId));
    }

    @Tool(description = "Search room list with natural language preferences. Province, city, district and payment type can be passed by Chinese names directly. Payment type is optional. If sort order is omitted, use asc by default. For broad browsing, location plus rent budget is enough.")
    public AssistantToolResult searchRooms(@ToolParam(description = "Page number, default 1") Integer pageNumber,
                                           @ToolParam(description = "Page size, default 10") Integer pageSize,
                                           @ToolParam(description = "Province name, optional, such as 北京市 or 河北省") String provinceName,
                                           @ToolParam(description = "City name, optional, such as 北京市 or 广州市") String cityName,
                                           @ToolParam(description = "District name, optional, such as 昌平区 or 天河区") String districtName,
                                           @ToolParam(description = "Minimum rent, optional") BigDecimal minRent,
                                           @ToolParam(description = "Maximum rent, optional") BigDecimal maxRent,
                                           @ToolParam(description = "Payment type name, optional, such as 月付、季付、半年付、年付") String paymentTypeName,
                                           @ToolParam(description = "Sort order, optional: asc or desc. Default asc") String orderType,
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

    @Tool(description = "List rooms under a specific apartment.")
    public AssistantToolResult listRoomsByApartment(@ToolParam(description = "Apartment id", required = true) Long apartmentId,
                                                    @ToolParam(description = "Page number, default 1") Integer pageNumber,
                                                    @ToolParam(description = "Page size, default 10") Integer pageSize,
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

        // Municipalities like "北京市" are often stored as province names while city_info uses "市辖区".
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
        queryWrapper.in(DistrictInfo::getName, buildRegionCandidates(districtName, "区", "县", "旗"))
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
        String[] suffixes = {"特别行政区", "自治区", "自治州", "地区", "省", "市", "区", "县", "旗", "盟"};
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
                .replace("（", "")
                .replace("）", "")
                .replace("(", "")
                .replace(")", "")
                .toLowerCase(Locale.ROOT);
    }

    private record ResolvedRegion(Long provinceId, Long cityId, Long districtId) {
    }
}
