package io.devground.dbay.common.bench;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * bench 프로파일 전용 no-op VectorStore.
 *
 * <p>왜 필요한가:
 * {@code ElasticsearchVectorStore.afterPropertiesSet()} 은 {@code initialize-schema} 값과 무관하게
 * {@code indexExists()} 를 호출하므로, Elasticsearch 가 없으면 commerce 가 아예 기동하지 못한다.
 * 반면 {@code CartVectorSearchAdapter} 는 {@code VectorStore} 를 생성자 주입받는 {@code @Component} 라
 * 자동 구성만 제외하면 이번엔 그쪽에서 기동이 깨진다.
 *
 * <p>결제 시나리오 부하 테스트는 검색/AI 를 사용하지 않으므로,
 * bench 프로파일에서는 자동 구성을 제외(application-bench.yml)하고 이 no-op 빈으로 대체한다.
 * 이 빈의 메서드가 호출되면 부하 테스트 시나리오가 잘못된 것이므로 즉시 실패시킨다.
 *
 * @see <a href="file:../../../../../../../../claude-kafka-test-사전작업.md">claude-kafka-test-사전작업.md</a>
 */
@Configuration
@Profile("bench")
public class BenchAiConfig {

	@Bean
	public VectorStore vectorStore() {
		return new NoOpVectorStore();
	}

	static class NoOpVectorStore implements VectorStore {

		private static final String MSG =
			"bench 프로파일에서는 VectorStore 를 사용할 수 없습니다. "
				+ "결제 시나리오 부하 테스트가 검색/AI 경로를 타고 있는지 확인하세요.";

		@Override
		public String getName() {
			return "noop-bench";
		}

		@Override
		public void add(List<Document> documents) {
			throw new UnsupportedOperationException(MSG);
		}

		@Override
		public void delete(List<String> idList) {
			throw new UnsupportedOperationException(MSG);
		}

		@Override
		public void delete(Filter.Expression filterExpression) {
			throw new UnsupportedOperationException(MSG);
		}

		@Override
		public List<Document> similaritySearch(SearchRequest request) {
			throw new UnsupportedOperationException(MSG);
		}
	}
}
