package io.eventuate.examples.tram.ordersandcustomers.customers.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Logs the steps of the Create Order saga to the "saga" logger so that they can be
 * routed to a dedicated, human-readable timeline (see docker-local/logback.xml).
 */
public final class SagaLog {

  private static final Logger logger = LoggerFactory.getLogger("saga");

  private SagaLog() {
  }

  public static void info(String format, Object... args) {
    logger.info(format, args);
  }

  public static void warn(String format, Object... args) {
    logger.warn(format, args);
  }

  /**
   * Logs once the current local transaction has committed, which is the moment the
   * business change and the outbox MESSAGE row become visible together.
   */
  public static void afterCommit(String format, Object... args) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      logger.info(format, args);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCompletion(int status) {
        if (status == STATUS_COMMITTED)
          logger.info(format, args);
        else
          logger.warn("ROLLBACK, không có thay đổi nào được ghi (kể cả MESSAGE) -> " + format, args);
      }
    });
  }
}
