package com.atguigu.lease.web.app.chat.tool;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.model.entity.ApartmentInfo;
import com.atguigu.lease.model.entity.CityInfo;
import com.atguigu.lease.model.entity.RoomInfo;
import com.atguigu.lease.model.entity.ViewAppointment;
import com.atguigu.lease.model.enums.AppointmentStatus;
import com.atguigu.lease.model.enums.ReleaseStatus;
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
import com.atguigu.lease.web.app.vo.apartment.ApartmentItemVo;
import com.atguigu.lease.web.app.vo.room.RoomDetailVo;
import com.atguigu.lease.web.app.vo.room.RoomItemVo;
import com.atguigu.lease.web.app.vo.room.RoomQueryVo;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RentalAssistantToolsTest {

    @Mock
    private ApartmentInfoService apartmentInfoService;
    @Mock
    private RoomInfoService roomInfoService;
    @Mock
    private DistrictInfoService districtInfoService;
    @Mock
    private CityInfoService cityInfoService;
    @Mock
    private ProvinceInfoService provinceInfoService;
    @Mock
    private ViewAppointmentService viewAppointmentService;
    @Mock
    private LeaseAgreementService leaseAgreementService;
    @Mock
    private UserInfoService userInfoService;

    private RentalAssistantTools rentalAssistantTools;

    @BeforeEach
    void setUp() {
        AssistantProperties assistantProperties = new AssistantProperties();
        assistantProperties.setMaxSearchResults(5);
        rentalAssistantTools = new RentalAssistantTools(
                apartmentInfoService,
                roomInfoService,
                districtInfoService,
                cityInfoService,
                provinceInfoService,
                viewAppointmentService,
                leaseAgreementService,
                userInfoService,
                assistantProperties,
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    void searchRooms_shouldSupportCityNameAndIncludeLocation() {
        when(districtInfoService.getOne(any(), eq(false))).thenReturn(null, null);

        CityInfo cityInfo = new CityInfo();
        cityInfo.setId(11L);
        cityInfo.setName("北京市");
        when(cityInfoService.getOne(any(), eq(false))).thenReturn(cityInfo);

        RoomItemVo roomItemVo = new RoomItemVo();
        roomItemVo.setId(101L);
        roomItemVo.setRoomNumber("101");
        roomItemVo.setRent(new BigDecimal("2500"));

        ApartmentInfo apartmentInfo = new ApartmentInfo();
        apartmentInfo.setName("温都水城社区");
        apartmentInfo.setProvinceName("北京市");
        apartmentInfo.setCityName("北京市");
        apartmentInfo.setDistrictName("昌平区");
        apartmentInfo.setAddressDetail("温都水城北七家镇王府街55号");
        roomItemVo.setApartmentInfo(apartmentInfo);

        Page<RoomItemVo> page = new Page<>();
        page.setRecords(List.of(roomItemVo));
        page.setTotal(1);
        when(roomInfoService.pageItem(any(Page.class), any(RoomQueryVo.class))).thenReturn(page);

        String json = rentalAssistantTools.searchRooms("北京市", null, new BigDecimal("3000"));

        ArgumentCaptor<RoomQueryVo> queryCaptor = ArgumentCaptor.forClass(RoomQueryVo.class);
        verify(roomInfoService).pageItem(any(Page.class), queryCaptor.capture());
        assertEquals(11L, queryCaptor.getValue().getCityId());
        assertTrue(json.contains("\"regionLevel\":\"city\""));
        assertTrue(json.contains("北京市 昌平区 温都水城北七家镇王府街55号"));
    }

    @Test
    void getRoomDetailByKeyword_shouldResolveApartmentAndRoomNumber() {
        ApartmentInfo apartmentInfo = new ApartmentInfo();
        apartmentInfo.setId(8L);
        apartmentInfo.setName("温都水城社区");
        apartmentInfo.setIsRelease(ReleaseStatus.RELEASED);
        when(apartmentInfoService.list(any())).thenReturn(List.of(apartmentInfo));

        RoomInfo roomInfo = new RoomInfo();
        roomInfo.setId(101L);
        roomInfo.setApartmentId(8L);
        roomInfo.setRoomNumber("101");
        roomInfo.setIsRelease(ReleaseStatus.RELEASED);
        when(roomInfoService.getOne(any(), eq(false))).thenReturn(roomInfo);

        ApartmentItemVo apartmentItemVo = new ApartmentItemVo();
        apartmentItemVo.setName("温都水城社区");
        apartmentItemVo.setProvinceName("北京市");
        apartmentItemVo.setCityName("北京市");
        apartmentItemVo.setDistrictName("昌平区");
        apartmentItemVo.setAddressDetail("温都水城北七家镇王府街55号");

        RoomDetailVo roomDetailVo = new RoomDetailVo();
        roomDetailVo.setId(101L);
        roomDetailVo.setRoomNumber("101");
        roomDetailVo.setRent(new BigDecimal("2500"));
        roomDetailVo.setApartmentItemVo(apartmentItemVo);
        when(roomInfoService.getDetailById(101L)).thenReturn(roomDetailVo);

        String json = rentalAssistantTools.getRoomDetailByKeyword("温都水城社区101介绍一下");

        assertTrue(json.contains("已根据公寓名和房号找到房间详情。"));
        assertTrue(json.contains("\"roomId\":101"));
        assertTrue(json.contains("温都水城社区 101"));
    }

    @Test
    void getMyLeaseAgreements_shouldMarkSuspiciousRentAsPendingConfirmation() {
        LoginUserHolder.set(new LoginUser(1L, "13800000000"));

        AgreementItemVo agreementItemVo = new AgreementItemVo();
        agreementItemVo.setId(1L);
        agreementItemVo.setApartmentName("温都水城社区");
        agreementItemVo.setRoomNumber("102");
        agreementItemVo.setRent(new BigDecimal("1"));
        when(leaseAgreementService.listItemByPhone("13800000000")).thenReturn(List.of(agreementItemVo));

        String json = rentalAssistantTools.getMyLeaseAgreements();

        assertTrue(json.contains("\"tool\":\"getMyLeaseAgreements\""));
        assertTrue(json.contains("当前共有 1 条租约记录。"));
        assertTrue(json.contains("价格待确认"));
    }
    @Test
    void cancelAppointment_shouldCancelWaitingAppointment() {
        LoginUserHolder.set(new LoginUser(8L, "17503976585"));

        ViewAppointment appointment = new ViewAppointment();
        appointment.setId(13L);
        appointment.setUserId(8L);
        appointment.setApartmentId(12L);
        appointment.setAppointmentStatus(AppointmentStatus.WAITING);
        when(viewAppointmentService.getById(13L)).thenReturn(appointment);

        ApartmentInfo apartmentInfo = new ApartmentInfo();
        apartmentInfo.setId(12L);
        apartmentInfo.setName("Wendu Apartment");
        when(apartmentInfoService.getById(12L)).thenReturn(apartmentInfo);
        when(viewAppointmentService.cancelForCurrentUser(13L, 8L)).thenReturn(appointment);

        String json = rentalAssistantTools.cancelAppointment(13L);

        verify(viewAppointmentService).cancelForCurrentUser(13L, 8L);
        assertTrue(json.contains("\"tool\":\"cancelAppointment\""));
        assertTrue(json.contains("\"appointmentId\":13"));
        assertTrue(json.contains("已为你取消这条看房预约"));
    }
}
