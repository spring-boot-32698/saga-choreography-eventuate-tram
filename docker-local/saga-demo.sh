#!/usr/bin/env bash
#
# Runs the Create Order saga scenarios against the API Gateway started by docker-compose.yml
# and prints, for each one, the final order state plus the matching lines of logs/saga.log.
#
#   ./docker-local/saga-demo.sh            # all scenarios
#   ./docker-local/saga-demo.sh approve    # one of: approve | insufficient | unknown | cancel | pending-cancel
#
set -euo pipefail

GATEWAY=${GATEWAY:-http://localhost:8080}
# The API Gateway has no route for POST /orders/{id}/cancel, so cancel goes straight to Order Service
ORDER_SERVICE=${ORDER_SERVICE:-http://localhost:8081}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SAGA_LOG="$ROOT/logs/saga.log"

command -v jq >/dev/null || { echo "Cần cài jq (sudo apt install jq)"; exit 1; }

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }

post() { curl -fsS -X POST -H 'Content-Type: application/json' -d "$2" "$GATEWAY$1"; }

create_customer() { # name creditLimit
  post /customers "{\"name\":\"$1\",\"creditLimit\":{\"amount\":$2}}" | jq -r .customerId
}

create_order() { # customerId total
  post /orders "{\"customerId\":$1,\"orderTotal\":{\"amount\":$2}}" | jq -r .orderId
}

order_state() { curl -fsS "$GATEWAY/orders/$1" | jq -r '"\(.orderState) \(.rejectionReason // "")"'; }

wait_final_state() { # orderId  -> waits until the saga leaves PENDING
  local state
  for _ in $(seq 1 60); do
    state=$(order_state "$1")
    [[ $state != PENDING* ]] && { echo "$state"; return; }
    sleep 0.5
  done
  echo "VẪN PENDING sau 30s (kiểm tra cdc-service / kafka)"
}

show_saga_log() { # orderId
  sleep 1.5   # let Order History and the after-commit lines land in the file
  echo "  --- logs/saga.log (order=$1) ---"
  grep -E "order=$1([^0-9]|$)" "$SAGA_LOG" | sed 's/^/  /' || true
}

scenario_approve() {
  bold "KỊCH BẢN 1: đủ tín dụng -> APPROVED  (creditLimit 15.00, order 12.34)"
  local c o
  c=$(create_customer "Alice" 15.00); echo "  customerId=$c"
  o=$(create_order "$c" 12.34)
  echo "  POST /orders trả về orderId=$o, trạng thái ngay lúc đó: $(order_state "$o")"
  echo "  Kết quả cuối: $(wait_final_state "$o")"
  show_saga_log "$o"
}

scenario_insufficient() {
  bold "KỊCH BẢN 2: vượt hạn mức -> REJECTED (giao dịch bù)  (creditLimit 15.00, đã giữ 12.34, order mới 5.00)"
  local c o1 o2
  c=$(create_customer "Bob" 15.00); echo "  customerId=$c"
  o1=$(create_order "$c" 12.34); echo "  order đầu tiên=$o1 -> $(wait_final_state "$o1")"
  o2=$(create_order "$c" 5.00)
  echo "  order thứ hai=$o2 -> $(wait_final_state "$o2")"
  show_saga_log "$o2"
}

scenario_unknown() {
  bold "KỊCH BẢN 3: customer không tồn tại -> REJECTED UNKNOWN_CUSTOMER"
  local o
  o=$(create_order 999999 1.00)
  echo "  orderId=$o -> $(wait_final_state "$o")"
  show_saga_log "$o"
}

scenario_cancel() {
  bold "KỊCH BẢN 4: hủy đơn đã APPROVED -> CANCELLED, Customer trả lại tín dụng"
  local c o o2
  c=$(create_customer "Carol" 15.00); echo "  customerId=$c"
  o=$(create_order "$c" 12.34); echo "  orderId=$o -> $(wait_final_state "$o")"
  curl -fsS -X POST "$ORDER_SERVICE/orders/$o/cancel" | jq -c . | sed 's/^/  cancel response: /'
  show_saga_log "$o"
  o2=$(create_order "$c" 12.34)
  echo "  Đặt lại đơn 12.34 sau khi hủy (tín dụng đã được trả): order=$o2 -> $(wait_final_state "$o2")"
}

scenario_pending_cancel() {
  bold "KỊCH BẢN 5: hủy đơn khi còn PENDING -> bị từ chối (semantic lock)"
  local c o code
  c=$(create_customer "Dave" 15.00); echo "  customerId=$c"
  o=$(create_order "$c" 1.00)
  # gọi cancel ngay lập tức, thường saga chưa kịp chạy xong
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$ORDER_SERVICE/orders/$o/cancel")
  echo "  POST /orders/$o/cancel ngay sau khi tạo -> HTTP $code (500 = bị từ chối vì PENDING; 200 = saga đã kịp xong trước)"
  echo "  Kết quả cuối: $(wait_final_state "$o")"
  show_saga_log "$o"
}

curl -fsS "$GATEWAY/actuator/health" >/dev/null 2>&1 || {
  echo "API Gateway chưa sẵn sàng ở $GATEWAY. Chạy: docker compose up -d --build"; exit 1; }

case "${1:-all}" in
  approve) scenario_approve ;;
  insufficient) scenario_insufficient ;;
  unknown) scenario_unknown ;;
  cancel) scenario_cancel ;;
  pending-cancel) scenario_pending_cancel ;;
  all) scenario_approve; scenario_insufficient; scenario_unknown; scenario_cancel; scenario_pending_cancel ;;
  *) echo "Không biết kịch bản '$1'"; exit 1 ;;
esac

bold "Xem toàn bộ timeline: less logs/saga.log   |   chi tiết từng service: logs/<service>.log"
