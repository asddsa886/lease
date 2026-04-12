package com.atguigu.lease.web.app.chat.tool;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.model.entity.UserInfo;
import com.atguigu.lease.model.entity.ApartmentInfo;
import com.atguigu.lease.model.entity.CityInfo;
import com.atguigu.lease.model.entity.DistrictInfo;
import com.atguigu.lease.model.entity.LabelInfo;
import com.atguigu.lease.model.entity.LeaseTerm;
import com.atguigu.lease.model.entity.PaymentType;
import com.atguigu.lease.model.entity.ProvinceInfo;
import com.atguigu.lease.model.entity.RoomInfo;
import com.atguigu.lease.model.entity.ViewAppointment;
import com.atguigu.lease.model.enums.AppointmentStatus;
import com.atguigu.lease.model.enums.BaseEnum;
import com.atguigu.lease.model.enums.LeaseStatus;
import com.atguigu.lease.model.enums.ReleaseStatus;
import com.atguigu.lease.web.app.chat.agent.AppointmentTimeParser;
import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.atguigu.lease.web.app.service.ApartmentInfoService;
import com.atguigu.lease.web.app.service.CityInfoService;
import com.atguigu.lease.web.app.service.DistrictInfoService;
import com.atguigu.lease.web.app.service.LeaseAgreementService;
import com.atguigu.lease.web.app.service.ProvinceInfoService;
import com.atguigu.lease.web.app.service.RoomInfoService;
import com.atguigu.lease.web.app.service.UserInfoService;
import com.atguigu.lease.web.app.service.ViewAppointmentService;
import com.atguigu.lease.web.app.vo.agreement.AgreementItemVo;
import com.atguigu.lease.web.app.vo.appointment.AppointmentItemVo;
import com.atguigu.lease.web.app.vo.room.RoomDetailVo;
import com.atguigu.lease.web.app.vo.room.RoomItemVo;
import com.atguigu.lease.web.app.vo.room.RoomQueryVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class RentalAssistantTools {

    private static final BigDecimal MIN_REASONABLE_RENT = new BigDecimal("100");
    private static final ZoneId ZONE_ID = ZoneId.systemDefault();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern ROOM_HINT_PATTERN = Pattern.compile("^(.*?)(\\d{2,4})(?:室|房|号)?$");

    private final ApartmentInfoService apartmentInfoService;
    private final RoomInfoService roomInfoService;
    private final DistrictInfoService districtInfoService;
    private final CityInfoService cityInfoService;
    private final ProvinceInfoService provinceInfoService;
    private final ViewAppointmentService viewAppointmentService;
    private final LeaseAgreementService leaseAgreementService;
    private final UserInfoService userInfoService;
    private final AssistantProperties assistantProperties;
    private final ObjectMapper objectMapper;

    @Tool("查询平台数据库中的真实房源列表。用户询问某城市、某区、某预算范围内的房源时，必须优先调用这个工具，不要用公开市场信息代替。")
    public String searchRooms(
            @P(value = "区域名称，例如北京市、朝阳区", required = false) String districtName,
            @P(value = "最低月租，单位元", required = false) BigDecimal minRent,
            @P(value = "最高月租，单位元", required = false) BigDecimal maxRent) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", "searchRooms");

        RegionMatch regionMatch = resolveRegion(districtName);
        if (StringUtils.hasText(districtName) && regionMatch == null) {
            payload.put("summary", "没有找到对应的区域，请确认城市、区县名称后再试。");
            payload.put("items", List.of());
            return toJson(payload);
        }

        RoomQueryVo queryVo = new RoomQueryVo();
        if (regionMatch != null) {
            queryVo.setProvinceId(regionMatch.provinceId());
            queryVo.setCityId(regionMatch.cityId());
            queryVo.setDistrictId(regionMatch.districtId());
        }
        queryVo.setMinRent(minRent);
        queryVo.setMaxRent(maxRent);

        long pageSize = Math.max(1, assistantProperties.getMaxSearchResults() == null ? 5 : assistantProperties.getMaxSearchResults());
        IPage<RoomItemVo> result = roomInfoService.pageItem(new Page<>(1, pageSize), queryVo);

        List<Map<String, Object>> items = result.getRecords().stream()
                .map(this::buildRoomSearchItem)
                .toList();

        payload.put("summary", items.isEmpty()
                ? "没有查到符合条件的房源。"
                : "共找到 %d 套房源，当前返回 %d 套。".formatted(result.getTotal(), items.size()));
        payload.put("filters", buildSearchFilters(regionMatch, districtName, minRent, maxRent));
        payload.put("items", items);
        return toJson(payload);
    }

    @Tool("查询平台数据库中的真实房间详情。用户询问某个房间的介绍、配置、租金、支付方式、租期等时，必须优先调用这个工具。")
    public String getRoomDetail(@P("房间ID") Long roomId) {
        RoomDetailVo detail = roomInfoService.getDetailById(roomId);
        return buildRoomDetailPayload(detail, "已获取房间详情。");
    }

    @Tool("根据公寓名、小区名、房号等线索查询平台数据库中的真实房间详情，适合处理“温都水城社区101介绍一下”这类问题。")
    public String getRoomDetailByKeyword(@P("房间线索，例如温都水城社区101") String roomKeyword) {
        if (!StringUtils.hasText(roomKeyword)) {
            return toJson(Map.of("tool", "getRoomDetailByKeyword", "summary", "请提供公寓名或房号线索。"));
        }

        RoomKeywordParts parts = parseRoomKeyword(roomKeyword);
        List<ApartmentInfo> apartments = findApartments(parts.apartmentKeyword());
        if (apartments.isEmpty()) {
            return toJson(Map.of(
                    "tool", "getRoomDetailByKeyword",
                    "summary", "没有找到对应的小区或公寓，请提供更完整的公寓名。"
            ));
        }

        RoomInfo roomInfo = findRoomByApartmentHints(apartments, parts.roomNumber());
        if (roomInfo == null) {
            return toJson(Map.of(
                    "tool", "getRoomDetailByKeyword",
                    "summary", "找到相关公寓了，但没有定位到具体房间，请补充房号后再试。"
            ));
        }

        return buildRoomDetailPayload(roomInfoService.getDetailById(roomInfo.getId()), "已根据公寓名和房号找到房间详情。");
    }

    @Tool("查询当前登录用户在平台内的真实看房预约列表。用户说“我的预约”“查预约”“预约记录”时，必须优先调用这个工具。")
    public String getMyAppointments() {
        LoginUser loginUser = requireLoginUser();
        List<AppointmentItemVo> appointments = viewAppointmentService.getDetailByUserId(loginUser.getId());

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", "getMyAppointments");
        payload.put("summary", appointments == null || appointments.isEmpty()
                ? "当前没有预约记录。"
                : "当前共有 %d 条预约记录。".formatted(appointments.size()));
        payload.put("items", appointments == null ? List.of() : appointments.stream()
                .map(this::buildAppointmentItem)
                .toList());
        return toJson(payload);
    }

    @Tool("查询当前登录用户在平台内的真实租约列表。用户说“我的租约”“查租约”“租约记录”时，必须优先调用这个工具。")
    public String getMyLeaseAgreements() {
        LoginUser loginUser = requireLoginUser();
        List<AgreementItemVo> agreements = leaseAgreementService.listItemByPhone(loginUser.getUsername());

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", "getMyLeaseAgreements");
        payload.put("summary", agreements == null || agreements.isEmpty()
                ? "当前没有租约记录。"
                : "当前共有 %d 条租约记录。".formatted(agreements.size()));
        payload.put("items", agreements == null ? List.of() : agreements.stream()
                .map(this::buildAgreementItem)
                .toList());
        return toJson(payload);
    }

    @Tool("为当前登录用户创建看房预约，需要提供房间ID和明确的预约时间，例如 明天下午 或 2026-04-10 15:00。")
    public String createRoomAppointment(@P("房间ID") Long roomId,
                                        @P("预约时间描述，例如 明天下午、明天15点、2026-04-10 15:00") String appointmentTimeText,
                                        @P(value = "补充备注，可为空", required = false) String additionalInfo) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", "createRoomAppointment");

        LoginUser loginUser = requireLoginUser();
        if (roomId == null) {
            payload.put("summary", "缺少房间信息，暂时无法创建预约。");
            return toJson(payload);
        }

        RoomDetailVo detail = roomInfoService.getDetailById(roomId);
        if (detail == null || detail.getId() == null || detail.getApartmentId() == null) {
            payload.put("summary", "没有找到可预约的房源信息，请先确认房源详情。");
            return toJson(payload);
        }

        AppointmentTimeParser.ParsedAppointmentTime parsedAppointmentTime = AppointmentTimeParser.parse(appointmentTimeText, ZONE_ID);
        if (parsedAppointmentTime == null) {
            payload.put("summary", "预约时间无法识别，请明确到具体日期或时间段，例如“明天下午”或“2026-04-10 15:00”。");
            return toJson(payload);
        }

        UserInfo userInfo = userInfoService.getById(loginUser.getId());
        String contactName = userInfo != null && StringUtils.hasText(userInfo.getNickname())
                ? userInfo.getNickname().trim()
                : fallbackContactName(loginUser.getUsername());
        String contactPhone = userInfo != null && StringUtils.hasText(userInfo.getPhone())
                ? userInfo.getPhone().trim()
                : loginUser.getUsername();

        ViewAppointment appointment = new ViewAppointment();
        appointment.setUserId(loginUser.getId());
        appointment.setName(contactName);
        appointment.setPhone(contactPhone);
        appointment.setApartmentId(detail.getApartmentId());
        appointment.setAppointmentTime(parsedAppointmentTime.date());
        if (StringUtils.hasText(additionalInfo)) {
            appointment.setAdditionalInfo(additionalInfo.trim());
        }

        viewAppointmentService.saveOrUpdateForCurrentUser(appointment, loginUser.getId());

        payload.put("summary", "已为你创建看房预约，请留意预约时间并按时到场。");
        payload.put("appointmentId", appointment.getId());
        payload.put("appointmentTime", parsedAppointmentTime.displayText());
        payload.put("appointmentStatusText", AppointmentStatus.WAITING.getName());
        payload.put("roomId", detail.getId());
        payload.put("apartmentId", detail.getApartmentId());
        payload.put("title", buildRoomTitle(
                detail.getApartmentItemVo() == null ? null : detail.getApartmentItemVo().getName(),
                detail.getRoomNumber()));
        payload.put("locationText", buildLocationText(
                detail.getApartmentItemVo() == null ? null : detail.getApartmentItemVo().getProvinceName(),
                detail.getApartmentItemVo() == null ? null : detail.getApartmentItemVo().getCityName(),
                detail.getApartmentItemVo() == null ? null : detail.getApartmentItemVo().getDistrictName(),
                detail.getApartmentItemVo() == null ? null : detail.getApartmentItemVo().getAddressDetail()));
        return toJson(payload);
    }

    @Tool("取消当前登录用户的一条看房预约，需要提供预约ID")
    public String cancelAppointment(@P("预约ID") Long appointmentId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", "cancelAppointment");

        LoginUser loginUser = requireLoginUser();
        if (appointmentId == null) {
            payload.put("summary", "缺少预约ID，暂时无法取消预约。");
            return toJson(payload);
        }

        ViewAppointment appointment = viewAppointmentService.getById(appointmentId);
        if (appointment == null) {
            payload.put("summary", "没有找到对应的预约记录。");
            return toJson(payload);
        }
        if (!loginUser.getId().equals(appointment.getUserId())) {
            payload.put("summary", "当前预约不属于你，无法执行取消操作。");
            return toJson(payload);
        }

        ApartmentInfo apartmentInfo = apartmentInfoService.getById(appointment.getApartmentId());
        String apartmentName = apartmentInfo == null ? null : apartmentInfo.getName();
        String appointmentTime = formatDateTime(appointment.getAppointmentTime());

        if (appointment.getAppointmentStatus() == AppointmentStatus.CANCELED) {
            payload.put("summary", "这条预约已经取消，不需要重复操作。");
            payload.put("appointmentId", appointmentId);
            payload.put("appointmentStatusCode", AppointmentStatus.CANCELED.getCode());
            payload.put("appointmentStatusText", AppointmentStatus.CANCELED.getName());
            payload.put("apartmentName", safeText(apartmentName, "待确认公寓"));
            payload.put("appointmentTime", appointmentTime);
            return toJson(payload);
        }

        if (appointment.getAppointmentStatus() != AppointmentStatus.WAITING) {
            payload.put("summary", "当前预约状态不是“待看房”，暂时不能取消。");
            payload.put("appointmentId", appointmentId);
            payload.put("appointmentStatusCode", appointment.getAppointmentStatus() == null ? null : appointment.getAppointmentStatus().getCode());
            payload.put("appointmentStatusText", enumName(appointment.getAppointmentStatus(), "状态待确认"));
            payload.put("apartmentName", safeText(apartmentName, "待确认公寓"));
            payload.put("appointmentTime", appointmentTime);
            return toJson(payload);
        }

        ViewAppointment canceled = viewAppointmentService.cancelForCurrentUser(appointmentId, loginUser.getId());
        payload.put("summary", "已为你取消这条看房预约。");
        payload.put("appointmentId", appointmentId);
        payload.put("appointmentStatusCode", AppointmentStatus.CANCELED.getCode());
        payload.put("appointmentStatusText", AppointmentStatus.CANCELED.getName());
        payload.put("apartmentName", safeText(apartmentName, "待确认公寓"));
        payload.put("appointmentTime", appointmentTime);
        payload.put("apartmentId", canceled.getApartmentId());
        return toJson(payload);
    }

    @Tool("修改当前登录用户的一条看房预约时间，需要提供预约ID和新的预约时间，例如 明天下午 或 2026-04-10 15:00")
    public String rescheduleAppointment(@P("预约ID") Long appointmentId,
                                        @P("新的预约时间描述，例如 明天下午、明天15点、2026-04-10 15:00") String appointmentTimeText) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", "rescheduleAppointment");

        LoginUser loginUser = requireLoginUser();
        if (appointmentId == null) {
            payload.put("summary", "缺少预约ID，暂时无法修改预约时间。");
            return toJson(payload);
        }

        ViewAppointment appointment = viewAppointmentService.getById(appointmentId);
        if (appointment == null) {
            payload.put("summary", "没有找到对应的预约记录。");
            return toJson(payload);
        }
        if (!loginUser.getId().equals(appointment.getUserId())) {
            payload.put("summary", "当前预约不属于你，无法执行改约操作。");
            return toJson(payload);
        }

        ApartmentInfo apartmentInfo = apartmentInfoService.getById(appointment.getApartmentId());
        String apartmentName = apartmentInfo == null ? null : apartmentInfo.getName();
        String originalAppointmentTime = formatDateTime(appointment.getAppointmentTime());
        payload.put("appointmentId", appointmentId);
        payload.put("apartmentName", safeText(apartmentName, "待确认公寓"));
        payload.put("originalAppointmentTime", originalAppointmentTime);

        if (appointment.getAppointmentStatus() == AppointmentStatus.CANCELED) {
            payload.put("summary", "这条预约已经取消，不能继续改约。");
            payload.put("appointmentStatusCode", AppointmentStatus.CANCELED.getCode());
            payload.put("appointmentStatusText", AppointmentStatus.CANCELED.getName());
            return toJson(payload);
        }
        if (appointment.getAppointmentStatus() != AppointmentStatus.WAITING) {
            payload.put("summary", "当前预约状态不是“待看房”，暂时不能改约。");
            payload.put("appointmentStatusCode", appointment.getAppointmentStatus() == null ? null : appointment.getAppointmentStatus().getCode());
            payload.put("appointmentStatusText", enumName(appointment.getAppointmentStatus(), "状态待确认"));
            return toJson(payload);
        }

        AppointmentTimeParser.ParsedAppointmentTime parsedAppointmentTime = AppointmentTimeParser.parse(appointmentTimeText, ZONE_ID);
        if (parsedAppointmentTime == null) {
            payload.put("summary", "新的预约时间无法识别，请明确到具体日期或时间段，例如“明天下午”或“2026-04-10 15:00”。");
            return toJson(payload);
        }

        ViewAppointment rescheduled = viewAppointmentService.rescheduleForCurrentUser(
                appointmentId,
                parsedAppointmentTime.date(),
                loginUser.getId()
        );
        payload.put("summary", "已为你修改预约时间，请按新的预约时间到场。");
        payload.put("appointmentStatusCode", AppointmentStatus.WAITING.getCode());
        payload.put("appointmentStatusText", AppointmentStatus.WAITING.getName());
        payload.put("appointmentTime", parsedAppointmentTime.displayText());
        payload.put("apartmentId", rescheduled.getApartmentId());
        return toJson(payload);
    }

    private Map<String, Object> buildSearchFilters(RegionMatch regionMatch,
                                                   String regionKeyword,
                                                   BigDecimal minRent,
                                                   BigDecimal maxRent) {
        LinkedHashMap<String, Object> filters = new LinkedHashMap<>();
        if (regionMatch != null) {
            filters.put("regionKeyword", regionMatch.name());
            filters.put("regionLevel", regionMatch.level());
        } else if (StringUtils.hasText(regionKeyword)) {
            filters.put("regionKeyword", regionKeyword.trim());
        }
        if (minRent != null) {
            filters.put("minRent", minRent);
        }
        if (maxRent != null) {
            filters.put("maxRent", maxRent);
        }
        return filters;
    }

    private Map<String, Object> buildRoomSearchItem(RoomItemVo room) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        String apartmentName = room.getApartmentInfo() == null ? null : room.getApartmentInfo().getName();
        String roomNumber = room.getRoomNumber();
        List<String> notes = new ArrayList<>();

        if (!StringUtils.hasText(apartmentName) || !StringUtils.hasText(roomNumber)) {
            notes.add("部分房间信息待补充");
        }
        if (normalizeRent(room.getRent()) == null) {
            notes.add("价格待确认");
        }

        item.put("roomId", room.getId());
        item.put("title", buildRoomTitle(apartmentName, roomNumber));
        item.put("apartmentName", safeText(apartmentName, null));
        item.put("roomNumber", safeText(roomNumber, null));
        item.put("locationText", buildLocationText(
                room.getApartmentInfo() == null ? null : room.getApartmentInfo().getProvinceName(),
                room.getApartmentInfo() == null ? null : room.getApartmentInfo().getCityName(),
                room.getApartmentInfo() == null ? null : room.getApartmentInfo().getDistrictName(),
                room.getApartmentInfo() == null ? null : room.getApartmentInfo().getAddressDetail()));
        item.put("rentText", formatRent(room.getRent()));
        item.put("labels", toNames(room.getLabelInfoList()));
        if (!notes.isEmpty()) {
            item.put("notes", notes);
        }
        return item;
    }

    private String buildRoomDetailPayload(RoomDetailVo detail, String summary) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", "getRoomDetail");

        if (detail == null || detail.getId() == null) {
            payload.put("summary", "没有查到对应房间。");
            return toJson(payload);
        }

        List<String> notes = new ArrayList<>();
        if (!StringUtils.hasText(detail.getRoomNumber()) || detail.getApartmentItemVo() == null
                || !StringUtils.hasText(detail.getApartmentItemVo().getName())) {
            notes.add("部分房间信息待补充");
        }
        if (normalizeRent(detail.getRent()) == null) {
            notes.add("价格待确认");
        }

        payload.put("summary", summary);
        payload.put("roomId", detail.getId());
        payload.put("apartmentId", detail.getApartmentId());
        payload.put("title", buildRoomTitle(
                detail.getApartmentItemVo() == null ? null : detail.getApartmentItemVo().getName(),
                detail.getRoomNumber()));
        payload.put("apartmentName", safeText(detail.getApartmentItemVo() == null ? null : detail.getApartmentItemVo().getName(), null));
        payload.put("roomNumber", safeText(detail.getRoomNumber(), null));
        payload.put("locationText", buildLocationText(
                detail.getApartmentItemVo() == null ? null : detail.getApartmentItemVo().getProvinceName(),
                detail.getApartmentItemVo() == null ? null : detail.getApartmentItemVo().getCityName(),
                detail.getApartmentItemVo() == null ? null : detail.getApartmentItemVo().getDistrictName(),
                detail.getApartmentItemVo() == null ? null : detail.getApartmentItemVo().getAddressDetail()));
        payload.put("rentText", formatRent(detail.getRent()));
        payload.put("labels", toNames(detail.getLabelInfoList()));
        payload.put("paymentTypes", toPaymentTypeNames(detail.getPaymentTypeList()));
        payload.put("leaseTerms", toLeaseTerms(detail.getLeaseTermList()));
        payload.put("facilityCount", detail.getFacilityInfoList() == null ? 0 : detail.getFacilityInfoList().size());
        payload.put("attrCount", detail.getAttrValueVoList() == null ? 0 : detail.getAttrValueVoList().size());
        if (!notes.isEmpty()) {
            payload.put("notes", notes);
        }
        return toJson(payload);
    }

    private Map<String, Object> buildAppointmentItem(AppointmentItemVo item) {
        LinkedHashMap<String, Object> appointment = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        if (!StringUtils.hasText(item.getApartmentName())) {
            notes.add("公寓信息待补充");
        }

        appointment.put("appointmentId", item.getId());
        appointment.put("apartmentName", safeText(item.getApartmentName(), "待确认公寓"));
        appointment.put("appointmentTime", formatDateTime(item.getAppointmentTime()));
        appointment.put("appointmentStatusCode", item.getAppointmentStatus() == null ? null : item.getAppointmentStatus().getCode());
        appointment.put("statusText", enumName(item.getAppointmentStatus(), "状态待确认"));
        if (!notes.isEmpty()) {
            appointment.put("notes", notes);
        }
        return appointment;
    }

    private Map<String, Object> buildAgreementItem(AgreementItemVo item) {
        LinkedHashMap<String, Object> agreement = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        if (!StringUtils.hasText(item.getApartmentName()) || !StringUtils.hasText(item.getRoomNumber())) {
            notes.add("部分房间信息待补充");
        }
        if (normalizeRent(item.getRent()) == null) {
            notes.add("价格待确认");
        }

        agreement.put("agreementId", item.getId());
        agreement.put("apartmentName", safeText(item.getApartmentName(), "待确认公寓"));
        agreement.put("roomNumber", safeText(item.getRoomNumber(), "待补充"));
        agreement.put("leaseStatusText", enumName(item.getLeaseStatus(), "状态待确认"));
        agreement.put("leasePeriod", formatLeasePeriod(item.getLeaseStartDate(), item.getLeaseEndDate()));
        agreement.put("rentText", formatRent(item.getRent()));
        if (!notes.isEmpty()) {
            agreement.put("notes", notes);
        }
        return agreement;
    }

    private LoginUser requireLoginUser() {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new IllegalStateException("当前用户未登录");
        }
        return loginUser;
    }

    private RegionMatch resolveRegion(String regionKeyword) {
        if (!StringUtils.hasText(regionKeyword)) {
            return null;
        }

        String keyword = regionKeyword.trim();

        DistrictInfo district = findDistrict(keyword);
        if (district != null) {
            return new RegionMatch("district", keyword, null, null, district.getId().longValue());
        }

        CityInfo city = findCity(keyword);
        if (city != null) {
            return new RegionMatch("city", keyword, null, city.getId().longValue(), null);
        }

        ProvinceInfo province = findProvince(keyword);
        if (province != null) {
            return new RegionMatch("province", keyword, province.getId().longValue(), null, null);
        }

        return null;
    }

    private String buildRoomTitle(String apartmentName, String roomNumber) {
        String cleanApartmentName = safeText(apartmentName, "待确认公寓");
        String cleanRoomNumber = safeText(roomNumber, "待补充房间");
        return cleanApartmentName + " " + cleanRoomNumber;
    }

    private String buildLocationText(String provinceName, String cityName, String districtName, String addressDetail) {
        List<String> parts = new ArrayList<>();
        appendIfPresent(parts, provinceName);
        appendIfPresent(parts, cityName);
        appendIfPresent(parts, districtName);
        appendIfPresent(parts, addressDetail);
        if (parts.isEmpty()) {
            return null;
        }
        return String.join(" ", parts);
    }

    private List<String> toNames(List<LabelInfo> labels) {
        if (labels == null) {
            return List.of();
        }
        return labels.stream()
                .map(LabelInfo::getName)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }

    private List<String> toPaymentTypeNames(List<PaymentType> paymentTypes) {
        if (paymentTypes == null) {
            return List.of();
        }
        return paymentTypes.stream()
                .map(PaymentType::getName)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }

    private List<String> toLeaseTerms(List<LeaseTerm> leaseTerms) {
        if (leaseTerms == null) {
            return List.of();
        }
        return leaseTerms.stream()
                .map(term -> term.getMonthCount() + (StringUtils.hasText(term.getUnit()) ? term.getUnit() : "个月"))
                .toList();
    }

    private String safeText(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        return value.trim();
    }

    private String fallbackContactName(String phone) {
        if (!StringUtils.hasText(phone) || phone.length() < 4) {
            return "租客用户";
        }
        return "用户-" + phone.substring(phone.length() - 4);
    }

    private void appendIfPresent(List<String> parts, String value) {
        if (StringUtils.hasText(value)) {
            String normalized = value.trim();
            if (!parts.contains(normalized)) {
                parts.add(normalized);
            }
        }
    }

    private String formatRent(BigDecimal rent) {
        BigDecimal normalizedRent = normalizeRent(rent);
        if (normalizedRent == null) {
            return null;
        }
        return normalizedRent.stripTrailingZeros().toPlainString() + "元/月";
    }

    private BigDecimal normalizeRent(BigDecimal rent) {
        if (rent == null || rent.compareTo(MIN_REASONABLE_RENT) < 0) {
            return null;
        }
        return rent;
    }

    private String formatLeasePeriod(Date startDate, Date endDate) {
        String start = formatDate(startDate);
        String end = formatDate(endDate);
        if (start == null && end == null) {
            return null;
        }
        if (start == null) {
            return "截至 " + end;
        }
        if (end == null) {
            return start + " 起";
        }
        return start + " 至 " + end;
    }

    private String formatDate(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZONE_ID).toLocalDate().format(DATE_FORMATTER);
    }

    private String formatDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZONE_ID).toLocalDateTime().format(DATETIME_FORMATTER);
    }

    private String enumName(BaseEnum value, String fallback) {
        if (value == null) {
            return fallback;
        }

        if (value instanceof AppointmentStatus appointmentStatus && StringUtils.hasText(appointmentStatus.getName())) {
            return appointmentStatus.getName();
        }
        if (value instanceof LeaseStatus leaseStatus && StringUtils.hasText(leaseStatus.getName())) {
            return leaseStatus.getName();
        }
        return StringUtils.hasText(value.getName()) ? value.getName() : fallback;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return String.valueOf(payload);
        }
    }

    private DistrictInfo findDistrict(String keyword) {
        LambdaQueryWrapper<DistrictInfo> exactWrapper = new LambdaQueryWrapper<>();
        exactWrapper.eq(DistrictInfo::getName, keyword).last("limit 1");
        DistrictInfo district = districtInfoService.getOne(exactWrapper, false);
        if (district != null) {
            return district;
        }

        LambdaQueryWrapper<DistrictInfo> likeWrapper = new LambdaQueryWrapper<>();
        likeWrapper.like(DistrictInfo::getName, keyword).last("limit 1");
        return districtInfoService.getOne(likeWrapper, false);
    }

    private CityInfo findCity(String keyword) {
        LambdaQueryWrapper<CityInfo> exactWrapper = new LambdaQueryWrapper<>();
        exactWrapper.eq(CityInfo::getName, keyword).last("limit 1");
        CityInfo city = cityInfoService.getOne(exactWrapper, false);
        if (city != null) {
            return city;
        }

        LambdaQueryWrapper<CityInfo> likeWrapper = new LambdaQueryWrapper<>();
        likeWrapper.like(CityInfo::getName, keyword).last("limit 1");
        return cityInfoService.getOne(likeWrapper, false);
    }

    private ProvinceInfo findProvince(String keyword) {
        LambdaQueryWrapper<ProvinceInfo> exactWrapper = new LambdaQueryWrapper<>();
        exactWrapper.eq(ProvinceInfo::getName, keyword).last("limit 1");
        ProvinceInfo province = provinceInfoService.getOne(exactWrapper, false);
        if (province != null) {
            return province;
        }

        LambdaQueryWrapper<ProvinceInfo> likeWrapper = new LambdaQueryWrapper<>();
        likeWrapper.like(ProvinceInfo::getName, keyword).last("limit 1");
        return provinceInfoService.getOne(likeWrapper, false);
    }

    private RoomKeywordParts parseRoomKeyword(String roomKeyword) {
        String normalized = roomKeyword.trim()
                .replace("这个介绍介绍", "")
                .replace("介绍介绍", "")
                .replace("介绍一下", "")
                .replace("介绍", "")
                .trim();

        Matcher matcher = ROOM_HINT_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            String apartmentKeyword = matcher.group(1) == null ? "" : matcher.group(1).trim();
            String roomNumber = matcher.group(2);
            if (StringUtils.hasText(apartmentKeyword)) {
                return new RoomKeywordParts(apartmentKeyword, roomNumber);
            }
        }
        return new RoomKeywordParts(normalized, null);
    }

    private List<ApartmentInfo> findApartments(String apartmentKeyword) {
        if (!StringUtils.hasText(apartmentKeyword)) {
            return List.of();
        }

        LambdaQueryWrapper<ApartmentInfo> exactWrapper = new LambdaQueryWrapper<>();
        exactWrapper.eq(ApartmentInfo::getName, apartmentKeyword.trim())
                .eq(ApartmentInfo::getIsRelease, ReleaseStatus.RELEASED)
                .last("limit 5");
        List<ApartmentInfo> exactMatches = apartmentInfoService.list(exactWrapper);
        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }

        LambdaQueryWrapper<ApartmentInfo> likeWrapper = new LambdaQueryWrapper<>();
        likeWrapper.like(ApartmentInfo::getName, apartmentKeyword.trim())
                .eq(ApartmentInfo::getIsRelease, ReleaseStatus.RELEASED)
                .last("limit 5");
        return apartmentInfoService.list(likeWrapper);
    }

    private RoomInfo findRoomByApartmentHints(List<ApartmentInfo> apartments, String roomNumber) {
        for (ApartmentInfo apartment : apartments) {
            LambdaQueryWrapper<RoomInfo> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(RoomInfo::getApartmentId, apartment.getId())
                    .eq(RoomInfo::getIsRelease, ReleaseStatus.RELEASED);
            if (StringUtils.hasText(roomNumber)) {
                wrapper.eq(RoomInfo::getRoomNumber, roomNumber);
            }
            wrapper.last("limit 1");
            RoomInfo roomInfo = roomInfoService.getOne(wrapper, false);
            if (roomInfo != null) {
                return roomInfo;
            }
        }
        return null;
    }

    private record RegionMatch(String level, String name, Long provinceId, Long cityId, Long districtId) {
    }

    private record RoomKeywordParts(String apartmentKeyword, String roomNumber) {
    }
}
