package io.eventuate.examples.tram.ordersandcustomers.orderhistory.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.eventuate.examples.tram.ordersandcustomers.customers.domain.CustomerCreatedEvent;
import io.eventuate.examples.tram.ordersandcustomers.orders.domain.OrderApprovedEvent;
import io.eventuate.examples.tram.ordersandcustomers.orders.domain.OrderCancelledEvent;
import io.eventuate.examples.tram.ordersandcustomers.orders.domain.OrderCreatedEvent;
import io.eventuate.examples.tram.ordersandcustomers.orders.domain.OrderRejectedEvent;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import io.eventuate.tram.events.subscriber.annotations.EventuateDomainEventHandler;


public class OrderHistoryEventConsumer {

  private static final Logger sagaLogger = LoggerFactory.getLogger("saga");

  public final OrderHistoryViewService orderHistoryViewService;

  public OrderHistoryEventConsumer(OrderHistoryViewService orderHistoryViewService) {
    this.orderHistoryViewService = orderHistoryViewService;
  }

  @EventuateDomainEventHandler(subscriberId = "customerHistoryServiceEvents", channel = "io.eventuate.examples.tram.ordersandcustomers.customers.domain.Customer")
  public void customerCreatedEventHandler(DomainEventEnvelope<CustomerCreatedEvent> domainEventEnvelope) {
    sagaLogger.info("[CQRS VIEW] CustomerCreatedEvent customer={} -> cập nhật MongoDB (không tham gia saga, chỉ đọc event)", domainEventEnvelope.getAggregateId());
    CustomerCreatedEvent customerCreatedEvent = domainEventEnvelope.getEvent();
    orderHistoryViewService.createCustomer(Long.parseLong(domainEventEnvelope.getAggregateId()),
        customerCreatedEvent.name(), customerCreatedEvent.creditLimit());
  }

  @EventuateDomainEventHandler(subscriberId = "customerHistoryServiceEvents", channel = "io.eventuate.examples.tram.ordersandcustomers.orders.domain.Order")
  public void orderCreatedEventHandler(DomainEventEnvelope<OrderCreatedEvent> domainEventEnvelope) {
    sagaLogger.info("[CQRS VIEW] OrderCreatedEvent order={} -> cập nhật MongoDB (không tham gia saga, chỉ đọc event)", domainEventEnvelope.getAggregateId());
    OrderCreatedEvent orderCreatedEvent = domainEventEnvelope.getEvent();
    orderHistoryViewService.addOrder(orderCreatedEvent.orderDetails().customerId(),
            Long.parseLong(domainEventEnvelope.getAggregateId()), orderCreatedEvent.orderDetails().orderTotal());
  }

  @EventuateDomainEventHandler(subscriberId = "customerHistoryServiceEvents", channel = "io.eventuate.examples.tram.ordersandcustomers.orders.domain.Order")
  public void orderApprovedEventHandler(DomainEventEnvelope<OrderApprovedEvent> domainEventEnvelope) {
    sagaLogger.info("[CQRS VIEW] OrderApprovedEvent order={} -> cập nhật MongoDB (không tham gia saga, chỉ đọc event)", domainEventEnvelope.getAggregateId());
    OrderApprovedEvent orderApprovedEvent = domainEventEnvelope.getEvent();
    orderHistoryViewService.approveOrder(orderApprovedEvent.orderDetails().customerId(),
            Long.parseLong(domainEventEnvelope.getAggregateId()));
  }

  @EventuateDomainEventHandler(subscriberId = "customerHistoryServiceEvents", channel = "io.eventuate.examples.tram.ordersandcustomers.orders.domain.Order")
  public void orderRejectedEventHandler(DomainEventEnvelope<OrderRejectedEvent> domainEventEnvelope) {
    sagaLogger.info("[CQRS VIEW] OrderRejectedEvent order={} -> cập nhật MongoDB (không tham gia saga, chỉ đọc event)", domainEventEnvelope.getAggregateId());
    OrderRejectedEvent orderRejectedEvent = domainEventEnvelope.getEvent();
    orderHistoryViewService.rejectOrder(orderRejectedEvent.orderDetails().customerId(),
            Long.parseLong(domainEventEnvelope.getAggregateId()));
  }

  @EventuateDomainEventHandler(subscriberId = "customerHistoryServiceEvents", channel = "io.eventuate.examples.tram.ordersandcustomers.orders.domain.Order")
  public void handleOrderCancelledEvent(DomainEventEnvelope<OrderCancelledEvent> domainEventEnvelope) {
    sagaLogger.info("[CQRS VIEW] OrderCancelledEvent order={} -> cập nhật MongoDB (không tham gia saga, chỉ đọc event)", domainEventEnvelope.getAggregateId());
    OrderCancelledEvent orderRejectedEvent = domainEventEnvelope.getEvent();
    orderHistoryViewService.cancelOrder(orderRejectedEvent.orderDetails().customerId(),
            Long.parseLong(domainEventEnvelope.getAggregateId()));
  }
}
