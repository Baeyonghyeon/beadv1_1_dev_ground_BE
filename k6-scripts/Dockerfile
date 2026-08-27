# k6 + xk6-kafka v2.1.0 (공식 사전 빌드 바이너리, k6 v1.7.1 내장)
# 빌드: docker build -t k6-kafka:latest -f Dockerfile .
#
# 참고: mostafa/xk6-kafka v2.1.0 릴리즈의 linux_arm64 바이너리를 사용.
#   - 바이너리: https://github.com/mostafa/xk6-kafka/releases/download/v2.1.0/xk6-kafka_v2.1.0_linux_arm64.tar.gz
#   - Writer/Reader는 v2에서 deprecated alias로 동작하므로 기존 k6 스크립트 그대로 호환
#   - Intel Mac이면 linux_amd64 바이너리로 교체 필요
FROM ubuntu:24.04

RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates librdkafka1 && \
    rm -rf /var/lib/apt/lists/*

COPY xk6-kafka_v2.1.0_linux_arm64 /usr/local/bin/k6
ENTRYPOINT ["k6"]