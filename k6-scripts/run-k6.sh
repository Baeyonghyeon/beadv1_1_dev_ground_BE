#!/bin/bash
# k6 Docker 실행 헬퍼 스크립트
# 사용법: ./run-k6.sh <script-name> [k6-options...]
#
# 예시:
#   ./run-k6.sh mixed-workload.js                    # 설계기준 30분 테스트
#   ./run-k6.sh mixed-workload.js --duration 1m      # 1분 스모크 테스트
#   ./run-k6.sh charge-limit.js                      # 충전 한계 테스트

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT_NAME="${1:-mixed-workload.js}"
shift 2>/dev/null || true

echo "🚀 k6 Docker 실행: ${SCRIPT_NAME}"
echo "   스크립트 디렉토리: ${SCRIPT_DIR}"
echo "   추가 옵션: $@"
echo ""

docker run --rm -i \
    -v "${SCRIPT_DIR}:/scripts" \
    --add-host=host.docker.internal:host-gateway \
    grafana/k6:latest \
    run "/scripts/${SCRIPT_NAME}" "$@"
