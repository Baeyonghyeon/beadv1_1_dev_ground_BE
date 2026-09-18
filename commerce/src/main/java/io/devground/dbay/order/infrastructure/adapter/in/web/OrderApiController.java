package io.devground.dbay.order.infrastructure.adapter.in.web;

import io.devground.core.model.web.BaseResponse;
import io.devground.dbay.order.application.service.OrderApplication;
import io.devground.dbay.order.application.vo.OrderAcceptance;
import io.devground.dbay.order.domain.vo.*;
import io.devground.dbay.order.domain.vo.pagination.PageDto;
import io.devground.dbay.order.domain.vo.pagination.PageQuery;
import io.devground.dbay.order.domain.vo.pagination.SortSpec;
import io.devground.dbay.order.infrastructure.vo.CartProductsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/commerce/order")
@Tag(name = "OrderController")
public class OrderApiController {

    private final OrderApplication orderApplication;

    @PostMapping("/{productCode}")
    @Operation(summary = "주문 단건 생성", description = "단건 주문을 생성합니다.")
    public BaseResponse<OrderAcceptedResponse> createSingleOrder(
            @RequestHeader("X-CODE") String userCode,
            @PathVariable String productCode
    ) {
        return toResponse(orderApplication.createOrderByOne(new UserCode(userCode), new ProductCode(productCode)));
    }

    @PostMapping
    @Operation(summary = "주문 다건 생성", description = "다건의 주문을 생성합니다.")
    public BaseResponse<OrderAcceptedResponse> createSelectedOrder(
            @RequestHeader("X-CODE") String userCode,
            @RequestBody CartProductsRequest request
    ) {
        List<ProductCode> productCodes = request.productCodes().stream()
                        .map(ProductCode::new)
                        .toList();

        return toResponse(orderApplication.createOrderBySelected(new UserCode(userCode), productCodes));
    }

    /**
     * 접수 결과를 HTTP 응답으로 옮긴다.
     *
     * <p><b>동기 경로의 응답은 한 바이트도 바꾸지 않는다</b>(204, 본문 없음).
     * 기존 부하 측정과의 비교 가능성이 거기 걸려 있다.
     *
     * <p>비동기 경로만 <b>202 Accepted + 주문 코드</b>로 응답한다.
     * 204 는 정의상 본문을 실을 수 없어 주문 코드를 돌려줄 방법이 없고,
     * 주문 코드가 없으면 클라이언트가 결제 상태를 폴링할 수 없다.
     */
    private BaseResponse<OrderAcceptedResponse> toResponse(OrderAcceptance acceptance) {
        return switch (acceptance.state()) {
            // 동기 경로 성공 — 기존 응답을 한 바이트도 바꾸지 않는다 (부하 측정 비교 가능성)
            case SETTLED -> BaseResponse.success(204, "주문 생성 완료");

            case AWAITING_PAYMENT -> BaseResponse.success(
                    202,
                    pollable(acceptance),
                    acceptance.message()
            );

            // 잔액 부족 등 — 사용자 사정이지 서버 오류가 아니다
            case REJECTED -> BaseResponse.fail(400, acceptance.message());

            // 결제 서비스에 닿지 못했다 — 재시도하면 되는 상황이므로 503
            case PAYMENT_UNAVAILABLE -> BaseResponse.fail(503, acceptance.message());

            // 결제 여부 불명 — 주문은 회수 대상으로 남아 있다.
            // 주문 코드를 함께 돌려줘 클라이언트가 나중에 상태를 조회할 수 있게 한다.
            case PAYMENT_UNCERTAIN -> new BaseResponse<>(
                    503,
                    acceptance.message() + " 주문 상태를 확인해 주세요.",
                    pollable(acceptance)
            );
        };
    }

    private OrderAcceptedResponse pollable(OrderAcceptance acceptance) {
        return new OrderAcceptedResponse(
                acceptance.orderCode(),
                OrderStatus.PAYMENT_PENDING,
                "/api/commerce/order/" + acceptance.orderCode() + "/payment-status"
        );
    }

    /**
     * 결제 상태 폴링 — 클라이언트는 {@code awaitingPayment} 가 false 가 될 때까지 이 엔드포인트를 호출한다.
     *
     * <p>무한 폴링을 막는 책임은 클라이언트에 있다. 최대 시도 횟수를 넘기면
     * "처리가 지연되고 있습니다. 주문 내역에서 확인해 주세요" 로 안내하고 멈춰야 한다 —
     * 서버 쪽 회수 스케줄러가 5분 안에 결론을 내므로 주문이 영원히 대기 상태로 남지는 않는다.
     */
    @GetMapping("/{orderCode}/payment-status")
    @Operation(summary = "결제 상태 조회", description = "비동기 결제의 진행 상태를 폴링합니다.")
    public BaseResponse<OrderPaymentStatusResponse> getPaymentStatus(
            @RequestHeader("X-CODE") String userCode,
            @PathVariable String orderCode
    ) {
        OrderPaymentStatus status = orderApplication.getPaymentStatus(
                new UserCode(userCode), new OrderCode(orderCode));

        return BaseResponse.success(
                200,
                new OrderPaymentStatusResponse(
                        status.orderCode(),
                        status.orderStatus(),
                        status.isAwaitingPayment(),
                        describe(status.orderStatus())
                ),
                "결제 상태 조회 성공"
        );
    }

    private static String describe(OrderStatus status) {
        return switch (status) {
            case PAYMENT_PENDING -> "결제를 처리하고 있습니다.";
            case PAYMENT_FAILED -> "결제에 실패했습니다.";
            case CANCELLED -> "주문이 취소되었습니다.";
            default -> "결제가 완료되었습니다.";
        };
    }

    public record OrderAcceptedResponse(
            String orderCode,
            OrderStatus orderStatus,
            String pollUrl
    ) {
    }

    public record OrderPaymentStatusResponse(
            String orderCode,
            OrderStatus orderStatus,
            boolean awaitingPayment,
            String message
    ) {
    }

    @GetMapping
    @Operation(summary = "주문 조회", description = "주문 목록 조회")
    public BaseResponse<PageDto<OrderDescription>> getOrders(
            @RequestHeader("X-CODE") String userCode,
            @RequestHeader("ROLE") RoleType roleType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "DESC") SortSpec.Direction dir,
            @RequestParam(defaultValue = "ALL") OrderStatus orderStatus
            ) {
        PageQuery pageQuery = new PageQuery(page, size, new SortSpec(sort, dir));
        return BaseResponse.success(
                200,
                orderApplication.getOrderLists(new UserCode(userCode), roleType, pageQuery, orderStatus),
                "주문 목록 조회 성공"
        );
    }

    @GetMapping("/{orderCode}")
    @Operation(summary = "주문 상세 조회", description = "주문 상세 조회")
    public BaseResponse<OrderDetailDescription> getOrderDetail(
            @RequestHeader("X-CODE") String userCode,
            @PathVariable String orderCode
    ) {
        return BaseResponse.success(
                200,
                orderApplication.getOrderDetail(new UserCode(userCode), new OrderCode(orderCode)),
                "주문 상세 조회 성공"
        );
    }

    @PatchMapping("/{orderCode}")
    @Operation(summary = "주문 취소", description = "주문 취소")
    public BaseResponse<Void> cancelOrder(
            @RequestHeader("X-CODE") String userCode,
            @PathVariable String orderCode
    ) {
        orderApplication.cancelOrder(new UserCode(userCode), new OrderCode(orderCode));
        return BaseResponse.success(
                204,
                "주문 취소 완료"
        );
    }

    @PatchMapping("/confirm/{orderCode}")
    @Operation(summary = "주문 구매 확정", description = "주문 구매 확정")
    public BaseResponse<Void> confirmOrder(
            @RequestHeader("X-CODE") String userCode,
            @PathVariable String orderCode
    ) {
        orderApplication.confirmOrder(new UserCode(userCode), new OrderCode(orderCode));

        return BaseResponse.success(
                204,
                "구매 확정 완료"
        );
    }

    @GetMapping("/unsettled-items")
    @Operation(summary = "정산 위한 주문 정보 조회", description = "주문 완료된 주문들에 대해 정산처리하기 위함")
    public BaseResponse<PageDto<UnsettledOrderItemResponse>> getUnsettledOrderItems(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "1000") int size
    ) {
        PageQuery pageQuery = new PageQuery(page, size, new SortSpec("id", SortSpec.Direction.ASC));
        return BaseResponse.success(
                200,
                orderApplication.getUnsettledOrderItems(pageQuery),
                "정산 처리를 위한 주문 정보 조회 완료"
        );
    }
}
