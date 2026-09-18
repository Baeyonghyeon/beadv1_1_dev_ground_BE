#!/bin/bash
# lag-monitor.sh — payments consumer group의 lag을 일정 간격으로 CSV 기록
# deepseek-kafka-ab-test.md 의 버전1 drain 관찰용
#
# 사용법:
#   ./lag-monitor.sh [interval_seconds] [output_file] [duration_minutes]
#   예) ./lag-monitor.sh 5 lag-report.csv 0    # 5초 간격, 무제한 (Ctrl+C 종료)
#   예) ./lag-monitor.sh 5 lag-report.csv 40   # 40분 후 자동 종료

INTERVAL="${1:-5}"
OUTPUT="${2:-lag-report.csv}"
DURATION_MIN="${3:-0}"

if docker ps --format '{{.Names}}' | grep -q '^kafka$'; then
    echo "✅ kafka 컨테이너 확인됨"
else
    echo "❌ kafka 컨테이너가 실행 중이 아님. 먼저 docker compose up 하세요." >&2
    exit 1
fi

echo "timestamp,group,topic,partition,current_offset,log_end_offset,lag" > "$OUTPUT"
echo "📊 lag 기록 시작 → ${OUTPUT} (간격: ${INTERVAL}s, 제한: ${DURATION_MIN}분, Ctrl+C 종료)"

START=$(date +%s)
while true; do
    if [ "$DURATION_MIN" -gt 0 ] && [ $(( ($(date +%s) - START) / 60 )) -ge "$DURATION_MIN" ]; then
        echo "✅ ${DURATION_MIN}분 경과 — 기록 종료"
        break
    fi

    NOW=$(date '+%Y-%m-%dT%H:%M:%S')
    docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --group payments-consumer-group \
        --describe 2>/dev/null | awk -v ts="$NOW" \
        'NR>2 && NF>=6 && $6 != "-" {print ts","$1","$2","$3","$4","$5","$6}' >> "$OUTPUT"

    sleep "$INTERVAL"
done
