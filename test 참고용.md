# Kafka 증명 A/B 부하 테스트 — 실행 커맨드 모음

> 복붙용 치트시트. 설계는 [`deepseek-kafka-ab-test.md`](./deepseek-kafka-ab-test.md) 참고.
> 준비 완료 상태: MySQL/Kafka/Prometheus/Grafana 기동 ✅, payments 앱 실행 중(8085) ✅, 시드 10,000건 ✅, k6-kafka 이미지 빌드 ✅

---

## ① 예비 측정 — consumer 한계 TPS 찾기

```bash
cd ~/IdeaProjects/beadv1_1_dev_ground_BE/k6-scripts

# 50 TPS × 2분
docker run --rm -i --network docker_default -v $(pwd):/scripts \
  -e KAFKA_BROKER=kafka:9090 -e BURST_RATE=50 -e BURST_MIN=2m -e BASELINE_MIN=1m \
  k6-kafka:latest run /scripts/burst-kafka-v1.js

# 종료 직후 lag 확인 (0이면 통과 → rate 올려서 반복: 100 → 150 → 200)
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --group payments-consumer-group --describe
```

> lag가 커지기 시작한 직전 rate = consumer 한계. 버스트 R은 그 **5~10배**.

---

## ② lag 모니터 (본 테스트와 동시에 다른 터미널에서)

```bash
cd ~/IdeaProjects/beadv1_1_dev_ground_BE/k6-scripts
./lag-monitor.sh 5 lag-report.csv 60   # 5초 간격, 60분 기록
```

---

## ③ 버스트 본 테스트 (예: R=300, 총 약 40분)

```bash
cd ~/IdeaProjects/beadv1_1_dev_ground_BE/k6-scripts
docker run --rm -i --network docker_default -v $(pwd):/scripts \
  -e KAFKA_BROKER=kafka:9090 -e BURST_RATE=300 -e BURST_MIN=30m -e BASELINE_MIN=10m \
  k6-kafka:latest run /scripts/burst-kafka-v1.js
```

---

## ④ 검증 (테스트 전후로 각각 기록)

```bash
# deposit_history 건수 (produce 건수와 대조)
docker exec mysql mysql -umysqlId -pmysqlPwd dbay_db -e \
  "SELECT COUNT(*) AS history_count FROM DepositHistoryEntity;"

# 잔액 정합성 (사전: 100억)
docker exec mysql mysql -umysqlId -pmysqlPwd dbay_db -e \
  "SELECT SUM(balance) AS total FROM DepositEntity WHERE userCode LIKE 'USER-%';"

# 슬로우 쿼리 / 락 대기
docker exec mysql mysql -umysqlId -pmysqlPwd dbay_db -e \
  "SHOW GLOBAL STATUS LIKE 'Slow_queries'; SHOW STATUS LIKE 'innodb_row_lock%';"
```

---

## 참고

- **무손실 계산**: produce 건수 = `deposits-commands`의 LOG-END-OFFSET 증가량 (기준 9,000) 또는 ④의 history 건수 증가량
- drain 관찰: k6 종료 후 ②의 lag-monitor가 계속 기록 중이면 lag→0 곡선이 CSV에 남음
- 테스트 중 Grafana에서 볼 것: `HikariCP Connections`, `GC Pause Time`, `에러율`, `TPS` — 중단 기준은 active=max, Full GC, 에러율 1% 돌파
- Grafana: http://localhost:3000 (admin/admin) → 💰 예치금 성능 테스트 대시보드
- 앱 로그: `/tmp/payments-app.log` / GC 로그: `/tmp/gc-payments.log`