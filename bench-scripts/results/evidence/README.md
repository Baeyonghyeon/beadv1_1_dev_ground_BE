# 측정 원본 데이터

문서에 실린 수치의 근거가 되는 raw 데이터. **재생성되지 않으므로 지우지 말 것.**

| 파일 | 내용 |
|---|---|
| `burst600-sync.txt` / `burst600-kafka.txt` | 동시 60 / 총 600건. 각 줄 = `응답코드 응답시간(초)` |
| `ladder-{sync,kafka}-{10,30,60,120}.txt` | 동시성 사다리, 각 800건 |

## 이 데이터로 계산된 값

`claude-kafka-test-결과.md` §3.1 의 이력서 대표 수치:

```
p50: 1,087 → 885 ms   (-18.6%)
p95: 1,672 → 1,389 ms (-16.9%)   ← 이력서에 쓰는 값
p99: 2,394 → 1,781 ms (-25.6%)   ← 표본 600건이라 불안정, 사용 안 함
```

재계산:

```bash
for f in burst600-sync.txt burst600-kafka.txt; do
  echo -n "$f: "
  awk '{print $2*1000}' "$f" | sort -n | \
    awk '{a[NR]=$1} END {printf "p50=%.0f p95=%.0f p99=%.0f (n=%d)\n", a[int(NR*0.5)], a[int(NR*0.95)], a[int(NR*0.99)], NR}'
done
```
