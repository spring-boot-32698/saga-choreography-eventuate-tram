package io.eventuate.examples.tram.ordersandcustomers.orders.domain;

import io.eventuate.tram.events.publisher.ResultWithTypedEvents;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public class OrderService {

  private final OrderRepository orderRepository;
  private final OrderEventPublisher orderEventPublisher;


  public OrderService(OrderRepository orderRepository, OrderEventPublisher orderEventPublisher) {
    this.orderRepository = orderRepository;
    this.orderEventPublisher = orderEventPublisher;
  }

  @Transactional
  public Order createOrder(OrderDetails orderDetails) {
    ResultWithTypedEvents<Order, OrderEvent> orderWithEvents = Order.createOrder(orderDetails);
    Order order = orderWithEvents.getResult();
    orderRepository.save(order);
    SagaLog.info("[BƯỚC 1/3][Tx1 BEGIN] order={} customer={} total={}: INSERT orders(state=PENDING)",
            order.getId(), orderDetails.customerId(), orderDetails.orderTotal().getAmount());
    orderEventPublisher.publish(order, orderWithEvents.getEvents());
    SagaLog.info("[BƯỚC 1/3][OUTBOX] order={}: INSERT message(OrderCreatedEvent) trong CÙNG transaction với orders",
            order.getId());
    SagaLog.afterCommit("[BƯỚC 1/3][Tx1 COMMIT] order={} state=PENDING (semantic lock). CDC sẽ đọc binlog và đẩy OrderCreatedEvent lên Kafka",
            order.getId());
    return order;
  }

  public void approveOrder(Long orderId) {
    Order order = orderRepository
            .findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("order with id %s not found".formatted(orderId)));
    OrderState before = order.getState();
    order.noteCreditReserved();
    SagaLog.info("[BƯỚC 3/3][Tx3 BEGIN] order={}: state {} -> {} (retriable transaction sau pivot)",
            orderId, before, order.getState());
    orderEventPublisher.publish(order, new OrderApprovedEvent(order.getOrderDetails()));
    SagaLog.afterCommit("[BƯỚC 3/3][Tx3 COMMIT] order={} APPROVED + OrderApprovedEvent vào outbox. SAGA KẾT THÚC: THÀNH CÔNG",
            orderId);
  }

  public void rejectOrder(Long orderId, RejectionReason rejectionReason) {
    Order order = orderRepository
            .findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("order with id %s not found".formatted(orderId)));
    OrderState before = order.getState();
    order.noteCreditReservationFailed(rejectionReason);
    SagaLog.warn("[BÙ][Tx3 BEGIN] order={}: compensating transaction cho Tx1, state {} -> {} reason={}",
            orderId, before, order.getState(), rejectionReason);
    orderEventPublisher.publish(order, new OrderRejectedEvent(order.getOrderDetails()));
    SagaLog.afterCommit("[BÙ][Tx3 COMMIT] order={} REJECTED ({}) + OrderRejectedEvent vào outbox. SAGA KẾT THÚC: ĐÃ BÙ",
            orderId, rejectionReason);
  }

  @Transactional
  public Order cancelOrder(Long orderId) {
    Order order = orderRepository
            .findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("order with id %s not found".formatted(orderId)));
    SagaLog.info("[HỦY ĐƠN][Tx BEGIN] order={} state={}: yêu cầu cancel", orderId, order.getState());
    try {
      order.cancel();
    } catch (PendingOrderCantBeCancelledException e) {
      SagaLog.warn("[HỦY ĐƠN][SEMANTIC LOCK] order={} đang PENDING, saga Create Order chưa xong nên không được hủy", orderId);
      throw e;
    }
    orderEventPublisher.publish(order, new OrderCancelledEvent(order.getOrderDetails()));
    SagaLog.afterCommit("[HỦY ĐƠN][Tx COMMIT] order={} CANCELLED + OrderCancelledEvent vào outbox. Customer Service sẽ trả lại tín dụng",
            orderId);
    return order;
  }

  public Optional<Order> findById(long orderId) {
    return orderRepository.findById(orderId);
  }

  public Iterable<Order> findAll() {
    return orderRepository.findAll();
  }
}
