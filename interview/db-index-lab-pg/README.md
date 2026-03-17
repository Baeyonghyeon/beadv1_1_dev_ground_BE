# DB 인덱스 실습 랩 (PostgreSQL)

이 디렉토리는 프로젝트와 분리된 인덱스 학습용 실험 환경입니다.  
실무와 유사한 테이블/쿼리로 `인덱스 전/후` 차이를 직접 측정합니다.

## 1. 목표
- 인덱스가 왜 필요한지 실행계획으로 이해하기
- 복합 인덱스 컬럼 순서가 성능에 미치는 영향 확인하기
- 조회 성능 개선과 쓰기 비용 증가의 트레이드오프 이해하기

## 2. 파일 설명
- `schema.sql`  
  실습용 스키마/테이블 생성
- `seed.sql`  
  샘플 데이터 대량 적재 (`generate_series` 사용)
- `queries_baseline.sql`  
  기준 쿼리 + `EXPLAIN (ANALYZE, BUFFERS)`
- `indexes.sql`  
  실습용 인덱스 생성 스크립트
- `measure-template.md`  
  측정 결과 기록 양식

## 3. 학습 순서 (권장)
1. 스키마 생성
2. 샘플 데이터 적재
3. 인덱스 없는 상태에서 기준 쿼리 실행/기록
4. 인덱스 생성
5. 같은 쿼리 재실행/비교
6. 어떤 인덱스를 유지/삭제할지 결론 작성

## 4. 실행 예시
```bash
psql -U postgres -f interview/db-index-lab-pg/schema.sql
psql -U postgres -f interview/db-index-lab-pg/seed.sql
psql -U postgres -f interview/db-index-lab-pg/queries_baseline.sql
psql -U postgres -f interview/db-index-lab-pg/indexes.sql
psql -U postgres -f interview/db-index-lab-pg/queries_baseline.sql
```

## 5. 실습 체크리스트
- 실행계획에서 `Seq Scan`이 왜 나왔는지 설명 가능한가?
- 인덱스 생성 후 `Index Scan / Bitmap Index Scan`으로 바뀌었는가?
- `ORDER BY` 비용이 줄었는가?
- 인덱스 추가로 `INSERT/UPDATE` 부담이 늘었는가?
- 중복/불필요 인덱스는 없는가?

