package io.eventuate.examples.tram.ordersandcustomers.orders.restapi;

import io.eventuate.examples.tram.ordersandcustomers.orders.domain.Order;
import io.eventuate.examples.tram.ordersandcustomers.orders.domain.OrderDetails;
import io.eventuate.examples.tram.ordersandcustomers.orders.domain.OrderService;
import io.eventuate.examples.tram.ordersandcustomers.orders.domain.SagaLog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@RestController
public class OrderController {

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  @PostMapping("/orders")
  public CreateOrderResponse createOrder(@RequestBody CreateOrderRequest createOrderRequest) {
    SagaLog.info("════════ [SAGA START] POST /orders customer={} total={} ════════",
            createOrderRequest.customerId(), createOrderRequest.orderTotal().getAmount());
    Order order = orderService.createOrder(new OrderDetails(createOrderRequest.customerId(), createOrderRequest.orderTotal()));
    SagaLog.info("[HTTP 200] order={} state={}: trả về NGAY cho client, các bước còn lại chạy bất đồng bộ qua Kafka",
            order.getId(), order.getState());
    return new CreateOrderResponse(order.getId());
  }

  @GetMapping("/orders/{orderId}")
  public ResponseEntity<GetOrderResponse> getOrder(@PathVariable Long orderId) {
     return orderService.findById(orderId)
            .map(this::makeSuccessfulResponse)
            .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/orders/{orderId}/cancel")
  public ResponseEntity<GetOrderResponse> cancelOrder(@PathVariable Long orderId) {
     Order order = orderService.cancelOrder(orderId);
     return makeSuccessfulResponse(order);
  }

  private ResponseEntity<GetOrderResponse> makeSuccessfulResponse(Order order) {
    return ResponseEntity.ok(new GetOrderResponse(order.getId(), order.getState(), order.getRejectionReason()));
  }

  @GetMapping("/orders")
  public ResponseEntity<GetOrdersResponse> getAll() {
    return ResponseEntity.ok(new GetOrdersResponse(StreamSupport.stream(orderService.findAll().spliterator(), false)
        .map(o -> new GetOrderResponse(o.getId(), o.getState(), o.getRejectionReason())).collect(Collectors.toList())));
  }

}
