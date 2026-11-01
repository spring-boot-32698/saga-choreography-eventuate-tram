# Chạy local bằng Docker Compose để học Saga

Không cần cài JDK: jar được build bên trong container `eclipse-temurin:17-jdk`.
Chỉ cần Docker (có Compose v2), `curl` và `jq`.

```bash
docker compose up -d --build      # lần đầu ~3-5 phút (tải dependency Gradle)
docker compose ps                 # chờ tất cả "healthy"
./docker-local/saga-demo.sh       # chạy 5 kịch bản saga
tail -f logs/saga.log             # xem timeline saga trực tiếp
docker compose down -v            # tắt và xóa dữ liệu
```

## Các container

| Service | Cổng host | Vai trò |
|---|---|---|
| `api-gateway` | 8080 | Điểm vào cho client (Spring Cloud Gateway) |
| `order-service` | 8081 | Khởi đầu saga, giữ `orders` |
| `customer-service` | 8082 | Bước pivot: giữ tín dụng |
| `order-history-service` | 8083 | CQRS view trong MongoDB |
| `order-db` / `customer-db` | 3307 / 3308 | MySQL riêng cho mỗi service (database per service), mỗi DB có bảng `message` (outbox) |
| `cdc-service` | 8099 | Đọc binlog của **cả hai** MySQL, đẩy dòng `message` lên Kafka |
| `kafka` | 9092 | Message broker (KRaft, không cần ZooKeeper) |
| `mongodb` | 27017 | Lưu view của Order History |

Swagger UI: http://localhost:8081/swagger-ui/index.html (tương tự với 8082, 8083).

## Các file log (thư mục `logs/`)

| File | Nội dung | Dùng khi |
|---|---|---|
| `saga.log` | **Chỉ** các bước saga, từ cả 3 service, gộp vào một dòng thời gian | Đọc đầu tiên |
| `order-service.log` | Toàn bộ log Order Service, có ranh giới transaction (`JpaTransactionManager`) và SQL Hibernate | Muốn xem transaction mở/commit ở đâu |
| `customer-service.log` | Như trên cho Customer Service | |
| `order-history-service.log`, `api-gateway.log` | Log của 2 service còn lại | |

Định dạng `saga.log`:

```
giờ          | service               | thread                 | level | nội dung
14:37:32.351 | customer-service      | pool-5-thread-1        | WARN  | [BƯỚC 2/3][THẤT BẠI] order=3 ...
```

Cách đọc các nhãn:

| Nhãn | Ý nghĩa |
|---|---|
| `[SAGA START]` | Client gọi `POST /orders` |
| `[BƯỚC n/3][TxN BEGIN]` | Bắt đầu transaction cục bộ thứ n, trong **một** service |
| `[OUTBOX]` | Event được ghi vào bảng `message` trong cùng transaction |
| `[TxN COMMIT]` | Được log **sau khi** commit thật sự (qua `TransactionSynchronization`), lúc này thay đổi và event cùng xuất hiện |
| `[HTTP 200]` | Order Service trả lời client khi đơn còn `PENDING`: saga bất đồng bộ |
| `[NHẬN EVENT]` | Consumer nhận event từ Kafka, kèm `messageId` dùng để chống trùng (`received_messages`) |
| `[BÙ]` | Compensating transaction: `PENDING -> REJECTED` |
| `[SEMANTIC LOCK]` | Từ chối hủy đơn đang `PENDING` |
| `[CQRS VIEW]` | Order History cập nhật view; không tham gia saga |

Để ý cột **thread**: `http-nio-*` là thread xử lý HTTP, `pool-*-thread-*` là thread consumer Kafka.
Bước 1 chạy trên thread HTTP, bước 2 và 3 chạy trên thread consumer, ở service khác, sau đó.

Lọc theo một đơn: `grep -E 'order=3( |:|$)' logs/saga.log`

Đổi mức log khi cần (trong `environment` của service ở `docker-compose.yml`):
`TX_LOG_LEVEL=INFO` tắt log transaction, `SQL_LOG_LEVEL=INFO` tắt SQL, `EVENTUATE_LOG_LEVEL=DEBUG` xem chi tiết Eventuate.

## Các kịch bản của `saga-demo.sh`

```bash
./docker-local/saga-demo.sh approve         # đủ tín dụng -> APPROVED
./docker-local/saga-demo.sh insufficient    # vượt hạn mức -> REJECTED (giao dịch bù)
./docker-local/saga-demo.sh unknown         # customer không tồn tại -> REJECTED
./docker-local/saga-demo.sh cancel          # hủy đơn APPROVED -> trả lại tín dụng
./docker-local/saga-demo.sh pending-cancel  # hủy khi PENDING -> bị từ chối (semantic lock)
```

API Gateway không có route cho `POST /orders/{id}/cancel`, nên script gọi thẳng Order Service ở cổng 8081.

## Thí nghiệm nên tự làm

**1. Saga sống sót khi một service chết** (thấy được tính bất đồng bộ và độ bền của Kafka)

```bash
docker compose stop customer-service
curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' \
     -d '{"customerId":1,"orderTotal":{"amount":1.00}}'          # -> {"orderId":N}
curl -s localhost:8080/orders/N                                  # PENDING, đứng yên
docker compose start customer-service                            # ~15s sau: APPROVED
```

Trong `saga.log`, khoảng cách thời gian giữa `[Tx1 COMMIT]` và `[NHẬN EVENT] OrderCreatedEvent` chính là
thời gian service bị tắt. Event không mất vì nó đã nằm trong bảng `message` và Kafka.

**2. Xem bảng outbox và bảng chống trùng**

```bash
docker compose exec order-db mysql -umysqluser -pmysqlpw eventuate -e \
  "select id, json_unquote(json_extract(headers,'$.\"event-type\"')) event_type from message order by creation_time desc limit 5"
docker compose exec order-db mysql -umysqluser -pmysqlpw eventuate -e \
  "select consumer_id, message_id from received_messages limit 5"
```

Cột `published` luôn là `0` với CDC đọc binlog: CDC theo dõi vị trí binlog (lưu ở bảng `offset_store`)
chứ không đánh dấu từng dòng. Chỉ chế độ polling mới cập nhật cột này.

**3. Xem CQRS view**

```bash
docker compose exec mongodb mongosh --quiet customers_and_orders --eval 'db.customerView.find().pretty()'
# hoặc qua API: curl -s localhost:8080/customers/2/orderhistory | jq
```

**4. Xem event trong Kafka** (lần đầu sẽ tải image `apache/kafka`, mất khoảng một phút)

```bash
docker run --rm --network saga-choreography_default apache/kafka:3.8.0 \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 --from-beginning \
  --topic io.eventuate.examples.tram.ordersandcustomers.orders.domain.Order
```

## Gỡ lỗi

- Đơn kẹt ở `PENDING`: `docker compose logs cdc-service` (CDC có đọc được binlog không) và `docker compose ps`.
- Build lại sau khi sửa code: `docker compose up -d --build`.
- Muốn reset dữ liệu và log: `docker compose down -v && rm -f logs/*.log`.
- Nếu UID của bạn không phải 1000: `HOST_UID=$(id -u) HOST_GID=$(id -g) docker compose up -d`.
