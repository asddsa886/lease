package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.model.entity.PaymentType;
import com.atguigu.lease.model.entity.ProvinceInfo;
import com.atguigu.lease.web.app.service.ApartmentInfoService;
import com.atguigu.lease.web.app.service.CityInfoService;
import com.atguigu.lease.web.app.service.DistrictInfoService;
import com.atguigu.lease.web.app.service.PaymentTypeService;
import com.atguigu.lease.web.app.service.ProvinceInfoService;
import com.atguigu.lease.web.app.service.RoomCompareService;
import com.atguigu.lease.web.app.service.RoomFavoriteService;
import com.atguigu.lease.web.app.service.RoomInfoService;
import com.atguigu.lease.web.app.vo.apartment.ApartmentDetailVo;
import com.atguigu.lease.web.app.vo.room.RoomItemVo;
import com.atguigu.lease.web.app.vo.room.RoomQueryVo;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantRoomToolsTest {

    private final ApartmentInfoService apartmentInfoService = mock(ApartmentInfoService.class);
    private final RoomInfoService roomInfoService = mock(RoomInfoService.class);
    private final ProvinceInfoService provinceInfoService = mock(ProvinceInfoService.class);
    private final CityInfoService cityInfoService = mock(CityInfoService.class);
    private final DistrictInfoService districtInfoService = mock(DistrictInfoService.class);
    private final PaymentTypeService paymentTypeService = mock(PaymentTypeService.class);
    private final RoomCompareService roomCompareService = mock(RoomCompareService.class);
    private final RoomFavoriteService roomFavoriteService = mock(RoomFavoriteService.class);

    private final AssistantRoomTools assistantRoomTools = new AssistantRoomTools(
            apartmentInfoService,
            roomInfoService,
            provinceInfoService,
            cityInfoService,
            districtInfoService,
            paymentTypeService,
            roomCompareService,
            roomFavoriteService
    );

    @Test
    void shouldGetApartmentDetailThroughRoomTools() {
        ApartmentDetailVo detailVo = new ApartmentDetailVo();
        detailVo.setId(9L);
        detailVo.setName("测试公寓");
        when(apartmentInfoService.getDetailById(9L)).thenReturn(detailVo);

        AssistantToolResult result = assistantRoomTools.getApartmentDetail(9L, new ToolContext(Map.of()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isSameAs(detailVo);
        verify(apartmentInfoService).getDetailById(9L);
    }

    @Test
    void shouldSearchRoomsWithOptionalPaymentTypeAndDefaultAscendingOrder() {
        ProvinceInfo provinceInfo = new ProvinceInfo();
        provinceInfo.setId(11L);
        provinceInfo.setName("\u5317\u4eac\u5e02");

        when(provinceInfoService.list(org.mockito.ArgumentMatchers.<Wrapper<ProvinceInfo>>any())).thenReturn(List.of(provinceInfo));
        when(cityInfoService.list(any(Wrapper.class))).thenReturn(List.of());
        when(districtInfoService.list(any(Wrapper.class))).thenReturn(List.of());
        when(roomInfoService.pageItem(any(Page.class), any(RoomQueryVo.class))).thenReturn(new Page<>(1, 10));

        AssistantToolResult result = assistantRoomTools.searchRooms(
                1,
                10,
                null,
                "\u5317\u4eac\u5e02",
                null,
                null,
                new BigDecimal("3000"),
                null,
                null,
                new ToolContext(Map.of())
        );

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<RoomQueryVo> queryCaptor = ArgumentCaptor.forClass(RoomQueryVo.class);
        verify(roomInfoService).pageItem(any(Page.class), queryCaptor.capture());

        RoomQueryVo queryVo = queryCaptor.getValue();
        assertThat(queryVo.getProvinceId()).isEqualTo(11L);
        assertThat(queryVo.getCityId()).isNull();
        assertThat(queryVo.getDistrictId()).isNull();
        assertThat(queryVo.getPaymentTypeId()).isNull();
        assertThat(queryVo.getOrderType()).isEqualTo("asc");
        assertThat(queryVo.getMaxRent()).isEqualByComparingTo("3000");
    }

    @Test
    void shouldResolvePaymentTypeNameWhenUserProvidesNaturalLanguage() {
        PaymentType paymentType = new PaymentType();
        paymentType.setId(6L);
        paymentType.setName("\u6708\u4ed8");
        paymentType.setAdditionalInfo("\u62bc\u4e00\u4ed8\u4e00");

        when(provinceInfoService.list(any(Wrapper.class))).thenReturn(List.of());
        when(cityInfoService.list(any(Wrapper.class))).thenReturn(List.of());
        when(districtInfoService.list(any(Wrapper.class))).thenReturn(List.of());
        when(paymentTypeService.list()).thenReturn(List.of(paymentType));
        when(roomInfoService.pageItem(any(Page.class), any(RoomQueryVo.class))).thenReturn(new Page<>(1, 10));

        AssistantToolResult result = assistantRoomTools.searchRooms(
                1,
                10,
                null,
                null,
                null,
                null,
                new BigDecimal("3000"),
                "\u62bc\u4e00\u4ed8\u4e00",
                "desc",
                new ToolContext(Map.of())
        );

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<RoomQueryVo> queryCaptor = ArgumentCaptor.forClass(RoomQueryVo.class);
        verify(roomInfoService).pageItem(any(Page.class), queryCaptor.capture());

        RoomQueryVo queryVo = queryCaptor.getValue();
        assertThat(queryVo.getPaymentTypeId()).isEqualTo(6L);
        assertThat(queryVo.getOrderType()).isEqualTo("desc");
    }
}
