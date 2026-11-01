package io.eventuate.examples.tram.ordersandcustomers.customers.domain;

import io.eventuate.examples.common.money.Money;
import io.eventuate.tram.events.publisher.ResultWithTypedEvents;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class CustomerService {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final CustomerRepository customerRepository;

  private final CustomerEventPublisher customerEventPublisher;

  public CustomerService(CustomerRepository customerRepository, CustomerEventPublisher customerEventPublisher) {
    this.customerRepository = customerRepository;
    this.customerEventPublisher = customerEventPublisher;
  }

  @Transactional
  public Customer createCustomer(String name, Money creditLimit) {
    ResultWithTypedEvents<Customer, CustomerEvent> customerWithEvents = Customer.create(name, creditLimit);
    Customer customer = customerRepository.save(customerWithEvents.getResult());
    customerEventPublisher.publish(customer, customerWithEvents.getEvents());
    SagaLog.afterCommit("[CUSTOMER] customer={} name={} creditLimit={}: đã tạo + CustomerCreatedEvent vào outbox",
            customer.getId(), name, creditLimit.getAmount());
    return customer;
  }

  public void reserveCredit(long orderId, long customerId, Money orderTotal) {

    SagaLog.info("[BƯỚC 2/3][Tx2 BEGIN] order={} customer={} total={}: thử giữ tín dụng (pivot transaction)",
            orderId, customerId, orderTotal.getAmount());

    Optional<Customer> possibleCustomer = customerRepository.findById(customerId);

    if (possibleCustomer.isEmpty()) {
      logger.info("Non-existent customer: {}", customerId);
      SagaLog.warn("[BƯỚC 2/3][THẤT BẠI] order={}: customer={} không tồn tại -> phát CustomerValidationFailedEvent",
              orderId, customerId);
      customerEventPublisher.publishById(
              customerId,
              new CustomerValidationFailedEvent(customerId, orderId));
      SagaLog.afterCommit("[BƯỚC 2/3][Tx2 COMMIT] order={}: CustomerValidationFailedEvent đã commit. Order Service sẽ chạy giao dịch bù",
              orderId);
      return;
    }

    Customer customer = possibleCustomer.get();
    Money availableBefore = customer.availableCredit();

    try {
      customer.reserveCredit(orderId, orderTotal);
      SagaLog.info("[BƯỚC 2/3][THÀNH CÔNG] order={} customer={}: available {} >= total {} -> creditReservations.put(order); còn lại {}",
              orderId, customerId, availableBefore.getAmount(), orderTotal.getAmount(),
              customer.availableCredit().getAmount());

      CustomerCreditReservedEvent customerCreditReservedEvent =
              new CustomerCreditReservedEvent(customerId, orderId);

      customerEventPublisher.publish(customer, customerCreditReservedEvent);
      SagaLog.afterCommit("[BƯỚC 2/3][Tx2 COMMIT] order={}: reservation + CustomerCreditReservedEvent đã commit cùng nhau",
              orderId);

    } catch (CustomerCreditLimitExceededException e) {
      SagaLog.warn("[BƯỚC 2/3][THẤT BẠI] order={} customer={}: available {} < total {} -> KHÔNG giữ chỗ, phát CustomerCreditReservationFailedEvent",
              orderId, customerId, availableBefore.getAmount(), orderTotal.getAmount());

      CustomerCreditReservationFailedEvent customerCreditReservationFailedEvent =
              new CustomerCreditReservationFailedEvent(customerId, orderId);

      customerEventPublisher.publish(customer, customerCreditReservationFailedEvent);
      SagaLog.afterCommit("[BƯỚC 2/3][Tx2 COMMIT] order={}: CustomerCreditReservationFailedEvent đã commit. Order Service sẽ chạy giao dịch bù",
              orderId);
    }
  }

  public void releaseCredit(long orderId, long customerId) {
    Customer customer = customerRepository.findById(customerId).get();
    Money availableBefore = customer.availableCredit();
    customer.unreserveCredit(orderId);
    SagaLog.info("[HỦY ĐƠN][TRẢ TÍN DỤNG] order={} customer={}: creditReservations.remove(order); available {} -> {}",
            orderId, customerId, availableBefore.getAmount(), customer.availableCredit().getAmount());
  }

  public Iterable<Customer> findAll() {
    return customerRepository.findAll();
  }

  public Optional<Customer> findById(long customerId) {
    return customerRepository.findById(customerId);
  }
}
